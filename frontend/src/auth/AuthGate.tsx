// frontend/src/auth/AuthGate.tsx
import { AlertTriangle, LogIn } from 'lucide-react';
import { useAuth } from 'react-oidc-context';
import { setAccessToken } from '../api/client';
import { App } from '../App';

/**
 * Puerta de autenticación OIDC: mantiene el access token en memoria (para las llamadas a la API),
 * muestra el login cuando no hay sesión y delega en <App/> cuando el usuario está autenticado.
 */
export function AuthGate() {
  const auth = useAuth();

  // Se fija durante el render (no en un efecto): React ejecuta los efectos de los hijos antes que
  // los del padre, así que con un useEffect la primera petición de la lista saldría sin token (401).
  // Es idempotente y se actualiza en cada renovación silenciosa del token.
  setAccessToken(auth.user?.access_token ?? null);

  if (auth.isLoading) {
    return (
      <div className="auth-screen" aria-busy="true">
        <p>Cargando…</p>
      </div>
    );
  }

  if (auth.error) {
    return (
      <div className="auth-screen">
        <div className="alert" role="alert">
          <AlertTriangle size={18} aria-hidden="true" /> Error de autenticación: {auth.error.message}
        </div>
        <button className="btn btn-primary" type="button" onClick={() => void auth.signinRedirect()}>
          Reintentar
        </button>
      </div>
    );
  }

  if (!auth.isAuthenticated) {
    return (
      <div className="auth-screen">
        <h1>Catálogo</h1>
        <p>Inicia sesión para consultar y gestionar el catálogo.</p>
        <button className="btn btn-primary" type="button" onClick={() => void auth.signinRedirect()}>
          <LogIn size={18} aria-hidden="true" /> Iniciar sesión
        </button>
      </div>
    );
  }

  return <App />;
}
