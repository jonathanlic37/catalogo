# Catálogo distribuido con sincronización por webhooks (SSoT)

Dos APIs Spring Boot independientes y un frontend React. La **Producer API** es la única fuente de
verdad (SSoT). La **Consumer API** mantiene una proyección local para el frontend y envía cada
cambio al Producer mediante un **webhook autenticado e idempotente**. Un cambio hecho en la UI no
se da por bueno hasta que el Producer lo acepta.

```bash
cp .env.example .env
docker compose up --build
```

Luego abre **<http://localhost:8088>** e inicia sesión con **`demo` / `demo-dev-only`** (contraseñas en
`.env`: `DEMO_USER_PASSWORD`, `DEMO_ADMIN_PASSWORD`). El primer arranque
compila las imágenes y tarda unos minutos.

---

## Índice

1. [Cumplimiento del enunciado](#1-cumplimiento-del-enunciado)
2. [Puesta en marcha](#2-puesta-en-marcha)
3. [Arquitectura](#3-arquitectura)
4. [Modelo de información](#4-modelo-de-información)
5. [Flujo de sincronización](#5-flujo-de-sincronización)
6. [Qué ocurre si…](#6-qué-ocurre-si)
7. [Errores, duplicados y fallos de sincronización](#7-errores-duplicados-y-fallos-de-sincronización)
8. [Seguridad y estrategia de autenticación](#8-seguridad-y-estrategia-de-autenticación)
9. [Configuración](#9-configuración)
10. [Probar el sistema](#10-probar-el-sistema)
11. [Tests automatizados](#11-tests-automatizados)
12. [Decisiones técnicas y trade-offs](#12-decisiones-técnicas-y-trade-offs)
13. [Supuestos](#13-supuestos)
14. [Limitaciones conocidas y lo que no se implementó](#14-limitaciones-conocidas-y-lo-que-no-se-implementó)
15. [Cómo evolucionaría](#15-cómo-evolucionaría)
16. [Estructura del repositorio](#16-estructura-del-repositorio)

---

## 1. Cumplimiento del enunciado

| Criterio de aceptación | Cómo se cumple | Dónde se comprueba |
|---|---|---|
| Las dos APIs son servicios independientes | Dos proyectos Maven, dos imágenes y dos contenedores. Solo se comunican por HTTP. | `docker-compose.yml` |
| Cada API usa su propio volumen SQLite | `producer-data` y `consumer-data`. Ninguna API monta el volumen de la otra. | `docker-compose.yml` |
| Producer es la fuente canónica | Asigna versión y fechas. El Consumer reescribe su proyección con la respuesta del Producer. | §5, `SyncService` |
| Consumer sincroniza datos creados en Producer | Reconciliación automática cada 60 s y manual (botón «Sincronizar ahora» / `POST /api/reconcile`). | smoke paso 1, test `reconciliacion*` |
| React obtiene la información desde Consumer | El SPA solo llama a `/api` (nginx → Consumer). Nunca accede a una BD ni al Producer. | `frontend/src/api` |
| Un cambio desde React llega al Producer por webhook | `PUT /api/items/{id}` → outbox → `POST /webhooks/catalogo` con Bearer e `Idempotency-Key`. | smoke paso 2, test `editar*` |
| Consumer actualiza su proyección con el resultado confirmado | Aplica versión, fechas y campos canónicos de la respuesta. | smoke paso 2 (v2) |
| Un webhook duplicado no genera duplicados ni corrupción | Idempotency-Key + hash del payload: el reenvío devuelve la respuesta original sin reaplicar. | smoke paso 3, test `webhookDuplicado*` |
| El reinicio de Consumer no elimina su proyección | Proyección y outbox persisten en `consumer-data`. Los eventos pendientes se reenvían tras el reinicio. | smoke paso 6 |
| Los errores de comunicación no se presentan como éxitos | Las escrituras responden **202 PENDING**. Solo pasan a *Sincronizado* con la confirmación del Producer. Los fallos se muestran como *Error de sincronización* con el motivo. | smoke paso 5, UI |
| Los endpoints protegidos rechazan peticiones sin Bearer válido | 401 en Consumer (JWT OIDC), Producer y webhook (token de servicio). 403 si la credencial no tiene el privilegio: el token del Consumer no puede escribir directamente en el Producer. | smoke, tests `sinToken*`, `separacionDePrivilegios*` |
| Se ejecuta desde cero con Docker Compose | `cp .env.example .env && docker compose up --build`, sin pasos manuales. El CI lo hace igual. | `.github/workflows/ci.yml` |
| Pruebas automatizadas del flujo principal | 64 tests (22 Producer, 21 Consumer, 21 frontend), smoke E2E de API y **E2E de navegador (Playwright)** con login real en Keycloak. | §11 |

Funcionalidades opcionales implementadas, todas en §5 a §8:

- Outbox transaccional.
- Reintentos con backoff exponencial.
- Control de versiones.
- Detección y gestión de conflictos (política configurable).
- Reconciliación automática y manual.
- Manejo de eventos fuera de orden.
- Contract tests entre las APIs.
- Healthchecks diferenciados (liveness/readiness).
- Métricas de sincronización (Prometheus).
- Registro de eventos fallidos (API y pantalla *Sincronización*).
- UI con estados claros de sincronización.
- Paginación, filtros y búsqueda.
- Varios tipos de contenido (`PRODUCTO`, `SERVICIO`, `CONTENIDO`).

## 2. Puesta en marcha

Solo hace falta Docker con Compose v2. No hace falta Java ni Node en local.

```bash
cp .env.example .env
docker compose up --build        # o `up -d --build` para dejarlo en segundo plano
docker compose ps                # auth, producer-api, consumer-api y frontend en "healthy"
```

El orden de arranque lo garantizan los healthchecks: `auth` y `producer-api` primero, después
`consumer-api` y por último `frontend`.

| Servicio | URL (solo `127.0.0.1`) | Credenciales |
|---|---|---|
| UI (React) | <http://localhost:8088> | `demo` / `DEMO_USER_PASSWORD` (usuario) · `admin` / `DEMO_ADMIN_PASSWORD` (además, métricas). Con `.env.example`: `demo-dev-only` / `admin-dev-only` |
| Consumer API | <http://localhost:8081> | JWT del IdP |
| Producer API | <http://localhost:8082> | `PRODUCER_ADMIN_TOKEN` (escritura directa) · `CONSUMER_TO_PRODUCER_TOKEN` (webhook y lectura) |
| Keycloak (IdP) | <http://localhost:8095> | consola: `KEYCLOAK_ADMIN` / `KEYCLOAK_ADMIN_PASSWORD` |

`.env.example` trae valores **`dev-only-…`**. No son secretos reales: sirven para arrancar en
local sin pasos extra, y las APIs lo advierten en el log. Fuera de local, sustitúyelos:

```bash
sed -i "s/^CONSUMER_TO_PRODUCER_TOKEN=.*/CONSUMER_TO_PRODUCER_TOKEN=$(openssl rand -hex 32)/" .env
chmod 600 .env
```

Para parar: `docker compose down`. Para borrar también los datos: `docker compose down -v`.

## 3. Arquitectura

```
                 OIDC (Authorization Code + PKCE)
   Navegador ─────────────────────────────────────────► Keycloak (auth)
      │  React SPA · JWT en memoria                          ▲ JWKS (validación de firma)
      ▼                                                      │
   nginx (frontend) ──/api + Bearer JWT──► Consumer API ─────┘
   CSP · rate limit · 64 KB                 │  SQLite: proyección + outbox_events
                                            │  relay del outbox ─► POST /webhooks/catalogo
                                            │    Bearer de servicio · Idempotency-Key · X-Event-Type
                                            │  reconciliación ◄─ GET /api/items (paginado)
                                            ▼
                                       Producer API (SSoT)
                                       SQLite: items + idempotency_records · backups
```

| Servicio | Tecnología | Responsabilidad |
|---|---|---|
| `producer-api` | Java 21, Spring Boot 3.3, JPA, SQLite | Estado canónico. Recibe el webhook, valida, aplica e informa del estado aceptado. API de administración directa (crear/editar/borrar) y lectura paginada. |
| `consumer-api` | Java 21, Spring Boot 3.3, JPA, SQLite | API única del frontend. Mantiene la proyección local, el outbox, el relay con reintentos, la reconciliación, las métricas y el registro de eventos. |
| `frontend` | React 18 + Vite + TypeScript, TanStack Query, nginx sin privilegios | UI de consulta y edición con estados de sincronización. Hace de proxy `/api` hacia el Consumer. |
| `auth` | Keycloak 26 (modo dev) | Proveedor de identidad OIDC de los usuarios de la UI. |

**Redes.** `backend` es interna, sin salida a Internet, y une Consumer, Producer y Keycloak.
`frontend` une nginx y Consumer. `debug` solo sirve para publicar en `127.0.0.1` los puertos del
Producer y de Keycloak.

**Volúmenes.** `producer-data` guarda la SSoT, `producer-backups` sus copias y `consumer-data` la
proyección con el outbox.

## 4. Modelo de información

Entidad principal: el **ítem de catálogo**.

| Campo | Requerido por el enunciado | Motivo |
|---|---|---|
| `id` (UUID) | ✔ identificador | El Consumer lo genera en las altas y el Producer lo respeta, así la identidad es la misma en ambas BD. En las altas directas lo asigna el Producer. |
| `nombre` (≤120) | ✔ nombre/título | |
| `descripcion` (≤1000) | ✔ descripción | |
| `estado` (`ACTIVO`/`INACTIVO`) | ✔ estado | |
| `fechaCreacion`, `fechaActualizacion` | ✔ fechas | Las fija el Producer: son canónicas. |
| `version` | ampliación | Concurrencia optimista y detección de conflictos. |
| `tipo` (`PRODUCTO`/`SERVICIO`/`CONTENIDO`) | ampliación | Varios tipos de contenido sin cambiar el esquema; los atributos propios de cada tipo son una evolución prevista (§15). |

Además, la proyección del Consumer guarda el estado de sincronización de cada ítem:
`syncStatus` (`PENDING`/`CONFIRMED`/`FAILED`), la operación pendiente y el último error. El
Producer guarda `lastEventAt` para ordenar los eventos que no traen versión.

## 5. Flujo de sincronización

Los pasos siguen el flujo del enunciado:

1. **Se crea en el Producer.** `POST /api/items` en el Producer, o un alta que llega por webhook.
2. **El Consumer sincroniza.** La reconciliación, cada 60 s o a demanda, recorre el Producer por
   páginas de 500. Siembra los ítems que no tiene y actualiza los `CONFIRMED` cuya versión remota
   sea mayor. Retira los que el Producer borró, tras confirmarlo con una consulta puntual. Nunca
   toca un ítem con cambios locales pendientes o fallidos.
3. **Aparece en React.** La UI lista desde el Consumer, con paginación, búsqueda y filtros.
4. **y 5. El usuario edita y React lo envía al Consumer.** `PUT /api/items/{id}` con el JWT del
   usuario.
6. **El Consumer envía el webhook.** En **una sola transacción** actualiza la proyección con
   `syncStatus=PENDING` y encola un evento en `outbox_events`. Ese evento lleva el payload, una
   `Idempotency-Key` (UUID) y `baseVersion` (la versión en la que se basó la edición). Responde
   **202**. Tras el commit, el relay envía `POST /webhooks/catalogo` con el Bearer de servicio,
   `Idempotency-Key`, `X-Event-Type` y `occurredAt`.
7. **El Producer valida y aplica.** Valida el payload (Bean Validation). Si la clave ya se procesó,
   devuelve la respuesta guardada. Si `baseVersion` no coincide con la versión actual, responde 409
   (conflicto). Si todo es correcto, aplica el cambio, sube la versión y guarda el resultado junto
   a la clave, todo en una transacción.
8. **El Producer devuelve el estado aceptado.** Responde 201/200 con el ítem canónico: versión y
   fechas.
9. **El Consumer actualiza su proyección.** En **una sola transacción** marca el evento `SENT` y
   reescribe el ítem con la respuesta canónica en estado `CONFIRMED`.
10. **El frontend lo muestra.** Mientras haya ítems pendientes, la UI consulta cada 1,5 s, y la
    insignia pasa de «Pendiente de confirmación» a «Sincronizado».

## 6. Qué ocurre si…

| Situación | Comportamiento |
|---|---|
| **El Producer no está disponible** | El cambio queda guardado en el outbox y el ítem en `PENDING`; la UI lo muestra como pendiente, nunca como éxito. El relay reintenta con backoff exponencial (10 s × 2ⁿ, tope 5 min) y, si el Producer no responde, corta el lote en el primer fallo para no esperar un timeout por evento. Tras `SYNC_MAX_RETRIES` intentos el evento pasa a `FAILED`: queda en el registro con su motivo y la UI ofrece *Reintentar*. El Consumer sigue sirviendo su proyección, porque su readiness no depende del Producer. |
| **El webhook se recibe más de una vez** | El Producer guarda cada `Idempotency-Key` con el hash SHA-256 de tipo y payload, y la respuesta que dio. Si llega la misma clave con el mismo contenido, devuelve esa respuesta (`Idempotent-Replayed: true`) **sin reaplicar**. Si llega la misma clave con otro contenido, responde 409. Dos entregas simultáneas se serializan, porque la transacción y el pool SQLite de 1 conexión lo impiden en paralelo. |
| **El registro cambió en el Producer antes de recibir la modificación** | El evento trae `baseVersion` distinta de la actual, así que el Producer responde **409** y no sobrescribe nada. El Consumer lo trata según `SYNC_CONFLICT_POLICY`. Con `MANUAL`, el valor por defecto, el ítem queda *Error de sincronización* con el motivo, y el usuario puede *Descartar cambio* (adopta la versión canónica) o volver a editar. Con `PRODUCER_WINS` se adopta la versión del Producer automáticamente. Con `CONSUMER_WINS` se rebasa el cambio sobre la versión actual y se reintenta. |
| **El Consumer se reinicia durante la sincronización** | El cambio y su evento se escribieron juntos antes de responder 202, así que no se pierden. Si el Producer ya lo había aplicado pero el Consumer cayó antes de registrar la respuesta, el evento sigue `PENDING` y se reenvía con **la misma clave**: el Producer devuelve la respuesta original y no lo duplica. La confirmación (evento `SENT` más proyección) es atómica, por lo que nunca queda un ítem `PENDING` sin evento. La proyección vive en un volumen y no se borra al reiniciar. |
| **Llega un evento fuera de orden** | Primero decide la versión: un evento basado en una versión superada es conflicto (409). Así no se depende de los relojes: si la versión coincide, se aplica aunque su `occurredAt` sea anterior. Los eventos que no traen versión se ordenan por `occurredAt`: uno más antiguo que el último aplicado se descarta sin revertir el estado. En el Consumer, la reconciliación solo acepta versiones mayores. |

## 7. Errores, duplicados y fallos de sincronización

- **Errores de API.** Ambos backends responden `ProblemDetail` (RFC 7807) con un mensaje seguro y,
  en validación, el error de cada campo. Nunca incluyen stack traces
  (`server.error.include-stacktrace=never` y un `@RestControllerAdvice` basado en
  `ResponseEntityExceptionHandler`). Los códigos estándar se respetan: 400, 401, 403, 404, 405,
  409, 415, 502. Lo inesperado responde 500 genérico y se registra en el log.
- **Duplicados.** Ver §6. Además, el Consumer impide editar un ítem con un cambio pendiente (409).
  Así el contenido de un envío que pudo llegar al Producer nunca cambia bajo la misma clave.
- **Fallos de sincronización.** Se distinguen tres casos:
  - **Transitorios** (red, 5xx): reintento con backoff.
  - **Definitivos** (4xx, como conflicto o validación): el evento pasa a `FAILED` sin reintentar.
  - **Borrar lo que ya no existe** (404 en un `DELETED`): se trata como éxito, porque el borrado es
    idempotente.

  Todo evento `FAILED` queda en el **registro de eventos fallidos**
  (`GET /api/sync/events?status=FAILED` y la pantalla *Sincronización*) con su motivo, intentos y
  fechas. En la lista, el ítem ofrece *Reintentar* (reenvía el mismo evento con la misma clave) o
  *Descartar cambio* (recupera la versión canónica).
- **Observabilidad.** `/actuator/prometheus` (solo rol `admin`) expone `sync_outbox_attempts|success|failures|retries|conflicts_total`,
  los gauges `sync_outbox_pending|failed`, `sync_reconcile_runs_total`,
  `sync_reconcile_items_total{change=created|updated|deleted}` y `sync_reconcile_last_success_seconds`.

## 8. Seguridad y estrategia de autenticación

### Estrategia elegida

| Tramo | Mecanismo | Motivo |
|---|---|---|
| Usuario → UI → **Consumer API** | **OAuth2/OIDC con Keycloak.** El SPA usa Authorization Code + PKCE (S256), cliente público y sin secreto. El Consumer es *resource server*: valida la firma JWT contra el JWKS, el `iss` y la expiración, y exige el rol de realm `user` o `admin`. | Identidad real de usuario sin guardar contraseñas en el sistema y sin ningún token en el bundle. El JWT vive en memoria y la sesión en `sessionStorage`. |
| **Consumer → Producer** (webhook, lectura y reconciliación) | **Bearer token de servicio** (`CONSUMER_TO_PRODUCER_TOKEN`, rol `SERVICE`) comparado en tiempo constante. Solo puede enviar el webhook y leer. La aplicación no arranca con tokens de menos de 32 caracteres ni con marcadores sin sustituir. | Comunicación máquina a máquina en una red interna: suficiente y simple (el enunciado no exige OAuth). |
| API de administración del **Producer** | **Credencial distinta** (`PRODUCER_ADMIN_TOKEN`, rol `ADMIN`): escritura directa, lectura y métricas. No sirve para el webhook, y el Producer no arranca si coincide con la del Consumer. | Mínimo privilegio: el Consumer **no puede** escribir en la fuente de verdad saltándose el webhook (403). Solo el Producer conoce el token de administración. |

Keycloak arranca con dos usuarios de demostración: `demo` (rol `user`) y `admin` (roles `user` y
`admin`). **Sus contraseñas no están en el repositorio**: el realm versionado solo contiene los
placeholders `${DEMO_USER_PASSWORD}` y `${DEMO_ADMIN_PASSWORD}`, que Keycloak resuelve al importarlo
desde las variables de entorno. El cliente `catalogo-cli`, solo para desarrollo, permite obtener un token desde curl o
Postman. El cliente del SPA **no** admite el password grant.

### Requisitos mínimos del enunciado

| Requisito | Implementación |
|---|---|
| Bearer Token en endpoints protegidos | JWT en el Consumer; en el Producer, token de servicio (webhook y lectura) y token de administración (escritura directa). Solo `/actuator/health/**` es público. El resto responde 401 o 403. |
| Autenticación del webhook | `POST /webhooks/catalogo` exige el Bearer de servicio. |
| Secretos solo por variables de entorno | Tokens y contraseñas vienen de `.env` → `docker-compose.yml` → variables del contenedor. `.env` está en `.gitignore`. |
| Ningún secreto hardcodeado | El código solo lee `${…}`, y el realm de Keycloak solo lleva placeholders. `.env.example` trae valores de ejemplo `dev-only-…` que las APIs marcan en el log. |
| Sin tokens privados en el bundle | El bundle solo tiene configuración pública del OIDC. El token se obtiene en runtime. El smoke test comprueba que el bundle no contiene el token de servicio. |
| Validación estricta de payloads | Bean Validation en los DTO (`@NotBlank`, `@Size`, `@Pattern`, enums). Cabeceras `Idempotency-Key` y `X-Event-Type` validadas. Tipo de contenido JSON obligatorio (415). nginx limita el cuerpo a 64 KB. |
| Consultas parametrizadas | Spring Data JPA y Criteria API. La búsqueda `LIKE` escapa los comodines del usuario. No hay SQL concatenado con entrada de usuario. El `backup to` de SQLite usa una ruta generada por la aplicación. |
| Errores sin stack traces | Ver §7. |
| CORS controlado | Orígenes explícitos desde `CORS_ALLOWED_ORIGINS`, métodos y cabeceras en lista blanca, sin credenciales de cookie. |
| Logs sin datos sensibles | Nunca se registran tokens, cabeceras `Authorization` ni payloads. Solo ids de ítem y evento, estados y motivos. |
| Protección contra eventos duplicados | Idempotency-Key + hash (§6). |
| URL del webhook no configurable por el usuario | `PRODUCER_BASE_URL` es configuración del despliegue y la ruta está fija en código. Ningún endpoint acepta una URL de destino. |

### OWASP Top 10 (2021)

| Riesgo | Mitigación |
|---|---|
| A01 Control de acceso roto | `denyAll` por defecto y roles por ruta (métricas solo `admin`). En el Producer, credenciales separadas: el Consumer solo puede usar el webhook. Las APIs no exponen nada sin autenticar salvo health. |
| A02 Fallos criptográficos | JWT firmado y validado contra el JWKS. Comparación en tiempo constante del token de servicio. TLS en el proxy es evolución documentada. |
| A03 Inyección | Consultas parametrizadas, `LIKE` escapado y validación de entrada. |
| A04 Diseño inseguro | SSoT, idempotencia, concurrencia optimista y edición bloqueada con cambios pendientes. |
| A05 Configuración insegura | Contenedores `read_only`, `cap_drop: ALL`, `no-new-privileges`, usuarios no root y límites de memoria. nginx con CSP estricta, `X-Frame-Options`, `nosniff`, `Referrer-Policy` y `server_tokens off`. Puertos solo en `127.0.0.1`. |
| A06 Componentes vulnerables | Versiones fijadas (Spring Boot 3.3, Keycloak 26, `npm ci` con lockfile). |
| A07 Fallos de identificación | IdP estándar con PKCE, protección de fuerza bruta en el realm y tokens de 5 min. |
| A08 Integridad de datos | Hash del payload por clave de idempotencia y contratos verificados en tests. |
| A09 Registro y monitorización | Registro de eventos fallidos, métricas de sincronización y logs sin secretos. |
| A10 SSRF | La URL de destino no es configurable por el usuario. |

## 9. Configuración

Todas las variables están comentadas en [`.env.example`](.env.example). Las principales:

| Variable | Para qué |
|---|---|
| `FRONTEND_PORT`, `CONSUMER_PORT`, `PRODUCER_PORT`, `KEYCLOAK_PORT` | Puertos del host. |
| `CONSUMER_TO_PRODUCER_TOKEN` | Token de servicio del Consumer: webhook y lectura (≥ 32 caracteres). |
| `PRODUCER_ADMIN_TOKEN` | Token de administración del Producer: escritura directa. Debe ser distinto del anterior. |
| `DEMO_USER_PASSWORD`, `DEMO_ADMIN_PASSWORD` | Contraseñas de los usuarios de demostración del realm. |
| `KEYCLOAK_ADMIN`, `KEYCLOAK_ADMIN_PASSWORD` | Consola de Keycloak. |
| `OIDC_ISSUER`, `OIDC_JWK_SET_URI`, `OIDC_CLIENT_ID`, `OIDC_REDIRECT_URI` | OIDC del SPA y del resource server. |
| `CORS_ALLOWED_ORIGINS` | Orígenes permitidos. |
| `SYNC_MAX_RETRIES`, `SYNC_RETRY_INTERVAL_MS`, `SYNC_RECONCILE_INTERVAL_MS`, `SYNC_CONFLICT_POLICY`, `SYNC_OUTBOX_RETENTION_DAYS` | Sincronización. |
| `IDEMPOTENCY_RETENTION_DAYS`, `BACKUP_*` | Mantenimiento del Producer. |
| `RATE_LIMIT_RATE`, `RATE_LIMIT_BURST` | Límite de peticiones por IP en nginx (responde 429). |

## 10. Probar el sistema

**Desde la interfaz.** Recorrido del flujo completo:

1. Entra en <http://localhost:8088> con `demo` y la contraseña `DEMO_USER_PASSWORD` (`demo-dev-only`).
2. Crea un ítem en el Producer desde la terminal (§3b de [`docs/curl.md`](docs/curl.md)) y pulsa
   **Sincronizar ahora**: aparece en la lista.
3. Edítalo. Verás «Pendiente de confirmación» y, en uno o dos segundos, «Sincronizado» con la
   versión nueva.
4. Para provocar un conflicto, edítalo en el Producer con curl y después en la UI sin sincronizar.
   Queda «Error de sincronización» con el motivo. Pulsa *Descartar cambio*.
5. Para simular una caída, ejecuta `docker compose stop producer-api` y crea un ítem: queda
   pendiente. Ejecuta `docker compose start producer-api` y se confirma solo.
6. Revisa **Sincronización** en la barra superior: es el registro de eventos, que por defecto
   muestra los fallidos.

**Con curl:** [`docs/curl.md`](docs/curl.md) cubre todos los endpoints y escenarios, y cómo
obtener el token de usuario. **Con Postman:**
[`docs/catalogo.postman_collection.json`](docs/catalogo.postman_collection.json), que obtiene el
token automáticamente.

**Smoke test end-to-end**, contra el stack levantado: recorre los criterios de aceptación y los
cuatro escenarios de §6, e incluye parar el Producer y reiniciar el Consumer.

```bash
./scripts/smoke-test.sh
```

## 11. Tests automatizados

Todos se ejecutan en Docker:

```bash
docker run --rm -v "$PWD":/work -v catalogo-m2:/root/.m2 -w /work/producer-api maven:3.9-eclipse-temurin-21 mvn -q -B test
docker run --rm -v "$PWD":/work -v catalogo-m2:/root/.m2 -w /work/consumer-api maven:3.9-eclipse-temurin-21 mvn -q -B test
docker run --rm -v "$PWD/frontend":/src:ro -w /tmp node:22-alpine sh -c \
  "mkdir /work && cd /src && tar cf - --exclude=node_modules --exclude=dist . | tar xf - -C /work \
   && cd /work && npm ci --no-audit --no-fund >/dev/null 2>&1 && npx vitest run && npx tsc --noEmit"
```

Los tests de backend montan la raíz del repositorio porque los contratos viven en `contracts/`.

| Módulo | Tests | Cubren |
|---|---|---|
| `producer-api` | 20 (`WebhookIdempotencyTest`) | Webhook duplicado, clave reutilizada con otro contenido (409), versión obsoleta en edición y borrado (409), prioridad de la versión sobre `occurredAt`, eventos fuera de orden, escritura directa, paginación, 401, validación sin stack trace, campos desconocidos rechazados, códigos estándar (400/415), **contrato** (acepta `webhook-created.json` y responde con la forma de `item-response.json`), purga de idempotencia, backup legible y validación de tokens. |
| `consumer-api` | 21 (`SyncServiceWireMockTest` + `OutboxBatchCutoffTest`) con el Producer simulado en WireMock | Alta → webhook (Bearer, Idempotency-Key, X-Event-Type) → `CONFIRMED`; edición con `baseVersion`; reintentos con la misma clave; rechazo → `FAILED`; borrado (y 404 idempotente); edición bloqueada si hay cambio pendiente; paginación, búsqueda y filtros (estado, tipo, sincronización); reconciliación (siembra, borrado seguro y manual); registro de eventos fallidos; métricas solo para admin; healthchecks; **contrato** en ambos sentidos; purga del outbox; corte del lote con el Producer caído; seguridad y errores estándar. |
| `frontend` | 21 (Vitest + Testing Library) | Login OIDC (sin sesión no hay llamadas; tras `/callback` la primera petición ya lleva el Bearer), lista con estados de sincronización, paginación, búsqueda, filtros y atajos, reintentar/descartar, confirmación de borrado, reconciliación manual, formulario (validación local y del servidor, sin pisar lo que se escribe) y registro de eventos. |
| E2E de API | `scripts/smoke-test.sh` | Stack real con los criterios de aceptación y escenarios de §6, incluida la separación de privilegios del Producer. |
| E2E de navegador | `e2e/` (Playwright, Chromium) | Login real en Keycloak, dato del Producer visible tras sincronizar, edición desde la UI confirmada por el Producer (v2), conflicto detectado y descartado, filtros, registro de eventos, tema oscuro y vista móvil. Guarda capturas. Se ejecuta en el CI; ver [`e2e/README.md`](e2e/README.md). |

El CI (`.github/workflows/ci.yml`) ejecuta las tres suites, valida `docker compose config` y lanza
el smoke y el E2E de navegador tras `cp .env.example .env && docker compose up -d --build`.

## 12. Decisiones técnicas y trade-offs

| Decisión | Alternativa descartada | Por qué / trade-off |
|---|---|---|
| **Outbox transaccional** en el Consumer | Llamar al Producer dentro de la petición del usuario | La UI responde al instante (202) y el cambio sobrevive a caídas y reinicios. A cambio, hay consistencia eventual y la UI debe mostrar estados intermedios. |
| **Idempotency-Key + hash** en el Producer | Deduplicar por id del ítem o por fechas | La clave identifica el *intento*, no el ítem. Permite reintentos seguros y detecta la reutilización indebida de una clave. |
| **Concurrencia optimista por versión** | Last-write-wins por timestamp | No depende de relojes y no pierde cambios en silencio: el conflicto se ve y se decide. |
| **Edición bloqueada con un cambio pendiente** | Encolar varias ediciones del mismo ítem | Garantiza que una clave nunca cambia de contenido y simplifica el razonamiento. A cambio, el usuario espera 1 o 2 s entre ediciones del mismo ítem. |
| **Reconciliación periódica** (pull) para cambios del Producer | Webhook Producer → Consumer (push) | Un solo sentido de dependencia y el Producer no conoce a sus consumidores. A cambio, los cambios hechos en el Producer tardan hasta 60 s (o un clic) en verse. |
| **HTTP fuera de transacción** y confirmación atómica después | Transacción que envuelva la llamada | SQLite admite un solo escritor, y bloquearlo durante un timeout degradaría todo. |
| **OIDC para usuarios y token estático entre servicios** | OAuth también entre servicios (client credentials) | Proporcional al alcance: el tramo máquina a máquina es interno. |
| **SQLite + `ddl-auto=update`** | Flyway/Liquibase | Lo pide el enunciado y reduce piezas. Migrar a esquemas versionados es el primer paso para producción. |
| **nginx como proxy del SPA** | CORS directo del navegador al Consumer | Mismo origen, CSP estricta, rate limit y límite de cuerpo en un solo punto. |

## 13. Supuestos

- Hay **un** Consumer; el diseño admite varios (§15).
- El catálogo tiene decenas de miles de ítems como mucho (SQLite, reconciliación completa).
- Los relojes de los contenedores están sincronizados. Solo afectan a eventos sin versión.
- La UI se usa en local. Para exponerla se pondría detrás de un proxy TLS (`FRONTEND_BIND`).
- «Producer crea información» significa su API REST de administración, protegida con su propio
  token de administración.

## 14. Limitaciones conocidas y lo que no se implementó

- **Sin TLS** entre contenedores ni en la UI. Todo escucha en `127.0.0.1` y la red `backend` es
  interna.
- **Keycloak en modo desarrollo.** Usa H2 embebido y reimporta el realm en cada arranque, con los
  usuarios de demostración (contraseñas por variable de entorno). En producción usaría una BD
  propia y usuarios reales.
- **Tokens estáticos** entre servicios (servicio y administración, ya separados), sin rotación.
- **No hay push** de cambios del Producer al Consumer: se usa reconciliación, O(N) cada minuto.
- La **búsqueda** usa `lower()` de SQLite, que solo distingue mayúsculas y minúsculas ASCII.
- **`ddl-auto=update`** en lugar de migraciones versionadas.
- Con `SYNC_CONFLICT_POLICY=MANUAL` el conflicto lo resuelve el usuario. No hay fusión de campos.
- **No se implementó:** gestión de usuarios propia (se delega en el IdP); auditoría de quién hizo
  cada cambio en el Producer; atributos específicos por tipo de contenido; borrado lógico; fusión
  automática de conflictos; dashboards de Grafana (las métricas están listas para Prometheus).

## 15. Cómo evolucionaría

1. **Seguridad:** TLS en el proxy. OAuth2 *client credentials* entre servicios, con rotación, en
   lugar de los tokens estáticos. Administración del Producer con usuarios del IdP (rol `admin`)
   en vez de un token. Keycloak con BD persistente.
2. **Varios consumidores:** el Producer publicaría eventos de dominio (outbox propio → broker como
   Kafka o RabbitMQ). Cada Consumer los aplicaría por versión. La reconciliación quedaría como red
   de seguridad incremental (`?updatedSince=`).
3. **Datos:** PostgreSQL con migraciones Flyway, borrado lógico y un historial de versiones
   consultable.
4. **Dominio:** atributos por tipo (`attributes` JSON validado por esquema según `tipo`) y
   auditoría con el `sub` del JWT del autor en el evento.
5. **Operación:** Prometheus + Grafana con alertas sobre `sync_outbox_failed` y la antigüedad de
   la última reconciliación, trazas distribuidas (OpenTelemetry) con la `Idempotency-Key` como
   correlación, y un DLQ con reproceso masivo.

## 16. Estructura del repositorio

```
producer-api/        Spring Boot · SSoT, webhook idempotente, API de administración, backups
consumer-api/        Spring Boot · proyección, outbox, relay, reconciliación, métricas, registro
frontend/            React + Vite · UI, OIDC (PKCE), nginx (proxy, CSP, rate limit)
auth/                Realm de Keycloak (clientes, roles y usuarios de demo)
contracts/           Contratos JSON Consumer↔Producer compartidos por los tests de ambos lados
scripts/             smoke-test.sh (E2E de API) y hooks de git
e2e/                 Playwright · E2E de navegador con login real y capturas
docs/                curl.md, colección Postman y AUDITORIA.md
docker-compose.yml   Orquestación · .env.example: configuración de ejemplo
```

La auditoría técnica, con los hallazgos, las mediciones de latencia y los riesgos residuales, está
en [`docs/AUDITORIA.md`](docs/AUDITORIA.md).
