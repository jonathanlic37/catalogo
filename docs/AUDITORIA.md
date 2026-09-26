# Auditoría técnica del proyecto

Alcance: `producer-api`, `consumer-api`, `frontend` y `docker-compose.yml`. Método: revisión del código,
reproducción de cada sospecha contra el stack en ejecución **antes** de corregirla, y verificación
posterior con las mismas pruebas. Solo se listan hallazgos con evidencia; lo que no se pudo verificar se dice.

Severidad: **Alta** = puede perder datos o comprometer el sistema · **Media** = comportamiento incorrecto
o degradación visible · **Baja** = pulido/robustez.

## 1. Bugs

| ID | Sev. | Hallazgo y evidencia | Mitigación | Verificación |
|---|---|---|---|---|
| B1 | Media | Los errores estándar de Spring MVC (p. ej. `Content-Type: text/plain`) devolvían **500** en vez de 415: el `@ExceptionHandler(Exception.class)` los capturaba. Reproducido: `POST /api/items` con `text/plain` → 500. | Los `GlobalExceptionHandler` heredan de `ResponseEntityExceptionHandler` (415, 405, 400, 404 conservan su código, siempre como `ProblemDetail` sin trazas). | `curl` → 415; tests en ambos backends. |
| B2 | Media | Borrar un ítem que ya no existe en el Producer dejaba el ítem en `FAILED` para siempre (el Producer responde 404). Reproducido con un borrado directo en el Producer y luego `DELETE` en el Consumer. | `DELETED` + 404 se trata como éxito (borrado idempotente). Para el resto de 4xx el error explica la causa (404/409/401) y la acción a tomar. | `curl` → 404 tras borrar; test `eliminarLoQueYaNoExisteEnElProducerSeTrataComoExito`. |
| B3 | Baja | El formulario de edición volvía a rellenarse con cada recarga en segundo plano (p. ej. al volver a la pestaña), **pisando lo que el usuario estaba escribiendo**. | Se inicializa una sola vez. | Test `al editar carga los datos una sola vez…`. |
| B4 | Baja | (Riesgo derivado de paginar la reconciliación) un ítem podía desplazarse entre páginas y parecer "borrado", eliminándose de la réplica. | Antes de borrar se confirma con una consulta puntual (`GET /api/items/{id}`); los ítems tocados durante el recorrido se ignoran. | Test `reconciliacionNoBorraUnItemAusenteDeLaListaSiElProducerAunLoTiene`. |

## 2. Latencia y escalabilidad

Medido con **1 500 ítems** en el Producer y la réplica (sembrados vía webhook), máquina de desarrollo.

| ID | Sev. | Hallazgo | Antes | Después | Mitigación |
|---|---|---|---|---|---|
| L1 | Alta a escala | La lista devolvía **todo** el catálogo: coste O(N) por carga de pantalla y por polling. | `GET /api/items`: mediana **178 ms**, **595 KB** | Página de 25: mediana **32 ms**, **1,1 KB** transferidos (8,9 KB sin comprimir). Máx. 100/página: 45 ms. | Paginación en servidor (`page`, `size` ≤ 100), búsqueda y filtro en base de datos (comodines `LIKE` escapados), `GET /api/items/summary` para los contadores, índices, gzip en las APIs y nginx; el frontend pasa a paginación en servidor con búsqueda con retardo (300 ms). |
| L2 | Media | La reconciliación descargaba todo el catálogo del Producer cada minuto en una única respuesta (231 ms / 595 KB, y memoria proporcional a N). | 1 respuesta | Páginas de 500, procesadas una a una | `ReconcileService` recorre por páginas con orden estable (`fechaCreacion, id`); borrado solo con verificación puntual. Verificado con 4 páginas (1 522 ítems). |
| L3 | Media | Con el Producer caído, el ciclo de reintentos esperaba un timeout **por ítem pendiente** en el único hilo del planificador, que además compartía con la reconciliación. | Bloqueo O(pendientes × timeout) | Corte en el primer fallo de red/5xx + pool de 2 hilos | `retryPending` aplaza el resto del lote; `spring.task.scheduling.pool.size=2`. Cubierto por `OutboxBatchCutoffTest` (`cortaElLoteAlPrimerFalloDelProducer`). |
| L4 | Baja | Sin índices en la consulta de reintentos (cada 10 s) ni en los órdenes por fecha. | Escaneo completo | Índices `(syncStatus, nextRetryAt)`, `fechaActualizacion`, `(fechaCreacion, id)`, `createdAt` | `@Index` en las entidades (hoy los crea la migración Flyway `V1__init.sql`). |
| — | Info | Camino de escritura sin degradación con 1 500 ítems. | POST 18 ms · hasta `CONFIRMED` 90 ms | POST 24 ms · hasta `CONFIRMED` 104 ms | Sin cambios necesarios. |

