// frontend/src/test/utils.tsx
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render } from '@testing-library/react';
import type { ReactElement } from 'react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { vi } from 'vitest';
import type { Item, Page, Summary } from '../types/item';

export function item(over: Partial<Item> = {}): Item {
  return {
    id: over.id ?? crypto.randomUUID(),
    nombre: 'Café',
    descripcion: 'Molido',
    estado: 'ACTIVO',
    tipo: 'PRODUCTO',
    fechaCreacion: '2026-01-01T00:00:00Z',
    fechaActualizacion: '2026-01-02T10:00:00Z',
    version: 1,
    syncStatus: 'CONFIRMED',
    ...over,
  };
}

export function page(content: Item[], over: Partial<Page<Item>> = {}): Page<Item> {
  return { content, page: 0, size: 25, totalElements: content.length, totalPages: content.length ? 1 : 0, last: true, ...over };
}

export const summary = (over: Partial<Summary> = {}): Summary => ({ total: 0, activos: 0, pendientes: 0, fallidos: 0, ...over });

const json = (body: unknown, status = 200) =>
  new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } });

/** Sustituye fetch: `handler` recibe método y URL relativa (p. ej. "/api/items?page=0&size=25"). */
export function mockFetch(handler: (method: string, url: string, body?: string) => Response | unknown) {
  const fn = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const result = handler((init?.method ?? 'GET').toUpperCase(), String(input), init?.body as string | undefined);
    return result instanceof Response ? result : json(result);
  });
  vi.stubGlobal('fetch', fn);
  return fn;
}

export function renderApp(ui: ReactElement, { route = '/', path = '/' } = {}) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } });
  return render(
    <QueryClientProvider client={client}>
      <MemoryRouter initialEntries={[route]}>
        <Routes>
          <Route path={path} element={ui} />
          <Route path="/" element={<p>Lista</p>} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  );
}
