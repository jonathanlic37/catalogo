# Catálogo distribuido con sincronización por Webhooks (SSoT)

Sistema de tres servicios en el que el **Producer** es la única fuente de verdad (Single Source of Truth) y el **Consumer** mantiene una réplica local de lectura y le envía los cambios mediante webhooks idempotentes.

| Servicio | Stack | Rol | Puerto host |
|---|---|---|---|
| `producer-api` | Java 21, Spring Boot 3.3, JPA, SQLite | Fuente de verdad. Recibe webhooks, aplica cambios, API de administración directa y lectura autoritativa | `127.0.0.1:8082` |
| `consumer-api` | Java 21, Spring Boot 3.3, JPA, SQLite | Réplica de lectura, API única del frontend, cliente de webhooks | `127.0.0.1:8081` |
| `frontend` | React + Vite + TypeScript, servido por nginx sin privilegios | UI; habla **solo** con el Consumer (vía `/api`) | `127.0.0.1:8088` |

## Arquitectura

```
Navegador ──► nginx (frontend) ──/api + Bearer──► Consumer API ──webhook + Bearer + Idempotency-Key──► Producer API
                                                    SQLite (réplica + outbox_events)                     SQLite (SSoT)
                                                    outbox PENDING → SENT / FAILED                       idempotency_records
```

Redes Docker: `frontend` (nginx ↔ consumer) y `backend` (consumer ↔ producer, `internal: true`, sin salida a Internet); una red `debug` solo permite publicar el puerto del Producer en `127.0.0.1` para consultarlo desde el host. Cada BD SQLite vive en su propio volumen (`producer-data`, `consumer-data`); las copias de seguridad del Producer, en `producer-backups`.

### Flujo de una escritura

1. El frontend hace `POST/PUT/DELETE /api/items` al Consumer.
2. El Consumer guarda el cambio en su réplica con `syncStatus = PENDING` y **encola un evento en su outbox** en la misma transacción, con una `Idempotency-Key` (UUID); responde **202**.
3. Un relay (inmediato tras el commit y, ante fallo, cada `SYNC_RETRY_INTERVAL_MS`) envía `POST /webhooks/catalogo` al Producer (`Authorization`, `Idempotency-Key`, `X-Event-Type: CREATED|UPDATED|DELETED`, `occurredAt`).
4. El Producer aplica el cambio en una transacción (descartando eventos fuera de orden por `occurredAt`) y guarda el resultado asociado a la clave. Responde 201/200 con el ítem autoritativo (versión y fechas).
5. El Consumer copia esa respuesta a la réplica, marca el evento `SENT` y el ítem `CONFIRMED` (o borra la fila si era `DELETED`).
6. El frontend hace polling cada 1,5 s **solo mientras haya ítems PENDING** y muestra la insignia «Pendiente de confirmación».

### Escritura directa en el Producer (paso 1 del enunciado)

El Producer es la aplicación central, así que también puede **originar** datos sin pasar por el Consumer: `POST/PUT/DELETE /api/items` (protegidos con el mismo Bearer que el webhook). El id y la versión los asigna el Producer. Los ítems creados o modificados así los descubre el Consumer en la siguiente **reconciliación** (`ReconcileService`, cada 60 s): siembra los que no tiene y actualiza los `CONFIRMED` cuya versión remota sea mayor; si se borran en el Producer, los retira de la réplica tras confirmarlo con una consulta puntual. Así se cubre el flujo «se crea en el Producer → se sincroniza en el Consumer → aparece en React».

### Decisiones técnicas

