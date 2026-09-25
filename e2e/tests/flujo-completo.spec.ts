// e2e/tests/flujo-completo.spec.ts
//
// Flujo funcional del enunciado en un navegador real, contra el stack levantado con docker compose:
//   login OIDC en Keycloak → dato creado en el Producer aparece tras sincronizar → edición desde la UI
//   (webhook) confirmada por el Producer → conflicto detectado y descartado → registro de eventos.
// Guarda capturas en test-results/capturas/ para revisión visual.
import { expect, request, test, type APIRequestContext, type Page } from '@playwright/test';

const PRODUCER = process.env.E2E_PRODUCER_URL ?? 'http://localhost:8082';
const ADMIN_TOKEN = process.env.PRODUCER_ADMIN_TOKEN ?? '';
const USER = 'demo';
const PASSWORD = process.env.DEMO_USER_PASSWORD ?? '';

const run = Date.now().toString(36);
const NOMBRE = `E2E ${run}`;
const EDITADO = `E2E ${run} editado`;
const CONFLICTO_PRODUCER = `E2E ${run} cambio en el Producer`;
const CONFLICTO_UI = `E2E ${run} edición obsoleta`;

let producer: APIRequestContext;
let itemId = '';

const shot = (page: Page, name: string) =>
  page.screenshot({ path: `test-results/capturas/${name}.png`, fullPage: true });

async function buscar(page: Page, texto: string) {
  const search = page.getByLabel('Buscar por nombre');
  await search.fill(texto);
}

const fila = (page: Page, nombre: string) => page.getByRole('row').filter({ hasText: nombre });

test.describe.configure({ mode: 'serial' });

test.beforeAll(async () => {
  expect(ADMIN_TOKEN, 'PRODUCER_ADMIN_TOKEN (cargar el .env)').not.toBe('');
  expect(PASSWORD, 'DEMO_USER_PASSWORD (cargar el .env)').not.toBe('');
  producer = await request.newContext({
    baseURL: PRODUCER,
    extraHTTPHeaders: { Authorization: `Bearer ${ADMIN_TOKEN}` },
  });
  // Paso 1 del enunciado: la información nace en el Producer (fuente de verdad).
  const res = await producer.post('/api/items', {
    data: { nombre: NOMBRE, descripcion: 'Creado en el Producer por el E2E', estado: 'ACTIVO', tipo: 'SERVICIO' },
  });
  expect(res.status()).toBe(201);
  itemId = (await res.json()).id;
});

test.afterAll(async () => {
  await producer?.dispose();
});

