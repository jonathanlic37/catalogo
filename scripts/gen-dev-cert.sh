#!/usr/bin/env bash
# scripts/gen-dev-cert.sh — certificado autofirmado para docker-compose.tls.yml (solo desarrollo).
# Crea certs/tls.crt y certs/tls.key (localhost, 127.0.0.1; 825 días). certs/ está en .gitignore.
# Con un dominio real se sustituyen por un certificado de una CA (p. ej. Let's Encrypt).
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p certs
if [[ -f certs/tls.crt && -f certs/tls.key && "${1:-}" != "--force" ]]; then
  echo "certs/tls.crt ya existe (usa --force para regenerarlo)"
  exit 0
fi
openssl req -x509 -newkey rsa:2048 -nodes -days 825 -sha256 \
  -keyout certs/tls.key -out certs/tls.crt \
  -subj "/CN=localhost" \
  -addext "subjectAltName=DNS:localhost,IP:127.0.0.1" >/dev/null 2>&1
# nginx corre sin privilegios (uid 101) dentro del contenedor: la clave debe ser legible para él.
chmod 644 certs/tls.key
echo "Certificado de desarrollo creado en certs/ (autofirmado: el navegador pedirá aceptarlo)"
