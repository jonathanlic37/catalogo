// frontend/src/pages/CatalogoList.test.tsx
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it } from 'vitest';
import { item, mockFetch, page, renderApp, summary } from '../test/utils';
import { CatalogoList } from './CatalogoList';

const render = () => renderApp(<CatalogoList />);

describe('CatalogoList', () => {
  it('muestra los ítems, el resumen global y los cambios pendientes de confirmación', async () => {
    mockFetch((_m, url) => {
      if (url.startsWith('/api/items/summary')) return summary({ total: 2, activos: 2, pendientes: 1 });
      return page([
        item({ id: 'a', nombre: 'Café molido' }),
        item({ id: 'b', nombre: 'Té verde', syncStatus: 'PENDING', pendingOperation: 'UPDATED' }),
      ]);
    });
    render();

    expect(await screen.findByText('Café molido')).toBeInTheDocument();
    expect(screen.getByText('Té verde')).toBeInTheDocument();
    expect(await screen.findByText(/1 cambio\(s\) pendiente\(s\) de confirmación/)).toBeInTheDocument();
    expect(screen.getByText('Pendiente de confirmación · edición')).toBeInTheDocument();
    expect(screen.getByText('1–2 de 2')).toBeInTheDocument();
    // Solo los ítems confirmados se pueden editar o eliminar.
    expect(screen.getByLabelText('Editar Café molido')).toBeInTheDocument();
    expect(screen.queryByLabelText('Editar Té verde')).not.toBeInTheDocument();
  });

  it('pide al servidor la búsqueda (con retardo) y vuelve a la primera página', async () => {
    const fetchMock = mockFetch((_m, url) => (url.startsWith('/api/items/summary') ? summary() : page([item()])));
    render();
    await screen.findByText('Café');

    await userEvent.type(screen.getByLabelText('Buscar por nombre'), 'té');

    await waitFor(
      () => expect(fetchMock.mock.calls.some(([u]) => String(u).includes('q=t%C3%A9') && String(u).includes('page=0'))).toBe(true),
      { timeout: 3000 },
    );
  });

  it('pagina con el servidor: "siguiente" pide la página 1', async () => {
    const fetchMock = mockFetch((_m, url) => {
      if (url.startsWith('/api/items/summary')) return summary({ total: 40 });
      return url.includes('page=1')
        ? page([item({ nombre: 'Segunda página' })], { page: 1, totalElements: 40, totalPages: 2, last: true })
        : page([item({ nombre: 'Primera página' })], { totalElements: 40, totalPages: 2, last: false });
    });
    render();
    await screen.findByText('Primera página');
    expect(screen.getByText('1–25 de 40')).toBeInTheDocument();

    await userEvent.click(screen.getByLabelText('Página siguiente'));

    expect(await screen.findByText('Segunda página')).toBeInTheDocument();
    expect(fetchMock.mock.calls.some(([u]) => String(u).includes('page=1'))).toBe(true);
    expect(screen.getByLabelText('Página siguiente')).toBeDisabled();
  });

  it('muestra el estado vacío cuando no hay ítems', async () => {
    mockFetch((_m, url) => (url.startsWith('/api/items/summary') ? summary() : page([])));
    render();
    expect(await screen.findByText('Aún no hay ítems')).toBeInTheDocument();
  });

  it('ofrece reintentar o descartar un cambio fallido y explica el motivo', async () => {
    const fetchMock = mockFetch((method, url) => {
      if (url.startsWith('/api/items/summary')) return summary({ total: 1, fallidos: 1 });
      if (method === 'POST' && url.endsWith('/retry')) return item({ id: 'f', syncStatus: 'PENDING', pendingOperation: 'UPDATED' });
      return page([item({ id: 'f', nombre: 'Roto', syncStatus: 'FAILED', syncError: 'Conflicto: el ítem cambió' })]);
    });
    render();

    const row = (await screen.findByText('Roto')).closest('tr')!;
    expect(within(row).getByText('Conflicto: el ítem cambió', { selector: '.err-line' })).toBeInTheDocument();
    await userEvent.click(within(row).getByLabelText('Reintentar Roto'));

    await waitFor(() =>
      expect(fetchMock.mock.calls.some(([u, i]) => String(u).endsWith('/items/f/retry') && i?.method === 'POST')).toBe(true),
    );
  });

  it('con un cambio fallido ofrece también descartarlo', async () => {
    mockFetch((_m, url) =>
      url.startsWith('/api/items/summary')
        ? summary({ total: 1, fallidos: 1 })
        : page([item({ id: 'g', nombre: 'Roto2', syncStatus: 'FAILED', syncError: 'x' })]),
    );
    render();
    expect(await screen.findByLabelText('Descartar cambio de Roto2')).toBeInTheDocument();
  });

  it('pide confirmación antes de eliminar y solo entonces llama a la API', async () => {
    const fetchMock = mockFetch((method, url) => {
      if (url.startsWith('/api/items/summary')) return summary({ total: 1 });
      if (method === 'DELETE') return item({ id: 'd', nombre: 'Borrable', syncStatus: 'PENDING', pendingOperation: 'DELETED' });
      return page([item({ id: 'd', nombre: 'Borrable' })]);
    });
    render();

    await userEvent.click(await screen.findByLabelText('Eliminar Borrable'));
    expect(fetchMock.mock.calls.some(([, i]) => i?.method === 'DELETE')).toBe(false);

    await userEvent.click(screen.getByRole('button', { name: 'Eliminar' }));
    await waitFor(() => expect(fetchMock.mock.calls.some(([, i]) => i?.method === 'DELETE')).toBe(true));
  });
});
