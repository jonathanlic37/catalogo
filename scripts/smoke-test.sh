#!/usr/bin/env bash
# Smoke test end-to-end del stack completo contra los contenedores reales.
# Requisito: `cp .env.example .env && docker compose up -d --build` (o el stack ya levantado).
#
# Recorre los criterios de aceptación y los cuatro escenarios del enunciado:
#   1. Producer crea → Consumer sincroniza (reconciliación) → visible vía Consumer (lo que usa React)
#   2. Edición vía Consumer → webhook autenticado → Producer aplica → proyección con la versión canónica
#   3. Webhook duplicado → sin duplicados ni corrupción
#   4. Registro cambiado en el Producer antes de recibir la modificación → conflicto detectado (FAILED)
#   5. Producer no disponible → el cambio queda PENDING (no se presenta como éxito) y se confirma al volver
#   6. Reinicio del Consumer → la proyección local se conserva
#   7. Seguridad: 401 sin Bearer válido en ambas APIs; métricas solo para administradores
set -euo pipefail

cd "$(dirname "$0")/.."
[ -f .env ] || { echo "Falta .env: cp .env.example .env"; exit 1; }
set -a; source .env; set +a

CONSUMER="http://localhost:${CONSUMER_PORT:-8081}"
PRODUCER="http://localhost:${PRODUCER_PORT:-8082}"
FRONTEND="http://localhost:${FRONTEND_PORT:-8088}"
REALM="http://localhost:${KEYCLOAK_PORT:-8095}/realms/catalogo"
PROD_AUTH="Authorization: Bearer ${CONSUMER_TO_PRODUCER_TOKEN}"    # credencial del Consumer: webhook + lectura
ADMIN_PROD_AUTH="Authorization: Bearer ${PRODUCER_ADMIN_TOKEN}"   # administración: escritura directa
JSON='Content-Type: application/json'

ok()   { echo "OK   $*"; }
fail() { echo "FAIL $*"; exit 1; }
jget() { python3 -c "import sys,json;d=json.load(sys.stdin);print(d$1)"; }

wait_ok() { # url nombre
  for _ in $(seq 1 90); do
    if curl -fsS "$1" >/dev/null 2>&1; then ok "$2"; return 0; fi
    sleep 5
  done
  fail "timeout esperando $2"
}

# Espera a que el ítem alcance el estado de sincronización indicado en el Consumer.
wait_sync() { # id estado [segundos]
  local s=""
  for _ in $(seq 1 "${3:-60}"); do
    s=$(curl -fsS "$CONSUMER/api/items/$1" -H "$FRONT_AUTH" | jget "['syncStatus']" 2>/dev/null || true)
    [ "$s" = "$2" ] && return 0
    sleep 1
  done
  fail "el ítem $1 quedó en '$s', se esperaba '$2'"
}

token() { # usuario contraseña
  curl -fsS -d "client_id=catalogo-cli" -d "grant_type=password" -d "username=$1" -d "password=$2" \
    "$REALM/protocol/openid-connect/token" | jget "['access_token']"
}

echo "== Servicios y healthchecks diferenciados =="
wait_ok "$PRODUCER/actuator/health/readiness" "producer readiness"
wait_ok "$PRODUCER/actuator/health/liveness"  "producer liveness"
wait_ok "$CONSUMER/actuator/health/readiness" "consumer readiness"
wait_ok "$CONSUMER/actuator/health/liveness"  "consumer liveness"
wait_ok "$REALM/.well-known/openid-configuration" "keycloak (IdP)"
wait_ok "$FRONTEND/healthz" "frontend"

echo "== Autenticación =="
FRONT_AUTH="Authorization: Bearer $(token demo "$DEMO_USER_PASSWORD")"
ADMIN_AUTH="Authorization: Bearer $(token admin "$DEMO_ADMIN_PASSWORD")"
ok "tokens OIDC de usuario (demo) y administrador (admin), contraseñas desde .env"
for url in "$CONSUMER/api/items" "$PRODUCER/api/items"; do
  [ "$(curl -s -o /dev/null -w '%{http_code}' "$url")" = 401 ] || fail "$url sin token debería dar 401"
  [ "$(curl -s -o /dev/null -w '%{http_code}' "$url" -H 'Authorization: Bearer no-valido')" = 401 ] \
    || fail "$url con token inválido debería dar 401"
