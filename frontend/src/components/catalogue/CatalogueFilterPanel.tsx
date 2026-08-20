import { useMemo, useState } from 'react';
import { SlidersHorizontal, X } from 'lucide-react';
import { CATEGORY_LABELS, SORT_LABELS, SORT_OPTIONS } from '../../types';
import type { ItemFacets, ItemFilters, MediaCategory } from '../../types';

interface Props {
  filters: ItemFilters;
  facets: ItemFacets | null;
  onChange: (filters: ItemFilters) => void;
}

const FIELD_CLASS = 'border border-gray-300 rounded-lg px-2.5 py-1.5 text-sm focus:outline-none focus:ring-2 focus:ring-indigo-500 bg-white';

/** Category/format/year-range/genre filters + sort, composed with the existing free-text search
 * on the catalogue page. State lives in the URL (see CataloguePage's useSearchParams) so filtered
 * views are bookmarkable — the app's existing convention for page state. `facets` (GET
 * /items/facets, scoped to the chosen category) bounds the year slider and populates the format/
 * genre dropdowns so neither ever offers a value that would return zero results — format used to
 * be the full static FORMATS_BY_CATEGORY list regardless of what was actually in the catalogue. */
export function CatalogueFilterPanel({ filters, facets, onChange }: Props) {
  const formats = facets?.formats ?? [];
  const hasActiveFilters = Object.values(filters).some((v) => v !== undefined && v !== '');

  const set = <K extends keyof ItemFilters>(key: K, value: ItemFilters[K]) =>
    onChange({ ...filters, [key]: value });

  const handleCategoryChange = (value: string) => {
    const category = (value || undefined) as MediaCategory | undefined;
    // A format only valid under the old category would silently filter out everything once the
    // category changes — clear it rather than leave a stale, now-nonsensical combination.
    onChange({ ...filters, category, format: undefined });
  };

  return (
    <div className="bg-white border border-gray-200 rounded-xl p-3 mb-4">
      <div className="flex items-center gap-2 mb-2 text-gray-500 text-xs font-medium">
        <SlidersHorizontal size={13} /> Filters
        {hasActiveFilters && (
          // CataloguePage.updateParams merges whatever's passed here on top of the *existing*
          // filters (that's what lets set()/handleCategoryChange above touch just one field at a
          // time) — so an empty object here would merge onto itself and clear nothing. Every
          // field explicitly nulled out is what actually resets the URL.
          <button
            type="button"
            onClick={() => onChange({ category: undefined, format: undefined, yearFrom: undefined, yearTo: undefined, genre: undefined, sort: undefined })}
            className="ml-auto flex items-center gap-1 text-indigo-600 hover:text-indigo-800"
          >
            <X size={12} /> Clear
          </button>
        )}
      </div>
      <div className="flex flex-wrap items-center gap-3">
        <select className={FIELD_CLASS} value={filters.category ?? ''} onChange={(e) => handleCategoryChange(e.target.value)}>
          <option value="">Any category</option>
          {(Object.keys(CATEGORY_LABELS) as MediaCategory[]).map((c) => (
            <option key={c} value={c}>{CATEGORY_LABELS[c]}</option>
          ))}
        </select>

        <select
          className={FIELD_CLASS}
          value={filters.format ?? ''}
          onChange={(e) => set('format', e.target.value || undefined)}
          disabled={!facets || formats.length === 0}
        >
          <option value="">
            {facets && formats.length === 0 ? 'No formats available' : 'Any format'}
          </option>
          {formats.map((f) => <option key={f} value={f}>{f}</option>)}
        </select>

        <select
          className={`${FIELD_CLASS} w-40`}
          value={filters.genre ?? ''}
          onChange={(e) => set('genre', e.target.value || undefined)}
          disabled={!facets || facets.genres.length === 0}
        >
          <option value="">
            {facets && facets.genres.length === 0 ? 'No genres available' : 'Any genre'}
          </option>
          {(facets?.genres ?? []).map((g) => <option key={g} value={g}>{g}</option>)}
        </select>

        <YearRangeSlider
          minYear={facets?.minYear}
          maxYear={facets?.maxYear}
          yearFrom={filters.yearFrom}
          yearTo={filters.yearTo}
          onChange={(yearFrom, yearTo) => onChange({ ...filters, yearFrom, yearTo })}
        />

        <select
          className={`${FIELD_CLASS} ml-auto`}
          value={filters.sort ?? ''}
          onChange={(e) => set('sort', (e.target.value || undefined) as ItemFilters['sort'])}
        >
          <option value="">Sort: relevance</option>
          {SORT_OPTIONS.map((s) => <option key={s} value={s}>{SORT_LABELS[s]}</option>)}
        </select>
      </div>
    </div>
  );
}

