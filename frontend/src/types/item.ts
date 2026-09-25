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

export interface ReconcileResult {
  created: number;
  updated: number;
  deleted: number;
}

export interface ListParams {
  page: number;
  size: number;
  q: string;
  estado: '' | Estado;
  tipo?: '' | TipoContenido;
  sync?: '' | SyncStatus;
}

export type OutboxStatus = 'PENDING' | 'SENT' | 'FAILED';

/** Entrada del registro de eventos de sincronización (outbox del Consumer). */
export interface SyncEvent {
  id: string;
  itemId: string;
  eventType: PendingOperation;
  status: OutboxStatus;
  attempts: number;
  lastError?: string;
  occurredAt: string;
  createdAt: string;
  nextAttemptAt?: string;
  sentAt?: string;
}
