import axios from 'axios';
import { isNativePlatform } from '../utils/platform';
import { getServerUrl } from '../utils/serverUrl';
import type { AuthResponse, Collection, CollectionEntry, CollectionExport, Item, ItemFacets, ItemFilters, MediaCategory, Page } from '../types';
import type { LookupResult, ScanVerifyResponse, ExtractResponse } from '../types/scan';
import type { ThriftScanResponse } from '../types/thrift';
import type { ScanSession, ScanDraft, ScanDraftInput, ScanDraftPhotoInput, SessionStatus } from '../types/scanSession';
import type {
  ThriftSession, ThriftSighting, ThriftClassifyInput, ThriftClassifyResult,
  ThriftSightingPhotoInput, ThriftSightingUpdateInput,
} from '../types/thriftSession';

// Default timeout covers ordinary CRUD calls. Vision calls (verify/extract/thriftScan) override
// this per-request below — the backend can retry across multiple Ollama endpoints before giving
// up, so those need enough headroom for that whole sequence to finish rather than racing it.
const DEFAULT_TIMEOUT_MS = 30_000;
const VISION_TIMEOUT_MS = 110_000;
// A shelf photo asks the model to find and describe every item in frame (measured: a single
// real, densely-packed shelf photo — ~115 spines — took 127s end to end), not just read one
// cover, so it needs real headroom beyond the single-item VISION_TIMEOUT_MS. Confirmed live: the
// backend was still successfully streaming back a full 115-item result set well past 110s when
// the old timeout cut the connection out from under it (a real ClientAbortException server-side,
// not a slow/broken analysis).
const SHELF_VISION_TIMEOUT_MS = 180_000;

// Web is always served by the same origin as the API (nginx/Vite proxy /api → backend), so a
// relative baseURL just works. The native app shell has no such origin — its WebView loads a
// locally bundled build with nothing behind it — so on native the user-configured server URL
// (set on ConnectServerPage, persisted via utils/serverUrl) becomes the actual host.
function resolveBaseUrl(): string {
  if (isNativePlatform()) {
    const serverUrl = getServerUrl();
    return serverUrl ? `${serverUrl}/api` : '/api';
  }
  return '/api';
}

const http = axios.create({ baseURL: resolveBaseUrl(), timeout: DEFAULT_TIMEOUT_MS });

/** Called after the user sets/changes the server URL on ConnectServerPage — no app reload needed. */
export function updateApiBaseUrl(): void {
  http.defaults.baseURL = resolveBaseUrl();
}

http.interceptors.request.use((config) => {
  const token = localStorage.getItem('accessToken');
  if (token) config.headers.Authorization = `Bearer ${token}`;
  return config;
});

http.interceptors.response.use(
  (r) => r,
  async (error) => {
    if (error.response?.status === 401) {
      const refresh = localStorage.getItem('refreshToken');
      if (refresh) {
        try {
          // Bare axios, not `http` — deliberately bypasses this same interceptor chain (a failed
          // refresh must not recursively trigger another refresh attempt) and must still resolve
          // against the real configured server (resolveBaseUrl(), not a hardcoded /api): on
          // native there's no same-origin backend behind the WebView's fixed https://localhost,
          // so a request with no baseURL at all silently went nowhere real, leaving every 401
          // stuck retrying forever instead of ever reaching /login. Confirmed live on Android:
          // an hour-old expired access token left every page spinning indefinitely.
          const res = await axios.post(`${resolveBaseUrl()}/auth/refresh`, { refreshToken: refresh });
          localStorage.setItem('accessToken', res.data.accessToken);
          localStorage.setItem('refreshToken', res.data.refreshToken);
          error.config.headers.Authorization = `Bearer ${res.data.accessToken}`;
          return http(error.config);
        } catch {
          localStorage.clear();
          window.location.href = '/login';
        }
      } else {
        localStorage.clear();
        window.location.href = '/login';
      }
    }
    return Promise.reject(error);
  }
);

