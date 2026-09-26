# Probar el sistema con curl

Requisitos: stack levantado (`cp .env.example .env && docker compose up -d --build`), `curl` y
`python3`. Todos los comandos se ejecutan desde la raíz del repositorio.

```bash
set -a; source .env; set +a
CONSUMER=http://localhost:${CONSUMER_PORT:-8081}
PRODUCER=http://localhost:${PRODUCER_PORT:-8082}
REALM=http://localhost:${KEYCLOAK_PORT:-8095}/realms/catalogo
json() { python3 -c "import sys,json;print(json.load(sys.stdin)$1)"; }

# Consumer API: JWT de usuario emitido por Keycloak (cliente catalogo-cli, solo desarrollo).
TOKEN=$(curl -s -d client_id=catalogo-cli -d grant_type=password -d username=demo -d "password=$DEMO_USER_PASSWORD" \
  "$REALM/protocol/openid-connect/token" | json "['access_token']")
FRONT_AUTH="Authorization: Bearer $TOKEN"
# Producer API: dos credenciales con privilegios distintos.
PROD_AUTH="Authorization: Bearer $CONSUMER_TO_PRODUCER_TOKEN"   # la del Consumer: webhook + lectura
ADMIN_PROD_AUTH="Authorization: Bearer $PRODUCER_ADMIN_TOKEN"   # administración: escritura directa
```

El token de usuario caduca a los 5 minutos: si recibes 401, vuelve a ejecutar la línea `TOKEN=...`.

## 1. Crear información en el Producer (la fuente de verdad)

La escritura directa exige la credencial de administración. La del Consumer recibe **403**: el
Consumer solo puede cambiar datos a través del webhook.

```bash
PID=$(curl -s -X POST "$PRODUCER/api/items" -H "$ADMIN_PROD_AUTH" -H 'Content-Type: application/json' \
  -d '{"nombre":"Creado en el Producer","descripcion":"origen canónico","estado":"ACTIVO","tipo":"SERVICIO"}' \
  | json "['id']")
echo "$PID"
```

## 2. El Consumer lo sincroniza

Ocurre automáticamente cada 60 s (`SYNC_RECONCILE_INTERVAL_MS`). Para no esperar, se puede
forzar, igual que con el botón «Sincronizar ahora» de la UI:

```bash
curl -s -X POST "$CONSUMER/api/reconcile" -H "$FRONT_AUTH"        # {"created":1,"updated":0,"deleted":0}
curl -s "$CONSUMER/api/items/$PID" -H "$FRONT_AUTH"               # syncStatus: CONFIRMED, version: 1
```

## 3. Consultar la proyección, que es lo que ve React

La lista es paginada (máximo 100 por página). Admite búsqueda por nombre y filtros por estado, por
tipo y por estado de sincronización:

```bash
curl -s "$CONSUMER/api/items?page=0&size=25" -H "$FRONT_AUTH"
curl -s "$CONSUMER/api/items?q=caf%C3%A9&estado=ACTIVO&tipo=PRODUCTO" -H "$FRONT_AUTH"
curl -s "$CONSUMER/api/items?sync=FAILED" -H "$FRONT_AUTH"         # PENDING | CONFIRMED | FAILED
curl -s "$CONSUMER/api/items/summary" -H "$FRONT_AUTH"             # contadores globales
```

## 4. Modificar desde el Consumer: llega al Producer por webhook

```bash
curl -s -X PUT "$CONSUMER/api/items/$PID" -H "$FRONT_AUTH" -H 'Content-Type: application/json' \
  -d '{"nombre":"Editado desde el Consumer","descripcion":"vía webhook","estado":"INACTIVO","tipo":"SERVICIO"}'
# → 202 con syncStatus PENDING: todavía no es definitivo.
sleep 2
curl -s "$CONSUMER/api/items/$PID" -H "$FRONT_AUTH"      # CONFIRMED, version 2 (la asignó el Producer)
curl -s "$PRODUCER/api/items/$PID" -H "$PROD_AUTH"       # el cambio está en la fuente de verdad
```

Crear y eliminar funcionan igual: `POST /api/items` y `DELETE /api/items/{id}` responden 202 y se
confirman de forma asíncrona.

## 5. Webhook duplicado: idempotencia

```bash
KEY=$(python3 -c 'import uuid;print(uuid.uuid4())'); NEWID=$(python3 -c 'import uuid;print(uuid.uuid4())')
BODY="{\"id\":\"$NEWID\",\"nombre\":\"Directo\",\"descripcion\":\"vía webhook\",\"estado\":\"ACTIVO\"}"
send() { curl -si -X POST "$PRODUCER/webhooks/catalogo" -H "$PROD_AUTH" -H "Idempotency-Key: $KEY" \
  -H 'X-Event-Type: CREATED' -H 'Content-Type: application/json' -d "$1" | grep -iE '^HTTP|idempotent-replayed'; }

send "$BODY"     # 201 · Idempotent-Replayed: false
send "$BODY"     # 201 · Idempotent-Replayed: true  → misma respuesta, no se aplica dos veces
send "{\"id\":\"$NEWID\",\"nombre\":\"Otro\",\"estado\":\"ACTIVO\"}"   # 409: clave reutilizada con otro contenido
```

