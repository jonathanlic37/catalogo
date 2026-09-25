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
| L3 | Media | Con el Producer caído, el ciclo de reintentos esperaba un timeout **por ítem pendiente** en el único hilo del planificador, que además compartía con la reconciliación. | Bloqueo O(pendientes × timeout) | Corte en el primer fallo de red/5xx + pool de 2 hilos | `retryPending` aplaza el resto del lote; `spring.task.scheduling.pool.size=2`. *Cubierto por revisión de código; no tiene una prueba dedicada.* |
| L4 | Baja | Sin índices en la consulta de reintentos (cada 10 s) ni en los órdenes por fecha. | Escaneo completo | Índices `(syncStatus, nextRetryAt)`, `fechaActualizacion`, `(fechaCreacion, id)`, `createdAt` | `@Index` en las entidades (creados con `ddl-auto=update` sobre la BD existente sin errores). |
| — | Info | Camino de escritura sin degradación con 1 500 ítems. | POST 18 ms · hasta `CONFIRMED` 90 ms | POST 24 ms · hasta `CONFIRMED` 104 ms | Sin cambios necesarios. |

Pruebas de concurrencia (stack real): **100 altas simultáneas** (50 hilos) → 100 × `202`, los 100 `CONFIRMED` en
~5 s, exactamente 100 ítems nuevos en el Producer, sin `SQLITE_BUSY` ni errores en los logs.
**20 webhooks simultáneos con la misma `Idempotency-Key`** → 1 ítem creado, 19 respuestas `Idempotent-Replayed: true`.

## 3. Seguridad

| ID | Sev. | Hallazgo | Mitigación | Estado |
|---|---|---|---|---|
| S1 | Alta | **La UI no tiene autenticación de usuario**: nginx inyecta el token del servicio, así que cualquiera que alcance el puerto puede modificar el catálogo. Además escuchaba en `0.0.0.0`. | La UI escucha solo en `127.0.0.1` por defecto (`FRONTEND_BIND`). Se documenta que exponerla exige un proxy con TLS y login. | **Riesgo residual**: añadir login requiere un proveedor de identidad (fuera del alcance). |
| S2 | Alta | Las APIs aceptaban tokens cortos o el valor de ejemplo de `.env.example`. | Las APIs **no arrancan** si un token tiene < 32 caracteres o contiene "cambiar". | Verificado: el contenedor aborta con el mensaje; tests unitarios. |
| S3 | Media | nginx sin cabeceras de seguridad, versión expuesta, corriendo como root, sin límite de cuerpo explícito. | Imagen `nginx-unprivileged` (usuario 101), CSP estricta (verificado: el `index.html` no tiene scripts inline), `X-Frame-Options`, `nosniff`, `Referrer-Policy`, `Permissions-Policy`, `server_tokens off`, cuerpo ≤ 64 KB (413), caché inmutable para assets con hash y `no-store` en `/api`. | Verificado con `curl`. |
| S4 | Media | Contenedores con privilegios por defecto: sistema de ficheros escribible, todas las capabilities, sin límites, logs sin rotar. | Java: `read_only`, `cap_drop: ALL`, `no-new-privileges`, 512 MB, 200 procesos; nginx: sin capabilities, 128 MB; logs `json-file` 10 MB × 3. | Verificado con `docker inspect`; los tres servicios `healthy`. |
| S5 | Baja | `.env` (con secretos) con permisos 664. | `chmod 600` y documentado. | Verificado. |
| S6 | Info | Sin límite de peticiones por cliente. | No se implementó: con tokens de 256 bits y sin exposición externa el riesgo es bajo. | **Aceptado**, documentado. |
| S7 | Info | Sin TLS entre contenedores. | La red `backend` es interna (sin salida a Internet). | **Aceptado**, documentado. |

Comprobado sin hallazgo: el navegador no puede suplantar el token (nginx sobrescribe `Authorization`),
sin token → 401 en ambas APIs, el bundle no contiene tokens, las validaciones no exponen trazas.

## 4. Datos y disponibilidad

| ID | Sev. | Hallazgo | Mitigación | Verificación |
|---|---|---|---|---|
| D1 | Alta | La BD del **Producer (fuente de verdad) no tenía copia de seguridad**. | `BackupService`: copia consistente con la API de backup online de SQLite cada 6 h (volumen `producer-backups`, se conservan las 8 últimas). La réplica del Consumer no la necesita (se reconstruye). | Test de backup; en el stack: fichero de 1,6 MB con cabecera `SQLite format 3`. *El procedimiento de restauración del README no se ha ejecutado de extremo a extremo.* |
| D2 | Media | `idempotency_records` crecía sin límite. | `IdempotencyPurger`: elimina registros de más de 7 días (≫ ventana de reintentos). | Test de purga. |
| D3 | Baja | Al parar un contenedor se podían cortar webhooks en vuelo. | `server.shutdown=graceful` (20 s) + `stop_grace_period: 30s`. | Configuración; sin prueba específica. |
| D4 | Info | Persistencia ante fallos. | — | Con el Producer parado se crearon 3 ítems (`PENDING`), se reinició el Consumer y se arrancó el Producer: los 3 llegaron a `CONFIRMED`. |

## 5. Pruebas

| Módulo | Antes | Ahora |
|---|---|---|
| `producer-api` | 5 | **11** (actualización y borrado idempotentes, paginación, 415/400, purga, backup, validación de tokens…) |
| `consumer-api` | 4 | **12** (edición, borrado incl. 404, paginación/búsqueda/resumen, reconciliación, seguridad, errores estándar…) |
| `frontend` | 0 | **14** con Vitest + Testing Library (lista, paginación, búsqueda, confirmaciones, reintento/descarte, formulario, badge) |

Ejecución: ver README → «Pruebas automatizadas».

## 6. Riesgos residuales (no mitigados)

1. **UI sin login** (S1): depende de mantener el puerto en `127.0.0.1` o de un proxy autenticado delante.
2. **Tokens estáticos compartidos**, sin rotación, y **sin rate limiting** (S6).
3. **SQLite de un solo escritor**: adecuado para decenas de miles de ítems; el backup retiene brevemente la única conexión.
4. **Reconciliación completa cada minuto**: ahora paginada y acotada en memoria, pero sigue siendo O(N) en red; con cientos de miles de ítems habría que hacerla incremental. Un borrado masivo en el Producer provoca una consulta puntual por ítem ausente.
5. La búsqueda por nombre solo ignora mayúsculas/minúsculas ASCII (`lower()` de SQLite).
6. `ddl-auto=update` en lugar de migraciones versionadas.
7. Un conflicto de versión (409) no se resuelve automáticamente: el usuario reintenta o descarta el cambio.
8. Sin verificar: el corte de lote ante Producer caído (L3) y la restauración de backups (D1) no tienen prueba automatizada.
