// frontend/src/auth/oidc.ts
import type { AuthProviderProps } from 'react-oidc-context';
import { WebStorageStateStore } from 'oidc-client-ts';

// Configuración pública (no secreta): autoridad, client público y redirect. El token se obtiene
// en runtime mediante Authorization Code + PKCE; no hay ningún secreto en el bundle.
const authority = (import.meta.env.VITE_OIDC_AUTHORITY as string | undefined) ?? '';
const clientId = (import.meta.env.VITE_OIDC_CLIENT_ID as string | undefined) ?? 'catalogo-ui';
const redirectUri =
  (import.meta.env.VITE_OIDC_REDIRECT_URI as string | undefined) ?? `${window.location.origin}/callback`;

export const oidcConfig: AuthProviderProps = {
  authority,
  client_id: clientId,
  redirect_uri: redirectUri,
  post_logout_redirect_uri: window.location.origin,
  scope: 'openid profile email',
  // El usuario se guarda en sessionStorage: se descarta al cerrar la pestaña.
  userStore: new WebStorageStateStore({ store: window.sessionStorage }),
  // Tras el canje del código se limpia `?code=&state=` de la URL; la ruta /callback redirige a "/".
  onSigninCallback: () => {
    window.history.replaceState({}, document.title, window.location.pathname);
  },
};
