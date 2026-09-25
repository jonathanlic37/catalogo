// frontend/src/App.tsx
import { Package } from 'lucide-react';
import { Link, Route, Routes } from 'react-router-dom';
import { ThemeToggle } from './components/ThemeToggle';
import { CatalogoList } from './pages/CatalogoList';
import { ItemForm } from './pages/ItemForm';

export function App() {
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
          <ThemeToggle />
        </div>
      </header>
      <main>
        <Routes>
          <Route path="/" element={<CatalogoList />} />
          <Route path="/nuevo" element={<ItemForm />} />
          <Route path="/editar/:id" element={<ItemForm />} />
          <Route path="*" element={<p>Página no encontrada.</p>} />
        </Routes>
      </main>
    </>
  );
}
