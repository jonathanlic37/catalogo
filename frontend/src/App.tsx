// frontend/src/App.tsx
import { Activity, LayoutGrid, LogOut, Package } from 'lucide-react';
import { useAuth } from 'react-oidc-context';
import { Navigate, Route, Routes } from 'react-router-dom';
import { DashboardLayout, type NavItem } from './components/layout/DashboardLayout';
import { ThemeToggle } from './components/ThemeToggle';
import { CatalogoList } from './pages/CatalogoList';
import { ItemForm } from './pages/ItemForm';
import { SyncEvents } from './pages/SyncEvents';

const NAV: NavItem[] = [
  { to: '/', label: 'Catálogo', icon: LayoutGrid, end: true },
  { to: '/sincronizacion', label: 'Sincronización', icon: Activity },
];

export function App() {
  const auth = useAuth();
  const username = auth.user?.profile.preferred_username ?? auth.user?.profile.email;

  return (
    <DashboardLayout
      brand={{ name: 'Catálogo', subtitle: 'Sincronizado con la fuente de verdad', icon: Package, homeLabel: 'Catálogo, ir al inicio' }}
      nav={NAV}
      actions={
        <>
          {username && <span className="user-chip" title={`Sesión: ${username}`}>{username}</span>}
          <ThemeToggle />
          <button
            className="icon-btn"
            type="button"
            aria-label="Cerrar sesión"
            title="Cerrar sesión"
            onClick={() => void auth.signoutRedirect()}
          >
            <LogOut size={18} aria-hidden="true" />
          </button>
        </>
      }
    >
      <Routes>
        <Route path="/" element={<CatalogoList />} />
        <Route path="/nuevo" element={<ItemForm />} />
        <Route path="/editar/:id" element={<ItemForm />} />
        <Route path="/sincronizacion" element={<SyncEvents />} />
        {/* Destino del redirect OIDC: una vez canjeado el código, se vuelve al catálogo. */}
        <Route path="/callback" element={<Navigate to="/" replace />} />
        <Route path="*" element={<p>Página no encontrada.</p>} />
      </Routes>
    </DashboardLayout>
  );
}
