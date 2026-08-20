import { create } from 'zustand';
import { apiClient } from '../api/apiClient';
import type { ScanSession, ScanDraft, ScanDraftInput, ScanDraftPhotoInput } from '../types/scanSession';
import type { ExtractResponse } from '../types/scan';

interface ScanSessionState {
  sessions: ScanSession[];
  drafts: ScanDraft[];
  isLoading: boolean;
  error: string | null;
  fetchSessions: () => Promise<void>;
  createSession: (collectionId: number) => Promise<ScanSession>;
  closeSession: (id: number) => Promise<void>;
  reopenSession: (id: number) => Promise<void>;
  discardSession: (id: number) => Promise<void>;
  fetchDrafts: (sessionId: number) => Promise<void>;
  createDraft: (sessionId: number, data: ScanDraftInput) => Promise<ScanDraft>;
  updateDraft: (sessionId: number, draftId: number, data: Partial<ScanDraftInput>) => Promise<ScanDraft>;
  approveDraft: (sessionId: number, draftId: number) => Promise<void>;
  discardDraft: (sessionId: number, draftId: number) => Promise<void>;
  deleteDraftPhoto: (sessionId: number, draftId: number, photoId: number) => Promise<void>;
  addDraftPhotos: (sessionId: number, draftId: number, photos: ScanDraftPhotoInput[]) => Promise<ScanDraft>;
  updateDraftPhotoAngle: (sessionId: number, draftId: number, photoId: number, angle: string) => Promise<void>;
  reorderDraftPhotos: (sessionId: number, draftId: number, photoIds: number[]) => Promise<void>;
  reextractDraft: (sessionId: number, draftId: number, hint?: string) => Promise<ExtractResponse>;
  mergeDrafts: (sessionId: number, primaryDraftId: number, secondaryDraftId: number) => Promise<void>;
}

export const useScanSessionStore = create<ScanSessionState>((set) => ({
  sessions: [],
  drafts: [],
  isLoading: false,
  error: null,

  fetchSessions: async () => {
    set({ isLoading: true, error: null });
    try {
      const res = await apiClient.getScanSessions();
      set({ sessions: res.data, isLoading: false });
    } catch {
      set({ error: 'Failed to load scan sessions', isLoading: false });
    }
  },

  createSession: async (collectionId) => {
    const res = await apiClient.createScanSession(collectionId);
    set((s) => ({ sessions: [res.data, ...s.sessions] }));
    return res.data;
  },

  closeSession: async (id) => {
    const res = await apiClient.updateScanSessionStatus(id, 'CLOSED');
    set((s) => ({ sessions: s.sessions.map((sess) => (sess.id === id ? res.data : sess)) }));
  },

  reopenSession: async (id) => {
    const res = await apiClient.updateScanSessionStatus(id, 'OPEN');
    set((s) => ({ sessions: s.sessions.map((sess) => (sess.id === id ? res.data : sess)) }));
  },

  discardSession: async (id) => {
    await apiClient.discardScanSession(id);
    set((s) => ({ sessions: s.sessions.filter((sess) => sess.id !== id) }));
  },

  fetchDrafts: async (sessionId) => {
    set({ isLoading: true, error: null });
    try {
      const res = await apiClient.getScanDrafts(sessionId);
      set({ drafts: res.data, isLoading: false });
    } catch {
      set({ error: 'Failed to load drafts', isLoading: false });
    }
  },

  createDraft: async (sessionId, data) => {
    const res = await apiClient.createScanDraft(sessionId, data);
    set((s) => ({ drafts: [...s.drafts, res.data] }));
    return res.data;
  },

  updateDraft: async (sessionId, draftId, data) => {
    const res = await apiClient.updateScanDraft(sessionId, draftId, data);
    set((s) => ({ drafts: s.drafts.map((d) => (d.id === draftId ? res.data : d)) }));
    return res.data;
  },

  approveDraft: async (sessionId, draftId) => {
    await apiClient.approveScanDraft(sessionId, draftId);
    set((s) => ({ drafts: s.drafts.filter((d) => d.id !== draftId) }));
  },

  discardDraft: async (sessionId, draftId) => {
    await apiClient.discardScanDraft(sessionId, draftId);
    set((s) => ({ drafts: s.drafts.filter((d) => d.id !== draftId) }));
  },

  deleteDraftPhoto: async (sessionId, draftId, photoId) => {
    const res = await apiClient.deleteScanDraftPhoto(sessionId, draftId, photoId);
    set((s) => ({ drafts: s.drafts.map((d) => (d.id === draftId ? res.data : d)) }));
  },

  addDraftPhotos: async (sessionId, draftId, photos) => {
    const res = await apiClient.addScanDraftPhotos(sessionId, draftId, photos);
    set((s) => ({ drafts: s.drafts.map((d) => (d.id === draftId ? res.data : d)) }));
    return res.data;
  },

  updateDraftPhotoAngle: async (sessionId, draftId, photoId, angle) => {
    const res = await apiClient.updateScanDraftPhotoAngle(sessionId, draftId, photoId, angle);
    set((s) => ({ drafts: s.drafts.map((d) => (d.id === draftId ? res.data : d)) }));
  },

  reorderDraftPhotos: async (sessionId, draftId, photoIds) => {
    const res = await apiClient.reorderScanDraftPhotos(sessionId, draftId, photoIds);
    set((s) => ({ drafts: s.drafts.map((d) => (d.id === draftId ? res.data : d)) }));
  },

  reextractDraft: async (sessionId, draftId, hint) => {
    const res = await apiClient.reextractScanDraft(sessionId, draftId, hint);
    return res.data;
  },

  mergeDrafts: async (sessionId, primaryDraftId, secondaryDraftId) => {
    const res = await apiClient.mergeScanDrafts(sessionId, primaryDraftId, secondaryDraftId);
    set((s) => ({
      drafts: s.drafts
        .filter((d) => d.id !== secondaryDraftId)
        .map((d) => (d.id === primaryDraftId ? res.data : d)),
    }));
  },
}));
