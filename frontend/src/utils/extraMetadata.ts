/** The "extra details" JSON blob — director/cast/tracklist/etc. — assembled server-side by
 * TmdbService/DiscogsService/MusicBrainzService/OpenLibraryService and merged client-side in
 * useCaptureLoop's mergeFindings(). Shared between ItemDetailPage (viewing an approved item) and
 * ScanReviewPage (viewing/editing a still-pending draft) so the two don't drift apart. */
export interface ExtraMetadata {
  // Multi-title discs (e.g. a "Free Willy Triple Feature" DVD) — the item's own title/format
  // stay the box's own title as normal; this is purely additive structured data for the
  // individual movies/albums/etc. contained on the one physical disc. No dedicated entity/schema
  // — reuses the same JSONB metadata catch-all every other field here already does, so it's
  // automatically picked up by the existing metadata-substring catalogue search for free.
  titles?: { title: string; year?: number; genre?: string }[];
  director?: string;
  cast?: string[];
  budget?: number;
  boxOffice?: number;
  runtimeMinutes?: number;
  tagline?: string;
  rating?: number; // TMDB vote average (out of 10) — see contentRating for PG-13/R/etc.
  genres?: string[];
  authors?: string[];
  pageCount?: number;
  subjects?: string[];
  series?: string;
  artist?: string;
  tracklist?: string[];
  country?: string;
  catalogNumber?: string;
  distributor?: string;
  aiNotes?: string;
  discCount?: number;
  dualSided?: boolean;
  // Everything below is vision-only (see backend VisualScanService's extract prompt) — read off
  // the box itself, never available from any barcode source.
  edition?: string;
  language?: string;
  countryOfRelease?: string;
  limitedEdition?: string;
  contentRating?: string; // PG-13, R, ESRB T, etc. — distinct from the numeric `rating` above
  aspectRatio?: string;
  audioLanguages?: string[];
  subtitleLanguages?: string[];
  specialFeatures?: string[];
  regionCode?: string;
  speed?: string;
  vinylColor?: string;
  vinylWeight?: string;
  gatefold?: boolean;
  isbn?: string;
  printing?: string;
  illustrator?: string;
  platform?: string;
  developer?: string;
  players?: string;
}

export function parseExtraMetadata(raw: string | undefined): ExtraMetadata | null {
  if (!raw) return null;
  try {
    const parsed: unknown = JSON.parse(raw);
    return parsed && typeof parsed === 'object' ? (parsed as ExtraMetadata) : null;
  } catch {
    return null;
  }
}

const CURRENCY = new Intl.NumberFormat('en-US', { style: 'currency', currency: 'USD', maximumFractionDigits: 0 });

/** Extra-details fields, in display order — label + how to render the value as text. */
export const EXTRA_FIELDS: { key: keyof ExtraMetadata; label: string; render: (v: NonNullable<ExtraMetadata[keyof ExtraMetadata]>) => string }[] = [
  {
    key: 'titles', label: 'Also contains', render: (v) =>
      (v as { title: string; year?: number; genre?: string }[])
        .map((t) => t.year ? `${t.title} (${t.year})` : t.title)
        .join(', '),
  },
  { key: 'tagline', label: 'Tagline', render: (v) => String(v) },
  { key: 'director', label: 'Director', render: (v) => String(v) },
  { key: 'cast', label: 'Cast', render: (v) => (v as string[]).join(', ') },
  { key: 'runtimeMinutes', label: 'Runtime', render: (v) => `${v} min` },
  { key: 'rating', label: 'Rating', render: (v) => `${v} / 10` },
  { key: 'budget', label: 'Budget', render: (v) => CURRENCY.format(v as number) },
  { key: 'boxOffice', label: 'Box office', render: (v) => CURRENCY.format(v as number) },
  { key: 'genres', label: 'Genres', render: (v) => (v as string[]).join(', ') },
  { key: 'authors', label: 'Author(s)', render: (v) => (v as string[]).join(', ') },
  { key: 'pageCount', label: 'Pages', render: (v) => String(v) },
  { key: 'series', label: 'Series', render: (v) => String(v) },
  { key: 'subjects', label: 'Subjects', render: (v) => (v as string[]).join(', ') },
  { key: 'artist', label: 'Artist', render: (v) => String(v) },
  { key: 'catalogNumber', label: 'Catalog #', render: (v) => String(v) },
  { key: 'country', label: 'Pressing country', render: (v) => String(v) },
  { key: 'distributor', label: 'Distributor', render: (v) => String(v) },
  { key: 'discCount', label: 'Discs', render: (v) => String(v) },
  { key: 'dualSided', label: 'Dual-sided disc', render: (v) => (v ? 'Yes' : 'No') },
  { key: 'tracklist', label: 'Tracklist', render: (v) => (v as string[]).join(', ') },
  { key: 'edition', label: 'Edition', render: (v) => String(v) },
  { key: 'limitedEdition', label: 'Limited edition', render: (v) => String(v) },
  { key: 'contentRating', label: 'Content rating', render: (v) => String(v) },
  { key: 'aspectRatio', label: 'Aspect ratio', render: (v) => String(v) },
  { key: 'regionCode', label: 'Region', render: (v) => String(v) },
  { key: 'language', label: 'Language', render: (v) => String(v) },
  { key: 'audioLanguages', label: 'Audio languages', render: (v) => (v as string[]).join(', ') },
  { key: 'subtitleLanguages', label: 'Subtitles', render: (v) => (v as string[]).join(', ') },
  { key: 'specialFeatures', label: 'Special features', render: (v) => (v as string[]).join(', ') },
  { key: 'countryOfRelease', label: 'Country of release', render: (v) => String(v) },
  { key: 'speed', label: 'Speed', render: (v) => String(v) },
  { key: 'vinylColor', label: 'Vinyl color', render: (v) => String(v) },
  { key: 'vinylWeight', label: 'Vinyl weight', render: (v) => String(v) },
  { key: 'gatefold', label: 'Gatefold', render: (v) => (v ? 'Yes' : 'No') },
  { key: 'isbn', label: 'ISBN', render: (v) => String(v) },
  { key: 'printing', label: 'Printing', render: (v) => String(v) },
  { key: 'illustrator', label: 'Illustrator', render: (v) => String(v) },
  { key: 'platform', label: 'Platform', render: (v) => String(v) },
  { key: 'developer', label: 'Developer', render: (v) => String(v) },
  { key: 'players', label: 'Players', render: (v) => String(v) },
  { key: 'aiNotes', label: 'AI notes', render: (v) => String(v) },
];

export function extraFieldRows(raw: string | undefined) {
  const extra = parseExtraMetadata(raw);
  if (!extra) return [];
  return EXTRA_FIELDS.filter(({ key }) => {
    const v = extra[key];
    return v !== undefined && v !== null && !(Array.isArray(v) && v.length === 0) && v !== '';
  }).map((field) => ({ ...field, value: extra[field.key] as NonNullable<ExtraMetadata[keyof ExtraMetadata]> }));
}