Pruebas de concurrencia (stack real): **100 altas simultáneas** (50 hilos) → 100 × `202`, los 100 `CONFIRMED` en
~5 s, exactamente 100 ítems nuevos en el Producer, sin `SQLITE_BUSY` ni errores en los logs.
**20 webhooks simultáneos con la misma `Idempotency-Key`** → 1 ítem creado, 19 respuestas `Idempotent-Replayed: true`.

## 3. Seguridad

| ID | Sev. | Hallazgo | Mitigación | Estado |
|---|---|---|---|---|
| S1 | Alta | **La UI no tiene autenticación de usuario**: nginx inyecta el token del servicio, así que cualquiera que alcance el puerto puede modificar el catálogo. Además escuchaba en `0.0.0.0`. | Primero, la UI pasó a escuchar solo en `127.0.0.1`. **Después se resolvió del todo:** login OIDC (Keycloak, Authorization Code + PKCE), el Consumer valida el JWT y los roles, y nginx ya no inyecta ningún token (ver §7). | **Resuelto.** |
| S2 | Alta | Las APIs aceptaban tokens cortos o el valor de ejemplo de `.env.example`. | Las APIs **no arrancan** si un token tiene < 32 caracteres o contiene "cambiar". | Verificado: el contenedor aborta con el mensaje; tests unitarios. |
| S3 | Media | nginx sin cabeceras de seguridad, versión expuesta, corriendo como root, sin límite de cuerpo explícito. | Imagen `nginx-unprivileged` (usuario 101), CSP estricta (verificado: el `index.html` no tiene scripts inline), `X-Frame-Options`, `nosniff`, `Referrer-Policy`, `Permissions-Policy`, `server_tokens off`, cuerpo ≤ 64 KB (413), caché inmutable para assets con hash y `no-store` en `/api`. | Verificado con `curl`. |
| S4 | Media | Contenedores con privilegios por defecto: sistema de ficheros escribible, todas las capabilities, sin límites, logs sin rotar. | Java: `read_only`, `cap_drop: ALL`, `no-new-privileges`, 512 MB, 200 procesos; nginx: sin capabilities, 128 MB; logs `json-file` 10 MB × 3. | Verificado con `docker inspect`; los tres servicios `healthy`. |
| S5 | Baja | `.env` (con secretos) con permisos 664. | `chmod 600` y documentado. | Verificado. |
| S6 | Info | Sin límite de peticiones por cliente. | Después se añadió `limit_req` por IP en nginx (`RATE_LIMIT_RATE`/`RATE_LIMIT_BURST`, responde 429). | **Resuelto** en el proxy; las APIs no tienen límite propio. |
| S7 | Info | Sin TLS entre contenedores. | La red `backend` es interna (sin salida a Internet). | **Aceptado**, documentado. |

Comprobado sin hallazgo (en su momento): sin token → 401 en ambas APIs, el bundle no contiene tokens y
las validaciones no exponen trazas.

## 4. Datos y disponibilidad

