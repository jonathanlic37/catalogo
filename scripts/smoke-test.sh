#!/usr/bin/env bash
# Smoke test end-to-end del stack completo (requiere `docker compose up -d --build`).
# Ejercita: salud, 401 sin token, alta vía Consumer, confirmación en Producer, idempotencia
# del webhook, escritura directa en Producer y reconciliación en Consumer.
set -euo pipefail

cd "$(dirname "$0")/.."
[ -f .env ] || { echo "Falta .env (copia .env.example y genera los tokens)"; exit 1; }
set -a; source .env; set +a

CONSUMER="http://localhost:${CONSUMER_PORT:-8081}"
PRODUCER="http://localhost:${PRODUCER_PORT:-8082}"
FRONTEND="http://localhost:${FRONTEND_PORT:-8088}"
FRONT_AUTH="Authorization: Bearer ${FRONTEND_TO_CONSUMER_TOKEN}"
PROD_AUTH="Authorization: Bearer ${CONSUMER_TO_PRODUCER_TOKEN}"

wait_ok() { # url nombre
  for _ in $(seq 1 60); do
    if curl -fsS "$1" >/dev/null 2>&1; then echo "OK  $2"; return 0; fi
    sleep 5
  done
  echo "TIMEOUT esperando $2"; return 1
}

echo "== Esperando servicios =="
wait_ok "$PRODUCER/actuator/health/readiness" "producer readiness"
wait_ok "$CONSUMER/actuator/health/readiness" "consumer readiness"
wait_ok "$FRONTEND/healthz" "frontend"

echo "== 401 sin token =="
code=$(curl -s -o /dev/null -w '%{http_code}' "$CONSUMER/api/items")
[ "$code" = "401" ] || { echo "esperaba 401, obtuve $code"; exit 1; }

echo "== Alta vía Consumer y confirmación en Producer =="
ID=$(curl -fsS -X POST "$CONSUMER/api/items" -H "$FRONT_AUTH" -H 'Content-Type: application/json' \
  -d '{"nombre":"Smoke","estado":"ACTIVO","tipo":"PRODUCTO"}' \
  | python3 -c 'import sys,json;print(json.load(sys.stdin)["id"])')
for _ in $(seq 1 30); do
  curl -fsS "$PRODUCER/api/items/$ID" -H "$PROD_AUTH" >/dev/null 2>&1 && break
  sleep 2
done
curl -fsS "$PRODUCER/api/items/$ID" -H "$PROD_AUTH" >/dev/null || { echo "el ítem no llegó al Producer"; exit 1; }
echo "OK  $ID confirmado en el Producer"

echo "== Idempotencia: mismo webhook dos veces =="
KEY=$(python3 -c 'import uuid;print(uuid.uuid4())')
NID=$(python3 -c 'import uuid;print(uuid.uuid4())')
BODY="{\"id\":\"$NID\",\"nombre\":\"Idem\",\"estado\":\"ACTIVO\"}"
replayed() {
  curl -fsS -X POST "$PRODUCER/webhooks/catalogo" -H "$PROD_AUTH" -H "Idempotency-Key: $KEY" \
    -H 'X-Event-Type: CREATED' -H 'Content-Type: application/json' -d "$BODY" -D - -o /dev/null \
    | tr -d '\r' | awk 'tolower($1)=="idempotent-replayed:"{print $2}'
}
[ "$(replayed)" = "false" ] || { echo "primer envío: esperaba Idempotent-Replayed: false"; exit 1; }
[ "$(replayed)" = "true" ] || { echo "segundo envío: esperaba Idempotent-Replayed: true"; exit 1; }
echo "OK  replay idempotente"

echo "== Alta en Producer + reconciliación manual en Consumer =="
PID=$(curl -fsS -X POST "$PRODUCER/api/items" -H "$PROD_AUTH" -H 'Content-Type: application/json' \
  -d '{"nombre":"Desde el Producer","estado":"ACTIVO"}' \
  | python3 -c 'import sys,json;print(json.load(sys.stdin)["id"])')
curl -fsS -X POST "$CONSUMER/api/reconcile" -H "$FRONT_AUTH" >/dev/null
curl -fsS "$CONSUMER/api/items/$PID" -H "$FRONT_AUTH" >/dev/null || { echo "el ítem no se replicó"; exit 1; }
echo "OK  $PID replicado tras reconciliación"

echo "SMOKE OK"
