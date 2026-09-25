// frontend/src/api/items.ts
import type { Item, ItemInput, ListParams, Page, Summary } from '../types/item';
import { request } from './client';

export const fetchItemsPage = async ({ page, size, q, estado }: ListParams): Promise<Page<Item>> => {
  const params = new URLSearchParams({ page: String(page), size: String(size) });
  if (q.trim()) params.set('q', q.trim());
  if (estado) params.set('estado', estado);
  return (await request<Page<Item>>(`/items?${params}`))!;
};

export const fetchSummary = async (): Promise<Summary> => (await request<Summary>('/items/summary'))!;

export const fetchItem = async (id: string): Promise<Item> => (await request<Item>(`/items/${id}`))!;

export const createItem = async (input: ItemInput): Promise<Item> =>
  (await request<Item>('/items', { method: 'POST', body: JSON.stringify(input) }))!;

export const updateItem = async (id: string, input: ItemInput): Promise<Item> =>
  (await request<Item>(`/items/${id}`, { method: 'PUT', body: JSON.stringify(input) }))!;

export const deleteItem = async (id: string): Promise<Item> =>
  (await request<Item>(`/items/${id}`, { method: 'DELETE' }))!;

export const retryItem = async (id: string): Promise<Item> =>
  (await request<Item>(`/items/${id}/retry`, { method: 'POST' }))!;

export const resyncItem = async (id: string): Promise<Item | undefined> =>
  request<Item>(`/items/${id}/resync`, { method: 'POST' });