- **Idempotencia**: clave `Idempotency-Key` + hash SHA-256 (tipo de evento + payload). Misma clave y mismo hash → se devuelve la respuesta almacenada (`Idempotent-Replayed: true`) sin reaplicar. Misma clave con otro contenido → **409**. Todo ocurre en una transacción, y el pool de SQLite es de 1 conexión, así que dos entregas simultáneas se serializan.
- **Control de versión** (concurrencia optimista): los `UPDATED` llevan `baseVersion`; si el Producer ya avanzó, responde 409 y el ítem queda `FAILED` (no se reintenta un conflicto).
- **Reintentos**: 5xx o red caída → el ítem sigue `PENDING`, con backoff exponencial (`SYNC_RETRY_INTERVAL_MS` × 2ⁿ, tope 5 min). Tras `SYNC_MAX_RETRIES` intentos pasa a `FAILED`. Cada reintento **reutiliza la misma clave**, por lo que un cambio que sí llegó al Producer no se duplica.
- **Sin transacción durante la llamada HTTP**: evita bloquear la única conexión SQLite.
- **Borrado idempotente**: si el Producer responde 404 a un `DELETED` (ya no existía), el Consumer lo trata como éxito y retira la fila.
- **Si el Producer no responde**, el ciclo de reintentos se corta en el primer fallo de red o 5xx (en vez de esperar un timeout por ítem) y continúa en el siguiente ciclo.
- **Reconciliación** (`ReconcileService`): cada `SYNC_RECONCILE_INTERVAL_MS` (60 s) el Consumer recorre la lista del Producer **por páginas de 500**: siembra una réplica vacía, recoge cambios hechos directamente en el Producer y retira ítems borrados allí (confirmándolo con una consulta puntual antes de borrar). Nunca toca ítems con cambios locales PENDING/FAILED.
- **Lecturas acotadas**: la lista del Consumer (`GET /api/items?page&size&q&estado`, máx. 100 por página) busca y filtra en base de datos; `GET /api/items/summary` da los contadores globales. Hay índices para las consultas frecuentes y compresión gzip de las respuestas JSON.
- **Edición bloqueada mientras hay cambios sin confirmar**: solo los ítems `CONFIRMED` se pueden editar/eliminar (409 en otro caso). Así el contenido de un envío que pudo haber llegado al Producer nunca cambia bajo la misma clave. En `FAILED` la UI ofrece *Reintentar* (mismo contenido) o *Descartar cambio* (recupera la versión del Producer).
- **Sin secretos en el bundle**: el frontend llama a `/api` (relativo) y **no envía ningún token**. El nginx del contenedor inyecta `Authorization: Bearer $FRONTEND_TO_CONSUMER_TOKEN` en runtime (plantilla `envsubst`). `VITE_API_URL` solo contiene una URL base.
- **Seguridad**: Bearer tokens estáticos comparados en tiempo constante, **exigidos de al menos 32 caracteres** (las APIs no arrancan con tokens cortos ni con el valor de ejemplo); sesión stateless, CSRF desactivado (API sin cookies); CORS global configurable; `@RestControllerAdvice` (basado en `ResponseEntityExceptionHandler`) devuelve `ProblemDetail` (RFC 7807) sin stack traces y conserva los códigos estándar (415, 405, 400...); `/actuator/health` es el único endpoint público.
- **nginx**: imagen sin privilegios, CSP estricta, `X-Frame-Options`, `nosniff`, `Referrer-Policy`, `Permissions-Policy`, cuerpo máximo 64 KB, gzip, caché inmutable para assets con hash y `no-store` en `/api`.
- **Contenedores**: Java con sistema de ficheros de solo lectura, sin capabilities, `no-new-privileges`, límites de memoria/procesos, logs rotados y apagado ordenado (`stop_grace_period`). La UI escucha solo en `127.0.0.1` (no tiene login de usuario; ver limitaciones).
- **Mantenimiento del Producer**: los registros de idempotencia se purgan pasados 7 días (`IDEMPOTENCY_RETENTION_DAYS`) y cada 6 h se hace una copia consistente de la BD con la API de backup de SQLite (se conservan las últimas 8, en el volumen `producer-backups`).
- **SQLite**: modo WAL, `busy_timeout`, pool Hikari de 1 conexión, `ddl-auto=update`.

### Outbox transaccional, conflictos y eventos fuera de orden

