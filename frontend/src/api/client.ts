// frontend/src/api/client.ts
// Único punto de contacto con el backend: la Consumer API, siempre vía VITE_API_URL (por defecto /api).
// El Authorization es el JWT del usuario obtenido del IdP en runtime (OIDC + PKCE); el bundle no
// contiene ningún token ni secreto.
const BASE_URL = (import.meta.env.VITE_API_URL as string | undefined) ?? '/api';

// Token del usuario (JWT del IdP), en memoria. Lo fija la capa de autenticación tras el login OIDC;
// nunca se incluye en el bundle ni se persiste en un almacén accesible a scripts de terceros.
let accessToken: string | null = null;

export function setAccessToken(token: string | null): void {
  accessToken = token;
}

export class ApiError extends Error {
  constructor(
    public readonly status: number,
    message: string,
    public readonly fieldErrors: Record<string, string> = {},
  ) {
    super(message);
  }
}

export async function request<T>(path: string, init: RequestInit = {}): Promise<T | undefined> {
  const headers: Record<string, string> = { 'Content-Type': 'application/json', ...(init.headers as Record<string, string>) };
  if (accessToken) {
    headers.Authorization = `Bearer ${accessToken}`;
  }

  let response: Response;
  try {
    response = await fetch(`${BASE_URL}${path}`, { ...init, headers });
  } catch {
    throw new ApiError(0, 'No se pudo conectar con el servidor');
  }

  if (!response.ok) {
    let detail = `Error ${response.status}`;
    let fieldErrors: Record<string, string> = {};
    try {
      const problem = await response.json();
      detail = problem.detail ?? detail;
      fieldErrors = problem.errors ?? {};
    } catch {
      /* cuerpo no JSON */
    }
    throw new ApiError(response.status, detail, fieldErrors);
  }
  if (response.status === 204) return undefined;
  return (await response.json()) as T;
}
