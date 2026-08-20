import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { X, ExternalLink, Plus, CheckCircle, AlertCircle, Sparkles } from 'lucide-react';
import type { ThriftItemCardData } from '../../types/thrift';
import { OWNED_STATUS_COLOR, OWNED_STATUS_LABEL } from '../../types/thrift';
import { Badge } from '../ui/Badge';
import { useCollectionStore } from '../../store/collectionStore';
import { apiClient } from '../../api/apiClient';
import { apiError } from '../../utils/apiError';

interface Props {
  item: ThriftItemCardData;
  onClose: () => void;
}

const STATUS_ICON = {
  OWNED: <CheckCircle size={16} className="text-blue-500" />,
  DIFFERENT_VERSION: <AlertCircle size={16} className="text-amber-500" />,
  NOT_OWNED: <Plus size={16} className="text-green-500" />,
  INTERESTING: <Sparkles size={16} className="text-orange-500" />,
};

export function ThriftItemCard({ item, onClose }: Props) {
  const navigate = useNavigate();
  const { collections, fetchCollections } = useCollectionStore();
  const color = OWNED_STATUS_COLOR[item.ownedStatus];

  const [showPicker, setShowPicker] = useState(false);
  const [collectionId, setCollectionId] = useState<number | ''>('');
  const [adding, setAdding] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const canAdd = item.ownedStatus === 'NOT_OWNED' || item.ownedStatus === 'INTERESTING';

  const openPicker = () => {
    fetchCollections();
    setShowPicker(true);
  };

  const handleScanToAdd = async () => {
    if (!collectionId) return;
    setAdding(true);
    setError(null);
    try {
      // Reuse an existing open session for this collection rather than always starting a new
      // one — otherwise adding several finds in one trip clutters /scan with a separate
      // single-draft session per item instead of one consolidated batch.
      const sessions = await apiClient.getScanSessions();
      const existingOpen = sessions.data.find((s) => s.collectionId === collectionId && s.status === 'OPEN');
      const targetSessionId = existingOpen ? existingOpen.id : (await apiClient.createScanSession(collectionId)).data.id;

      await apiClient.createScanDraft(targetSessionId, {
        matchKind: 'MANUAL',
        category: item.category,
        format: item.format,
        title: item.title,
        subtitle: item.artistOrAuthor,
      });
      navigate(`/scan/${targetSessionId}/review`);
    } catch (e: unknown) {
      setError(apiError(e, 'Failed to start a draft for this item'));
    } finally {
      setAdding(false);
    }
  };

  return (
    <div className="fixed bottom-0 left-0 right-0 z-50 animate-in slide-in-from-bottom duration-200">
      <div className="bg-white rounded-t-2xl shadow-2xl border-t border-gray-200 p-4 max-w-lg mx-auto">
        <div className="flex items-center justify-between mb-3">
          <div className="w-10 h-1 rounded-full bg-gray-300 mx-auto absolute left-1/2 -translate-x-1/2 top-2" />
          <button onClick={onClose} className="ml-auto p-1 rounded-full hover:bg-gray-100 transition-colors">
            <X size={18} className="text-gray-500" />
          </button>
        </div>

        <div className="flex items-center gap-2 mb-2">
          {STATUS_ICON[item.ownedStatus]}
          <span className="text-sm font-medium" style={{ color }}>
            {OWNED_STATUS_LABEL[item.ownedStatus]}
          </span>
          {item.confidence && <Badge variant="gray" className="ml-auto text-xs">{item.confidence}</Badge>}
        </div>

        <p className="font-semibold text-gray-900 text-base leading-snug">{item.title}</p>
        {item.artistOrAuthor && (
          <p className="text-sm text-gray-500 mt-0.5">{item.artistOrAuthor}</p>
        )}
        {item.format && (
          <p className="text-xs text-gray-400 mt-0.5">{item.format}</p>
        )}

        {!showPicker ? (
          <div className="flex gap-2 mt-4">
            {item.itemId && (
              <button
                onClick={() => navigate(`/items/${item.itemId}`)}
                className="flex items-center gap-1.5 px-3 py-2 rounded-lg border border-gray-200 text-sm text-gray-700 hover:bg-gray-50 transition-colors"
              >
                <ExternalLink size={14} />
                View item
              </button>
            )}
            {canAdd && (
              <button
                onClick={openPicker}
                className="flex items-center gap-1.5 px-3 py-2 rounded-lg bg-indigo-600 text-white text-sm font-medium hover:bg-indigo-700 transition-colors"
              >
                <Plus size={14} />
                Scan to add
              </button>
            )}
          </div>
        ) : (
          <div className="mt-4 space-y-2">
            <select
              value={collectionId}
              onChange={(e) => setCollectionId(e.target.value ? Number(e.target.value) : '')}
              className="w-full border border-gray-300 rounded-lg px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-indigo-500"
            >
              <option value="" disabled>Add to which collection?</option>
              {collections.map((c) => (
                <option key={c.id} value={c.id}>{c.name}</option>
              ))}
            </select>
            {error && <p className="text-sm text-red-600">{error}</p>}
            <div className="flex gap-2">
              <button
                onClick={handleScanToAdd}
                disabled={!collectionId || adding}
                className="flex-1 bg-indigo-600 text-white rounded-lg py-2 text-sm font-medium hover:bg-indigo-700 disabled:opacity-50"
              >
                {adding ? 'Adding…' : 'Confirm'}
              </button>
              <button
                onClick={() => setShowPicker(false)}
                className="px-3 py-2 rounded-lg text-sm text-gray-500 hover:bg-gray-50"
              >
                Cancel
              </button>
            </div>
          </div>
        )}
      </div>
    </div>
  );
}
