# Contribuir

## Flujo de ramas

- `main` es la rama estable y desplegable. **No se commitea directamente en `main`.**
- Cada cambio va en una rama corta creada desde `main`, con nombre `tipo/descripcion`:
  - `feature/...` nueva funcionalidad
  - `fix/...` corrección
  - `docs/...` documentación
  - `chore/...` mantenimiento
- Se integra con **Pull Request** hacia `main` (squash merge) y se borra la rama.

## Hook pre-push

El repo incluye `scripts/hooks/pre-push`, que **rechaza el push directo a `main`/`master`**. Actívalo
una vez por clon:

```bash
git config core.hooksPath scripts/hooks
```

Para saltarlo de forma consciente (p. ej. una emergencia): `ALLOW_PUSH_MAIN=1 git push origin main`.

> La protección de rama del lado del servidor (branch protection / rulesets) exige GitHub Pro en
> repos privados; con este hook la convención queda aplicada en local.

## Commits

- Asunto imperativo y concreto (`fix(webhook): ...`, `feat(outbox): ...`).
- El cuerpo explica **por qué** (causa raíz, síntoma que producía, cómo se verificó), no qué líneas cambiaron.
- Un commit = un propósito.

## Antes de abrir el PR

No hace falta Java/Node local: las pruebas corren en Docker.

```bash
docker run --rm -v "$PWD/producer-api":/app -v catalogo-m2:/root/.m2 -w /app maven:3.9-eclipse-temurin-21 mvn -q -B test
docker run --rm -v "$PWD/consumer-api":/app -v catalogo-m2:/root/.m2 -w /app maven:3.9-eclipse-temurin-21 mvn -q -B test
docker run --rm -v "$PWD/frontend":/src:ro -w /tmp node:22-alpine sh -c \
  "mkdir /work && cd /src && tar cf - --exclude=node_modules --exclude=dist . | tar xf - -C /work \
   && cd /work && npm install --no-audit --no-fund >/dev/null 2>&1 && npx vitest run && npx tsc --noEmit"
docker compose config -q
```

## Nunca versionar

- Secretos: `.env`, credenciales, tokens.
- Bases SQLite de runtime: `*.db`, `*.db-shm`, `*.db-wal`.
- Artefactos de build: `target/`, `dist/`.
- Dependencias: `node_modules/`.

Están cubiertos por `.gitignore`; verifica con `git status` antes de cada commit.
