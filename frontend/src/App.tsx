// frontend/src/App.tsx
import { Activity, LogOut, Package } from 'lucide-react';
import { useAuth } from 'react-oidc-context';
import { Link, Navigate, NavLink, Route, Routes } from 'react-router-dom';
import { ThemeToggle } from './components/ThemeToggle';
import { CatalogoList } from './pages/CatalogoList';
import { ItemForm } from './pages/ItemForm';
import { SyncEvents } from './pages/SyncEvents';

export function App() {
  const auth = useAuth();
  const username = auth.user?.profile.preferred_username ?? auth.user?.profile.email;

  return (
    <>
      <header className="topbar">
        <div className="topbar-inner">
          <Link to="/" className="brand" aria-label="Catálogo, ir al inicio">
            <span className="brand-mark" aria-hidden="true">
              <Package size={20} />
            </span>
            <span>
              <span className="brand-name">Catálogo</span>
              <span className="brand-sub">Sincronizado con la fuente de verdad</span>
            </span>
          </Link>
          <div className="topbar-actions">
            <NavLink to="/sincronizacion" className="nav-link" title="Registro de eventos de sincronización">
              <Activity size={18} aria-hidden="true" /> <span className="nav-label">Sincronización</span>
            </NavLink>
            {username && <span className="user-chip" title={`Sesión: ${username}`}>{username}</span>}
            <button
              className="icon-btn"
              type="button"
              aria-label="Cerrar sesión"
              title="Cerrar sesión"
              onClick={() => void auth.signoutRedirect()}
            >
              <LogOut size={18} aria-hidden="true" />
            </button>
            <ThemeToggle />
          </div>
        </div>
      </header>
      <main>
        <Routes>
          <Route path="/" element={<CatalogoList />} />
          <Route path="/nuevo" element={<ItemForm />} />
          <Route path="/editar/:id" element={<ItemForm />} />
          <Route path="/sincronizacion" element={<SyncEvents />} />
          {/* Destino del redirect OIDC: una vez canjeado el código, se vuelve al catálogo. */}
          <Route path="/callback" element={<Navigate to="/" replace />} />
          <Route path="*" element={<p>Página no encontrada.</p>} />
        </Routes>
      </main>
    </>
  );
}