- **Outbox transaccional**: cada cambio se persiste en `outbox_events` dentro de la misma transacción que la escritura de la réplica. El relay (`SyncService`) lo envía y registra el resultado (`PENDING`/`SENT`/`FAILED`) con el motivo y el número de intentos, así que ningún cambio se pierde si el proceso se reinicia.
- **Registro de eventos fallidos**: los eventos `FAILED` permanecen en `outbox_events` con `lastError`, intentos y fechas, y son la base del botón *Reintentar* de la UI.
- **Gestión de conflictos**: `SYNC_CONFLICT_POLICY` (`MANUAL` por defecto) decide ante un 409: `PRODUCER_WINS` descarta el cambio local y adopta el del Producer; `CONSUMER_WINS` rebasa el cambio local sobre la versión actual y reintenta.
- **Eventos fuera de orden**: cada evento lleva `occurredAt`; el Producer guarda `lastEventAt` y descarta los más antiguos que el último aplicado, evitando revertir estado.
- **Healthchecks diferenciados**: Actuator expone `liveness` y `readiness` por servicio; los healthchecks de Compose usan readiness.
- **Métricas de sincronización**: Micrometer publica `sync.outbox.attempts|success|failures|retries|conflicts` y los gauges `sync.outbox.pending|failed` en `/actuator/prometheus` (protegido con Bearer).
- **Contract tests**: ambos módulos validan sus payloads contra fixtures compartidos (`contracts/webhook-created.json`, `contracts/item-response.json`).
- **Multi-tipo de contenido**: la entidad incluye `tipo` (`PRODUCTO|SERVICIO|CONTENIDO`); añadir tipos no requiere cambios de esquema.
- **Reconciliación manual**: `POST /api/reconcile` (botón «Sincronizar ahora» en la UI) fuerza la sincronización y devuelve cuántos ítems se sembraron, actualizaron o borraron.
- **Purga del outbox**: los eventos `SENT` se eliminan pasado `SYNC_OUTBOX_RETENTION_DAYS` (7 por defecto); los `FAILED` se conservan para reintento/auditoría.
- **Rate limiting**: nginx limita por IP las peticiones a `/api` (`RATE_LIMIT_RATE`/`RATE_LIMIT_BURST`, responde 429).

### Copias de seguridad y restauración

```bash
# Ver copias disponibles
docker compose run --rm --no-deps --entrypoint ls producer-api -l /backups
# Restaurar (con el Producer parado): copia la elegida sobre la BD activa
docker compose stop producer-api consumer-api
docker run --rm -v catalogo_producer-backups:/b -v catalogo_producer-data:/d alpine \
  sh -c 'cp /b/producer-AAAAMMDD-HHMMSS-mmm.db /d/producer.db && rm -f /d/producer.db-wal /d/producer.db-shm'
docker compose start producer-api consumer-api
```

La réplica del Consumer no necesita backup: se reconstruye sola desde el Producer con la reconciliación.

## Puesta en marcha

Requisitos: Docker + Docker Compose v2.

```bash
cp .env.example .env && chmod 600 .env
# Genera tokens propios (obligatorio: las APIs rechazan el valor de ejemplo):
sed -i "s/^CONSUMER_TO_PRODUCER_TOKEN=.*/CONSUMER_TO_PRODUCER_TOKEN=$(openssl rand -hex 32)/" .env
sed -i "s/^FRONTEND_TO_CONSUMER_TOKEN=.*/FRONTEND_TO_CONSUMER_TOKEN=$(openssl rand -hex 32)/" .env

docker compose up -d --build
docker compose ps            # los tres servicios deben quedar "healthy"
```

Orden de arranque garantizado por healthchecks: `producer-api` → `consumer-api` → `frontend`.

- UI: <http://localhost:8088>
- Consumer API: <http://localhost:8081> · Producer API: <http://localhost:8082> (solo `127.0.0.1`)

Parar / borrar datos: `docker compose down` / `docker compose down -v`.

### Pruebas automatizadas

No requieren Java local (usan Docker):

```bash
docker run --rm -v "$PWD/producer-api":/app -v catalogo-m2:/root/.m2 -w /app maven:3.9-eclipse-temurin-21 mvn -q -B test
docker run --rm -v "$PWD/consumer-api":/app -v catalogo-m2:/root/.m2 -w /app maven:3.9-eclipse-temurin-21 mvn -q -B test
```

- `producer-api`: `WebhookIdempotencyTest` (16 pruebas) — duplicados, conflicto de clave, versión obsoleta, actualización y borrado idempotentes, escritura directa (crear/editar/borrar) y su 401, eventos fuera de orden, contract test del webhook y de la respuesta, paginación, 401, validación sin stack trace, códigos estándar (415/400), purga de idempotencia, backup legible con los datos y validación de tokens.
- `frontend` (15 pruebas, Vitest + Testing Library; se ejecutan en una copia aislada para no escribir en el proyecto):
  ```bash
  docker run --rm -v "$PWD/frontend":/src:ro -w /tmp node:22-alpine sh -c \
    "mkdir /work && cd /src && tar cf - --exclude=node_modules --exclude=dist . | tar xf - -C /work \
     && cd /work && npm install --no-audit --no-fund >/dev/null 2>&1 && npx vitest run && npx tsc --noEmit"
  ```
