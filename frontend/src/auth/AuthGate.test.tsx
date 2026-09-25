// frontend/src/auth/AuthGate.test.tsx
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { mockFetch, page, summary } from '../test/utils';
import { AuthGate } from './AuthGate';

const auth = vi.hoisted(() => ({ current: {} as Record<string, unknown> }));
vi.mock('react-oidc-context', () => ({ useAuth: () => auth.current }));

function renderAt(route: string) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={client}>
      <MemoryRouter initialEntries={[route]}>
        <AuthGate />
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

describe('AuthGate', () => {
  beforeEach(() => {
    auth.current = {};
  });

  it('sin sesión muestra el acceso y no llama a la API', () => {
    const fetchMock = mockFetch(() => page([]));
    auth.current = { isLoading: false, isAuthenticated: false, user: null, signinRedirect: vi.fn() };
    renderAt('/');

    expect(screen.getByRole('button', { name: /Iniciar sesión/ })).toBeInTheDocument();
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it('tras el login (/callback) vuelve al catálogo y la PRIMERA petición ya lleva el Bearer del usuario', async () => {
    const fetchMock = mockFetch((_m, url) => (url.startsWith('/api/items/summary') ? summary() : page([])));
    auth.current = {
      isLoading: false,
      isAuthenticated: true,
      user: { access_token: 'jwt-de-prueba', profile: { preferred_username: 'demo' } },
      signoutRedirect: vi.fn(),
    };
    renderAt('/callback');

    expect(await screen.findByRole('heading', { name: 'Catálogo' })).toBeInTheDocument();
    expect(screen.queryByText('Página no encontrada.')).not.toBeInTheDocument();
    await waitFor(() => expect(fetchMock).toHaveBeenCalled());
    for (const [, init] of fetchMock.mock.calls) {
      expect((init?.headers as Record<string, string>).Authorization).toBe('Bearer jwt-de-prueba');
    }
  });
});
