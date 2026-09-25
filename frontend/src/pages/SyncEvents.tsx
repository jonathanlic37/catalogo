// frontend/src/pages/SyncEvents.tsx
import { AlertTriangle, ArrowLeft, CheckCircle2, ChevronLeft, ChevronRight, Clock, Inbox } from 'lucide-react';
import { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { useSyncEvents } from '../hooks/useItems';
import type { OutboxStatus, PendingOperation, SyncEvent } from '../types/item';

const PAGE_SIZE = 25;
const dateFormat = new Intl.DateTimeFormat('es', { dateStyle: 'short', timeStyle: 'medium' });

const FILTERS: { value: '' | OutboxStatus; label: string }[] = [
  { value: 'FAILED', label: 'Fallidos' },
  { value: 'PENDING', label: 'Pendientes' },
  { value: 'SENT', label: 'Enviados' },
  { value: '', label: 'Todos' },
];

const EVENT_LABEL: Record<PendingOperation, string> = {
  CREATED: 'Creación',
  UPDATED: 'Edición',
  DELETED: 'Eliminación',
};

/**
 * Registro de eventos de sincronización (outbox del Consumer). Por defecto muestra los fallidos con
 * su motivo: es la vista de diagnóstico de los cambios que el Producer rechazó o que agotaron los
 * reintentos. Las acciones (reintentar / descartar) están en la lista del catálogo.
 */
export function SyncEvents() {
  const [status, setStatus] = useState<'' | OutboxStatus>('FAILED');
  const [page, setPage] = useState(0);
  const events = useSyncEvents(status, page, PAGE_SIZE);
  const content = events.data?.content ?? [];

  useEffect(() => setPage(0), [status]);

  return (
    <section>
      <Link className="back" to="/">
        <ArrowLeft size={18} aria-hidden="true" /> Volver al catálogo
      </Link>
      <div className="page-head">
        <div>
          <h1>Sincronización</h1>
          <p>Registro de eventos enviados al Producer por webhook, con su resultado e intentos.</p>
        </div>
      </div>

      <div className="filters">
        <div className="segmented" role="radiogroup" aria-label="Filtrar eventos por resultado">
          {FILTERS.map((f) => (
            <label key={f.label}>
              <input type="radio" name="filtro-eventos" checked={status === f.value} onChange={() => setStatus(f.value)} />
              <span>{f.label}</span>
            </label>
          ))}
        </div>
      </div>

      <div className="card">
        {events.isLoading && (
          <div aria-busy="true" aria-label="Cargando">
            {[0, 1, 2].map((n) => (
              <div key={n} className="skeleton-row" />
            ))}
          </div>
        )}
        {events.error && (
          <div className="alert" role="alert" style={{ margin: '1rem' }}>
            <AlertTriangle size={18} aria-hidden="true" /> {events.error.message}
          </div>
        )}
        {!events.isLoading && !events.error && content.length === 0 && (
          <div className="empty">
            <div className="empty-icon" aria-hidden="true">
              <Inbox size={30} />
            </div>
            <h2>{status === 'FAILED' ? 'Sin eventos fallidos' : 'Sin eventos'}</h2>
            <p>{status === 'FAILED' ? 'Todos los cambios se han confirmado o siguen en curso.' : 'No hay eventos con este filtro.'}</p>
          </div>
        )}
        {content.length > 0 && (
          <table aria-busy={events.isPlaceholderData}>
            <thead>
              <tr>
                <th scope="col">Evento</th>
                <th scope="col">Ítem</th>
                <th scope="col">Resultado</th>
                <th scope="col">Intentos</th>
                <th scope="col">Motivo</th>
                <th scope="col">Registrado</th>
              </tr>
            </thead>
            <tbody>
              {content.map((e) => (
                <tr key={e.id}>
                  <td data-label="Evento">{EVENT_LABEL[e.eventType] ?? e.eventType}</td>
                  <td data-label="Ítem" className="mono" title={e.itemId}>
                    {e.itemId.slice(0, 8)}
                  </td>
                  <td data-label="Resultado">
                    <EventBadge event={e} />
                  </td>
                  <td data-label="Intentos">{e.attempts}</td>
                  <td data-label="Motivo" className="cell-desc" title={e.lastError}>
                    {e.lastError || '—'}
                  </td>
                  <td data-label="Registrado" className="cell-date">
                    {dateFormat.format(new Date(e.createdAt))}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
        {(events.data?.totalElements ?? 0) > 0 && (
          <nav className="pagination" aria-label="Paginación de eventos">
            <span className="pagination-info" aria-live="polite">
              {events.data!.totalElements} eventos
            </span>
            <div className="pagination-actions">
              <button className="icon-btn" aria-label="Página anterior" disabled={page === 0} onClick={() => setPage((p) => p - 1)}>
                <ChevronLeft size={20} aria-hidden="true" />
              </button>
              <span className="pagination-page">
                {page + 1} / {Math.max(events.data!.totalPages, 1)}
              </span>
              <button
                className="icon-btn"
                aria-label="Página siguiente"
                disabled={events.data?.last ?? true}
                onClick={() => setPage((p) => p + 1)}
              >
                <ChevronRight size={20} aria-hidden="true" />
              </button>
            </div>
          </nav>
        )}
      </div>
    </section>
  );
}

function EventBadge({ event }: { event: SyncEvent }) {
  if (event.status === 'SENT')
    return (
      <span className="badge badge-ok">
        <CheckCircle2 size={14} aria-hidden="true" /> Confirmado
      </span>
    );
  if (event.status === 'PENDING')
    return (
      <span className="badge badge-pending">
        <Clock size={14} aria-hidden="true" /> Pendiente
      </span>
    );
  return (
    <span className="badge badge-failed">
      <AlertTriangle size={14} aria-hidden="true" /> Fallido
    </span>
  );
}
