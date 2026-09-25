// frontend/src/hooks/useItems.ts
import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { createItem, deleteItem, fetchItem, fetchItemsPage, fetchSummary, reconcileNow, resyncItem, retryItem, updateItem } from '../api/items';
import type { Item, ItemInput, ListParams, Page } from '../types/item';

const POLL_MS = 1500;

// Claves jerárquicas: invalidar ['items'] refresca listas, resumen y detalle a la vez.
const keys = {
  all: ['items'] as const,
  list: (params: ListParams) => ['items', 'list', params] as const,
  lists: ['items', 'list'] as const,
  summary: ['items', 'summary'] as const,
  detail: (id: string | undefined) => ['items', 'detail', id] as const,
};

/**
 * Página del catálogo. Mientras algún ítem visible esté PENDING (cambio aún no confirmado por el
 * Producer) se consulta cada 1,5 s; cuando todos quedan confirmados el polling se detiene solo.
 */
export function useItemsPage(params: ListParams) {
  return useQuery({
    queryKey: keys.list(params),
    queryFn: () => fetchItemsPage(params),
    placeholderData: keepPreviousData,
    refetchInterval: (query) =>
      query.state.data?.content.some((i) => i.syncStatus === 'PENDING') ? POLL_MS : false,
  });
}

/** Contadores globales; también hace polling mientras exista algún cambio pendiente en cualquier página. */
export function useSummary() {
  return useQuery({
    queryKey: keys.summary,
    queryFn: fetchSummary,
    refetchInterval: (query) => ((query.state.data?.pendientes ?? 0) > 0 ? POLL_MS : false),
  });
}

export function useItem(id: string | undefined) {
  return useQuery({ queryKey: keys.detail(id), queryFn: () => fetchItem(id!), enabled: !!id });
}

/** Refleja al instante en las cachés el ítem devuelto por el backend (ya marcado PENDING). */
function useApplyToCache() {
  const qc = useQueryClient();
  return (item: Item) => {
    qc.setQueriesData<Page<Item>>({ queryKey: keys.lists }, (old) =>
      old ? { ...old, content: old.content.map((i) => (i.id === item.id ? item : i)) } : old,
    );
    qc.setQueryData(keys.detail(item.id), item);
  };
}

export function useSaveItem(id?: string) {
  const qc = useQueryClient();
  const apply = useApplyToCache();
  return useMutation({
    mutationFn: (input: ItemInput) => (id ? updateItem(id, input) : createItem(input)),
    onSuccess: (item) => {
      apply(item);
      void qc.invalidateQueries({ queryKey: keys.all });
    },
  });
}

export function useDeleteItem() {
  const qc = useQueryClient();
  const apply = useApplyToCache();
  return useMutation({
    mutationFn: deleteItem,
    onSuccess: (item) => {
      apply(item);
      void qc.invalidateQueries({ queryKey: keys.all });
    },
  });
}

export function useRetryItem() {
  const qc = useQueryClient();
  const apply = useApplyToCache();
  return useMutation({
    mutationFn: retryItem,
    onSuccess: (item) => {
      apply(item);
      void qc.invalidateQueries({ queryKey: keys.all });
    },
  });
}

export function useResyncItem() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: resyncItem,
    onSuccess: () => qc.invalidateQueries({ queryKey: keys.all }),
  });
}

/** Fuerza una reconciliación con el Producer (útil para ver al instante datos creados allí). */
export function useReconcile() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: reconcileNow,
    onSuccess: () => qc.invalidateQueries({ queryKey: keys.all }),
  });
}
