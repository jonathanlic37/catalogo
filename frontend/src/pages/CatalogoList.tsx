// frontend/src/pages/CatalogoList.tsx
import {
  AlertTriangle, CheckCircle2, ChevronLeft, ChevronRight, Clock, Layers, PackageOpen, Pencil, Plus, RefreshCw,
  RotateCcw, Search, Trash2,
} from 'lucide-react';
import { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { ConfirmDialog } from '../components/ConfirmDialog';
import { SyncBadge } from '../components/SyncBadge';
import { useDebouncedValue } from '../hooks/useDebouncedValue';
import { useDeleteItem, useItemsPage, useReconcile, useResyncItem, useRetryItem, useSummary } from '../hooks/useItems';
import type { Estado, Item, TipoContenido } from '../types/item';

const dateFormat = new Intl.DateTimeFormat('es', { dateStyle: 'medium', timeStyle: 'short' });
const PAGE_SIZE = 25;

type Filter = '' | Estado;
type Pending = { kind: 'delete' | 'discard'; item: Item } | null;

const FILTERS: { value: Filter; label: string }[] = [
  { value: '', label: 'Todos' },
  { value: 'ACTIVO', label: 'Activos' },
  { value: 'INACTIVO', label: 'Inactivos' },
];

const TIPO_LABEL: Record<TipoContenido, string> = {
  PRODUCTO: 'Producto',
  SERVICIO: 'Servicio',
  CONTENIDO: 'Contenido',
};

export function CatalogoList() {
  const [search, setSearch] = useState('');
  const [estado, setEstado] = useState<Filter>('');
  const [page, setPage] = useState(0);
  const [confirm, setConfirm] = useState<Pending>(null);
  const q = useDebouncedValue(search);

  const list = useItemsPage({ page, size: PAGE_SIZE, q, estado });
  const summary = useSummary();
  const remove = useDeleteItem();
  const retry = useRetryItem();
  const resync = useResyncItem();
  const sync = useReconcile();

  const items = list.data?.content ?? [];
  const totalElements = list.data?.totalElements ?? 0;
  const totalPages = list.data?.totalPages ?? 0;
  const pending = summary.data?.pendientes ?? 0;
  const mutationError = remove.error ?? retry.error ?? resync.error;
  const hasFilters = q.trim() !== '' || estado !== '';

  // Vuelve a la primera página al cambiar la búsqueda o el filtro.
  useEffect(() => setPage(0), [q, estado]);
  // Si tras borrar la página actual queda fuera de rango, retrocede a la última existente.
  useEffect(() => {
    if (list.data && list.data.content.length === 0 && page > 0 && totalPages > 0) setPage(totalPages - 1);
  }, [list.data, page, totalPages]);

  function runConfirmed() {
    if (!confirm) return;
    if (confirm.kind === 'delete') remove.mutate(confirm.item.id);
    else resync.mutate(confirm.item.id);
    setConfirm(null);
  }

  const from = totalElements === 0 ? 0 : page * PAGE_SIZE + 1;
  const to = Math.min((page + 1) * PAGE_SIZE, totalElements);

  return (
    <section>
      <div className="page-head">
        <div>
          <h1>Catálogo</h1>
          <p>Gestiona tus ítems; cada cambio se confirma con la fuente de verdad.</p>
        </div>
        <div className="page-actions">
          <button
            className="btn"
            type="button"
            onClick={() => sync.mutate()}
            disabled={sync.isPending}
            title="Traer ahora los cambios hechos en la fuente de verdad"
          >
            <RefreshCw size={18} aria-hidden="true" /> {sync.isPending ? 'Sincronizando…' : 'Sincronizar ahora'}
          </button>
          <Link className="btn btn-primary" to="/nuevo">
            <Plus size={18} aria-hidden="true" /> Nuevo ítem
          </Link>
        </div>
      </div>

      <div className="stats" aria-label="Resumen del catálogo">
        <Stat icon={<Layers size={20} />} tone="total" value={summary.data?.total} label="Total de ítems" />
        <Stat icon={<CheckCircle2 size={20} />} tone="ok" value={summary.data?.activos} label="Activos" />
        <Stat icon={<Clock size={20} />} tone="warn" value={summary.data?.pendientes} label="Pendientes de confirmar" />
        <Stat icon={<AlertTriangle size={20} />} tone="fail" value={summary.data?.fallidos} label="Con error" />
      </div>

      {pending > 0 && (
        <div className="banner" role="status">
          <span className="spinner" aria-hidden="true" />
          {pending} cambio(s) pendiente(s) de confirmación por la fuente de verdad…
        </div>
      )}
      {mutationError && (
        <div className="alert" role="alert">
          <AlertTriangle size={18} aria-hidden="true" /> {mutationError.message}
        </div>
      )}
      {sync.isSuccess && (
        <div className="hint" role="status">
          Reconciliación completada: {sync.data.created} nuevos, {sync.data.updated} actualizados,{' '}
          {sync.data.deleted} eliminados.
        </div>
      )}
      {sync.isError && (
        <div className="alert" role="alert">
          <AlertTriangle size={18} aria-hidden="true" /> {sync.error.message}
        </div>
      )}

      <div className="filters">
        <div className="search">
          <Search size={18} aria-hidden="true" />
          <input
            type="search"
            placeholder="Buscar por nombre…"
            value={search}
            maxLength={100}
            onChange={(e) => setSearch(e.target.value)}
            aria-label="Buscar por nombre"
          />
        </div>
        <div className="segmented" role="radiogroup" aria-label="Filtrar por estado">
          {FILTERS.map((f) => (
            <label key={f.label}>
              <input type="radio" name="filtro-estado" checked={estado === f.value} onChange={() => setEstado(f.value)} />
              <span>{f.label}</span>
            </label>
          ))}
        </div>
      </div>

      <div className="card">
        {list.isLoading && (
          <div aria-busy="true" aria-label="Cargando">
            {[0, 1, 2, 3].map((n) => (
              <div key={n} className="skeleton-row" />
            ))}
          </div>
        )}

        {list.error && (
          <div className="alert" role="alert" style={{ margin: '1rem' }}>
            <AlertTriangle size={18} aria-hidden="true" /> {list.error.message}
          </div>
        )}

        {!list.isLoading && !list.error && items.length === 0 && (
          <div className="empty">
            <div className="empty-icon" aria-hidden="true">
              <PackageOpen size={30} />
            </div>
            <h2>{hasFilters ? 'Sin resultados' : 'Aún no hay ítems'}</h2>
            <p>
              {hasFilters
                ? 'Prueba con otro nombre o cambia el filtro de estado.'
                : 'Crea el primero y verás cómo se confirma en la fuente de verdad.'}
            </p>
            {!hasFilters && (
              <Link className="btn btn-primary" to="/nuevo">
                <Plus size={18} aria-hidden="true" /> Crear ítem
              </Link>
            )}
          </div>
        )}

        {items.length > 0 && (
          <table aria-busy={list.isPlaceholderData}>
            <thead>
              <tr>
                <th scope="col">Nombre</th>
                <th scope="col">Descripción</th>
                <th scope="col">Estado</th>
                <th scope="col">Tipo</th>
                <th scope="col">Actualizado</th>
                <th scope="col">Sincronización</th>
                <th scope="col">
                  <span className="sr-only">Acciones</span>
                </th>
              </tr>
            </thead>
            <tbody>
              {items.map((item) => (
                <tr key={item.id} className={item.pendingOperation === 'DELETED' ? 'row-deleting' : undefined}>
                  <td className="cell-name" data-label="Nombre">
                    {item.nombre}
                  </td>
                  <td className="cell-desc" data-label="Descripción" title={item.descripcion}>
                    {item.descripcion || '—'}
                  </td>
                  <td data-label="Estado">
                    <span className={item.estado === 'ACTIVO' ? 'pill pill-on' : 'pill pill-off'}>
                      <span className="dot" aria-hidden="true" />
                      {item.estado === 'ACTIVO' ? 'Activo' : 'Inactivo'}
                    </span>
                  </td>
                  <td data-label="Tipo">{TIPO_LABEL[item.tipo] ?? '—'}</td>
                  <td className="cell-date" data-label="Actualizado">
                    {dateFormat.format(new Date(item.fechaActualizacion))}
                  </td>
                  <td data-label="Sincronización">
                    <SyncBadge item={item} />
                    {item.syncStatus === 'FAILED' && item.syncError && <div className="err-line">{item.syncError}</div>}
                  </td>
                  <td className="cell-actions">
                    {item.syncStatus === 'CONFIRMED' && (
                      <>
                        <Link className="icon-btn" to={`/editar/${item.id}`} aria-label={`Editar ${item.nombre}`} title="Editar">
                          <Pencil size={18} aria-hidden="true" />
                        </Link>
                        <button
                          className="icon-btn danger"
                          aria-label={`Eliminar ${item.nombre}`}
                          title="Eliminar"
                          disabled={remove.isPending}
                          onClick={() => setConfirm({ kind: 'delete', item })}
                        >
                          <Trash2 size={18} aria-hidden="true" />
                        </button>
                      </>
                    )}
                    {item.syncStatus === 'FAILED' && (
                      <>
                        <button
                          className="icon-btn"
                          aria-label={`Reintentar ${item.nombre}`}
                          title="Reintentar envío"
                          disabled={retry.isPending}
                          onClick={() => retry.mutate(item.id)}
                        >
                          <RefreshCw size={18} aria-hidden="true" />
                        </button>
                        <button
                          className="icon-btn danger"
                          aria-label={`Descartar cambio de ${item.nombre}`}
                          title="Descartar cambio"
                          disabled={resync.isPending}
                          onClick={() => setConfirm({ kind: 'discard', item })}
                        >
                          <RotateCcw size={18} aria-hidden="true" />
                        </button>
                      </>
                    )}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}

        {totalElements > 0 && (
          <nav className="pagination" aria-label="Paginación">
            <span className="pagination-info" aria-live="polite">
              {from}–{to} de {totalElements}
            </span>
            <div className="pagination-actions">
              <button
                className="icon-btn"
                aria-label="Página anterior"
                disabled={page === 0}
                onClick={() => setPage((p) => Math.max(p - 1, 0))}
              >
                <ChevronLeft size={20} aria-hidden="true" />
              </button>
              <span className="pagination-page">
                {page + 1} / {Math.max(totalPages, 1)}
              </span>
              <button
                className="icon-btn"
                aria-label="Página siguiente"
                disabled={list.data?.last ?? true}
                onClick={() => setPage((p) => p + 1)}
              >
                <ChevronRight size={20} aria-hidden="true" />
              </button>
            </div>
          </nav>
        )}
      </div>

      <ConfirmDialog
        open={confirm !== null}
        danger={confirm?.kind === 'delete'}
        title={confirm?.kind === 'delete' ? 'Eliminar ítem' : 'Descartar cambio'}
        message={
          confirm?.kind === 'delete'
            ? `Se eliminará "${confirm.item.nombre}" de la fuente de verdad. Esta acción no se puede deshacer.`
            : 'Se descartará el cambio local y se recuperará la versión que tiene el servidor.'
        }
        confirmLabel={confirm?.kind === 'delete' ? 'Eliminar' : 'Descartar cambio'}
        onConfirm={runConfirmed}
        onCancel={() => setConfirm(null)}
      />
    </section>
  );
}

function Stat({ icon, tone, value, label }: { icon: React.ReactNode; tone: string; value?: number; label: string }) {
  return (
    <div className="stat">
      <div className={`stat-icon tone-${tone}`} aria-hidden="true">
        {icon}
      </div>
      <div>
        <div className="stat-value">{value ?? '–'}</div>
        <div className="stat-label">{label}</div>
      </div>
    </div>
  );
}