export const apiClient = {
  // Temporary — see backend DebugController. Fire-and-forget diagnostics for cases (like phone
  // camera behavior) where the browser console isn't easily reachable. Remove along with it.
  debugLog: (data: Record<string, unknown>) => http.post('/debug/log', data).catch(() => {}),

  // Auth
  login: (email: string, password: string) =>
    http.post<AuthResponse>('/auth/login', { email, password }),
  register: (email: string, password: string, displayName: string) =>
    http.post<AuthResponse>('/auth/register', { email, password, displayName }),
  refresh: (refreshToken: string) =>
    http.post<AuthResponse>('/auth/refresh', { refreshToken }),

  // Collections
  getCollections: () => http.get<Collection[]>('/collections'),
  getCollection: (id: number) => http.get<Collection>(`/collections/${id}`),
  createCollection: (data: { name: string; description?: string; primaryCategory?: string }) =>
    http.post<Collection>('/collections', data),
  updateCollection: (id: number, data: { name: string; description?: string; primaryCategory?: string }) =>
    http.put<Collection>(`/collections/${id}`, data),
  deleteCollection: (id: number) => http.delete(`/collections/${id}`),

  // Collection entries
  getEntries: (collectionId: number, page = 0, size = 20, q = '') =>
    http.get<Page<CollectionEntry>>(`/collections/${collectionId}/entries`, { params: { page, size, q } }),
  addEntry: (collectionId: number, data: { itemId: number; condition?: string; notes?: string; acquisitionDate?: string; purchasePrice?: number; location?: string }) =>
    http.post<CollectionEntry>(`/collections/${collectionId}/entries`, data),
  updateEntry: (collectionId: number, entryId: number, data: Partial<CollectionEntry>) =>
    http.put<CollectionEntry>(`/collections/${collectionId}/entries/${entryId}`, data),
  removeEntry: (collectionId: number, entryId: number) =>
    http.delete(`/collections/${collectionId}/entries/${entryId}`),

  // Collection export / import
  exportCollectionExcel: (collectionId: number) =>
    http.get<Blob>(`/collections/${collectionId}/export/excel`, { responseType: 'blob' }),
  exportCollectionJson: (collectionId: number) =>
    http.get<Blob>(`/collections/${collectionId}/export/json`, { responseType: 'blob' }),
  importCollectionJson: (collectionId: number, data: CollectionExport) =>
    http.post<{ imported: number }>(`/collections/${collectionId}/import/json`, data),

  // Settings
  clearScannerCache: () =>
    http.delete<{ cleared: number }>('/settings/scanner-cache'),
  tailLogs: (lines = 200) =>
    http.get<string>('/settings/logs/tail', { params: { lines }, responseType: 'text' }),
  downloadLogs: () =>
    http.get<Blob>('/settings/logs/download', { responseType: 'blob' }),

  // Items
  searchItems: (q: string, page = 0, size = 20, filters?: ItemFilters) =>
    http.get<Page<Item>>('/items', { params: { q, page, size, ...filters } }),
  getItemFacets: (category?: MediaCategory) =>
    http.get<ItemFacets>('/items/facets', { params: { category } }),
  getItem: (id: number) => http.get<Item>(`/items/${id}`),
  getItemByBarcode: (barcode: string) => http.get<Item>(`/items/barcode/${barcode}`),
  createItem: (data: Partial<Item>) => http.post<Item>('/items', data),
  updateItem: (id: number, data: Partial<Item>) => http.put<Item>(`/items/${id}`, data),
  // Unlike updateItem (a full PUT — every field not included gets nulled out server-side), this
  // only touches whatever's actually in `data`. Use this for anything that isn't a full edit-form
  // submit — e.g. picking a cover photo, applying an AI re-extraction suggestion.
  patchItem: (id: number, data: Partial<Item>) => http.patch<Item>(`/items/${id}`, data),
  deleteItem: (id: number) => http.delete(`/items/${id}`),
  addItemPhotos: (id: number, photos: ScanDraftPhotoInput[]) =>
    http.post<Item>(`/items/${id}/photos`, { photos }),
  deleteItemPhoto: (id: number, photoId: number) =>
    http.delete<Item>(`/items/${id}/photos/${photoId}`),
  updateItemPhotoAngle: (id: number, photoId: number, angle: string) =>
    http.patch<Item>(`/items/${id}/photos/${photoId}`, { angle }),
  reorderItemPhotos: (id: number, photoIds: number[]) =>
    http.patch<Item>(`/items/${id}/photos/order`, { photoIds }),
  reextractItem: (id: number, hint?: string) =>
    http.post<ExtractResponse>(`/items/${id}/reextract`, { hint }, { timeout: VISION_TIMEOUT_MS }),

  // Scanner
  lookupBarcode: (barcode: string, excludeSources?: string[], excludeExternalIds?: string[]) =>
    http.get<LookupResult>(`/scan/barcode/${encodeURIComponent(barcode)}`, {
      params: {
        ...(excludeSources?.length ? { exclude: excludeSources } : {}),
        ...(excludeExternalIds?.length ? { excludeExternalId: excludeExternalIds } : {}),
      },
    }),
  verifyScan: (imageBase64: string, lookupResult: LookupResult) =>
    http.post<ScanVerifyResponse>('/scan/verify', {
      imageBase64,
      imageMimeType: 'image/jpeg',
      lookupResult,
    }, { timeout: VISION_TIMEOUT_MS }),
  extractFromImages: (imagesBase64: string[], hint: string) =>
    http.post<ExtractResponse>('/scan/extract', {
      imagesBase64,
      imageMimeType: 'image/jpeg',
      hint,
    }, { timeout: VISION_TIMEOUT_MS }),

  // Thrift sessions
  createThriftSession: (location?: string) =>
    http.post<ThriftSession>('/thrift-sessions', { location }),
  getThriftSessions: () => http.get<ThriftSession[]>('/thrift-sessions'),
  getThriftSession: (id: number) => http.get<ThriftSession>(`/thrift-sessions/${id}`),
  updateThriftSessionStatus: (id: number, status: SessionStatus) =>
    http.patch<ThriftSession>(`/thrift-sessions/${id}`, { status }),
  discardThriftSession: (id: number) => http.delete(`/thrift-sessions/${id}`),
  getThriftSightings: (sessionId: number) => http.get<ThriftSighting[]>(`/thrift-sessions/${sessionId}/sightings`),
  searchThriftSightings: (q: string) => http.get<ThriftSighting[]>('/thrift-sessions/search', { params: { q } }),
  thriftScan: (sessionId: number, imageBase64: string, collectionIds: number[]) =>
    http.post<ThriftScanResponse>(`/thrift-sessions/${sessionId}/scan`, {
      imageBase64,
      imageMimeType: 'image/jpeg',
      collectionIds,
    }, { timeout: VISION_TIMEOUT_MS }),
  classifyThriftItem: (sessionId: number, data: ThriftClassifyInput) =>
    http.post<ThriftClassifyResult>(`/thrift-sessions/${sessionId}/classify`, data),
  // Photos are scanned sequentially server-side — timeout scales with batch size rather than
  // reusing a flat cap, which would race a multi-shot analyze pass.
  analyzeShelf: (sessionId: number, photos: { imageBase64: string; imageMimeType: string }[], collectionIds: number[]) =>
    http.post<ThriftSighting[]>(`/thrift-sessions/${sessionId}/shelf/analyze`, {
      photos, collectionIds,
    }, { timeout: SHELF_VISION_TIMEOUT_MS * photos.length }),
  addThriftSightingPhotos: (sessionId: number, sightingId: number, photos: ThriftSightingPhotoInput[]) =>
    http.post<ThriftSighting>(`/thrift-sessions/${sessionId}/sightings/${sightingId}/photos`, { photos }),
  deleteThriftSightingPhoto: (sessionId: number, sightingId: number, photoId: number) =>
    http.delete<ThriftSighting>(`/thrift-sessions/${sessionId}/sightings/${sightingId}/photos/${photoId}`),
  reextractThriftSighting: (sessionId: number, sightingId: number, hint?: string) =>
    http.post<ExtractResponse>(`/thrift-sessions/${sessionId}/sightings/${sightingId}/reextract`, { hint }, { timeout: VISION_TIMEOUT_MS }),
  updateThriftSighting: (sessionId: number, sightingId: number, data: ThriftSightingUpdateInput) =>
    http.patch<ThriftSighting>(`/thrift-sessions/${sessionId}/sightings/${sightingId}`, data),

  // Scan sessions (bulk scan mode)
  createScanSession: (collectionId: number) =>
    http.post<ScanSession>('/scan-sessions', { collectionId }),
  getScanSessions: () => http.get<ScanSession[]>('/scan-sessions'),
  getScanSession: (id: number) => http.get<ScanSession>(`/scan-sessions/${id}`),
  updateScanSessionStatus: (id: number, status: SessionStatus) =>
    http.patch<ScanSession>(`/scan-sessions/${id}`, { status }),
  discardScanSession: (id: number) => http.delete(`/scan-sessions/${id}`),

  getScanDrafts: (sessionId: number) => http.get<ScanDraft[]>(`/scan-sessions/${sessionId}/drafts`),
  createScanDraft: (sessionId: number, data: ScanDraftInput) =>
    http.post<ScanDraft>(`/scan-sessions/${sessionId}/drafts`, data),
  updateScanDraft: (sessionId: number, draftId: number, data: Partial<ScanDraftInput>) =>
    http.put<ScanDraft>(`/scan-sessions/${sessionId}/drafts/${draftId}`, data),
  approveScanDraft: (sessionId: number, draftId: number) =>
    http.post<CollectionEntry>(`/scan-sessions/${sessionId}/drafts/${draftId}/approve`),
  discardScanDraft: (sessionId: number, draftId: number) =>
    http.delete(`/scan-sessions/${sessionId}/drafts/${draftId}`),
  deleteScanDraftPhoto: (sessionId: number, draftId: number, photoId: number) =>
    http.delete<ScanDraft>(`/scan-sessions/${sessionId}/drafts/${draftId}/photos/${photoId}`),
  updateScanDraftPhotoAngle: (sessionId: number, draftId: number, photoId: number, angle: string) =>
    http.patch<ScanDraft>(`/scan-sessions/${sessionId}/drafts/${draftId}/photos/${photoId}`, { angle }),
  reorderScanDraftPhotos: (sessionId: number, draftId: number, photoIds: number[]) =>
    http.patch<ScanDraft>(`/scan-sessions/${sessionId}/drafts/${draftId}/photos/order`, { photoIds }),
  addScanDraftPhotos: (sessionId: number, draftId: number, photos: ScanDraftPhotoInput[]) =>
    http.post<ScanDraft>(`/scan-sessions/${sessionId}/drafts/${draftId}/photos`, { photos }),
  reextractScanDraft: (sessionId: number, draftId: number, hint?: string) =>
    http.post<ExtractResponse>(`/scan-sessions/${sessionId}/drafts/${draftId}/reextract`, { hint }, { timeout: VISION_TIMEOUT_MS }),
  mergeScanDrafts: (sessionId: number, primaryDraftId: number, secondaryDraftId: number) =>
    http.post<ScanDraft>(`/scan-sessions/${sessionId}/drafts/merge`, { primaryDraftId, secondaryDraftId }),
};