| ID | Sev. | Hallazgo | Mitigación | Verificación |
|---|---|---|---|---|
| D1 | Alta | La BD del **Producer (fuente de verdad) no tenía copia de seguridad**. | `BackupService`: copia consistente con la API de backup online de SQLite cada 6 h (volumen `producer-backups`, se conservan las 8 últimas). La réplica del Consumer no la necesita (se reconstruye). | Test de backup; en el stack: fichero de 1,6 MB con cabecera `SQLite format 3`. *El procedimiento de restauración del README no se ha ejecutado de extremo a extremo.* |
| D2 | Media | `idempotency_records` crecía sin límite. | `IdempotencyPurger`: elimina registros de más de 7 días (≫ ventana de reintentos). | Test de purga. |
| D3 | Baja | Al parar un contenedor se podían cortar webhooks en vuelo. | `server.shutdown=graceful` (20 s) + `stop_grace_period: 30s`. | Configuración; sin prueba específica. |
| D4 | Info | Persistencia ante fallos. | — | Con el Producer parado se crearon 3 ítems (`PENDING`), se reinició el Consumer y se arrancó el Producer: los 3 llegaron a `CONFIRMED`. |

## 5. Pruebas

| Módulo | Primera auditoría | Tras la verificación contra el enunciado (§7) |
|---|---|---|
| `producer-api` | 5 → 11 | **23** (incluye `webhooksConcurrentesConLaMismaClaveNoDan500`) |
| `consumer-api` | 4 → 12 | **26** (incluye `OutboxBatchCutoffTest`, que cubre L3, y las pruebas de las tres políticas de conflicto) |
| `frontend` | 0 → 14 | **31** |
| E2E de API | — | `scripts/smoke-test.sh`: criterios de aceptación y los cuatro escenarios del enunciado sobre el stack real |
| E2E de navegador | — | `e2e/` (Playwright): login real en Keycloak y flujo completo en Chromium, con capturas |

Ejecución: README §11.

## 6. Riesgos residuales (no mitigados)

1. **Tokens estáticos** entre servicios (servicio y administración, ya separados), sin rotación y sin TLS en la red interna.
2. **Keycloak en modo desarrollo** (H2 embebido y usuarios de demostración; sus contraseñas vienen del entorno).
3. **SQLite de un solo escritor**: adecuado para decenas de miles de ítems; el backup retiene brevemente la única conexión.
4. **Reconciliación completa cada minuto**: paginada y acotada en memoria, pero O(N) en red; con cientos de miles de ítems habría que hacerla incremental.
5. La búsqueda por nombre solo ignora mayúsculas/minúsculas ASCII (`lower()` de SQLite).
6. ~~`ddl-auto=update` en lugar de migraciones versionadas.~~ Resuelto: Flyway `V1__init.sql` (DDL volcado de Hibernate) + `ddl-auto=validate`; verificado desde cero y sobre volúmenes existentes (baseline 0).
7. Sin verificar de extremo a extremo: la restauración de backups (D1).

## 7. Verificación contra el enunciado de la prueba técnica

Revisión completa del código, la configuración y los documentos contra el enunciado y su rúbrica.
Se reprodujo cada hallazgo y se corrigió; la verificación final se hizo **en una copia limpia del
repositorio**, siguiendo literalmente `cp .env.example .env && docker compose up --build`.

