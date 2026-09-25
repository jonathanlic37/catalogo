// frontend/src/components/SyncBadge.test.tsx
import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { item } from '../test/utils';
import { SyncBadge } from './SyncBadge';

describe('SyncBadge', () => {
  it('muestra "Pendiente de confirmación" con la operación en curso', () => {
    render(<SyncBadge item={item({ syncStatus: 'PENDING', pendingOperation: 'DELETED' })} />);
    expect(screen.getByRole('status')).toHaveTextContent('Pendiente de confirmación · eliminación');
  });

  it('muestra el error de sincronización con su detalle', () => {
    render(<SyncBadge item={item({ syncStatus: 'FAILED', syncError: 'Conflicto' })} />);
    expect(screen.getByText('Error de sincronización')).toHaveAttribute('title', 'Conflicto');
  });

  it('muestra "Sincronizado" cuando está confirmado', () => {
    render(<SyncBadge item={item()} />);
    expect(screen.getByText('Sincronizado')).toBeInTheDocument();
  });
});