interface YearRangeSliderProps {
  minYear?: number;
  maxYear?: number;
  yearFrom?: number;
  yearTo?: number;
  onChange: (yearFrom: number | undefined, yearTo: number | undefined) => void;
}

/** Two-handle range slider bounded by the catalogue's actual min/max year (from facets) — an
 * unbounded pair of number inputs let you type a range that matches nothing, this can't. Built
 * from two overlapping native `<input type="range">` tracks (a transparent thumb-only top layer
 * over a visible bottom layer) rather than a drag library, since a plain range input already
 * gives correct keyboard/touch/accessibility behavior for free. */
function YearRangeSlider({ minYear, maxYear, yearFrom, yearTo, onChange }: YearRangeSliderProps) {
  const bounded = minYear != null && maxYear != null && maxYear > minYear;
  const [lo, hi] = useMemo(() => {
    if (!bounded) return [minYear ?? 0, maxYear ?? 0];
    return [
      Math.min(Math.max(yearFrom ?? minYear!, minYear!), maxYear!),
      Math.max(Math.min(yearTo ?? maxYear!, maxYear!), minYear!),
    ];
  }, [bounded, minYear, maxYear, yearFrom, yearTo]);
  const [dragging, setDragging] = useState(false);

  if (!bounded) {
    // No spread of years to bound a slider against yet (empty catalogue, or a category with
    // no release years recorded) — nothing meaningful to render.
    return null;
  }

  const pct = (v: number) => ((v - minYear!) / (maxYear! - minYear!)) * 100;

  const commit = (nextLo: number, nextHi: number) => {
    // Only round-trip through the URL as yearFrom/yearTo when the range is narrower than the
    // full bounds — leaving both at the catalogue's actual min/max is equivalent to "no filter".
    onChange(nextLo <= minYear! ? undefined : nextLo, nextHi >= maxYear! ? undefined : nextHi);
  };

  return (
    <div className="flex items-center gap-2 text-xs text-gray-600">
      <span className="tabular-nums w-9 text-right">{lo}</span>
      <div className="relative w-36 h-5 flex items-center">
        <div className="absolute inset-x-0 h-1 rounded bg-gray-200" />
        <div
          className="absolute h-1 rounded bg-indigo-500"
          style={{ left: `${pct(lo)}%`, right: `${100 - pct(hi)}%` }}
        />
        <input
          type="range"
          min={minYear}
          max={maxYear}
          value={lo}
          onChange={(e) => commit(Math.min(Number(e.target.value), hi), hi)}
          onPointerDown={() => setDragging(true)}
          onPointerUp={() => setDragging(false)}
          className="range-thumb-only absolute inset-x-0 w-full"
          style={{ zIndex: dragging ? 3 : 2 }}
        />
        <input
          type="range"
          min={minYear}
          max={maxYear}
          value={hi}
          onChange={(e) => commit(lo, Math.max(Number(e.target.value), lo))}
          onPointerDown={() => setDragging(true)}
          onPointerUp={() => setDragging(false)}
          className="range-thumb-only absolute inset-x-0 w-full"
          style={{ zIndex: 2 }}
        />
      </div>
      <span className="tabular-nums w-9">{hi}</span>
    </div>
  );
}