| ID | Sev. | Hallazgo | Corrección | Verificación |
|---|---|---|---|---|
| V1 | **Alta** | **El sistema no arrancaba siguiendo las instrucciones del enunciado**: el token de `.env.example` contenía «cambiar» y las APIs se negaban a arrancar (el CI lo ocultaba sustituyéndolo con `sed`). | `.env.example` trae valores `dev-only-…` (no son secretos reales) que las APIs aceptan con un aviso en el log. Los tokens cortos o con marcador siguen rechazándose. El CI usa ahora las instrucciones literales. | Arranque desde cero en una copia limpia: 4 servicios `healthy` y smoke OK. Test `tokenDeDesarrolloDeEnvExampleSeAcepta`. |
| V2 | **Alta** | **La confirmación de un evento no era atómica**: el relay marcaba el evento `SENT` y actualizaba la proyección en dos transacciones. Si el Consumer caía entre ambas, el ítem quedaba `PENDING` para siempre, sin evento que lo reenviara y con la edición bloqueada (es el escenario «Consumer se reinicia durante la sincronización»). | Tras la respuesta del Producer, estado del evento y proyección se escriben en **una** transacción (`TransactionTemplate`), sin transacción durante el HTTP. | Tests del relay; smoke paso 6 (reinicio con un cambio pendiente → confirmado). |
| V3 | Media | **Un conflicto podía aceptarse como éxito en silencio**: el Producer comprobaba `occurredAt` antes de la versión. Una edición basada en una versión superada con un `occurredAt` anterior (p. ej. reloj atrasado) se descartaba y se respondía 200 con el estado actual: el usuario veía «Sincronizado» y su cambio se perdía. Además, el borrado ignoraba `baseVersion` y podía borrar una versión más nueva. | La versión decide primero (409 ante `baseVersion` obsoleta, también en `DELETED`). `occurredAt` solo ordena eventos sin versión. | Tests `laVersionPrevaleceSobreOccurredAt` y `borradoConVersionObsoletaDa409YNoBorra`; smoke paso 4. |
| V4 | Media | **Resync hacía HTTP dentro de una transacción**, bloqueando la única conexión SQLite hasta 5 s. La reconciliación podía sobrescribir un ítem que el usuario acababa de editar (lectura y escritura no atómicas). | HTTP fuera de transacción; cada escritura relee el ítem y comprueba que sigue `CONFIRMED` dentro de su transacción. | Tests de reconciliación y resync. |
| V5 | Media | **La primera carga tras el login daba 401**: el token se fijaba en un `useEffect` del padre, que React ejecuta *después* de los efectos de los hijos que ya lanzaban la petición. **Además, tras el login se mostraba «Página no encontrada»** (no había ruta `/callback`). | El token se fija durante el render; la ruta `/callback` redirige al catálogo. | Test `tras el login (/callback)… la PRIMERA petición ya lleva el Bearer`. |
| V6 | Media | **Documentación contradictoria**: README, `curl.md` y Postman describían el modelo anterior (token inyectado por nginx, «UI sin login», sin rate limiting ni purga del outbox) y pedían una variable que ya no existe. Faltaban la estrategia de autenticación, qué pasa en cada escenario, qué no se implementó y cómo evolucionaría. | README reescrito (cumplimiento, escenarios, OWASP, trade-offs, supuestos, limitaciones, evolución); `curl.md` y Postman con el login OIDC. | Revisión manual; los comandos de `curl.md` son los que ejecuta el smoke. |
| V7 | Media | **Validación laxa**: los campos desconocidos se ignoraban en silencio (p. ej. un cliente podía enviar `syncStatus`). | `fail-on-unknown-properties` en el Producer; `ItemRequest` estricto en el Consumer → 400. | Tests `payloadConCamposDesconocidosSeRechaza` y de seguridad del Consumer. |
| V8 | Baja | Contratos duplicados en cada módulo (podían divergir); el Consumer no verificaba que entendía la respuesta del Producer. | Fuente única `contracts/` cargada por ambos módulos; test de deserialización del contrato en el Consumer. | Tests de contrato en ambos lados. |
| V9 | Baja | Métricas accesibles a cualquier usuario; realm con password grant en el cliente del SPA; rol `admin` descrito con un permiso inexistente; `KEYCLOAK_ADMIN_PASSWORD` con valor por defecto `admin` en compose; Keycloak sin healthcheck (la UI podía arrancar antes que el IdP). | Métricas solo `admin`; cliente `catalogo-cli` separado para desarrollo; descripción corregida; variable obligatoria; healthcheck y `depends_on`. | Test `lasMetricasDeSincronizacionSoloParaAdministradores`; smoke. |
| V11 | Media | **El Consumer podía saltarse el webhook**: la API de administración del Producer aceptaba el mismo token que el webhook, así que el Consumer podía escribir directamente en la fuente de verdad. | Dos credenciales con roles: `SERVICE` (Consumer: solo webhook y lectura) y `ADMIN` (`PRODUCER_ADMIN_TOKEN`, que solo conoce el Producer: escritura directa y métricas). El Producer no arranca si ambas coinciden. | Tests `separacionDePrivilegiosEntreConsumerYAdministracion` y `lasCredencialesDeServicioYAdministracionDebenSerDistintas`; smoke (403 del Consumer en escritura directa). |
| V12 | Media | **Contraseñas de los usuarios de demo versionadas** en el realm de Keycloak (`demo/demo`, `admin/admin`). | El realm solo lleva los placeholders `${DEMO_USER_PASSWORD}` y `${DEMO_ADMIN_PASSWORD}`, que Keycloak resuelve desde el entorno al importarlo. | Login real en el E2E de navegador y en el smoke con las contraseñas de `.env`. |
| V13 | Media | **La UI nunca se había probado en un navegador real**: el login OIDC con PKCE y la experiencia solo tenían tests unitarios simulados. | E2E con Playwright (Chromium, instalación ligera sin imagen Docker) contra el stack real, en el CI. | En local, sin descargar navegadores: el flujo Authorization Code + PKCE se ejecutó con `curl` contra el Keycloak real (redirección a `/callback`, canje con `code_verifier`, 200 del Consumer vía nginx; password grant del SPA rechazado; contraseña antigua rechazada). **El spec de Playwright no se ejecutó en esta máquina.** **Revisión visual manual** en navegador del flujo completo (login, sincronizar, editar, filtros, registro, tema oscuro, móvil): todo correcto salvo V14. |
| V14 | Baja | **La lupa del buscador tapaba las primeras letras**: la regla genérica `input[type='search']`, declarada después y con la misma especificidad, anulaba el `padding-left` reservado para el icono. | Selector más específico (`.search input[type='search']`). | Detectado en la revisión visual manual; CSS servido verificado. |
| V15 | **Alta** | **Con el Producer caído más de ~2,5 min, los cambios pasaban a `FAILED` y dejaban de reintentarse** (5 intentos con backoff): al volver el Producer, el usuario tenía que reintentar ítem por ítem. Un fallo transitorio se comportaba como definitivo. | Los fallos transitorios (red, 5xx, 408, 425, 429) se reintentan **sin límite** con backoff (tope 5 min) y se confirman solos; solo los rechazos 4xx son definitivos. `SYNC_MAX_RETRIES` limita únicamente los rebases de `CONSUMER_WINS`. | Test `conElProducerCaidoSigueReintentandoSinLimite…` (supera `SYNC_MAX_RETRIES=2` y se confirma al volver). |
| V16 | Media | **Las políticas `PRODUCER_WINS` y `CONSUMER_WINS` estaban implementadas y documentadas pero sin ningún test.** | Tests de la adopción de la versión canónica, del rebase con la misma clave y `baseVersion` nueva, y del tope de conflictos repetidos. | `SyncProducerWinsTest`, `SyncConsumerWinsTest`. |
| V17 | Baja | **La UI ofrecía «Reintentar» ante un conflicto**, que siempre vuelve a fallar. | El fallo guarda su motivo (`CONFLICT`, `GONE`, `REJECTED`); solo `REJECTED` ofrece reintentar. Mientras se reintenta, la UI muestra el motivo y el intento. | `elMotivoDelFalloDistingue…` y tests de `CatalogoList`. |
| V10 | Baja | Faltaban filtros por tipo y por estado de sincronización, una consulta del registro de eventos fallidos y métricas de reconciliación; la readiness no comprobaba la BD. | `?tipo=`, `?sync=`, `GET /api/sync/events`, pantalla *Sincronización*, `sync_reconcile_*`, readiness con `db`. | Tests de filtros, registro, métricas y health. |
