import { create } from 'zustand';
import { apiClient } from '../api/apiClient';
import type { ThriftSession, ThriftSighting } from '../types/thriftSession';

interface ThriftSessionState {
  sessions: ThriftSession[];
  // Keyed by session id — a page showing several sessions' expandable histories needs each
  // session's sightings cached independently, not one flat list that the last fetch overwrites.
  sightingsBySession: Record<number, ThriftSighting[]>;
  searchResults: ThriftSighting[];
  isLoading: boolean;
  error: string | null;
  fetchSessions: () => Promise<void>;
  createSession: (location?: string) => Promise<ThriftSession>;
  closeSession: (id: number) => Promise<void>;
  reopenSession: (id: number) => Promise<void>;
  discardSession: (id: number) => Promise<void>;
  fetchSightings: (sessionId: number) => Promise<void>;
  searchSightings: (q: string) => Promise<void>;
}

export const useThriftSessionStore = create<ThriftSessionState>((set) => ({
  sessions: [],
  sightingsBySession: {},
  searchResults: [],
  isLoading: false,
  error: null,

  fetchSessions: async () => {
    set({ isLoading: true, error: null });
    try {
      const res = await apiClient.getThriftSessions();
      set({ sessions: res.data, isLoading: false });
    } catch {
      set({ error: 'Failed to load thrift sessions', isLoading: false });
    }
  },

  createSession: async (location) => {
    const res = await apiClient.createThriftSession(location);
    set((s) => ({ sessions: [res.data, ...s.sessions] }));
    return res.data;
  },

  closeSession: async (id) => {
    const res = await apiClient.updateThriftSessionStatus(id, 'CLOSED');
    set((s) => ({ sessions: s.sessions.map((sess) => (sess.id === id ? res.data : sess)) }));
  },

  reopenSession: async (id) => {
    const res = await apiClient.updateThriftSessionStatus(id, 'OPEN');
    set((s) => ({ sessions: s.sessions.map((sess) => (sess.id === id ? res.data : sess)) }));
  },

  discardSession: async (id) => {
    await apiClient.discardThriftSession(id);
    set((s) => ({ sessions: s.sessions.filter((sess) => sess.id !== id) }));
  },

  fetchSightings: async (sessionId) => {
    set({ isLoading: true, error: null });
    try {
      const res = await apiClient.getThriftSightings(sessionId);
      set((s) => ({ sightingsBySession: { ...s.sightingsBySession, [sessionId]: res.data }, isLoading: false }));
    } catch {
      set({ error: 'Failed to load sightings', isLoading: false });
    }
  },

  searchSightings: async (q) => {
    if (!q.trim()) {
      set({ searchResults: [] });
      return;
    }
    try {
      const res = await apiClient.searchThriftSightings(q);
      set({ searchResults: res.data });
    } catch {
      set({ error: 'Search failed' });
    }
  },
}));
