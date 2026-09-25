// frontend/src/types/item.ts
export type Estado = 'ACTIVO' | 'INACTIVO';
export type TipoContenido = 'PRODUCTO' | 'SERVICIO' | 'CONTENIDO';
export type SyncStatus = 'PENDING' | 'CONFIRMED' | 'FAILED';
export type PendingOperation = 'CREATED' | 'UPDATED' | 'DELETED';

export interface Item {
  id: string;
  nombre: string;
  descripcion?: string;
  estado: Estado;
  tipo: TipoContenido;
  fechaCreacion: string;
  fechaActualizacion: string;
  version: number;
  syncStatus: SyncStatus;
  pendingOperation?: PendingOperation;
  syncError?: string;
}

export interface ItemInput {
  nombre: string;
  descripcion: string;
  estado: Estado;
  tipo: TipoContenido;
}

export interface Page<T> {
  content: T[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
  last: boolean;
}

export interface Summary {
  total: number;
  activos: number;
  pendientes: number;
  fallidos: number;
}

export interface ListParams {
  page: number;
  size: number;
  q: string;
  estado: '' | Estado;
}
