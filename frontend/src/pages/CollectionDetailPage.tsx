import { useEffect, useRef, useState } from 'react';
import { useParams, Link } from 'react-router-dom';
import { ArrowLeft, Plus, Search, Trash2, AlertTriangle, X, Download, Upload, ChevronDown } from 'lucide-react';
import { apiClient } from '../api/apiClient';
import { useCollectionStore } from '../store/collectionStore';
import { AppLayout } from '../components/layout/AppLayout';
import { CategoryBadge, ConditionBadge } from '../components/ui/Badge';
import { Spinner } from '../components/ui/Spinner';
import { mediaUrl } from '../utils/mediaUrl';
import { apiError } from '../utils/apiError';
import type { AxiosResponse } from 'axios';
import type { Collection, CollectionEntry, CollectionExport, Item, Page } from '../types';

/** Pulls the filename the backend picked (Content-Disposition) rather than inventing our own —
 * falls back to a sensible default if it's ever missing. */
function filenameFromResponse(res: AxiosResponse<Blob>, fallback: string): string {
  const disposition = res.headers['content-disposition'] as string | undefined;
  const match = disposition?.match(/filename\*?=(?:UTF-8'')?"?([^";]+)"?/i);
  return match ? decodeURIComponent(match[1]) : fallback;
}

function downloadBlob(blob: Blob, filename: string) {
  const url = URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = url;
  a.download = filename;
  document.body.appendChild(a);
  a.click();
  a.remove();
  URL.revokeObjectURL(url);
}