- `consumer-api`: `SyncServiceWireMockTest` (15 pruebas) + `OutboxBatchCutoffTest` (1) — el webhook llega al Producer (WireMock) con Bearer, `Idempotency-Key` y `X-Event-Type`; reintentos con la misma clave; rechazo 4xx → `FAILED`; edición y borrado (incluido 404 idempotente); contract test del payload; reconciliación manual con resumen; purga del outbox; paginación, búsqueda y resumen; seguridad y errores estándar; corte del lote con el Producer caído.

### Integración continua

`.github/workflows/ci.yml` corre en cada push/PR a `main`:
`backend` (tests de ambos módulos, matriz), `frontend` (vitest + tsc), `compose` (valida `docker compose config`) y `e2e`
(levanta el stack con `docker compose up -d --build` y ejecuta `scripts/smoke-test.sh`, que recorre salud, 401,
alta vía Consumer, confirmación en el Producer, idempotencia del webhook, alta directa en el Producer y reconciliación).

Para correr el smoke en local con el stack levantado:

```bash
docker compose up -d --build
./scripts/smoke-test.sh
```

### Desarrollo del frontend sin Docker

```bash
cd frontend && npm install
DEV_CONSUMER_TOKEN=<FRONTEND_TO_CONSUMER_TOKEN> npm run dev   # http://localhost:5173, proxy /api → :8081
```

El token se pasa al proxy de Vite por variable de entorno **sin prefijo `VITE_`**, por lo que no entra al bundle.

## Probar el flujo

Ver [`docs/curl.md`](docs/curl.md) (comandos `curl`) y [`docs/catalogo.postman_collection.json`](docs/catalogo.postman_collection.json) (Postman).

## Supuestos y limitaciones

- Un único Consumer. Con varios habría que añadir un canal Producer → Consumer (push/eventos); hoy la réplica se alinea por reconciliación periódica.
- Consistencia eventual: entre el 202 y la confirmación la réplica muestra el cambio local como PENDING.
- **La UI no tiene autenticación de usuario**: nginx inyecta el token del servicio, así que quien llegue al puerto de la UI puede modificar el catálogo. Por eso escucha solo en `127.0.0.1` por defecto. Para exponerla hace falta un proxy con TLS y login (OIDC, basic auth...) delante.
- Tokens Bearer estáticos compartidos (sin usuarios, roles ni rotación) y sin TLS entre contenedores. Para producción: OAuth2/JWT, TLS y gestión de secretos.
- La API de administración del Producer reutiliza el mismo Bearer que el webhook (un único token de servicio). Para producción convendría separar el token de administración del de la máquina-a-máquina.
- Sin límite de peticiones (rate limiting) en las APIs; solo el cuerpo máximo de 64 KB en nginx.
- SQLite es de un único escritor: adecuado para este alcance (decenas de miles de ítems), no para alta concurrencia. La copia de seguridad retiene brevemente la única conexión.
- La búsqueda por nombre usa `lower()` de SQLite, que solo distingue mayúsculas/minúsculas ASCII (`Café` ≠ `CAFÉ`).
- La reconciliación recorre todo el catálogo del Producer cada minuto; con cientos de miles de ítems habría que hacerla incremental.
- `ddl-auto=update` en lugar de migraciones (Flyway) por simplicidad. Las tablas/columnas nuevas (`outbox_events`, `tipo`) se crean solas sobre bases vacías; una base con datos previos exigiría migración o recrear el volumen.
- El outbox no tiene todavía purga de eventos `SENT`: crece con cada cambio (se puede añadir una retención como la de `idempotency_records`).
- Un conflicto de versión no se resuelve automáticamente: el usuario reintenta o descarta el cambio local.
- Informe completo de la auditoría (hallazgos, mediciones y riesgos residuales): [`docs/AUDITORIA.md`](docs/AUDITORIA.md).
