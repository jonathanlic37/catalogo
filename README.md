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

### Recorrido rápido para el evaluador (≈ 5 minutos)

1. **Arrancar:** los dos comandos de arriba; `docker compose ps` debe mostrar los 4 servicios `healthy`.
2. **Ver el flujo completo automatizado:** `./scripts/smoke-test.sh` recorre los criterios de
   aceptación y los cuatro escenarios del enunciado (Producer caído, webhook duplicado, conflicto y
   reinicio del Consumer) contra el stack real, y termina con `SMOKE OK`.
3. **Verlo en la interfaz:** crea un ítem en el Producer con el comando de
   [`docs/curl.md`](docs/curl.md) §1, pulsa **«Sincronizar ahora»**, edítalo y observa
   «Pendiente de confirmación» → «Sincronizado». La tabla de
   [§5](#cómo-demostrar-cada-paso) da cada paso del enunciado a mano y con su prueba automática.
4. **Documentación visual** (se abre con doble clic): [`docs/manual.html`](docs/manual.html), manual
   básico de uso con la misma estética de la aplicación, y [`docs/api.html`](docs/api.html), referencia
   Swagger de ambas APIs.
5. **Dónde está cada cosa que se evalúa:**

| Criterio de la rúbrica | Dónde mirarlo |
|---|---|
| Flujo funcional y experiencia mínima | [§5](#5-flujo-de-sincronización), la UI y el E2E de navegador (`e2e/`) |
| Separación Producer/Consumer y SSoT | [§3](#3-arquitectura) y [cómo se cumple la regla SSoT](#cómo-se-cumple-la-regla-ssot) |
| Webhook, sincronización e idempotencia | [§6 Qué ocurre si…](#6-qué-ocurre-si) y [§7](#7-errores-duplicados-y-fallos-de-sincronización) |
| Seguridad y configuración | [§8](#8-seguridad-y-estrategia-de-autenticación) (estrategia de autenticación y OWASP) y [§9](#9-configuración) |
| Tests automatizados | [§11](#11-tests-automatizados) |
| Docker y documentación | [§2](#2-puesta-en-marcha) y este README |
| Iniciativa y justificación de decisiones | [Priorización](#priorización-núcleo-del-enunciado-y-extras), [§12 D-01…D-17](#12-decisiones-técnicas-y-trade-offs), [§13](#13-supuestos) y [`docs/AUDITORIA.md`](docs/AUDITORIA.md) |

---

## Índice

1. [Cumplimiento del enunciado](#1-cumplimiento-del-enunciado) ·
   [Priorización: núcleo y extras](#priorización-núcleo-del-enunciado-y-extras)
2. [Puesta en marcha](#2-puesta-en-marcha)
3. [Arquitectura](#3-arquitectura) · [Varios canales y aplicaciones](#varios-canales-y-aplicaciones)
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
| Pruebas automatizadas del flujo principal | 79 tests (22 Producer, 26 Consumer, 31 frontend), smoke E2E de API y **E2E de navegador (Playwright)** con login real en Keycloak. | §11 |

Funcionalidades opcionales del enunciado, todas implementadas:

| Opcional | Cómo se implementa | Dónde leerlo | Prueba |
|---|---|---|---|
| Outbox transaccional | Cambio y evento se guardan en una transacción (`outbox_events`); la confirmación (evento `SENT` + proyección) también es atómica. | §5, D-01 | smoke bloque 6 (reinicio con un cambio pendiente), `crearEnviaWebhook*` |
| Reintentos con backoff | 10 s × 2ⁿ con tope de 5 min; los fallos transitorios se reintentan sin límite y con la misma `Idempotency-Key`. | §6, D-06 | `siElProducerFalla*`, `conElProducerCaido*`, `OutboxBatchCutoffTest` |
| Control de versiones | `version` en el Producer y `baseVersion` en cada cambio. | §4, D-04 | `actualizacionIncrementaVersion*`, `laVersionPrevalece*` |
| Detección y gestión de conflictos | 409 sin sobrescribir; política `MANUAL` por defecto, `PRODUCER_WINS` y `CONSUMER_WINS` configurables. | §6, D-05 | `rechazoDelProducerMarcaFailed*`, `SyncProducerWinsTest`, `SyncConsumerWinsTest`, smoke bloque 4 |
| Reconciliación manual o automática | Automática cada 60 s y manual («Sincronizar ahora» / `POST /api/reconcile`). | §5, D-09 | `reconciliacion*`, smoke bloque 1 |
| Manejo de eventos fuera de orden | La versión decide; los eventos sin versión se ordenan por `occurredAt`. | §6 | `descartaEventosFueraDeOrden`, `laVersionPrevalece*` |
| Contract tests entre las APIs | Fuente única en `contracts/`, verificada desde ambos lados. | `contracts/README.md` | `elPayloadDelWebhookCumple*`, `elWebhookAceptaElContrato*`, `elConsumerEntiende*` |
| Healthchecks diferenciados | Liveness (proceso) y readiness (proceso + BD); compose usa readiness. | §3 | `healthchecksDiferenciadosSonPublicos` |
| Métricas de sincronización | `sync_outbox_*` y `sync_reconcile_*` en `/actuator/prometheus`, solo para `admin`. | §7 | `lasMetricasDeSincronizacionSoloParaAdministradores`, smoke |
| Registro de eventos fallidos | `GET /api/sync/events?status=FAILED` y pantalla *Sincronización*, con motivo e intentos. | §7 | `registroDeEventosFallidosConSuMotivo`, `SyncEvents.test` |
| Interfaz con estados claros de sincronización | Pills con icono y texto: pendiente, sincronizado y error con su motivo; reintento automático visible. | manual de uso | `SyncBadge.test`, `CatalogoList.test`, E2E de navegador |
| Paginación, filtros y búsqueda | En servidor: por nombre, estado, tipo y sincronización. | §12, D-15 | `listaPaginadaConBusquedaYResumen`, `filtraPorTipoYPorEstadoDeSincronizacion` |
| Varios tipos de contenido | Campo `tipo` (`PRODUCTO`, `SERVICIO`, `CONTENIDO`); añadir uno es añadir un valor al enum. | §4 | `filtraPorTipo*`, contrato `item-response.json` |

### Priorización: núcleo del enunciado y extras

El enunciado pide demostrar **criterio técnico**, no una plataforma de producción completa. El
trabajo se ordenó en tres niveles, y cada nivel se cerró (con tests) antes de empezar el siguiente:

1. **Núcleo obligatorio.** SSoT en el Producer, proyección en el Consumer, webhook autenticado e
   idempotente, UI que solo habla con el Consumer, Bearer en ambas APIs, validación, errores sin
   trazas, SQLite independiente por API y Docker Compose. Es lo que se evalúa como "flujo funcional".
2. **Robustez de la sincronización.** Los cuatro escenarios que el enunciado exige definir (§6):
   outbox transaccional, reintentos con backoff, control de versión y conflictos, eventos fuera de
   orden y reconciliación. Sin esto, el núcleo funciona solo en el camino feliz.
3. **Extras.** Cada uno se añadió porque resolvía un riesgo concreto detectado en la revisión, no por
   completar una lista. La tabla indica qué aporta, cuánto cuesta y cómo prescindir de él:

| Extra | Qué riesgo cubre | Coste | Cómo prescindir de él |
|---|---|---|---|
| **Keycloak (OIDC)** para los usuarios | Sin login, cualquiera que alcanzara la UI podía modificar el catálogo, y el token viajaba inyectado por un proxy. OIDC da identidad real sin tokens en el bundle. El enunciado no lo exige; se eligió frente a un token estático de usuario por ser la opción más segura sin programar gestión de usuarios. | +1 contenedor, ~1 min más de arranque | Sustituir el resource server del Consumer por un Bearer estático (el patrón ya existe en el Producer) y quitar el servicio `auth`. |
| **Credenciales separadas** en el Producer | Con una sola credencial, el Consumer podía escribir en la SSoT saltándose el webhook. | Una variable más | — (es parte de la regla SSoT). |
| **Copias de seguridad** del Producer | La SSoT era un único fichero SQLite sin copia. | Un volumen | `BACKUP_ENABLED=false`. |
| **Rate limiting** en nginx | Abuso o bucles del cliente contra la API. | Ninguno apreciable | Subir `RATE_LIMIT_RATE`. |
| **Métricas y registro de eventos** | Sin ellos, un fallo de sincronización solo se veía en los logs. | Un endpoint protegido | Ignorarlos: no afectan al flujo. |
| **Smoke E2E, E2E de navegador y CI** | Verificar el enunciado sobre el stack real y no solo con mocks. | Tiempo de CI | Opcionales: no forman parte del arranque. |
| **Sistema de diseño propio** | Estados de sincronización claros y usables en móvil, tablet y POS táctil. | CSS propio, sin dependencias | — |
| **Endurecimiento de contenedores** | Mínimo privilegio (solo lectura, sin capabilities, límites de memoria). | Ninguno en ejecución | Quitar las claves `x-java-service` de `docker-compose.yml`. |

**Se descartó por alcance** (ver §14): broker de mensajes, varios Consumers, migraciones
versionadas, TLS, auditoría por usuario, fusión automática de conflictos y dashboards. Todo ello
aparece como evolución en §15.

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

### Varios canales y aplicaciones

El contexto del enunciado habla de un catálogo "utilizado por diferentes canales y aplicaciones",
pero solo pide implementar **un** Consumer. El diseño no ata al Producer a ningún consumidor
concreto: no conoce quién lo usa ni guarda estado por canal. Un canal nuevo se incorporaría así:

| Tipo de canal | Cómo se integra hoy | Qué habría que añadir |
|---|---|---|
| **Solo lectura** (web pública, app móvil, buscador) | Lee la lista paginada o el detalle del Producer (`GET /api/items`), o se alimenta de la proyección de un Consumer. | Una credencial propia de solo lectura. Hoy el Producer reconoce dos credenciales (servicio y administración); añadir un rol por canal es un cambio acotado en `SecurityConfig`. |
| **Con proyección propia y edición** (otro back-office, un TPV) | Replica el patrón del Consumer: su propia BD, outbox, webhook con `Idempotency-Key`, `baseVersion` y reconciliación. | Nada en el Producer: las claves de idempotencia son UUID únicos por intento y los conflictos entre canales se detectan por versión (409), igual que entre el Consumer y la administración directa. |

**Limitación con varios canales:** un cambio aceptado por el Producer solo llega a los demás
canales en su siguiente reconciliación (60 s por defecto o a demanda), porque el Producer no
notifica a nadie. Con un canal es suficiente; con muchos, el paso natural es que el Producer
publique eventos de dominio desde su propio outbox hacia un broker (Kafka, RabbitMQ) y que cada
canal los aplique por versión, dejando la reconciliación como red de seguridad (§15).

## 4. Modelo de información

Entidad principal: el **ítem de catálogo**. Tiene los seis campos que pide el enunciado y dos
ampliaciones, y es la misma en ambas APIs.

| Campo | Enunciado | Obligatorio | Quién lo fija | Motivo |
|---|---|---|---|---|
| `id` (UUID) | ✔ identificador | Sí | El Consumer en las altas desde la UI (el Producer lo valida); el Producer en sus altas directas | Misma identidad en ambas BD ([D-10](#12-decisiones-técnicas-y-trade-offs)). |
| `nombre` | ✔ nombre/título | Sí (≤ 120) | Usuario | No es único: la identidad es el `id`. |
| `descripcion` | ✔ descripción | No (≤ 1000) | Usuario | Opcional: no todos los ítems la necesitan. |
| `estado` | ✔ estado | Sí | Usuario | `ACTIVO` / `INACTIVO`: atributo del ítem, no una baja (ver abajo). |
| `fechaCreacion` | ✔ fecha de creación | — | Producer | Canónica. Se fija al aceptar el alta; no cambia. |
| `fechaActualizacion` | ✔ fecha de actualización | — | Producer | Canónica. Se actualiza con cada cambio aceptado. |
| `version` | ampliación | — | Producer | Empieza en 1 y sube con cada cambio aceptado. Es la base del control de concurrencia y de la detección de conflictos ([D-04](#12-decisiones-técnicas-y-trade-offs)). |
| `tipo` | ampliación | No (por defecto `PRODUCTO`) | Usuario | `PRODUCTO` / `SERVICIO` / `CONTENIDO`: cubre «productos, contenidos, servicios u otro tipo» del enunciado sin cambiar el esquema. Añadir un tipo es añadir un valor al enum en ambas APIs. |

Las fechas se guardan en UTC y viajan en ISO-8601. Mientras un cambio está pendiente, la UI muestra
los valores locales; al confirmarse se sustituyen por los del Producer, fechas y versión incluidas.

**Por qué solo dos estados.** El enunciado pide un estado pero no define su ciclo de vida.
`ACTIVO`/`INACTIVO` cubre el caso habitual (mostrar u ocultar un ítem en los canales) sin inventar
reglas de negocio. Un ciclo editorial (`BORRADOR` → `PUBLICADO` → `ARCHIVADO`) cabría en el mismo
campo si el negocio lo requiriera.

**Datos de dominio frente a metadatos técnicos.** Solo los ocho campos de arriba son el modelo de
negocio. El Producer devuelve los ocho como estado canónico; el webhook envía solo los que decide el
usuario (`id`, `nombre`, `descripcion`, `estado`, `tipo`) más `baseVersion` (versión en la que se
basó el cambio) y `occurredAt` (cuándo ocurrió), porque fechas y versión las fija el Producer. Ver
`contracts/`. El resto son metadatos de la sincronización:

| Dónde | Campo | ¿Se expone en la API? | Para qué |
|---|---|---|---|
| Consumer | `syncStatus` | Sí | `PENDING` / `CONFIRMED` / `FAILED`: lo que la UI muestra como estado de sincronización. |
| Consumer | `pendingEvent` (en la API, `pendingOperation`) | Sí | Operación aún no confirmada: alta, edición o eliminación. |
| Consumer | `lastSyncError`, `syncAttempts` (en la API, `syncError`, `syncAttempts`) | Sí | Motivo e intentos del último envío, también mientras se reintenta. |
| Consumer | `failureReason` | Sí, solo en `FAILED` | `CONFLICT`, `GONE` o `REJECTED`: decide qué acciones ofrece la UI. |
| Consumer | `idempotencyKey`, `nextRetryAt` | No | Clave del envío en curso y momento del próximo reintento. |
| Consumer | tabla `outbox_events` | Solo el registro (`GET /api/sync/events`) | Eventos pendientes, enviados y fallidos. |
| Producer | `lastEventAt` | No | Ordena los eventos que no traen versión (eventos fuera de orden). |
| Producer | tabla `idempotency_records` | No | Respuesta guardada de cada `Idempotency-Key`. |

**Qué se decidió no añadir.** Precio, stock, categorías, imágenes o atributos específicos por tipo
de contenido. Ninguno es necesario para demostrar la sincronización, y cada uno arrastraría reglas
de negocio y validaciones que el enunciado no define. Los atributos por tipo están previstos como
evolución (un campo `attributes` validado por esquema según `tipo`, §15).

### Cómo se cumple la regla SSoT

| Regla del enunciado | Cómo se garantiza |
|---|---|
| El Producer define el estado oficial | Al aceptar un cambio, su respuesta (campos, versión y fechas) **sustituye** la copia del Consumer. La reconciliación solo acepta versiones mayores del Producer. |
| El Consumer mantiene solo una copia | Nada que decida el Consumer es definitivo; ver las dos precisiones de abajo. |
| Un cambio no es definitivo hasta que el Producer lo acepta | Las escrituras responden `202` con `syncStatus=PENDING` y solo pasan a `CONFIRMED` con la respuesta del Producer. Mientras, la UI muestra «Pendiente de confirmación» y bloquea la edición del ítem. |
| El Consumer no modifica la BD del Producer | No tiene conexión ni volumen de esa BD: solo su URL HTTP. Su credencial solo sirve para el webhook y para leer; si escribe por la API de administración recibe `403`. |
| Bases de datos completamente separadas | Un fichero SQLite por API, cada uno en su volumen (`producer-data`, `consumer-data`) y montado solo por su servicio. Toda comunicación es HTTP. |

**Qué guarda el Consumer además de la copia.** Metadatos de sincronización (estado, operación
pendiente, outbox y registro de eventos) y, **de forma temporal**, el valor local de un cambio que
el Producer aún no ha aceptado. Así un cambio no se pierde si el Producer está caído, pero siempre
aparece marcado como pendiente o fallido y nunca como oficial. Si el Producer rechaza un alta, ese
ítem existe solo en la proyección, marcado con error, hasta que el usuario lo descarta.

**Quién define la identidad.** En un alta desde la UI, el id (UUID) lo genera el Consumer, y el
Producer lo valida (formato y que no exista; si existe, `409`) y acepta o rechaza el alta. En las
altas directas en el Producer lo asigna el Producer. Se eligió así para que el ítem tenga la misma
identidad en ambas BD desde el primer momento y los reintentos sean idempotentes sin traducir
identificadores. La alternativa (id asignado siempre por el Producer) obligaría al Consumer a
guardar un id temporal y remapearlo al confirmar, con más estados intermedios.

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

### Cómo demostrar cada paso

| Paso | A mano | Prueba automática que lo verifica |
|---|---|---|
| 1. Crear en el Producer | `curl -X POST $PRODUCER/api/items` con el token de administración ([`docs/curl.md`](docs/curl.md) §1) o Postman («1. Producer — crear ítem»). El Producer no tiene pantalla: el enunciado pide interfaz solo para el Consumer. | E2E (preparación), smoke bloque 1 |
| 2. El Consumer sincroniza | Botón **«Sincronizar ahora»** en la UI (o esperar ≤ 60 s). | E2E paso «2-3», smoke bloque 1, test `reconciliacion*` |
| 3. Aparece en React | Buscar el nombre en la lista: aparece «Sincronizado». | E2E paso «2-3» |
| 4–5. Editar desde la UI | Icono de lápiz → cambiar el nombre → **Guardar**. | E2E paso «4-10», test `ItemForm` |
| 6. Webhook autenticado | Pantalla **Sincronización** → filtro «Enviados»: aparece el evento de edición. | test `crearEnviaWebhook*`, smoke bloque 2 |
| 7–8. El Producer valida, aplica y responde | `curl $PRODUCER/api/items/{id}` con el token del Consumer: nombre nuevo y `version` + 1. | E2E paso «4-10» (comprueba versión 2), smoke bloque 2 |
| 9–10. Proyección y UI actualizadas | En uno o dos segundos la insignia pasa de «Pendiente de confirmación» a «Sincronizado» con el dato nuevo. | E2E paso «4-10», smoke bloque 2 |

**Sincronización y refresco no son push.** El paso 2 es una consulta periódica (60 s, o al instante
con el botón) porque el Producer no notifica a sus consumidores; el paso 10 es un sondeo cada
1,5 s, activo solo mientras hay cambios pendientes. Ambas decisiones mantienen el Producer
desacoplado y el sistema simple; la alternativa con eventos (broker, SSE/WebSocket) está en §15.

## 6. Qué ocurre si…

| Situación | Comportamiento |
|---|---|
| **El Producer no está disponible** | El cambio queda guardado en el outbox y el ítem en `PENDING`; la UI lo muestra como pendiente, nunca como éxito. El relay reintenta con backoff exponencial (10 s × 2ⁿ, tope 5 min) y, si el Producer no responde, corta el lote en el primer fallo para no esperar un timeout por evento. Un fallo transitorio **nunca** se da por definitivo: el cambio sigue pendiente, la UI muestra «Reintentando automáticamente · Producer no disponible (intento N)» y se confirma solo cuando el Producer vuelve, sin que el usuario tenga que reintentar ítem por ítem. El Consumer sigue sirviendo su proyección, porque su readiness no depende del Producer. |
| **El webhook se recibe más de una vez** | El Producer guarda cada `Idempotency-Key` con el hash SHA-256 de tipo y payload, y la respuesta que dio. Si llega la misma clave con el mismo contenido, devuelve esa respuesta (`Idempotent-Replayed: true`) **sin reaplicar**. Si llega la misma clave con otro contenido, responde 409. Dos entregas simultáneas se serializan, porque la transacción y el pool SQLite de 1 conexión lo impiden en paralelo. Los registros de idempotencia se purgan a los 7 días (`IDEMPOTENCY_RETENTION_DAYS`); un duplicado aún más tardío tampoco corrompería datos, porque el control de versión lo rechazaría (409). |
| **El registro cambió en el Producer antes de recibir la modificación** | El evento trae `baseVersion` distinta de la actual, así que el Producer responde **409** y no sobrescribe nada. El Consumer lo trata según `SYNC_CONFLICT_POLICY`. Con `MANUAL`, el valor por defecto, el ítem queda *Error de sincronización* con el motivo `CONFLICT`, y el usuario puede *Descartar cambio* (adopta la versión canónica) y volver a editar; la UI no ofrece *Reintentar* porque reenviar la misma versión obsoleta volvería a fallar. Con `PRODUCER_WINS` se adopta la versión del Producer automáticamente. Con `CONSUMER_WINS` se rebasa el cambio sobre la versión actual y se reenvía con la misma clave; si el conflicto se repite `SYNC_MAX_RETRIES` veces, queda en error para que decida el usuario. |
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
  - **Transitorios** (red, 5xx, 408, 429): reintento automático con backoff exponencial, **sin límite**
    de intentos (tope de 5 min entre intentos). El ítem sigue pendiente, nunca pasa a error por esto.
  - **Definitivos** (4xx): el evento pasa a `FAILED` sin reintentar y guarda el motivo:
    `CONFLICT` (409, versión obsoleta), `GONE` (404, el ítem ya no existe) o `REJECTED` (otro 4xx,
    como validación o credenciales).
  - **Borrar lo que ya no existe** (404 en un `DELETED`): se trata como éxito, porque el borrado es
    idempotente.

  Todo evento `FAILED` queda en el **registro de eventos fallidos**
  (`GET /api/sync/events?status=FAILED` y la pantalla *Sincronización*) con su motivo, intentos y
  fechas. En la lista, el ítem ofrece *Descartar cambio* (recupera la versión canónica) y, solo si
  el motivo es `REJECTED`, también *Reintentar* (reenvía el mismo evento con la misma clave, útil
  tras corregir la causa). Ante `CONFLICT` o `GONE` reintentar volvería a fallar, así que no se ofrece.
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

### Qué se versiona y qué no

- **No se versiona** (`.gitignore`): `.env`, las bases SQLite (`*.db`, `*.db-wal`, `*.db-shm`), ni
  dependencias o artefactos generados (`node_modules/`, `target/`, `dist/`, resultados de E2E). El
  historial del repositorio nunca ha contenido un `.env`.
- **Secretos:** ninguno real. `.env.example` solo trae valores de ejemplo `dev-only-…` (las APIs los
  aceptan en local con un aviso y exigen sustituirlos fuera de él), el realm de Keycloak solo tiene
  placeholders `${…}` y los tokens que aparecen en los tests son valores ficticios de prueba.
- **Sí se versionan:** los lockfiles (`package-lock.json`), para builds reproducibles, y las fuentes
  del tema de login (Outfit y Work Sans, licencia SIL OFL 1.1, con su texto de licencia junto a los
  ficheros).

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

**Manual de uso:** [`docs/manual.html`](docs/manual.html) explica la aplicación para un usuario
final: acceso, pantallas, qué significa cada estado de sincronización, qué hacer ante un conflicto
o un error, y la administración del Producer. Autocontenido: se abre con doble clic, sin conexión.

**Referencia de las APIs (Swagger / OpenAPI 3):** [`docs/api.html`](docs/api.html) muestra con
Swagger UI todos los endpoints, parámetros, esquemas, códigos de respuesta y la seguridad de ambas
APIs. Las especificaciones fuente son [`docs/openapi/consumer-api.yaml`](docs/openapi/consumer-api.yaml)
y [`docs/openapi/producer-api.yaml`](docs/openapi/producer-api.yaml), validadas con Redocly e
importables en Postman, Insomnia o <https://editor.swagger.io>. Tras editar un YAML, regenera la
página con `python3 scripts/build-api-docs.py`. Es una referencia de solo lectura: abierta como
fichero local no puede llamar a las APIs (su CORS solo admite el origen de la UI); para ejecutar
peticiones usa curl o Postman.

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
| `producer-api` | 22 (`WebhookIdempotencyTest`) | Webhook duplicado, clave reutilizada con otro contenido (409), versión obsoleta en edición y borrado (409), prioridad de la versión sobre `occurredAt`, eventos fuera de orden, escritura directa, separación de privilegios entre las credenciales del Consumer y de administración (403), paginación, 401, validación sin stack trace, campos desconocidos rechazados, códigos estándar (400/415), **contrato** (acepta `webhook-created.json` y responde con la forma de `item-response.json`), purga de idempotencia, backup legible y validación de tokens. |
| `consumer-api` | 26 (`SyncServiceWireMockTest`, `SyncProducerWinsTest`, `SyncConsumerWinsTest`, `OutboxBatchCutoffTest`) con el Producer simulado en WireMock | Alta → webhook (Bearer, Idempotency-Key, X-Event-Type) → `CONFIRMED`; edición con `baseVersion`; reintentos con la misma clave; Producer caído más allá de `SYNC_MAX_RETRIES` sigue pendiente y se confirma al volver; políticas `PRODUCER_WINS` y `CONSUMER_WINS` (rebase y tope de conflictos); rechazo → `FAILED` con su motivo (`CONFLICT`, `GONE`, `REJECTED`); borrado (y 404 idempotente); edición bloqueada si hay cambio pendiente; paginación, búsqueda y filtros (estado, tipo, sincronización); reconciliación (siembra, borrado seguro y manual); registro de eventos fallidos; métricas solo para admin; healthchecks; **contrato** en ambos sentidos; purga del outbox; corte del lote con el Producer caído; seguridad y errores estándar. |
| `frontend` | 31 (Vitest + Testing Library) | Login OIDC (sin sesión no hay llamadas; tras `/callback` la primera petición ya lleva el Bearer), lista con estados de sincronización, paginación, búsqueda, filtros y atajos, reintentar solo cuando tiene sentido y descartar, aviso de reintento automático con el Producer caído, componentes del sistema de diseño, confirmación de borrado, reconciliación manual, formulario (validación local y del servidor, sin pisar lo que se escribe) y registro de eventos. |
| E2E de API | `scripts/smoke-test.sh` | Stack real con los criterios de aceptación y escenarios de §6, incluida la separación de privilegios del Producer. |
| E2E de navegador | `e2e/` (Playwright, Chromium) | Login real en Keycloak, dato del Producer visible tras sincronizar, edición desde la UI confirmada por el Producer (v2), conflicto detectado y descartado, filtros, registro de eventos, tema oscuro y vista móvil. Guarda capturas. Se ejecuta en el CI; ver [`e2e/README.md`](e2e/README.md). |

El CI (`.github/workflows/ci.yml`) ejecuta las tres suites, valida `docker compose config` y lanza
el smoke y el E2E de navegador tras `cp .env.example .env && docker compose up -d --build`.

## 12. Decisiones técnicas y trade-offs

Registro de las decisiones de diseño, con la alternativa que se descartó y lo que se gana y se paga
con cada una. Las que tienen más contexto enlazan a su sección.

**Sincronización e integridad**

| # | Decisión | Alternativa descartada | Por qué / trade-off |
|---|---|---|---|
| D-01 | **Outbox transaccional** en el Consumer | Llamar al Producer dentro de la petición del usuario | El cambio y su evento se guardan juntos: sobrevive a caídas y reinicios. A cambio, consistencia eventual. |
| D-02 | **Escrituras con `202 Accepted`** (asíncronas) | Esperar al Producer y devolver `200` | La UI responde al instante y funciona con el Producer caído. A cambio, la UI debe mostrar el estado «pendiente» ([§5](#5-flujo-de-sincronización)). |
| D-03 | **Idempotency-Key + hash del contenido** en el Producer | Deduplicar por id del ítem o por fechas | La clave identifica el *intento*, no el ítem: reintentos seguros y detección de claves reutilizadas con otro contenido. |
| D-04 | **Concurrencia optimista por versión** (`baseVersion`) | Last-write-wins por timestamp | No depende de relojes y no pierde cambios en silencio: el conflicto se detecta (409) y se decide. |
| D-05 | **Política de conflicto `MANUAL` por defecto** | `PRODUCER_WINS` o `CONSUMER_WINS` por defecto | Ninguna edición se pierde ni se impone sin que el usuario lo sepa. A cambio, requiere su acción; las otras dos políticas son configurables (`SYNC_CONFLICT_POLICY`). |
| D-06 | **Reintento sin límite de los fallos transitorios** (backoff, tope 5 min) | Dar el evento por fallido tras N intentos | Una caída larga del Producer se recupera sola, sin reintentar ítem por ítem. A cambio, si el Producer no vuelve nunca, los pendientes se acumulan (visibles en métricas y en *Sincronización*). Solo los rechazos (4xx) son definitivos. |
| D-07 | **Edición bloqueada mientras hay un cambio pendiente** | Encolar varias ediciones del mismo ítem | Una clave de idempotencia nunca cambia de contenido y el razonamiento es simple. A cambio, el usuario espera 1–2 s entre ediciones del mismo ítem. |
| D-08 | **HTTP fuera de transacción** y confirmación atómica después | Una transacción que envuelva la llamada al Producer | SQLite admite un solo escritor: bloquearlo durante un timeout degradaría todo. La confirmación (evento `SENT` + proyección) sigue siendo atómica. |
| D-09 | **Reconciliación periódica** (pull) para los cambios del Producer | Notificación Producer → Consumer (push) | Un solo sentido de dependencia: el Producer no conoce a sus consumidores. A cambio, sus cambios tardan hasta 60 s (o un clic) en verse ([§3](#varios-canales-y-aplicaciones)). |
| D-10 | **El id de un alta lo genera el Consumer** (UUID) y el Producer lo valida | Id asignado siempre por el Producer | Misma identidad en ambas BD y reintentos idempotentes sin remapear ids ([§4](#cómo-se-cumple-la-regla-ssot)). |
| D-11 | **Borrado físico** en la fuente de verdad | Borrado lógico (marca `eliminado`) | Simple y fiel a «eliminar». A cambio, no hay papelera ni historial; el borrado lógico figura como evolución (§15). |

**Seguridad**

| # | Decisión | Alternativa descartada | Por qué / trade-off |
|---|---|---|---|
| D-12 | **OIDC (Keycloak) para usuarios; tokens estáticos entre servicios** | Token estático también para usuarios, o OAuth *client credentials* entre servicios | Identidad real sin tokens en el bundle; el tramo máquina a máquina es interno. Keycloak es un extra: su coste y cómo prescindir de él están en la [priorización](#priorización-núcleo-del-enunciado-y-extras) y en [§8](#8-seguridad-y-estrategia-de-autenticación). |
| D-13 | **Dos credenciales en el Producer** (servicio y administración) | Un único token para todo | El Consumer no puede escribir en la SSoT saltándose el webhook (403). A cambio, una variable más. |

**Datos, API e interfaz**

| # | Decisión | Alternativa descartada | Por qué / trade-off |
|---|---|---|---|
| D-14 | **SQLite + `ddl-auto=update`** | Migraciones versionadas (Flyway/Liquibase) | Lo pide el enunciado y reduce piezas. Migrar a esquemas versionados es el primer paso hacia producción. |
| D-15 | **Paginación, búsqueda y filtros en el servidor** | Traer todo el catálogo y filtrar en el navegador | Coste constante por pantalla: con 1.500 ítems, de 178 ms / 595 KB a 32 ms / 1,1 KB ([auditoría](docs/AUDITORIA.md)). A cambio, una petición por cambio de filtro (amortiguada con 300 ms de retardo). |
| D-16 | **nginx como proxy del SPA** | CORS directo del navegador al Consumer | Mismo origen, CSP estricta, límite de peticiones y de tamaño en un solo punto. |
| D-17 | **CSS propio con design tokens, sin librería de componentes** | MUI, Tailwind, shadcn… | Control total del sistema visual, bundle ligero y sin dependencias de UI. A cambio, los componentes se mantienen a mano. |

## 13. Supuestos

Donde el enunciado no concreta, se asumió lo siguiente. Cambiar un supuesto tiene la consecuencia
indicada.

**De negocio**

| Supuesto | Consecuencia en la solución |
|---|---|
| «Modificar desde el frontend» incluye **crear, editar y eliminar**. | Las tres operaciones viajan por el mismo webhook (`CREATED`, `UPDATED`, `DELETED`). |
| `estado` (`ACTIVO`/`INACTIVO`) es un **atributo del ítem**, no una baja. | Un ítem inactivo sigue visible y editable; se puede filtrar por estado. Eliminar es otra operación. |
| El **nombre no es único**. | Se permiten dos ítems con el mismo nombre; la identidad es el `id`. |
| Todos los usuarios autenticados pueden **editar cualquier ítem**; no hay propiedad por usuario. | Un solo rol de negocio (`user`); `admin` solo añade acceso a métricas. |
| Se acepta **consistencia eventual de segundos** entre la UI y la fuente de verdad. | Los cambios se muestran como «pendientes» hasta que el Producer los confirma (D-02). |
| «Producer crea información» significa su **API REST de administración**. | La creación en el Producer se hace con curl o Postman; el enunciado solo pide interfaz para el Consumer. |
| La interfaz es **solo en español**. | Textos de la UI y del login de Keycloak en español, sin internacionalización. |

**Técnicos**

| Supuesto | Consecuencia en la solución |
|---|---|
| Hay **un** Consumer. | El diseño admite más ([§3](#varios-canales-y-aplicaciones)); con varios, los cambios del Producer les llegan por reconciliación. |
| El catálogo tiene **decenas de miles de ítems** como mucho. | SQLite y reconciliación completa cada 60 s son suficientes. |
| Las fechas se guardan en **UTC** y se intercambian en **ISO-8601**. | La UI las muestra en la zona horaria del navegador. |
| Los relojes de los contenedores están **sincronizados**. | Solo afecta a eventos sin versión (D-04 no depende de relojes). |
| Se ejecuta **en local**, sin TLS. | Todos los puertos en `127.0.0.1`; para exponerlo, un proxy con TLS delante (`FRONTEND_BIND`, §15). |
| Navegador **moderno** (con `backdrop-filter` y `<dialog>`). | En navegadores sin desenfoque, las superficies se vuelven más opacas y el contenido sigue siendo legible. |

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
scripts/             smoke-test.sh (E2E de API), build-api-docs.py (genera docs/api.html) y hooks de git
e2e/                 Playwright · E2E de navegador con login real y capturas
docs/                manual.html (manual de uso), api.html + openapi/ (Swagger de ambas APIs),
                     curl.md, colección Postman y AUDITORIA.md
docker-compose.yml   Orquestación · .env.example: configuración de ejemplo
```

La auditoría técnica, con los hallazgos, las mediciones de latencia y los riesgos residuales, está
en [`docs/AUDITORIA.md`](docs/AUDITORIA.md).