done
[ "$(curl -s -o /dev/null -w '%{http_code}' -X POST "$PRODUCER/webhooks/catalogo")" = 401 ] || fail "webhook sin token"
ok "401 sin Bearer válido en Consumer, Producer y webhook"
code=$(curl -s -o /dev/null -w '%{http_code}' -X POST "$PRODUCER/api/items" -H "$PROD_AUTH" -H "$JSON" \
  -d '{"nombre":"Atajo","estado":"ACTIVO"}')
[ "$code" = 403 ] || fail "la credencial del Consumer no debe escribir directamente en el Producer (obtuvo $code)"
ok "el Consumer no puede saltarse el webhook: escritura directa en el Producer → 403"
[ "$(curl -s -o /dev/null -w '%{http_code}' "$CONSUMER/actuator/prometheus" -H "$FRONT_AUTH")" = 403 ] \
  || fail "un usuario sin rol admin no debe ver las métricas"
# Se guarda la respuesta antes de filtrar: con pipefail, `curl | grep -q` falla por SIGPIPE en curl.
METRICS=$(curl -fsS "$CONSUMER/actuator/prometheus" -H "$ADMIN_AUTH")
grep -q '^sync_outbox_attempts_total' <<<"$METRICS" || fail "métricas de sincronización"
ok "métricas de sincronización solo para admin"
# El bundle del frontend no contiene el token de servicio.
for asset in $(curl -fsS "$FRONTEND/" | grep -oE '/assets/[^"]+\.js'); do
  BUNDLE=$(curl -fsS "$FRONTEND$asset")
  if grep -qF "$CONSUMER_TO_PRODUCER_TOKEN" <<<"$BUNDLE"; then fail "token en el bundle $asset"; fi
done
ok "el bundle del frontend no contiene secretos"

echo "== 1. Producer crea → Consumer sincroniza → visible para React (vía Consumer y vía nginx) =="
PID=$(curl -fsS -X POST "$PRODUCER/api/items" -H "$ADMIN_PROD_AUTH" -H "$JSON" \
  -d '{"nombre":"Creado en el Producer","descripcion":"origen canónico","estado":"ACTIVO","tipo":"SERVICIO"}' | jget "['id']")
curl -fsS -X POST "$CONSUMER/api/reconcile" -H "$FRONT_AUTH" >/dev/null
[ "$(curl -fsS "$CONSUMER/api/items/$PID" -H "$FRONT_AUTH" | jget "['syncStatus']")" = CONFIRMED ] || fail "no replicado"
curl -fsS "$FRONTEND/api/items/$PID" -H "$FRONT_AUTH" >/dev/null || fail "no visible a través del proxy del frontend"
ok "$PID creado en el Producer y replicado en el Consumer"

echo "== 2. Edición vía Consumer → webhook → Producer → proyección canónica =="
code=$(curl -s -o /tmp/smoke-put.json -w '%{http_code}' -X PUT "$CONSUMER/api/items/$PID" -H "$FRONT_AUTH" -H "$JSON" \
  -d '{"nombre":"Editado desde React","descripcion":"vía webhook","estado":"INACTIVO","tipo":"SERVICIO"}')
[ "$code" = 202 ] || fail "PUT debería responder 202 (pendiente), obtuvo $code"
[ "$(jget "['syncStatus']" < /tmp/smoke-put.json)" = PENDING ] || fail "la respuesta debería estar PENDING"
wait_sync "$PID" CONFIRMED
[ "$(curl -fsS "$PRODUCER/api/items/$PID" -H "$PROD_AUTH" | jget "['nombre']")" = "Editado desde React" ] \
  || fail "el cambio no llegó al Producer"
[ "$(curl -fsS "$CONSUMER/api/items/$PID" -H "$FRONT_AUTH" | jget "['version']")" = 2 ] \
  || fail "la proyección no tiene la versión canónica"
ok "cambio aplicado por el Producer (v2) y reflejado en la proyección"