export function CollectionDetailPage() {
  const { id } = useParams<{ id: string }>();
  const collectionId = Number(id);
  const { addEntry, removeEntry } = useCollectionStore();

  const [collection, setCollection] = useState<Collection | null>(null);
  const [collectionLoading, setCollectionLoading] = useState(true);
  const [entriesPage, setEntriesPage] = useState<Page<CollectionEntry> | null>(null);
  const [pageNum, setPageNum] = useState(0);
  const [reloadKey, setReloadKey] = useState(0);

  // Filters the items already in this collection — distinct from the "Add item" panel below,
  // which searches the whole catalogue to find something to add.
  const [q, setQ] = useState('');
  const [inputQ, setInputQ] = useState('');

  const [addQ, setAddQ] = useState('');
  const [addResults, setAddResults] = useState<Item[]>([]);
  const [adding, setAdding] = useState(false);
  const [showAdd, setShowAdd] = useState(false);

  const [showExportMenu, setShowExportMenu] = useState(false);
  const [exporting, setExporting] = useState(false);
  const [importing, setImporting] = useState(false);
  const [importMsg, setImportMsg] = useState<string | null>(null);
  const importInputRef = useRef<HTMLInputElement>(null);

  useEffect(() => {
    let cancelled = false;
    apiClient.getCollection(collectionId)
      .then((r) => { if (!cancelled) setCollection(r.data); })
      .finally(() => { if (!cancelled) setCollectionLoading(false); });
    return () => { cancelled = true; };
  }, [collectionId]);

  useEffect(() => {
    let cancelled = false;
    // Deliberately doesn't clear entriesPage/show a blocking spinner on every filter/page
    // change — only the very first load (entriesPage still null) does that; subsequent fetches
    // just swap the list in-place once they resolve, so filtering doesn't flash the page blank.
    apiClient.getEntries(collectionId, pageNum, 20, q)
      .then((res) => { if (!cancelled) setEntriesPage(res.data); });
    return () => { cancelled = true; };
  }, [collectionId, pageNum, q, reloadKey]);

  const handleFilter = (e: React.FormEvent) => {
    e.preventDefault();
    setPageNum(0);
    setQ(inputQ);
  };

  const handleAddSearch = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!addQ.trim()) return;
    setAdding(true);
    try {
      const res = await apiClient.searchItems(addQ);
      setAddResults(res.data.content);
    } finally { setAdding(false); }
  };

  const handleAdd = async (item: Item) => {
    await addEntry(collectionId, item.id);
    setShowAdd(false);
    setAddQ('');
    setAddResults([]);
    setPageNum(0);
    setReloadKey((k) => k + 1);
  };

  const handleRemove = async (entryId: number, e: React.MouseEvent) => {
    e.preventDefault();
    e.stopPropagation();
    if (!confirm('Remove from collection?')) return;
    await removeEntry(collectionId, entryId);
    setReloadKey((k) => k + 1);
  };

  const handleExport = async (format: 'excel' | 'json') => {
    setShowExportMenu(false);
    setExporting(true);
    try {
      const res = format === 'excel'
        ? await apiClient.exportCollectionExcel(collectionId)
        : await apiClient.exportCollectionJson(collectionId);
      const ext = format === 'excel' ? 'xlsx' : 'json';
      downloadBlob(res.data, filenameFromResponse(res, `collection.${ext}`));
    } catch (e) {
      setImportMsg(apiError(e, `Could not export as ${format === 'excel' ? 'Excel' : 'JSON'}`));
    } finally {
      setExporting(false);
    }
  };

  // JSON import always creates new items (no dedup against the catalogue) — it's meant for
  // restoring a backup or bringing in someone else's exported collection, not merging into an
  // existing one. Photos are embedded as base64 in the file itself, so this works even importing
  // into a totally different BOCollections instance than the one that produced the export.
  const handleImportFile = async (file: File) => {
    setImportMsg(null);
    setImporting(true);
    try {
      const text = await file.text();
      const data = JSON.parse(text) as CollectionExport;
      if (!Array.isArray(data.entries)) {
        setImportMsg('Not a valid collection export file (missing "entries").');
        return;
      }
      const res = await apiClient.importCollectionJson(collectionId, data);
      setImportMsg(`Imported ${res.data.imported} item${res.data.imported !== 1 ? 's' : ''}.`);
      setPageNum(0);
      setReloadKey((k) => k + 1);
    } catch (e) {
      setImportMsg(e instanceof SyntaxError ? 'That file is not valid JSON.' : apiError(e, 'Import failed'));
    } finally {
      setImporting(false);
      if (importInputRef.current) importInputRef.current.value = '';
    }
  };

  if (!collection) {
    return collectionLoading
      ? <AppLayout><div className="flex justify-center py-24"><Spinner size="lg" /></div></AppLayout>
      : <AppLayout><div className="p-6 text-gray-500">Collection not found.</div></AppLayout>;
  }

  const entries = entriesPage?.content ?? [];

  return (
    <AppLayout>
      <div className="p-4 sm:p-6 max-w-5xl mx-auto">
        {/* Header */}
        <div className="flex items-center gap-3 mb-1">
          <Link to="/collections" className="text-gray-400 hover:text-gray-600">
            <ArrowLeft size={18} />
          </Link>
          <div className="flex items-center gap-2">
            {collection.primaryCategory && <CategoryBadge category={collection.primaryCategory} />}
            <h1 className="text-xl font-bold text-gray-900">{collection.name}</h1>
          </div>
        </div>
        {collection.description && (
          <p className="text-gray-500 text-sm ml-8 mb-4">{collection.description}</p>
        )}
        <p className="text-xs text-gray-400 ml-8 mb-6">{collection.itemCount} item{collection.itemCount !== 1 ? 's' : ''}</p>

        {/* Filter this collection + add item */}
        <div className="flex flex-wrap gap-2 mb-6">
          <form onSubmit={handleFilter} className="flex-1 flex gap-2">
            <div className="relative flex-1">
              <Search size={16} className="absolute left-3 top-1/2 -translate-y-1/2 text-gray-400" />
              <input
                value={inputQ}
                onChange={(e) => setInputQ(e.target.value)}
                placeholder="Filter this collection…"
                className="w-full border border-gray-300 rounded-lg pl-9 pr-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-indigo-500"
              />
            </div>
            {q && (
              <button
                type="button"
                onClick={() => { setInputQ(''); setQ(''); setPageNum(0); }}
                className="px-3 py-2 rounded-lg text-sm text-gray-500 hover:bg-gray-100"
                title="Clear filter"
              >
                <X size={14} />
              </button>
            )}
          </form>
          <div className="relative shrink-0">
            <button
              onClick={() => setShowExportMenu((s) => !s)}
              disabled={exporting}
              className="flex items-center gap-1.5 border border-gray-300 text-gray-700 px-3 py-2 rounded-lg text-sm font-medium hover:bg-gray-50 disabled:opacity-50"
            >
              <Download size={15} /> {exporting ? 'Exporting…' : 'Export'} <ChevronDown size={14} />
            </button>
            {showExportMenu && (
              <>
                <div className="fixed inset-0 z-10" onClick={() => setShowExportMenu(false)} />
                <div className="absolute right-0 mt-1 w-44 bg-white border border-gray-200 rounded-lg shadow-lg z-20 py-1">
                  <button
                    onClick={() => void handleExport('excel')}
                    className="w-full text-left px-3 py-2 text-sm text-gray-700 hover:bg-gray-50"
                  >
                    Excel (.xlsx)
                  </button>
                  <button
                    onClick={() => void handleExport('json')}
                    className="w-full text-left px-3 py-2 text-sm text-gray-700 hover:bg-gray-50"
                  >
                    JSON (with photos)
                  </button>
                </div>
              </>
            )}
          </div>
          <button
            onClick={() => importInputRef.current?.click()}
            disabled={importing}
            className="flex items-center gap-1.5 border border-gray-300 text-gray-700 px-3 py-2 rounded-lg text-sm font-medium hover:bg-gray-50 disabled:opacity-50 shrink-0"
            title="Import a collection previously exported as JSON"
          >
            <Upload size={15} /> {importing ? 'Importing…' : 'Import JSON'}
          </button>
          <input
            ref={importInputRef}
            type="file"
            accept="application/json"
            className="hidden"
            onChange={(e) => { const f = e.target.files?.[0]; if (f) void handleImportFile(f); }}
          />
          <button
            onClick={() => setShowAdd((s) => !s)}
            className="flex items-center gap-2 bg-indigo-600 text-white px-4 py-2 rounded-lg text-sm font-medium hover:bg-indigo-700 shrink-0"
          >
            <Plus size={16} /> Add item
          </button>
        </div>

        {importMsg && (
          <div className="flex items-center justify-between bg-indigo-50 border border-indigo-200 rounded-lg px-3 py-2 mb-4 text-sm text-indigo-800">
            <span>{importMsg}</span>
            <button onClick={() => setImportMsg(null)} className="text-indigo-400 hover:text-indigo-600 shrink-0 ml-2">
              <X size={14} />
            </button>
          </div>
        )}

        {/* Add-to-collection panel — searches the whole catalogue, not just this collection */}
        {showAdd && (
          <div className="bg-white rounded-xl border border-gray-200 p-4 mb-6">
            <form onSubmit={handleAddSearch} className="flex gap-2 mb-3">
              <input
                value={addQ}
                onChange={(e) => setAddQ(e.target.value)}
                placeholder="Search the whole catalogue by title…"
                className="flex-1 border border-gray-300 rounded-lg px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-indigo-500"
              />
              <button type="submit" disabled={adding}
                className="flex items-center gap-2 bg-gray-900 text-white px-3 py-2 rounded-lg text-sm">
                <Search size={14} /> {adding ? '…' : 'Search'}
              </button>
            </form>
            <p className="text-xs text-gray-400 mb-2">
              Item not found? <Link to="/items/new" className="text-indigo-600 hover:underline">Add it to the catalogue first</Link>
            </p>
            {addResults.length > 0 && (
              <ul className="divide-y divide-gray-100">
                {addResults.map((item) => (
                  <li key={item.id} className="flex items-center justify-between py-2">
                    <div>
                      <p className="text-sm font-medium text-gray-900">{item.title}</p>
                      <p className="text-xs text-gray-500">{item.format} · {item.publisher}</p>
                    </div>
                    <button
                      onClick={() => handleAdd(item)}
                      className="text-sm text-indigo-600 hover:text-indigo-800 font-medium px-3 py-1 rounded hover:bg-indigo-50"
                    >
                      Add
                    </button>
                  </li>
                ))}
              </ul>
            )}
          </div>
        )}

        {!entriesPage && <div className="flex justify-center py-12"><Spinner /></div>}

        {/* Entries grid */}
        {entriesPage && entries.length === 0 && (
          <div className="text-center py-16 text-gray-400">
            <Search size={40} className="mx-auto mb-3 opacity-40" />
            <p className="font-medium">{q ? 'No items match that filter' : 'Empty collection'}</p>
            <p className="text-sm mt-1">{q ? 'Try a different search' : 'Add items using the button above'}</p>
          </div>
        )}

        {entriesPage && entries.length > 0 && (
          <div className="flex flex-col gap-2 sm:grid sm:grid-cols-2 sm:gap-3 lg:grid-cols-3">
            {entries.map((entry) => (
              <Link
                key={entry.id}
                to={`/items/${entry.item.id}`}
                className="bg-white rounded-xl border border-gray-200 hover:border-indigo-300 hover:shadow-sm transition-all group relative
                  flex flex-row gap-3 p-3 items-center
                  sm:flex-col sm:items-stretch sm:p-4"
              >
                {/* Cover — horizontal thumbnail on mobile, full-width poster on sm+ (portrait
                    aspect so movie/book covers aren't cropped top-and-bottom into a wide strip) */}
                {entry.item.coverUrl ? (
                  <img
                    src={mediaUrl(entry.item.coverUrl)}
                    alt={entry.item.title}
                    className="w-14 h-20 shrink-0 object-cover rounded-lg bg-gray-100
                               sm:w-full sm:h-auto sm:aspect-2/3 sm:mb-2"
                  />
                ) : (
                  <div className="w-14 h-20 shrink-0 rounded-lg bg-gray-100
                                  sm:w-full sm:h-auto sm:aspect-2/3 sm:mb-2" />
                )}

                {/* Text block */}
                <div className="flex-1 min-w-0">
                  <CategoryBadge category={entry.item.category} />
                  <h3 className="font-semibold text-gray-900 text-sm mt-0.5 line-clamp-2">{entry.item.title}</h3>
                  {entry.item.subtitle && (
                    <p className="text-xs text-gray-500 line-clamp-1">{entry.item.subtitle}</p>
                  )}
                  <p className="text-xs text-gray-400 mt-0.5">
                    {entry.item.format}{entry.item.releaseYear ? ` · ${entry.item.releaseYear}` : ''}
                  </p>
                  <div className="mt-1.5">
                    <ConditionBadge condition={entry.condition} />
                  </div>
                </div>

                {/* Action icons — always visible on mobile (no hover), hover-only on desktop */}
                <div className="flex flex-col items-center gap-1 shrink-0
                                sm:absolute sm:top-3 sm:right-3 sm:flex-row sm:opacity-0 sm:group-hover:opacity-100 sm:transition-opacity">
                  {entry.item.duplicates && entry.item.duplicates.length > 0 && (
                    <span className="text-amber-500" title="Other versions exist">
                      <AlertTriangle size={14} />
                    </span>
                  )}
                  <button
                    onClick={(e) => handleRemove(entry.id, e)}
                    className="p-1 rounded text-gray-300 hover:text-red-500 bg-white/80 sm:bg-transparent"
                    title="Remove from collection"
                  >
                    <Trash2 size={14} />
                  </button>
                </div>
              </Link>
            ))}
          </div>
        )}

        {/* Pagination */}
        {entriesPage && entriesPage.totalPages > 1 && (
          <div className="flex justify-center gap-2 mt-6">
            {Array.from({ length: entriesPage.totalPages }, (_, i) => (
              <button
                key={i}
                onClick={() => setPageNum(i)}
                className={`px-3 py-1 rounded text-sm ${i === entriesPage.number ? 'bg-indigo-600 text-white' : 'bg-white border text-gray-600 hover:bg-gray-50'}`}
              >
                {i + 1}
              </button>
            ))}
          </div>
        )}
      </div>
    </AppLayout>
  );
}
