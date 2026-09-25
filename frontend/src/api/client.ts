// frontend/src/api/client.ts
// Único punto de contacto con el backend: la Consumer API, siempre vía VITE_API_URL (por defecto /api).
// No se envía ningún token desde el navegador; el proxy nginx añade el Authorization.
const BASE_URL = (import.meta.env.VITE_API_URL as string | undefined) ?? '/api';

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
  let response: Response;
  try {
    response = await fetch(`${BASE_URL}${path}`, {
      ...init,
      headers: { 'Content-Type': 'application/json', ...init.headers },
    });
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
