// frontend/vite.config.ts
import react from '@vitejs/plugin-react';
import { defineConfig } from 'vitest/config';

// En desarrollo, /api se reenvía a la Consumer API. El token Bearer NO vive en el frontend:
// en producción lo inyecta el nginx del contenedor; en dev se puede exportar DEV_CONSUMER_TOKEN
// en el shell (nunca con prefijo VITE_, para que no entre al bundle).
export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    proxy: {
      '/api': {
        target: process.env.DEV_CONSUMER_URL ?? 'http://localhost:8081',
        changeOrigin: true,
        headers: process.env.DEV_CONSUMER_TOKEN
          ? { Authorization: `Bearer ${process.env.DEV_CONSUMER_TOKEN}` }
          : {},
      },
    },
  },
  test: {
    environment: 'jsdom',
    setupFiles: ['./src/test/setup.ts'],
    css: false,
  },
});
