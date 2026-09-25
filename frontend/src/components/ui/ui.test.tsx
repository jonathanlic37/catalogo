// frontend/src/components/ui/ui.test.tsx
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { Star } from 'lucide-react';
import { MemoryRouter } from 'react-router-dom';
import { describe, expect, it, vi } from 'vitest';
import { DashboardLayout } from '../layout/DashboardLayout';
import { StatCard } from './StatCard';
import { StatusPill } from './StatusPill';

describe('StatusPill', () => {
  it.each([
    ['ok', 'pill-ok'],
    ['warn', 'pill-warn'],
    ['danger', 'pill-danger'],
    ['muted', 'pill-muted'],
  ] as const)('tono %s → clase %s, siempre con icono y texto', (tone, className) => {
    const { container } = render(<StatusPill tone={tone} label="Estado" />);
    expect(screen.getByText('Estado')).toHaveClass('pill', className);
    expect(container.querySelector('svg')).toHaveAttribute('aria-hidden', 'true');
  });
});

describe('StatCard', () => {
  it('sin onClick es informativa (no es un botón)', () => {
    render(<StatCard icon={<Star />} tone="ok" label="Total" value={3} />);
    expect(screen.queryByRole('button')).not.toBeInTheDocument();
    expect(screen.getByText('3')).toBeInTheDocument();
  });

  it('con onClick es un atajo accesible con aria-pressed', async () => {
    const onClick = vi.fn();
    render(<StatCard icon={<Star />} tone="warn" label="Pendientes" value={2} onClick={onClick} active />);
    const btn = screen.getByRole('button', { name: /Pendientes/ });
    expect(btn).toHaveAttribute('aria-pressed', 'true');
    await userEvent.click(btn);
    expect(onClick).toHaveBeenCalledOnce();
  });
});

describe('DashboardLayout', () => {
  it('renderiza barra de vidrio, navegación, acciones y contenido', () => {
    render(
      <MemoryRouter>
        <DashboardLayout
          brand={{ name: 'Marca', subtitle: 'Sub', icon: Star }}
          nav={[{ to: '/', label: 'Inicio', icon: Star, end: true }]}
          actions={<button type="button">Acción</button>}
        >
          <p>Contenido</p>
        </DashboardLayout>
      </MemoryRouter>,
    );
    expect(screen.getByRole('banner')).toHaveClass('topbar');
    expect(screen.getByRole('navigation', { name: 'Navegación principal' })).toBeInTheDocument();
    expect(screen.getByRole('link', { name: /Inicio/ })).toHaveClass('active');
    expect(screen.getByRole('button', { name: 'Acción' })).toBeInTheDocument();
    expect(screen.getByRole('main')).toHaveTextContent('Contenido');
  });
});