test('flujo completo en el navegador', async ({ page }) => {
  await test.step('login OIDC en Keycloak (Authorization Code + PKCE)', async () => {
    await page.goto('/');
    await expect(page.getByRole('button', { name: /Iniciar sesión/ })).toBeVisible();
    await shot(page, '01-pantalla-de-acceso');
    await page.getByRole('button', { name: /Iniciar sesión/ }).click();

    await page.waitForURL(/\/realms\/catalogo\/protocol\/openid-connect\/auth/);
    await page.locator('#username').fill(USER);
    await page.locator('#password').fill(PASSWORD);
    await shot(page, '02-login-keycloak');
    await page.locator('#kc-login').click();

    // Vuelve por /callback y aterriza en el catálogo (no en «Página no encontrada»).
    await expect(page.getByRole('heading', { name: 'Catálogo', level: 1 })).toBeVisible();
    await expect(page.getByText('Página no encontrada.')).toHaveCount(0);
    await expect(page.getByText(USER, { exact: true })).toBeVisible();
    // La primera carga ya lleva el Bearer: no hay error de autorización en pantalla.
    await expect(page.getByText(/Token ausente o inválido|Error 401/)).toHaveCount(0);
  });

  await test.step('pasos 2-3: sincronizar trae el ítem creado en el Producer', async () => {
    await page.getByRole('button', { name: /Sincronizar ahora/ }).click();
    await expect(page.getByText(/Reconciliación completada/)).toBeVisible();
    await buscar(page, NOMBRE);
    await expect(fila(page, NOMBRE)).toBeVisible();
    await expect(fila(page, NOMBRE).getByText('Sincronizado')).toBeVisible();
    await expect(fila(page, NOMBRE).getByText('Servicio')).toBeVisible();
    await shot(page, '03-catalogo-sincronizado');
  });

  await test.step('pasos 4-10: editar en la UI → webhook → Producer confirma → UI actualizada', async () => {
    await page.getByLabel(`Editar ${NOMBRE}`).click();
    await expect(page.getByRole('heading', { name: 'Editar ítem' })).toBeVisible();
    await page.getByLabel('Nombre').fill(EDITADO);
    await shot(page, '04-formulario-edicion');
    await page.getByRole('button', { name: /Guardar/ }).click();

    await expect(page.getByRole('heading', { name: 'Catálogo', level: 1 })).toBeVisible();
    await buscar(page, EDITADO);
    await expect(fila(page, EDITADO).getByText('Sincronizado')).toBeVisible();
    await shot(page, '05-edicion-confirmada');

    // El cambio está en la fuente de verdad, con la versión que asignó el Producer.
    const canon = await (await producer.get(`/api/items/${itemId}`)).json();
    expect(canon.nombre).toBe(EDITADO);
    expect(canon.version).toBe(2);
  });

  await test.step('conflicto: el registro cambió en el Producer antes de recibir la modificación', async () => {
    const put = await producer.put(`/api/items/${itemId}`, {
      data: { nombre: CONFLICTO_PRODUCER, estado: 'ACTIVO', tipo: 'SERVICIO' },
    });
    expect(put.status()).toBe(200); // el Producer pasa a v3; la UI sigue en v2

    await page.getByLabel(`Editar ${EDITADO}`).click();
    await page.getByLabel('Nombre').fill(CONFLICTO_UI);
    await page.getByRole('button', { name: /Guardar/ }).click();

    await buscar(page, CONFLICTO_UI);
    const row = fila(page, CONFLICTO_UI);
    await expect(row.getByText('Error de sincronización')).toBeVisible();
    await expect(row.getByText(/Conflicto/)).toBeVisible();
    await shot(page, '06-conflicto-detectado');

    // La fuente de verdad no se sobrescribió.
    expect((await (await producer.get(`/api/items/${itemId}`)).json()).nombre).toBe(CONFLICTO_PRODUCER);

    // Descartar el cambio local adopta la versión canónica.
    await page.getByLabel(`Descartar cambio de ${CONFLICTO_UI}`).click();
    const dialog = page.getByRole('dialog');
    await expect(dialog).toBeVisible();
    await shot(page, '07-confirmar-descarte');
    await dialog.getByRole('button', { name: 'Descartar cambio', exact: true }).click();

    await buscar(page, CONFLICTO_PRODUCER);
    await expect(fila(page, CONFLICTO_PRODUCER).getByText('Sincronizado')).toBeVisible();
  });

  await test.step('filtros: tipo y estado de sincronización', async () => {
    await buscar(page, `E2E ${run}`);
    await page.getByLabel('Filtrar por tipo').selectOption('SERVICIO');
    await expect(fila(page, CONFLICTO_PRODUCER)).toBeVisible();
    await page.getByLabel('Filtrar por tipo').selectOption('CONTENIDO');
    await expect(page.getByText('Sin resultados')).toBeVisible();
    await page.getByLabel('Filtrar por tipo').selectOption('');
    await shot(page, '08-filtros');
  });

  await test.step('registro de eventos de sincronización', async () => {
    await page.getByRole('link', { name: /Sincronización/ }).click();
    await expect(page.getByRole('heading', { name: 'Sincronización', level: 1 })).toBeVisible();
    await page.getByLabel('Todos').check();
    await expect(page.getByText('Confirmado').first()).toBeVisible();
    await shot(page, '09-registro-de-eventos');
  });

  await test.step('revisión visual: tema claro (el oscuro es el predeterminado) y móvil', async () => {
    await page.getByRole('link', { name: /Catálogo, ir al inicio/ }).click();
    await shot(page, '10-tema-oscuro');
    await page.getByRole('button', { name: /Cambiar a tema claro/ }).click();
    await expect(page.locator('html')).toHaveAttribute('data-theme', 'light');
    await shot(page, '11-tema-claro');
    await page.getByRole('button', { name: /Cambiar a tema oscuro/ }).click();
    await page.setViewportSize({ width: 390, height: 844 });
    await shot(page, '12-movil');
  });
});
