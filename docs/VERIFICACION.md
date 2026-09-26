# Reporte de verificación

- **Fecha:** 2026-09-25
- **Commit verificado:** `b065240` (rama `main`)
- **Entorno:** Linux, Docker Compose 5.4.0, Maven 3.9 + Temurin 21 y Node 22 en contenedor, Playwright 1.48.2 (Chromium)

| Suite | Comando | Resultado |
|---|---|---|
| Producer API | `docker run --rm -v "$PWD/producer-api":/app -v "$PWD/contracts":/contracts -w /app maven:3.9-eclipse-temurin-21 mvn -q test` | **22/22** en verde (`WebhookIdempotencyTest`) |
| Consumer API | ídem en `consumer-api` | **26/26** en verde: `SyncServiceWireMockTest` 21, `SyncProducerWinsTest` 2, `SyncConsumerWinsTest` 2, `OutboxBatchCutoffTest` 1 |
| Frontend | `npm ci && npx vitest run && npx tsc --noEmit` (en `node:22-alpine`) | **31/31** en verde, 6 ficheros; `tsc` sin errores |
| Configuración | `cp .env.example .env && docker compose config -q` | Válida |
| Arranque | `docker compose up -d --build && docker compose ps` | `auth`, `producer-api`, `consumer-api`, `frontend`: **healthy** (4/4) |
| E2E de API | `./scripts/smoke-test.sh` | **SMOKE OK** (incluye reinicio del Producer y reenvío del outbox) |
| E2E de navegador | `cd e2e && npm ci && npx playwright install chromium && set -a; source ../.env; set +a && npx playwright test` | **1/1** en verde (39,4 s); 10 capturas en `e2e/test-results/capturas/` (no versionadas) |

**Total de pruebas automatizadas:** 79 (22 + 26 + 31), más el smoke y el E2E de navegador.

**Actualización (idempotencia concurrente):** Producer **23/23**; `WebhookIdempotencyTest` repetido 20 veces: **20/20** en verde. Total: 80.

Las mismas suites se ejecutan en el CI (`.github/workflows/ci.yml`) en cada PR.
