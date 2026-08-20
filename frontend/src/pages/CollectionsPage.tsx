import { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { Plus, Library, Trash2 } from 'lucide-react';
import { useCollectionStore } from '../store/collectionStore';
import { AppLayout } from '../components/layout/AppLayout';
import { CategoryBadge } from '../components/ui/Badge';
import { Spinner } from '../components/ui/Spinner';
import type { MediaCategory } from '../types';
import { CATEGORY_LABELS } from '../types';

export function CollectionsPage() {
  const { collections, isLoading, fetchCollections, createCollection, deleteCollection } = useCollectionStore();
  const [showForm, setShowForm] = useState(false);
  const [name, setName] = useState('');
  const [description, setDescription] = useState('');
  const [category, setCategory] = useState<MediaCategory | ''>('');
  const [saving, setSaving] = useState(false);

  useEffect(() => { fetchCollections(); }, [fetchCollections]);

  const handleCreate = async (e: React.FormEvent) => {
    e.preventDefault();
    setSaving(true);
    try {
      await createCollection({ name, description: description || undefined, primaryCategory: category || undefined });
      setName(''); setDescription(''); setCategory(''); setShowForm(false);
    } finally { setSaving(false); }
  };

  const handleDelete = async (id: number, e: React.MouseEvent) => {
    e.preventDefault();
    if (confirm('Delete this collection? Items are not deleted.')) {
      await deleteCollection(id);
    }
  };

  return (
    <AppLayout>
      <div className="p-4 sm:p-6 max-w-4xl mx-auto">
        <div className="flex items-center justify-between mb-6">
          <h1 className="text-xl font-bold text-gray-900">My Collections</h1>
          <button
            onClick={() => setShowForm(true)}
            className="flex items-center gap-2 bg-indigo-600 text-white px-4 py-2 rounded-lg text-sm font-medium hover:bg-indigo-700 transition-colors"
          >
            <Plus size={16} /> New collection
          </button>
        </div>

        {/* Create form */}
        {showForm && (
          <form onSubmit={handleCreate} className="bg-white rounded-xl border border-gray-200 p-5 mb-6 space-y-3">
            <h2 className="font-semibold text-gray-900">New collection</h2>
            <input
              required
              value={name}
              onChange={(e) => setName(e.target.value)}
              placeholder="Collection name"
              className="w-full border border-gray-300 rounded-lg px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-indigo-500"
            />
            <input
              value={description}
              onChange={(e) => setDescription(e.target.value)}
              placeholder="Description (optional)"
              className="w-full border border-gray-300 rounded-lg px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-indigo-500"
            />
            <select
              value={category}
              onChange={(e) => setCategory(e.target.value as MediaCategory | '')}
              className="w-full border border-gray-300 rounded-lg px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-indigo-500"
            >
              <option value="">Mixed / any category</option>
              {(Object.keys(CATEGORY_LABELS) as MediaCategory[]).map((c) => (
                <option key={c} value={c}>{CATEGORY_LABELS[c]}</option>
              ))}
            </select>
            <div className="flex gap-2">
              <button type="submit" disabled={saving}
                className="bg-indigo-600 text-white px-4 py-2 rounded-lg text-sm font-medium hover:bg-indigo-700 disabled:opacity-50">
                {saving ? 'Creating…' : 'Create'}
              </button>
              <button type="button" onClick={() => setShowForm(false)}
                className="px-4 py-2 rounded-lg text-sm font-medium text-gray-600 hover:bg-gray-100">
                Cancel
              </button>
            </div>
          </form>
        )}

        {isLoading && <div className="flex justify-center py-12"><Spinner /></div>}

        {!isLoading && collections.length === 0 && (
          <div className="text-center py-16 text-gray-400">
            <Library size={48} className="mx-auto mb-3 opacity-40" />
            <p className="font-medium">No collections yet</p>
            <p className="text-sm mt-1">Create your first collection to get started</p>
          </div>
        )}

        <div className="grid gap-3 sm:grid-cols-2">
          {collections.map((c) => (
            <Link
              key={c.id}
              to={`/collections/${c.id}`}
              className="bg-white rounded-xl border border-gray-200 p-4 hover:border-indigo-300 hover:shadow-sm transition-all group"
            >
              <div className="flex items-start justify-between">
                <div className="flex-1 min-w-0">
                  <div className="flex items-center gap-2 mb-1">
                    {c.primaryCategory && <CategoryBadge category={c.primaryCategory} />}
                    <h3 className="font-semibold text-gray-900 truncate">{c.name}</h3>
                  </div>
                  {c.description && <p className="text-sm text-gray-500 truncate">{c.description}</p>}
                  <p className="text-xs text-gray-400 mt-2">{c.itemCount} item{c.itemCount !== 1 ? 's' : ''}</p>
                </div>
                <button
                  onClick={(e) => handleDelete(c.id, e)}
                  className="ml-2 p-1.5 rounded-lg text-gray-300 hover:text-red-500 hover:bg-red-50 opacity-0 group-hover:opacity-100 transition-all"
                >
                  <Trash2 size={14} />
                </button>
              </div>
            </Link>
          ))}
        </div>
      </div>
    </AppLayout>
  );
}
