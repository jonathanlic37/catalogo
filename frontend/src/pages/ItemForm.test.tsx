// frontend/src/pages/ItemForm.test.tsx
import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it } from 'vitest';
import { item, mockFetch, renderApp } from '../test/utils';
import { ItemForm } from './ItemForm';

describe('ItemForm', () => {
  it('valida en el cliente y no envía nada si falta el nombre', async () => {
    const fetchMock = mockFetch(() => item());
    renderApp(<ItemForm />, { route: '/nuevo', path: '/nuevo' });

    await userEvent.click(screen.getByRole('button', { name: /Guardar/ }));

    expect(await screen.findByText('El nombre es obligatorio')).toBeInTheDocument();
    expect(screen.getByLabelText('Nombre')).toHaveAttribute('aria-invalid', 'true');
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it('crea el ítem, envía el cuerpo esperado y vuelve a la lista', async () => {
    const fetchMock = mockFetch(() => item({ syncStatus: 'PENDING', pendingOperation: 'CREATED' }));
    renderApp(<ItemForm />, { route: '/nuevo', path: '/nuevo' });

    await userEvent.type(screen.getByLabelText('Nombre'), 'Nuevo ítem');
    await userEvent.click(screen.getByLabelText('Inactivo'));
    await userEvent.click(screen.getByRole('button', { name: /Guardar/ }));

    expect(await screen.findByText('Lista')).toBeInTheDocument();
    const [url, init] = fetchMock.mock.calls[0];
    expect(String(url)).toBe('/api/items');
    expect(init?.method).toBe('POST');
    expect(JSON.parse(String(init?.body))).toEqual({
      nombre: 'Nuevo ítem',
      descripcion: '',
      estado: 'INACTIVO',
      tipo: 'PRODUCTO',
    });
  });

  it('muestra los errores de validación devueltos por el servidor junto al campo', async () => {
    mockFetch(
      () =>
        new Response(JSON.stringify({ detail: 'Datos de entrada inválidos', errors: { nombre: 'tamaño inválido' } }), {
          status: 400,
          headers: { 'Content-Type': 'application/problem+json' },
        }),
    );
    renderApp(<ItemForm />, { route: '/nuevo', path: '/nuevo' });

    await userEvent.type(screen.getByLabelText('Nombre'), 'x');
    await userEvent.click(screen.getByRole('button', { name: /Guardar/ }));

    expect(await screen.findByText('tamaño inválido')).toBeInTheDocument();
  });

  it('al editar carga los datos una sola vez y no los pisa con una recarga', async () => {
    mockFetch(() => item({ id: 'e1', nombre: 'Original', descripcion: 'desc' }));
    renderApp(<ItemForm />, { route: '/editar/e1', path: '/editar/:id' });

    const nombre = await screen.findByDisplayValue('Original');
    await userEvent.clear(nombre);
    await userEvent.type(nombre, 'Editado');
    window.dispatchEvent(new Event('focus')); // refetchOnWindowFocus

    await waitFor(() => expect(screen.getByLabelText('Nombre')).toHaveValue('Editado'));
  });
});
