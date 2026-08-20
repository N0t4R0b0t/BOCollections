import type { MediaCategory } from './index';

export type MatchKind = 'CONFIDENT' | 'VARIANT_MISMATCH' | 'UNMATCHED' | 'MANUAL' | 'ALREADY_OWNED';
export type DraftStatus = 'PENDING' | 'SKIPPED' | 'APPROVED';
export type SessionStatus = 'OPEN' | 'CLOSED';
export type PhotoAngle = 'REFERENCE' | 'FRONT' | 'BACK' | 'SPINE' | 'DISC';
// Every angle a user can pick from a dropdown — REFERENCE is capture-flow-assigned only (a
// fetched stock cover image), never something a user manually tags a photo as.
export const TAGGABLE_ANGLES: PhotoAngle[] = ['FRONT', 'BACK', 'SPINE', 'DISC'];
export const ANGLE_LABEL: Record<PhotoAngle, string> = { FRONT: 'Front', BACK: 'Back', SPINE: 'Spine', DISC: 'Disc', REFERENCE: 'Cover art' };

export interface ScanSession {
  id: number;
  collectionId: number;
  collectionName?: string;
  status: SessionStatus;
  pendingDraftCount: number;
  createdAt: string;
  updatedAt: string;
}

export interface ScanDraftPhoto {
  id: number;
  url: string;
  angle: PhotoAngle;
}

/** Another item already in the catalogue with the same (normalized) title as this draft — e.g.
 * you're scanning a Blu-ray of a movie you already own on DVD. Purely informational. */
export interface RelatedEdition {
  itemId: number;
  title?: string;
  format?: string;
  releaseYear?: number;
}

export interface ScanDraft {
  id: number;
  sessionId: number;
  status: DraftStatus;
  matchKind: MatchKind;
  existingItemId?: number;
  duplicateOfDraftId?: number;
  barcode?: string;
  category?: MediaCategory;
  format?: string;
  title?: string;
  subtitle?: string;
  description?: string;
  coverUrl?: string;
  releaseYear?: number;
  publisher?: string;
  metadata?: string;
  confidence?: 'HIGH' | 'MEDIUM' | 'LOW';
  /** The barcode lookup provider that resolved this draft (TMDB, OPEN_LIBRARY, MUSICBRAINZ,
   * DISCOGS) — undefined when no barcode lookup contributed (vision-only or manual entry). */
  externalSource?: string;
  photos: ScanDraftPhoto[];
  relatedEditions?: RelatedEdition[];
  createdAt: string;
  updatedAt: string;
}

export interface ScanDraftPhotoInput {
  imageBase64: string;
  imageMimeType: string;
  angle: PhotoAngle;
}

export interface ScanDraftInput {
  matchKind: MatchKind;
  existingItemId?: number;
  barcode?: string;
  category?: MediaCategory;
  format?: string;
  title?: string;
  subtitle?: string;
  description?: string;
  coverUrl?: string;
  releaseYear?: number;
  publisher?: string;
  metadata?: string;
  confidence?: 'HIGH' | 'MEDIUM' | 'LOW';
  externalSource?: string;
  photos?: ScanDraftPhotoInput[];
}