## 6. Conflicto: el registro cambió en el Producer antes de recibir la modificación

```bash
curl -s -X PUT "$PRODUCER/api/items/$PID" -H "$ADMIN_PROD_AUTH" -H 'Content-Type: application/json' \
  -d '{"nombre":"Cambio concurrente","estado":"ACTIVO"}'            # el Producer pasa a v3; el Consumer sigue en v2
curl -s -X PUT "$CONSUMER/api/items/$PID" -H "$FRONT_AUTH" -H 'Content-Type: application/json' \
  -d '{"nombre":"Edición basada en v2","estado":"ACTIVO"}'
sleep 2
curl -s "$CONSUMER/api/items/$PID" -H "$FRONT_AUTH"                 # FAILED, syncError: "Conflicto: …"
curl -s "$CONSUMER/api/sync/events?status=FAILED" -H "$FRONT_AUTH"  # registro de eventos fallidos
curl -s -X POST "$CONSUMER/api/items/$PID/resync" -H "$FRONT_AUTH"  # descartar y adoptar la versión canónica (v3)
```

Con `SYNC_CONFLICT_POLICY=PRODUCER_WINS` o `CONSUMER_WINS` en `.env`, el conflicto se resuelve solo.

## 7. El Producer no está disponible

```bash
docker compose stop producer-api
OID=$(curl -s -X POST "$CONSUMER/api/items" -H "$FRONT_AUTH" -H 'Content-Type: application/json' \
  -d '{"nombre":"Creado sin Producer","estado":"ACTIVO"}' | json "['id']")
curl -s "$CONSUMER/api/items/$OID" -H "$FRONT_AUTH"      # PENDING, syncError: "Producer no disponible"
docker compose restart consumer-api                      # reinicio del Consumer: la proyección y el outbox persisten
docker compose start producer-api                        # el relay reintenta con backoff y lo confirma
```

Mientras el Producer está caído el ítem sigue `PENDING`, con `syncError` y `syncAttempts` del último
intento: los fallos transitorios se reintentan sin límite y nunca pasan a `FAILED`. Solo un rechazo
del Producer (4xx) deja el ítem `FAILED`, con `failureReason` = `CONFLICT`, `GONE` o `REJECTED`. Se
descarta con `POST /api/items/{id}/resync`; si el motivo es `REJECTED` también se puede reenviar con
`POST /api/items/{id}/retry` (misma Idempotency-Key).

## 8. Seguridad y validación

```bash
curl -si "$CONSUMER/api/items" | head -1                        # 401 sin token
curl -si "$CONSUMER/api/items" -H 'Authorization: Bearer x' | head -1   # 401 token inválido
curl -si -X POST "$PRODUCER/webhooks/catalogo" | head -1        # 401 webhook sin token
curl -si -X POST "$PRODUCER/api/items" -H "$PROD_AUTH" -H 'Content-Type: application/json' \
  -d '{"nombre":"Atajo","estado":"ACTIVO"}' | head -1           # 403: el Consumer no puede saltarse el webhook
curl -s -X POST "$CONSUMER/api/items" -H "$FRONT_AUTH" -H 'Content-Type: application/json' \
  -d '{"nombre":"","estado":"ACTIVO"}'                          # 400 ProblemDetail con errores por campo, sin stack trace
curl -s -X POST "$CONSUMER/api/items" -H "$FRONT_AUTH" -H 'Content-Type: application/json' \
  -d '{"nombre":"X","estado":"ACTIVO","syncStatus":"CONFIRMED"}' # 400: campo no permitido
```

## 9. Observabilidad

```bash
curl -s "$CONSUMER/actuator/health/liveness"      # públicos: liveness (proceso) y readiness (proceso + BD)
curl -s "$CONSUMER/actuator/health/readiness"
curl -s "$PRODUCER/actuator/health/readiness"

# Métricas de sincronización: solo rol admin (usuario admin, contraseña DEMO_ADMIN_PASSWORD).
ADMIN=$(curl -s -d client_id=catalogo-cli -d grant_type=password -d username=admin -d "password=$DEMO_ADMIN_PASSWORD" \
  "$REALM/protocol/openid-connect/token" | json "['access_token']")
curl -s "$CONSUMER/actuator/prometheus" -H "Authorization: Bearer $ADMIN" | grep '^sync_'
```

nginx limita las peticiones a `/api` por IP (`RATE_LIMIT_RATE`/`RATE_LIMIT_BURST`). Al superar el
límite responde **429**.
