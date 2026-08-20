import { useEffect, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { Plus, ScanLine, Trash2, Archive, ArchiveRestore } from 'lucide-react';
import { AppLayout } from '../components/layout/AppLayout';
import { Spinner } from '../components/ui/Spinner';
import { useScanSessionStore } from '../store/scanSessionStore';
import { useCollectionStore } from '../store/collectionStore';
import { apiError } from '../utils/apiError';

export function ScanSessionsPage() {
  const navigate = useNavigate();
  const { sessions, isLoading, fetchSessions, createSession, closeSession, reopenSession, discardSession } = useScanSessionStore();
  const { collections, fetchCollections } = useCollectionStore();

  const [showForm, setShowForm] = useState(false);
  const [collectionId, setCollectionId] = useState<number | ''>('');
  const [creating, setCreating] = useState(false);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    fetchSessions();
    fetchCollections();
  }, [fetchSessions, fetchCollections]);

  const handleCreate = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!collectionId) return;
    setCreating(true);
    setError(null);
    try {
      const session = await createSession(collectionId);
      navigate(`/scan/${session.id}`);
    } catch (err: unknown) {
      setError(apiError(err, 'Failed to start scan session'));
    } finally {
      setCreating(false);
    }
  };

  const handleDiscard = async (id: number, e: React.MouseEvent) => {
    e.stopPropagation();
    if (confirm('Discard this scan session? All its unreviewed drafts and photos will be deleted.')) {
      await discardSession(id);
    }
  };

  const handleToggleClosed = async (id: number, status: string, e: React.MouseEvent) => {
    e.stopPropagation();
    if (status === 'OPEN') await closeSession(id);
    else await reopenSession(id);
  };

  return (
    <AppLayout>
      <div className="p-4 sm:p-6 max-w-4xl mx-auto">
        <div className="flex items-center justify-between mb-6">
          <div className="flex items-center gap-2">
            <ScanLine size={20} className="text-indigo-600" />
            <h1 className="text-xl font-bold text-gray-900">Scan sessions</h1>
          </div>
          <button
            onClick={() => setShowForm(true)}
            className="flex items-center gap-2 bg-indigo-600 text-white px-4 py-2 rounded-lg text-sm font-medium hover:bg-indigo-700 transition-colors"
          >
            <Plus size={16} /> New session
          </button>
        </div>

        {showForm && (
          <form onSubmit={handleCreate} className="bg-white rounded-xl border border-gray-200 p-5 mb-6 space-y-3">
            <h2 className="font-semibold text-gray-900">Start a scan session</h2>
            <p className="text-sm text-gray-500">Pick the collection you're scanning into — this stays fixed for the whole session.</p>
            <select
              required
              value={collectionId}
              onChange={(e) => setCollectionId(e.target.value ? Number(e.target.value) : '')}
              className="w-full border border-gray-300 rounded-lg px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-indigo-500"
            >
              <option value="" disabled>Select a collection…</option>
              {collections.map((c) => (
                <option key={c.id} value={c.id}>{c.name}</option>
              ))}
            </select>
            {error && <p className="text-sm text-red-600">{error}</p>}
            <div className="flex gap-2">
              <button type="submit" disabled={creating || !collectionId}
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
            <ScanLine size={48} className="mx-auto mb-3 opacity-40" />
            <p className="font-medium">No scan sessions yet</p>
            <p className="text-sm mt-1">Start one to scan a shelf or box hands-free</p>
          </div>
        )}

        <div className="grid gap-3 sm:grid-cols-2">
          {sessions.map((s) => (
            <div
              key={s.id}
              className="bg-white rounded-xl border border-gray-200 p-4 hover:border-indigo-300 hover:shadow-sm transition-all"
            >
              <div className="flex items-start justify-between mb-2">
                <div className="flex-1 min-w-0">
                  <h3 className="font-semibold text-gray-900 truncate">{s.collectionName ?? 'Collection'}</h3>
                  <p className="text-xs text-gray-400 mt-0.5">
                    {s.status === 'OPEN' ? 'Open' : 'Closed'} · {s.pendingDraftCount} pending draft{s.pendingDraftCount !== 1 ? 's' : ''}
                  </p>
                </div>
                <div className="flex items-center gap-1">
                  <button
                    onClick={(e) => handleToggleClosed(s.id, s.status, e)}
                    title={s.status === 'OPEN' ? 'Close session' : 'Reopen session'}
                    className="p-1.5 rounded-lg text-gray-300 hover:text-gray-600 hover:bg-gray-50"
                  >
                    {s.status === 'OPEN' ? <Archive size={14} /> : <ArchiveRestore size={14} />}
                  </button>
                  <button
                    onClick={(e) => handleDiscard(s.id, e)}
                    title="Discard session"
                    className="p-1.5 rounded-lg text-gray-300 hover:text-red-500 hover:bg-red-50"
                  >
                    <Trash2 size={14} />
                  </button>
                </div>
              </div>
              <div className="flex gap-2 mt-3">
                <button
                  onClick={() => navigate(`/scan/${s.id}`)}
                  className="flex-1 bg-gray-900 text-white rounded-lg py-2 text-sm font-medium hover:bg-gray-800 transition-colors"
                >
                  Resume scanning
                </button>
                <button
                  onClick={() => navigate(`/scan/${s.id}/review`)}
                  className="flex-1 border border-gray-200 rounded-lg py-2 text-sm font-medium text-gray-700 hover:bg-gray-50 transition-colors"
                >
                  Review ({s.pendingDraftCount})
                </button>
              </div>
            </div>
          ))}
        </div>
      </div>
    </AppLayout>
  );
}
