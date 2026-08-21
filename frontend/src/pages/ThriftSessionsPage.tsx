import { useEffect, useState } from 'react';
import { useNavigate, Link } from 'react-router-dom';
import { Plus, ShoppingBag, Trash2, Archive, ArchiveRestore, Search, Camera } from 'lucide-react';
import { AppLayout } from '../components/layout/AppLayout';
import { Spinner } from '../components/ui/Spinner';
import { useThriftSessionStore } from '../store/thriftSessionStore';
import { apiError } from '../utils/apiError';
import { mediaUrl } from '../utils/mediaUrl';
import { OWNED_STATUS_COLOR, OWNED_STATUS_LABEL } from '../types/thrift';
import type { ThriftSighting } from '../types/thriftSession';

export function ThriftSessionsPage() {
  const navigate = useNavigate();
  const {
    sessions, sightingsBySession, searchResults, isLoading,
    fetchSessions, createSession, closeSession, reopenSession, discardSession, fetchSightings, searchSightings,
  } = useThriftSessionStore();

  const [showForm, setShowForm] = useState(false);
  const [location, setLocation] = useState('');
  const [creating, setCreating] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const [expandedId, setExpandedId] = useState<number | null>(null);

  const [query, setQuery] = useState('');
  const [hasSearched, setHasSearched] = useState(false);
  const [searching, setSearching] = useState(false);

  useEffect(() => { fetchSessions(); }, [fetchSessions]);

  const handleCreate = async (e: React.FormEvent) => {
    e.preventDefault();
    setCreating(true);
    setError(null);
    try {
      const session = await createSession(location || undefined);
      navigate(`/thrift/${session.id}`);
    } catch (err: unknown) {
      setError(apiError(err, 'Failed to start thrift session'));
    } finally {
      setCreating(false);
    }
  };

  const handleDiscard = async (id: number, e: React.MouseEvent) => {
    e.stopPropagation();
    if (confirm('Discard this thrift session? All its sightings and photos will be deleted.')) {
      await discardSession(id);
    }
  };

  const handleToggleClosed = async (id: number, status: string, e: React.MouseEvent) => {
    e.stopPropagation();
    if (status === 'OPEN') await closeSession(id);
    else await reopenSession(id);
  };

  const toggleExpand = async (id: number) => {
    if (expandedId === id) {
      setExpandedId(null);
      return;
    }
    setExpandedId(id);
    // Always refetch, not just the first time — an earlier expand (even one that found zero
    // sightings, e.g. right after the trip was created) previously cached that empty result
    // forever, so every sighting recorded afterward stayed invisible here even though the
    // session's own "N items seen" count (a fresh query every page load) kept climbing.
    await fetchSightings(id);
  };

  const handleSearch = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!query.trim()) {
      setHasSearched(false);
      return;
    }
    setSearching(true);
    try {
      await searchSightings(query);
      setHasSearched(true);
    } finally {
      setSearching(false);
    }
  };

  return (
    <AppLayout>
      <div className="p-4 sm:p-6 max-w-4xl mx-auto">
        <div className="flex items-center justify-between mb-6">
          <div className="flex items-center gap-2">
            <ShoppingBag size={20} className="text-indigo-600" />
            <h1 className="text-xl font-bold text-gray-900">Thrifting</h1>
          </div>
          <button
            onClick={() => setShowForm(true)}
            className="flex items-center gap-2 bg-indigo-600 text-white px-4 py-2 rounded-lg text-sm font-medium hover:bg-indigo-700 transition-colors"
          >
            <Plus size={16} /> New trip
          </button>
        </div>

        <form onSubmit={handleSearch} className="flex gap-2 mb-6">
          <div className="relative flex-1">
            <Search size={16} className="absolute left-3 top-1/2 -translate-y-1/2 text-gray-400" />
            <input
              value={query}
              onChange={(e) => setQuery(e.target.value)}
              placeholder="Have I seen this somewhere before?"
              className="w-full border border-gray-300 rounded-lg pl-9 pr-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-indigo-500"
            />
          </div>
          <button type="submit" disabled={searching}
            className="px-4 py-2 rounded-lg text-sm font-medium bg-gray-900 text-white hover:bg-gray-800 disabled:opacity-50">
            Search
          </button>
        </form>

        {hasSearched && (
          <div className="bg-white rounded-xl border border-gray-200 p-4 mb-6 space-y-2">
            <h2 className="text-sm font-semibold text-gray-700">
              {searchResults.length === 0 ? 'No past sightings found' : `${searchResults.length} past sighting${searchResults.length !== 1 ? 's' : ''}`}
            </h2>
            {searchResults.map((s) => <SightingRow key={s.id} sighting={s} />)}
          </div>
        )}

        {showForm && (
          <form onSubmit={handleCreate} className="bg-white rounded-xl border border-gray-200 p-5 mb-6 space-y-3">
            <h2 className="font-semibold text-gray-900">Start a thrift trip</h2>
            <input
              value={location}
              onChange={(e) => setLocation(e.target.value)}
              placeholder="Where are you? (optional, e.g. Half Price Books downtown)"
              className="w-full border border-gray-300 rounded-lg px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-indigo-500"
            />
            {error && <p className="text-sm text-red-600">{error}</p>}
            <div className="flex gap-2">
              <button type="submit" disabled={creating}
                className="bg-indigo-600 text-white px-4 py-2 rounded-lg text-sm font-medium hover:bg-indigo-700 disabled:opacity-50">
                {creating ? 'Starting…' : 'Start scanning'}
              </button>
              <button type="button" onClick={() => setShowForm(false)}
                className="px-4 py-2 rounded-lg text-sm font-medium text-gray-600 hover:bg-gray-100">
                Cancel
              </button>
            </div>
          </form>
        )}

        {isLoading && <div className="flex justify-center py-12"><Spinner /></div>}

        {!isLoading && sessions.length === 0 && (
          <div className="text-center py-16 text-gray-400">
            <ShoppingBag size={48} className="mx-auto mb-3 opacity-40" />
            <p className="font-medium">No thrift trips yet</p>
            <p className="text-sm mt-1">Start one next time you're browsing a shelf of media</p>
          </div>
        )}

        <div className="space-y-3">
          {sessions.map((s) => (
            <div key={s.id} className="bg-white rounded-xl border border-gray-200 overflow-hidden">
              <div className="p-4">
                <div className="flex items-start justify-between mb-2">
                  <div className="flex-1 min-w-0 cursor-pointer" onClick={() => toggleExpand(s.id)}>
                    <h3 className="font-semibold text-gray-900 truncate">{s.location || 'Untitled trip'}</h3>
                    <p className="text-xs text-gray-400 mt-0.5">
                      {s.status === 'OPEN' ? 'Open' : 'Closed'} · {s.sightingCount} item{s.sightingCount !== 1 ? 's' : ''} seen
                    </p>
                  </div>
                  <div className="flex items-center gap-1">
                    <button
                      onClick={(e) => handleToggleClosed(s.id, s.status, e)}
                      title={s.status === 'OPEN' ? 'Close trip' : 'Reopen trip'}
                      className="p-1.5 rounded-lg text-gray-300 hover:text-gray-600 hover:bg-gray-50"
                    >
                      {s.status === 'OPEN' ? <Archive size={14} /> : <ArchiveRestore size={14} />}
                    </button>
                    <button
                      onClick={(e) => handleDiscard(s.id, e)}
                      title="Discard trip"
                      className="p-1.5 rounded-lg text-gray-300 hover:text-red-500 hover:bg-red-50"
                    >
                      <Trash2 size={14} />
                    </button>
                  </div>
                </div>
                <div className="flex gap-2 mt-3">
                  <button
                    onClick={() => navigate(`/thrift/${s.id}`)}
                    className="flex-1 bg-gray-900 text-white rounded-lg py-2 text-sm font-medium hover:bg-gray-800 transition-colors"
                  >
                    Resume scanning
                  </button>
                  <button
                    onClick={() => toggleExpand(s.id)}
                    className="flex-1 border border-gray-200 rounded-lg py-2 text-sm font-medium text-gray-700 hover:bg-gray-50 transition-colors"
                  >
                    {expandedId === s.id ? 'Hide history' : 'View history'}
                  </button>
                </div>
              </div>

              {expandedId === s.id && (
                <div className="border-t border-gray-100 bg-gray-50 p-4 space-y-2">
                  {!sightingsBySession[s.id] ? (
                    <div className="flex justify-center py-4"><Spinner size="sm" /></div>
                  ) : sightingsBySession[s.id].length === 0 ? (
                    <p className="text-sm text-gray-400 text-center py-2">Nothing sighted yet</p>
                  ) : (
                    sightingsBySession[s.id].map((sighting) => <SightingRow key={sighting.id} sighting={sighting} />)
                  )}
                </div>
              )}
            </div>
          ))}
        </div>
      </div>
    </AppLayout>
  );
}

