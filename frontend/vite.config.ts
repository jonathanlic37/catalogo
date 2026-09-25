// frontend/vite.config.ts
import react from '@vitejs/plugin-react';
import { defineConfig } from 'vitest/config';

// En desarrollo, /api se reenvía a la Consumer API. El Authorization lo aporta el SPA (JWT del IdP
// vía OIDC + PKCE) y el proxy solo lo reenvía; no hay ningún token en el bundle ni en el shell.
export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    proxy: {
      '/api': {
        target: process.env.DEV_CONSUMER_URL ?? 'http://localhost:8081',
        changeOrigin: true,
      },
    },
  },
  test: {
    environment: 'jsdom',
    setupFiles: ['./src/test/setup.ts'],
    css: false,
  },
});
