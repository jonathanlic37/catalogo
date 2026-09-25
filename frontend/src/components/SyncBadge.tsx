// frontend/src/components/SyncBadge.tsx
import { AlertTriangle, CheckCircle2 } from 'lucide-react';
import type { Item, PendingOperation } from '../types/item';

const OPERATION_LABEL: Record<PendingOperation, string> = {
  CREATED: 'creación',
  UPDATED: 'edición',
  DELETED: 'eliminación',
};

export function SyncBadge({ item }: { item: Item }) {
  if (item.syncStatus === 'PENDING') {
    const op = item.pendingOperation ? ` · ${OPERATION_LABEL[item.pendingOperation]}` : '';
    return (
      <span className="badge badge-pending" role="status">
        <span className="spinner" aria-hidden="true" /> Pendiente de confirmación{op}
      </span>
    );
  }
  if (item.syncStatus === 'FAILED') {
    return (
      <span className="badge badge-failed" title={item.syncError}>
        <AlertTriangle size={14} aria-hidden="true" /> Error de sincronización
      </span>
    );
  }
  return (
    <span className="badge badge-ok">
      <CheckCircle2 size={14} aria-hidden="true" /> Sincronizado
    </span>
  );
}
