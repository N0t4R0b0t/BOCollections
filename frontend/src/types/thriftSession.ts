import type { MediaCategory } from './index';
import type { SessionStatus } from './scanSession';
import type { OwnedStatus } from './thrift';

export interface ThriftSession {
  id: number;
  location?: string;
  status: SessionStatus;
  sightingCount: number;
  createdAt: string;
  updatedAt: string;
}

export interface ThriftSighting {
  id: number;
  sessionId: number;
  title: string;
  category?: MediaCategory;
  format?: string;
  artistOrAuthor?: string;
  publisher?: string;
  releaseYear?: number;
  ownedStatus: OwnedStatus;
  itemId?: number;
  confidence?: 'HIGH' | 'MEDIUM' | 'LOW';
  photos: ThriftSightingPhoto[];
  sourceMode?: 'SHELF' | 'HELD_ITEM';
  timesSeen: number;
  /** Collection-relevance ranking from the most recent shelf-mode analyze pass that touched this
   * sighting — undefined for sightings never touched by one (e.g. held-item-only). */
  matchScore?: number;
  lastSeenAt: string;
  createdAt: string;
}

export interface ThriftSightingPhoto {
  id: number;
  url: string;
  /** Only set for shelf-mode detections — where in this specific photo the sighting was found
   * (normalized 0-1, origin top-left). */
  bboxX?: number;
  bboxY?: number;
  bboxW?: number;
  bboxH?: number;
}

export interface ThriftSightingPhotoInput {
  imageBase64: string;
  imageMimeType: string;
}

export interface ThriftSightingUpdateInput {
  title?: string;
  category?: MediaCategory;
  format?: string;
  artistOrAuthor?: string;
  publisher?: string;
  releaseYear?: number;
}

export interface ThriftClassifyInput {
  title: string;
  category?: MediaCategory;
  format?: string;
  publisher?: string;
  releaseYear?: number;
  confidence?: string;
  existingItemId?: number;
  ownedInCollections?: number[];
  collectionIds?: number[];
  imageBase64?: string;
  imageMimeType?: string;
}

export interface ThriftClassifyResult {
  ownedStatus: OwnedStatus;
  itemId?: number;
}
