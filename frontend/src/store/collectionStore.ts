import { create } from 'zustand';
import { apiClient } from '../api/apiClient';
import type { Collection, CollectionEntry, Page } from '../types';

interface CollectionState {
  collections: Collection[];
  isLoading: boolean;
  error: string | null;
  fetchCollections: () => Promise<void>;
  createCollection: (data: { name: string; description?: string; primaryCategory?: string }) => Promise<Collection>;
  deleteCollection: (id: number) => Promise<void>;
  fetchEntries: (collectionId: number, page?: number) => Promise<Page<CollectionEntry>>;
  addEntry: (collectionId: number, itemId: number, data?: Partial<CollectionEntry>) => Promise<CollectionEntry>;
  removeEntry: (collectionId: number, entryId: number) => Promise<void>;
}

export const useCollectionStore = create<CollectionState>((set) => ({
  collections: [],
  isLoading: false,
  error: null,

  fetchCollections: async () => {
    set({ isLoading: true, error: null });
    try {
      const res = await apiClient.getCollections();
      set({ collections: res.data, isLoading: false });
    } catch {
      set({ error: 'Failed to load collections', isLoading: false });
    }
  },

  createCollection: async (data) => {
    const res = await apiClient.createCollection(data);
    set((s) => ({ collections: [res.data, ...s.collections] }));
    return res.data;
  },

  deleteCollection: async (id) => {
    await apiClient.deleteCollection(id);
    set((s) => ({ collections: s.collections.filter((c) => c.id !== id) }));
  },

  fetchEntries: async (collectionId, page = 0) => {
    const res = await apiClient.getEntries(collectionId, page);
    return res.data;
  },

  addEntry: async (collectionId, itemId, data = {}) => {
    const res = await apiClient.addEntry(collectionId, { itemId, ...data });
    set((s) => ({
      collections: s.collections.map((c) =>
        c.id === collectionId ? { ...c, itemCount: c.itemCount + 1 } : c
      ),
    }));
    return res.data;
  },

  removeEntry: async (collectionId, entryId) => {
    await apiClient.removeEntry(collectionId, entryId);
    set((s) => ({
      collections: s.collections.map((c) =>
        c.id === collectionId ? { ...c, itemCount: Math.max(0, c.itemCount - 1) } : c
      ),
    }));
  },
}));
