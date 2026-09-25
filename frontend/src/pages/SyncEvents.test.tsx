// frontend/src/pages/SyncEvents.test.tsx
import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it } from 'vitest';
import { mockFetch, renderApp } from '../test/utils';
import type { SyncEvent } from '../types/item';
import { SyncEvents } from './SyncEvents';

const evento = (over: Partial<SyncEvent> = {}): SyncEvent => ({
  id: 'e1',
  itemId: 'abcdef12-0000-0000-0000-000000000000',
  eventType: 'UPDATED',
  status: 'FAILED',
  attempts: 1,
  lastError: 'Conflicto: el ítem cambió en el Producer',
  occurredAt: '2026-01-01T00:00:00Z',
  createdAt: '2026-01-01T00:00:00Z',
  ...over,
});

const pageOf = (content: SyncEvent[]) => ({ content, page: 0, size: 25, totalElements: content.length, totalPages: 1, last: true });

describe('SyncEvents', () => {
  it('muestra por defecto el registro de eventos fallidos con su motivo', async () => {
    const fetchMock = mockFetch(() => pageOf([evento()]));
    renderApp(<SyncEvents />);

    expect(await screen.findByText('Conflicto: el ítem cambió en el Producer')).toBeInTheDocument();
    expect(screen.getByText('Fallido')).toBeInTheDocument();
    expect(screen.getByText('Edición')).toBeInTheDocument();
    expect(String(fetchMock.mock.calls[0][0])).toContain('/api/sync/events?');
    expect(String(fetchMock.mock.calls[0][0])).toContain('status=FAILED');
  });

  it('permite ver todos los eventos (sin filtro de estado)', async () => {
    const fetchMock = mockFetch(() => pageOf([evento({ id: 'e2', status: 'SENT', lastError: undefined })]));
    renderApp(<SyncEvents />);
    await screen.findByText('Fallido').catch(() => undefined);

    await userEvent.click(screen.getByLabelText('Todos'));

    await waitFor(() =>
      expect(fetchMock.mock.calls.some(([u]) => String(u).includes('/api/sync/events?') && !String(u).includes('status='))).toBe(true),
    );
    expect(await screen.findByText('Confirmado')).toBeInTheDocument();
  });

  it('indica cuando no hay eventos fallidos', async () => {
    mockFetch(() => pageOf([]));
    renderApp(<SyncEvents />);
    expect(await screen.findByText('Sin eventos fallidos')).toBeInTheDocument();
  });
});
