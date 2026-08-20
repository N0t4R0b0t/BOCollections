import { useEffect, useState } from 'react';
import { Link, useSearchParams } from 'react-router-dom';
import { Search, Plus } from 'lucide-react';
import { apiClient } from '../api/apiClient';
import { AppLayout } from '../components/layout/AppLayout';
import { CategoryBadge } from '../components/ui/Badge';
import { Spinner } from '../components/ui/Spinner';
import { CatalogueFilterPanel } from '../components/catalogue/CatalogueFilterPanel';
import { mediaUrl } from '../utils/mediaUrl';
import type { Item, ItemFacets, ItemFilters, Page, MediaCategory, SortOption } from '../types';

const FILTER_KEYS: (keyof ItemFilters)[] = ['category', 'format', 'yearFrom', 'yearTo', 'genre', 'sort'];

function filtersFromParams(params: URLSearchParams): ItemFilters {
  const yearFrom = params.get('yearFrom');
  const yearTo = params.get('yearTo');
  return {
    category: (params.get('category') as MediaCategory) || undefined,
    format: params.get('format') || undefined,
    yearFrom: yearFrom ? Number(yearFrom) : undefined,
    yearTo: yearTo ? Number(yearTo) : undefined,
    genre: params.get('genre') || undefined,
    sort: (params.get('sort') as SortOption) || undefined,
  };
}

export function CataloguePage() {
  const [searchParams, setSearchParams] = useSearchParams();
  const q = searchParams.get('q') ?? '';
  const page = Number(searchParams.get('page') ?? '0');
  const filters = filtersFromParams(searchParams);

  const [inputQ, setInputQ] = useState(q);
  const [data, setData] = useState<Page<Item> | null>(null);
  const [facets, setFacets] = useState<ItemFacets | null>(null);

  useEffect(() => {
    let cancelled = false;
    apiClient.searchItems(q, page, 20, filters)
      .then((res) => { if (!cancelled) setData(res.data); });
    return () => { cancelled = true; };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [q, page, JSON.stringify(filters)]);
  const loading = data === null;

  // Scoped to the chosen category (a book's genres are a different set than a movie's) —
  // refetched whenever the category filter changes, powers the genre dropdown and the year
  // slider's bounds so neither ever offers a value that would return zero results.
  useEffect(() => {
    let cancelled = false;
    apiClient.getItemFacets(filters.category)
      .then((res) => { if (!cancelled) setFacets(res.data); });
    return () => { cancelled = true; };
  }, [filters.category]);

  const updateParams = (next: Partial<{ q: string; page: number } & ItemFilters>) => {
    const params = new URLSearchParams(searchParams);
    const merged = { q, page: 0, ...filters, ...next };
    if (merged.q) params.set('q', merged.q); else params.delete('q');
    if (merged.page) params.set('page', String(merged.page)); else params.delete('page');
    for (const key of FILTER_KEYS) {
      const value = merged[key];
      if (value !== undefined && value !== '') params.set(key, String(value));
      else params.delete(key);
    }
    setSearchParams(params);
  };

  const handleSearch = (e: React.FormEvent) => {
    e.preventDefault();
    updateParams({ q: inputQ, page: 0 });
  };

  return (
    <AppLayout>
      <div className="p-4 sm:p-6 max-w-5xl mx-auto">
        <div className="flex items-center justify-between mb-6">
          <h1 className="text-xl font-bold text-gray-900">Catalogue</h1>
          <Link
            to="/items/new"
            className="flex items-center gap-2 bg-indigo-600 text-white px-4 py-2 rounded-lg text-sm font-medium hover:bg-indigo-700"
          >
            <Plus size={16} /> Add item
          </Link>
        </div>

        <form onSubmit={handleSearch} className="flex gap-2 mb-4">
          <input
            value={inputQ}
            onChange={(e) => setInputQ(e.target.value)}
            placeholder="Search by title, publisher, or any metadata (director, cast, distributor…)"
            className="flex-1 border border-gray-300 rounded-lg px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-indigo-500"
          />
          <button type="submit"
            className="flex items-center gap-2 bg-gray-900 text-white px-4 py-2 rounded-lg text-sm font-medium hover:bg-gray-700">
            <Search size={14} /> Search
          </button>
        </form>

        <CatalogueFilterPanel filters={filters} facets={facets} onChange={(next) => updateParams({ ...next, page: 0 })} />

        {loading && <div className="flex justify-center py-12"><Spinner /></div>}

        {!loading && data && data.content.length === 0 && (
          <div className="text-center py-16 text-gray-400">
            <Search size={40} className="mx-auto mb-3 opacity-40" />
            <p className="font-medium">No items found</p>
            <p className="text-sm mt-1">Try a different search or <Link to="/items/new" className="text-indigo-600 hover:underline">add a new item</Link></p>
          </div>
        )}

        {!loading && data && data.content.length > 0 && (
          <>
            <div className="bg-white rounded-xl border border-gray-200 divide-y divide-gray-100">
              {data.content.map((item) => (
                <Link
                  key={item.id}
                  to={`/items/${item.id}`}
                  className="flex items-center gap-4 p-4 hover:bg-gray-50 transition-colors"
                >
                  {item.coverUrl
                    ? <img src={mediaUrl(item.coverUrl)} alt={item.title} className="w-12 h-auto aspect-2/3 object-cover rounded bg-gray-100 shrink-0" />
                    : <div className="w-12 h-auto aspect-2/3 rounded bg-gray-100 shrink-0" />
                  }
                  <div className="flex-1 min-w-0">
                    <div className="flex items-center gap-2 mb-0.5">
                      <CategoryBadge category={item.category} />
                      <span className="text-xs text-gray-400">{item.format}</span>
                    </div>
                    <p className="font-medium text-gray-900 truncate">{item.title}</p>
                    {item.subtitle && <p className="text-sm text-gray-500 truncate">{item.subtitle}</p>}
                    <p className="text-xs text-gray-400 mt-0.5">
                      {[item.publisher, item.releaseYear].filter(Boolean).join(' · ')}
                    </p>
                  </div>
                  {item.duplicates && item.duplicates.length > 0 && (
                    <span className="text-xs text-amber-600 bg-amber-50 px-2 py-0.5 rounded-full">
                      {item.duplicates.length} other version{item.duplicates.length > 1 ? 's' : ''}
                    </span>
                  )}
                </Link>
              ))}
            </div>

            {data.totalPages > 1 && (
              <div className="flex justify-center gap-2 mt-4">
                {Array.from({ length: data.totalPages }, (_, i) => (
                  <button key={i} onClick={() => updateParams({ page: i })}
                    className={`px-3 py-1 rounded text-sm ${i === data.number ? 'bg-indigo-600 text-white' : 'bg-white border text-gray-600 hover:bg-gray-50'}`}>
                    {i + 1}
                  </button>
                ))}
              </div>
            )}
          </>
        )}
      </div>
    </AppLayout>
  );
}
