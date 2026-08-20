export type MediaCategory = 'PRINT' | 'AUDIO' | 'VIDEO' | 'GAME' | 'OTHER';

export const CATEGORY_LABELS: Record<MediaCategory, string> = {
  PRINT: 'Print',
  AUDIO: 'Audio',
  VIDEO: 'Video',
  GAME: 'Game',
  OTHER: 'Other',
};

export const FORMATS_BY_CATEGORY: Record<MediaCategory, string[]> = {
  PRINT: ['Book', 'Magazine', 'Newspaper', 'Comic', 'Manga', 'Manual', 'Zine'],
  AUDIO: ['CD', 'Vinyl LP', 'Vinyl Single', 'Cassette Tape', '8-Track', 'MiniDisc', 'Reel-to-Reel', 'DAT'],
  VIDEO: ['DVD', 'Blu-ray', '4K UHD', 'VHS', 'LaserDisc', 'HD-DVD', 'UMD', 'Betamax', '8mm Film'],
  GAME: ['Game Cartridge', 'Game Disc', 'Game Cassette', 'Floppy Disk', 'Game Card'],
  OTHER: ['Other'],
};

export const CONDITIONS = ['MINT', 'NEAR_MINT', 'VERY_GOOD', 'GOOD', 'FAIR', 'POOR', 'UNKNOWN'] as const;
export type Condition = typeof CONDITIONS[number];

/** Catalogue filter+sort builder — see ItemSearchCriteria on the backend. All optional; `q` is
 * handled separately (unchanged free-text search param). */
export interface ItemFilters {
  category?: MediaCategory;
  format?: string;
  yearFrom?: number;
  yearTo?: number;
  genre?: string;
  sort?: SortOption;
}

export const SORT_OPTIONS = ['TITLE_ASC', 'TITLE_DESC', 'YEAR_NEWEST', 'YEAR_OLDEST', 'RECENTLY_ADDED', 'REVENUE_HIGHEST'] as const;
export type SortOption = typeof SORT_OPTIONS[number];
export const SORT_LABELS: Record<SortOption, string> = {
  TITLE_ASC: 'Title (A–Z)',
  TITLE_DESC: 'Title (Z–A)',
  YEAR_NEWEST: 'Year (newest first)',
  YEAR_OLDEST: 'Year (oldest first)',
  RECENTLY_ADDED: 'Recently added',
  // Box office revenue — only ever populated on TMDB-resolved movies (see TmdbService), so this
  // only ever sorts that subset meaningfully; everything else (no data) falls to the bottom.
  REVENUE_HIGHEST: 'Box office (highest)',
};

/** Year range + genres actually present in the catalogue (optionally scoped to a category) —
 * powers the filter builder's year slider bounds and genre dropdown so neither ever offers a
 * value that would return zero results. See GET /items/facets. */
export interface ItemFacets {
  minYear?: number;
  maxYear?: number;
  formats: string[];
  genres: string[];
}

export interface User {
  userId: number;
  email: string;
  displayName?: string | null;
}

export interface AuthResponse {
  userId: number;
  email: string;
  displayName?: string | null;
  accessToken: string;
  refreshToken: string;
}

export interface DuplicateHint {
  id: number;
  title: string;
  format: string;
  releaseYear?: number;
  publisher?: string;
}

export interface ItemPhoto {
  id: number;
  url: string;
  angle: string;
}

/** Round-trip shape for GET /collections/{id}/export/json and POST /collections/{id}/import/json
 * — see backend CollectionExport/CollectionExportService. Photos are embedded as base64, not
 * storage-key references, so an exported file is self-contained. */
export interface CollectionExportPhoto {
  angle: string;
  sortOrder?: number;
  contentType?: string;
  base64Data: string;
}

export interface CollectionExportItem {
  barcode?: string;
  barcodeType?: string;
  category?: MediaCategory;
  format?: string;
  title: string;
  subtitle?: string;
  description?: string;
  coverUrl?: string;
  releaseYear?: number;
  publisher?: string;
  externalId?: string;
  externalSource?: string;
  metadata?: string;
  photos?: CollectionExportPhoto[];
}

export interface CollectionExportEntry {
  condition?: string;
  notes?: string;
  acquisitionDate?: string;
  purchasePrice?: number;
  location?: string;
  item: CollectionExportItem;
}

export interface CollectionExport {
  collectionName?: string;
  description?: string;
  primaryCategory?: string;
  exportedAt?: string;
  entries: CollectionExportEntry[];
}

export interface Item {
  id: number;
  barcode?: string;
  barcodeType?: string;
  category: MediaCategory;
  format: string;
  title: string;
  subtitle?: string;
  description?: string;
  coverUrl?: string;
  releaseYear?: number;
  publisher?: string;
  externalId?: string;
  externalSource?: string;
  metadata?: string;
  createdAt: string;
  updatedAt: string;
  duplicates?: DuplicateHint[];
  // Only populated on GET /items/{id} and after create/update — omitted from search-result rows
  // to avoid an extra query per row (see ItemService.toResponse's includePhotos flag).
  photos?: ItemPhoto[];
}

export interface Collection {
  id: number;
  name: string;
  description?: string;
  primaryCategory?: MediaCategory;
  itemCount: number;
  createdAt: string;
  updatedAt: string;
}

export interface CollectionEntry {
  id: number;
  collectionId: number;
  item: Item;
  condition: string;
  notes?: string;
  acquisitionDate?: string;
  purchasePrice?: number;
  location?: string;
  createdAt: string;
  updatedAt: string;
}

export interface Page<T> {
  content: T[];
  totalElements: number;
  totalPages: number;
  number: number;
  size: number;
}