echo "== 3. Webhook duplicado =="
KEY=$(python3 -c 'import uuid;print(uuid.uuid4())'); NID=$(python3 -c 'import uuid;print(uuid.uuid4())')
BODY="{\"id\":\"$NID\",\"nombre\":\"Idem\",\"estado\":\"ACTIVO\"}"
replayed() {
  curl -fsS -X POST "$PRODUCER/webhooks/catalogo" -H "$PROD_AUTH" -H "Idempotency-Key: $KEY" \
    -H 'X-Event-Type: CREATED' -H "$JSON" -d "$BODY" -D - -o /dev/null \
    | tr -d '\r' | awk 'tolower($1)=="idempotent-replayed:"{print $2}'
}
[ "$(replayed)" = false ] || fail "primer envío"
[ "$(replayed)" = true ] || fail "reenvío no marcado como replay"
[ "$(curl -fsS "$PRODUCER/api/items/$NID" -H "$PROD_AUTH" | jget "['version']")" = 1 ] || fail "el duplicado reaplicó el cambio"
ok "el duplicado devuelve la respuesta original sin reaplicar (versión 1)"

echo "== 4. Registro cambiado en el Producer antes de recibir la modificación → conflicto =="
curl -fsS -X PUT "$PRODUCER/api/items/$PID" -H "$ADMIN_PROD_AUTH" -H "$JSON" \
  -d '{"nombre":"Cambio concurrente en el Producer","estado":"ACTIVO"}' >/dev/null      # v3; el Consumer sigue en v2
curl -fsS -X PUT "$CONSUMER/api/items/$PID" -H "$FRONT_AUTH" -H "$JSON" \
  -d '{"nombre":"Edición basada en v2","estado":"ACTIVO"}' >/dev/null
wait_sync "$PID" FAILED
[ "$(curl -fsS "$PRODUCER/api/items/$PID" -H "$PROD_AUTH" | jget "['nombre']")" = "Cambio concurrente en el Producer" ] \
  || fail "el cambio obsoleto sobrescribió la fuente de verdad"
EVENTS=$(curl -fsS "$CONSUMER/api/sync/events?status=FAILED" -H "$FRONT_AUTH")
grep -qF "$PID" <<<"$EVENTS" || fail "evento fallido no registrado"
curl -fsS -X POST "$CONSUMER/api/items/$PID/resync" -H "$FRONT_AUTH" >/dev/null
[ "$(curl -fsS "$CONSUMER/api/items/$PID" -H "$FRONT_AUTH" | jget "['version']")" = 3 ] || fail "resync"
ok "409 detectado, Producer intacto, evento registrado y resuelto adoptando la versión canónica (v3)"

echo "== 5. Producer no disponible =="
docker compose stop producer-api >/dev/null 2>&1
OID=$(curl -fsS -X POST "$CONSUMER/api/items" -H "$FRONT_AUTH" -H "$JSON" \
  -d '{"nombre":"Creado sin Producer","estado":"ACTIVO"}' | jget "['id']")
sleep 5
[ "$(curl -fsS "$CONSUMER/api/items/$OID" -H "$FRONT_AUTH" | jget "['syncStatus']")" = PENDING ] \
  || fail "sin Producer el cambio debe quedar PENDING, no confirmado"
ok "sin Producer el cambio queda PENDING (no se presenta como éxito)"

echo "== 6. Reinicio del Consumer con el cambio aún pendiente =="
BEFORE=$(curl -fsS "$CONSUMER/api/items/summary" -H "$FRONT_AUTH" | jget "['total']")
docker compose restart consumer-api >/dev/null 2>&1
wait_ok "$CONSUMER/actuator/health/readiness" "consumer tras reinicio"
AFTER=$(curl -fsS "$CONSUMER/api/items/summary" -H "$FRONT_AUTH" | jget "['total']")
[ "$BEFORE" = "$AFTER" ] || fail "la proyección cambió tras el reinicio ($BEFORE → $AFTER)"
ok "proyección conservada tras el reinicio ($AFTER ítems)"
docker compose start producer-api >/dev/null 2>&1
wait_ok "$PRODUCER/actuator/health/readiness" "producer de vuelta"
# El relay reintenta con backoff exponencial (SYNC_RETRY_INTERVAL_MS × 2ⁿ): el próximo intento puede
# tardar hasta ~80 s con los valores por defecto.
wait_sync "$OID" CONFIRMED 180
curl -fsS "$PRODUCER/api/items/$OID" -H "$PROD_AUTH" >/dev/null || fail "el cambio pendiente no llegó al Producer"
ok "el outbox reenvió el cambio pendiente tras el reinicio y el Producer lo confirmó"

echo "SMOKE OK"
