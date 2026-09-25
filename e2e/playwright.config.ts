// e2e/playwright.config.ts
import { defineConfig, devices } from '@playwright/test';

// Se ejecuta contra el stack real (`docker compose up`): no levanta servidores propios.
export default defineConfig({
  testDir: './tests',
  // Un único test recorre todo el flujo (login, sincronización, edición, conflicto, filtros, temas);
  // en los runners de CI tarda ~2 min, así que se deja margen.
  timeout: 180_000,
  expect: { timeout: 20_000 },
  fullyParallel: false, // un único flujo sobre datos compartidos
  workers: 1,
  retries: 0,
  reporter: [['list'], ['html', { open: 'never', outputFolder: 'playwright-report' }]],
  outputDir: 'test-results',
  use: {
    baseURL: process.env.E2E_BASE_URL ?? 'http://localhost:8088',
    locale: 'es-ES',
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
    video: 'off',
  },
  projects: [{ name: 'chromium', use: { ...devices['Desktop Chrome'], viewport: { width: 1366, height: 900 } } }],
});
