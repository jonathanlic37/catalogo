// frontend/src/components/layout/DashboardLayout.tsx
import type { LucideIcon } from 'lucide-react';
import type { ReactNode } from 'react';
import { Link, NavLink } from 'react-router-dom';

export interface NavItem {
  to: string;
  label: string;
  icon: LucideIcon;
  /** Solo activo en coincidencia exacta (p. ej. la raíz "/"). */
  end?: boolean;
}

export interface DashboardLayoutProps {
  brand: { name: string; subtitle?: string; icon: LucideIcon; homeLabel?: string };
  nav?: NavItem[];
  /** Zona derecha de la barra: usuario, tema, cerrar sesión… */
  actions?: ReactNode;
  children: ReactNode;
}

/**
 * Estructura principal «DAKA Glass»: barra superior de vidrio fija (translúcida con backdrop-filter)
 * y área de contenido sobre el resplandor de fondo (--glow-bg).
 */
export function DashboardLayout({ brand, nav = [], actions, children }: DashboardLayoutProps) {
  const BrandIcon = brand.icon;
  return (
    <div className="app-shell">
      <header className="topbar">
        <div className="topbar-inner">
          <Link to="/" className="brand" aria-label={brand.homeLabel ?? `${brand.name}, ir al inicio`}>
            <span className="brand-mark" aria-hidden="true">
              <BrandIcon size={20} />
            </span>
            <span>
              <span className="brand-name">{brand.name}</span>
              {brand.subtitle && <span className="brand-sub">{brand.subtitle}</span>}
            </span>
          </Link>

          {nav.length > 0 && (
            <nav className="topbar-nav" aria-label="Navegación principal">
              {nav.map(({ to, label, icon: Icon, end }) => (
                <NavLink key={to} to={to} end={end} className="nav-link" title={label}>
                  <Icon size={18} aria-hidden="true" /> <span className="nav-label">{label}</span>
                </NavLink>
              ))}
            </nav>
          )}

          {actions && <div className="topbar-actions">{actions}</div>}
        </div>
      </header>

      <main className="app-main">
        <div className="app-content">{children}</div>
      </main>
    </div>
  );
}
