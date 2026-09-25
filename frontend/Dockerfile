# frontend/Dockerfile
FROM node:22-alpine AS build
WORKDIR /app
COPY package.json package-lock.json ./
RUN npm config set fetch-retries 6 && npm config set fetch-retry-mintimeout 20000 \
    && npm ci --no-audit --no-fund
COPY . .
# Config no secreta del OIDC (autoridad, client público y redirect). El token lo obtiene el SPA en runtime.
ARG VITE_API_URL=/api
ARG VITE_OIDC_AUTHORITY
ARG VITE_OIDC_CLIENT_ID
ARG VITE_OIDC_REDIRECT_URI
ENV VITE_API_URL=$VITE_API_URL \
    VITE_OIDC_AUTHORITY=$VITE_OIDC_AUTHORITY \
    VITE_OIDC_CLIENT_ID=$VITE_OIDC_CLIENT_ID \
    VITE_OIDC_REDIRECT_URI=$VITE_OIDC_REDIRECT_URI
RUN npm run build

# Imagen sin privilegios: nginx corre como usuario no root y escucha en 8080.
# Aplica envsubst a /etc/nginx/templates/*.template al arrancar, solo con las variables del entorno
# (CONSUMER_UPSTREAM, OIDC_ORIGIN, RATE_LIMIT_*); las variables propias de nginx ($host...) no se tocan.
FROM nginxinc/nginx-unprivileged:1.27-alpine
COPY nginx/default.conf.template /etc/nginx/templates/default.conf.template
COPY nginx/security-headers.conf /etc/nginx/snippets/security-headers.conf
COPY --from=build /app/dist /usr/share/nginx/html
EXPOSE 8080
