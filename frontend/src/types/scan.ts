import type { MediaCategory } from './index';

export interface LookupResult {
  barcode: string;
  source: 'CATALOGUE' | 'OPEN_LIBRARY' | 'MUSICBRAINZ' | 'DISCOGS' | 'TMDB' | 'NOT_FOUND';
  category?: MediaCategory;
  format?: string;
  title?: string;
  subtitle?: string;
  description?: string;
  coverUrl?: string;
  releaseYear?: number;
  publisher?: string;
  externalId?: string;
  metadata?: string;
  existingItemId?: number;
  ownedInCollections: number[];
}

export interface ScanVerifyResponse {
  matches: boolean | null;
  confidence: 'HIGH' | 'MEDIUM' | 'LOW';
  reason: string;
  /** false only when every configured Ollama endpoint failed — distinct from a normal LOW-confidence result. */
  visionAvailable: boolean;
}

export interface ExtractResponse {
  category?: MediaCategory;
  format?: string;
  title?: string;
  subtitle?: string;
  description?: string;
  publisher?: string;
  releaseYear?: number;
  barcode?: string;
  confidence?: 'HIGH' | 'MEDIUM' | 'LOW';
  notes?: string;
  /** Everything else vision read off the box that no barcode source supplies — edition, disc
   * count, region, special features, credits, pressing details, etc. Same shape/keys as the
   * barcode-side sources' `metadata` (see backend VisualScanService's extract prompt and
   * frontend utils/extraMetadata.ts), so it merges and displays through the same pipeline. */
  metadata?: string;
  /** false only when every configured Ollama endpoint failed — distinct from a normal LOW-confidence result. */
  visionAvailable: boolean;
}

export type ScanPhase =
  | 'SCANNING'           // actively looking for a barcode
  | 'LOOKING_UP'         // found barcode, querying APIs
  | 'CONFIRMING'         // showing result + AI verifying in background
  | 'CONFIRMED'          // AI said yes (or user confirmed)
  | 'NO_MATCH'           // barcode found but unknown item
  | 'GUIDED_CAPTURE'     // no barcode — user capturing detail shots
  | 'EXTRACTING'         // sending captures to AI
  | 'REVIEW_DRAFT';      // AI extracted fields, user reviews before saving
