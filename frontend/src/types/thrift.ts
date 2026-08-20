import type { MediaCategory } from './index';

export type OwnedStatus = 'OWNED' | 'DIFFERENT_VERSION' | 'NOT_OWNED' | 'INTERESTING';

export interface ThriftBoundingBox {
  x: number;
  y: number;
  w: number;
  h: number;
}

export interface ThriftItem {
  title: string;
  artistOrAuthor?: string;
  category?: MediaCategory;
  format?: string;
  bbox: ThriftBoundingBox;
  confidence: 'HIGH' | 'MEDIUM' | 'LOW';
  ownedStatus: OwnedStatus;
  itemId?: number;
}

export interface ThriftScanResponse {
  items: ThriftItem[];
}

/** Narrower shape shared by shelf-mode's per-bbox ThriftItem and held-item mode's single
 * HeldItemResult — exactly the fields ThriftItemCard actually reads, so the same card works for
 * both without either one carrying fields it doesn't have (a bbox for a single held item makes
 * no sense, for instance). */
export interface ThriftItemCardData {
  title: string;
  artistOrAuthor?: string;
  category?: MediaCategory;
  format?: string;
  confidence?: 'HIGH' | 'MEDIUM' | 'LOW';
  ownedStatus: OwnedStatus;
  itemId?: number;
}

export const OWNED_STATUS_COLOR: Record<OwnedStatus, string> = {
  OWNED: '#3b82f6',          // blue-500
  DIFFERENT_VERSION: '#f59e0b', // amber-500
  NOT_OWNED: '#22c55e',      // green-500
  INTERESTING: '#f97316',   // orange-500
};

export const OWNED_STATUS_LABEL: Record<OwnedStatus, string> = {
  OWNED: 'Already owned',
  DIFFERENT_VERSION: 'Different version',
  NOT_OWNED: 'Not in collection',
  INTERESTING: 'Good find — matches your taste',
};
