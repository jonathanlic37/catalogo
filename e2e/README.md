# E2E de navegador (Playwright)

Recorre el flujo del enunciado en un Chromium real contra el stack levantado:

1. Login OIDC en Keycloak (Authorization Code + PKCE).
2. Un ítem creado en el Producer aparece en la UI tras «Sincronizar ahora».
3. La edición desde la UI viaja por webhook y el Producer la confirma (versión 2).
4. Un conflicto se detecta y se descarta adoptando la versión canónica.
5. Filtros, registro de eventos, tema oscuro y vista móvil.

Guarda capturas en `test-results/capturas/`.

Instalación ligera: solo Chromium (~150 MB), sin la imagen Docker de Playwright.

```bash
# Con el stack levantado desde la raíz: cp .env.example .env && docker compose up -d --build
cd e2e
npm ci
npx playwright install chromium          # en Linux sin librerías de navegador: --with-deps
set -a; source ../.env; set +a           # PRODUCER_ADMIN_TOKEN, DEMO_USER_PASSWORD
npx playwright test
```

El CI (`.github/workflows/ci.yml`) lo ejecuta así en cada push, tras el smoke de API.

Sin Node ni navegador, el mismo login OIDC con PKCE se puede comprobar con `curl`: autorización →
formulario de Keycloak → redirección a `/callback` con el código → canje con `code_verifier`.
Así se verificó en local (ver `docs/AUDITORIA.md`, V13).