function SightingRow({ sighting }: { sighting: ThriftSighting }) {
  const color = OWNED_STATUS_COLOR[sighting.ownedStatus];
  return (
    <div className="flex items-center gap-3 bg-white rounded-lg border border-gray-100 px-3 py-2">
      <span className="w-2 h-2 rounded-full flex-shrink-0" style={{ backgroundColor: color }} />
      {sighting.photos.length > 0 && (
        <img src={mediaUrl(sighting.photos[0].url)} alt="" className="w-8 h-8 rounded object-cover shrink-0 bg-gray-100" />
      )}
      <div className="flex-1 min-w-0">
        <p className="text-sm font-medium text-gray-900 truncate">{sighting.title}</p>
        <p className="text-xs text-gray-400">
          {OWNED_STATUS_LABEL[sighting.ownedStatus]}
          {sighting.timesSeen > 1 ? ` · seen ${sighting.timesSeen}×` : ''}
        </p>
      </div>
      <Link
        to={`/thrift/${sighting.sessionId}/sightings/${sighting.id}/photos`}
        title="Add photos or re-run AI vision"
        className="p-1.5 rounded-lg text-gray-300 hover:text-gray-600 hover:bg-gray-50 shrink-0"
      >
        <Camera size={14} />
      </Link>
    </div>
  );
}
