# Pruebas manuales con curl

Requisitos: entorno levantado (`docker compose up -d --build`) y `.env` cargado en la shell:

```bash
set -a; source .env; set +a
CONSUMER=http://localhost:${CONSUMER_PORT:-8081}
PRODUCER=http://localhost:${PRODUCER_PORT:-8082}
FRONT_AUTH="Authorization: Bearer $FRONTEND_TO_CONSUMER_TOKEN"
PROD_AUTH="Authorization: Bearer $CONSUMER_TO_PRODUCER_TOKEN"
```

## 1. Crear un ítem (vía Consumer) → 202 PENDING

```bash
curl -s -X POST "$CONSUMER/api/items" -H "$FRONT_AUTH" -H 'Content-Type: application/json' \
  -d '{"nombre":"Café molido","descripcion":"500 g","estado":"ACTIVO"}' | tee /tmp/item.json
ID=$(python3 -c 'import json;print(json.load(open("/tmp/item.json"))["id"])')
```

## 2. Listar (Consumer, réplica) → pasa a CONFIRMED en segundos

La lista es paginada (máx. 100 por página) y admite búsqueda y filtro por estado:

```bash
curl -s "$CONSUMER/api/items?page=0&size=25" -H "$FRONT_AUTH"
curl -s "$CONSUMER/api/items?q=caf%C3%A9&estado=ACTIVO" -H "$FRONT_AUTH"
curl -s "$CONSUMER/api/items/summary" -H "$FRONT_AUTH"      # contadores globales
curl -s "$CONSUMER/api/items/$ID" -H "$FRONT_AUTH"
```

## 3. Comprobar la fuente de verdad (Producer)

```bash
curl -s "$PRODUCER/api/items/$ID" -H "$PROD_AUTH"
```

### 3b. Crear/editar/borrar directamente en el Producer (paso 1 del enunciado)

El Producer es la aplicación central y puede originar datos sin pasar por el Consumer. Los ítems
así creados los descubre el Consumer en la siguiente reconciliación (ver paso 5).

```bash
# Crear → 201, id y version asignados por el Producer
curl -s -X POST "$PRODUCER/api/items" -H "$PROD_AUTH" -H 'Content-Type: application/json' \
  -d '{"nombre":"Creado en el Producer","descripcion":"origen canónico","estado":"ACTIVO"}' | tee /tmp/prod-item.json
PID=$(python3 -c 'import json;print(json.load(open("/tmp/prod-item.json"))["id"])')

# Editar (sube la versión) y borrar
curl -s -X PUT "$PRODUCER/api/items/$PID" -H "$PROD_AUTH" -H 'Content-Type: application/json' \
  -d '{"nombre":"Editado en el Producer","estado":"INACTIVO"}'
curl -s -X DELETE "$PRODUCER/api/items/$PID" -H "$PROD_AUTH"    # 204
```

## 4. Editar / eliminar (solo ítems CONFIRMED) → 202

```bash
curl -s -X PUT "$CONSUMER/api/items/$ID" -H "$FRONT_AUTH" -H 'Content-Type: application/json' \
  -d '{"nombre":"Café molido 1 kg","descripcion":"1 kg","estado":"ACTIVO"}'
curl -s -X DELETE "$CONSUMER/api/items/$ID" -H "$FRONT_AUTH"
```

## 5. Disparar el webhook directamente contra el Producer

```bash
KEY=$(uuidgen); NEWID=$(uuidgen)
BODY="{\"id\":\"$NEWID\",\"nombre\":\"Directo\",\"descripcion\":\"vía webhook\",\"estado\":\"ACTIVO\"}"

# Primera entrega → 201, cabecera Idempotent-Replayed: false
curl -si -X POST "$PRODUCER/webhooks/catalogo" -H "$PROD_AUTH" -H "Idempotency-Key: $KEY" \
  -H 'X-Event-Type: CREATED' -H 'Content-Type: application/json' -d "$BODY"

# Reenvío idéntico → misma respuesta, Idempotent-Replayed: true, sin duplicar
curl -si -X POST "$PRODUCER/webhooks/catalogo" -H "$PROD_AUTH" -H "Idempotency-Key: $KEY" \
  -H 'X-Event-Type: CREATED' -H 'Content-Type: application/json' -d "$BODY"

# Misma clave con otro contenido → 409
curl -si -X POST "$PRODUCER/webhooks/catalogo" -H "$PROD_AUTH" -H "Idempotency-Key: $KEY" \
  -H 'X-Event-Type: CREATED' -H 'Content-Type: application/json' \
  -d "{\"id\":\"$NEWID\",\"nombre\":\"Otro\",\"estado\":\"ACTIVO\"}"
```

El ítem creado en el paso 5 aparece en el Consumer tras la siguiente reconciliación
(`SYNC_RECONCILE_INTERVAL_MS`, 60 s por defecto).

## 6. Seguridad y validación

```bash
curl -si "$CONSUMER/api/items"                                   # 401 sin token
curl -si "$PRODUCER/webhooks/catalogo" -X POST                   # 401 sin token
curl -si -X POST "$CONSUMER/api/items" -H "$FRONT_AUTH" \
  -H 'Content-Type: application/json' -d '{"nombre":"","estado":"ACTIVO"}'   # 400 ProblemDetail, sin stack trace
```

## 7. Resiliencia: Producer caído

```bash
docker compose stop producer-api
curl -s -X POST "$CONSUMER/api/items" -H "$FRONT_AUTH" -H 'Content-Type: application/json' \
  -d '{"nombre":"Offline","estado":"ACTIVO"}'      # queda PENDING (evento en el outbox)
docker compose start producer-api                   # el relay programado lo confirma
```

El cambio no se pierde aunque el Consumer se reinicie: el evento queda en `outbox_events` y el relay
lo retoma. Los eventos agotados quedan `FAILED` con su motivo y se pueden reintentar
(`POST /api/items/{id}/retry`) o descartar (`POST /api/items/{id}/resync`).

## 8. Observabilidad y opcionales

```bash
# Healthchecks diferenciados (públicos)
curl -s "$CONSUMER/actuator/health/readiness"
curl -s "$PRODUCER/actuator/health/liveness"

# Métricas de sincronización (protegidas con Bearer)
curl -s "$CONSUMER/actuator/prometheus" -H "$FRONT_AUTH" | grep '^sync_outbox'
```

La política de conflicto se configura con `SYNC_CONFLICT_POLICY` (`MANUAL` | `PRODUCER_WINS` |
`CONSUMER_WINS`). Para probar `PRODUCER_WINS`, edita el mismo ítem en el Producer y en el Consumer
antes de que se confirme el del Consumer; el conflicto (409) se resuelve solo adoptando el del Producer.

El tipo de contenido (`PRODUCTO` | `SERVICIO` | `CONTENIDO`) es opcional al crear/editar:

```bash
curl -s -X POST "$CONSUMER/api/items" -H "$FRONT_AUTH" -H 'Content-Type: application/json' \
  -d '{"nombre":"Asesoría","estado":"ACTIVO","tipo":"SERVICIO"}'
```
