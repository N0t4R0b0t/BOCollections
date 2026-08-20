import { useEffect, useState, Fragment } from 'react';
import { useNavigate, useParams, Link } from 'react-router-dom';
import { X, ScanLine, Check, Trash2, Pencil, GitMerge, Layers, Camera } from 'lucide-react';
import { AppLayout } from '../components/layout/AppLayout';
import { Spinner } from '../components/ui/Spinner';
import { useScanSessionStore } from '../store/scanSessionStore';
import { apiError } from '../utils/apiError';
import { mediaUrl } from '../utils/mediaUrl';
import { extraFieldRows } from '../utils/extraMetadata';
import { PhotoLightbox } from '../components/ui/PhotoLightbox';
import { PhotoThumbnail } from '../components/ui/PhotoThumbnail';
import { PhotoCropModal } from '../components/ui/PhotoCropModal';
import { CATEGORY_LABELS, FORMATS_BY_CATEGORY } from '../types';
import type { MediaCategory } from '../types';
import type { ScanDraft, PhotoAngle } from '../types/scanSession';

export function ScanReviewPage() {
  const { sessionId } = useParams<{ sessionId: string }>();
  const navigate = useNavigate();
  const id = Number(sessionId);

  const { drafts, isLoading, fetchDrafts, approveDraft, discardDraft, updateDraft, mergeDrafts, deleteDraftPhoto } = useScanSessionStore();
  const [error, setError] = useState<string | null>(null);
  const [editingId, setEditingId] = useState<number | null>(null);
  const [selected, setSelected] = useState<number[]>([]);
  const [busyId, setBusyId] = useState<number | null>(null);

  useEffect(() => { fetchDrafts(id); }, [id, fetchDrafts]);

  const pending = drafts.filter((d) => d.status === 'PENDING');
  const skipped = drafts.filter((d) => d.status === 'SKIPPED');

  const handleApprove = async (draftId: number) => {
    setBusyId(draftId);
    setError(null);
    try {
      await approveDraft(id, draftId);
    } catch (e: unknown) {
      setError(apiError(e, 'Failed to approve draft'));
    } finally {
      setBusyId(null);
    }
  };

  const handleDiscard = async (draftId: number) => {
    if (!confirm('Discard this draft and its photos?')) return;
    setBusyId(draftId);
    try {
      await discardDraft(id, draftId);
    } finally {
      setBusyId(null);
    }
  };

  const toggleSelect = (draftId: number) => {
    setSelected((s) => (s.includes(draftId) ? s.filter((x) => x !== draftId) : [...s, draftId].slice(-2)));
  };

  const handleMerge = async () => {
    if (selected.length !== 2) return;
    setError(null);
    try {
      await mergeDrafts(id, selected[0], selected[1]);
      setSelected([]);
    } catch (e: unknown) {
      setError(apiError(e, 'Failed to merge drafts'));
    }
  };

  const renderDraftCard = (d: ScanDraft, addAnywayLabel = false) => (
    <DraftCard
      key={d.id}
      draft={d}
      sessionId={id}
      busy={busyId === d.id}
      editing={editingId === d.id}
      selected={selected.includes(d.id)}
      onEdit={() => setEditingId(editingId === d.id ? null : d.id)}
      onSave={async (fields) => { await updateDraft(id, d.id, fields); setEditingId(null); }}
      onApprove={() => handleApprove(d.id)}
      onDiscard={() => handleDiscard(d.id)}
      onToggleSelect={() => toggleSelect(d.id)}
      onDeletePhoto={(photoId) => deleteDraftPhoto(id, d.id, photoId)}
      onSetCover={(coverUrl) => updateDraft(id, d.id, { coverUrl })}
      addAnywayLabel={addAnywayLabel}
    />
  );

  return (
    <AppLayout>
      <div className="p-4 sm:p-6 max-w-2xl mx-auto space-y-4">
        <div className="flex items-center justify-between">
          <div className="flex items-center gap-2">
            <ScanLine size={20} className="text-indigo-600" />
            <h1 className="text-lg font-bold text-gray-900">Review drafts</h1>
          </div>
          <button onClick={() => navigate('/scan')} className="text-gray-400 hover:text-gray-600 p-1">
            <X size={20} />
          </button>
        </div>

        {selected.length === 2 && (
          <div className="flex items-center justify-between bg-indigo-50 border border-indigo-200 rounded-xl px-4 py-2.5">
            <span className="text-sm text-indigo-800">2 drafts selected — merge into one?</span>
            <div className="flex gap-2">
              <button onClick={handleMerge} className="flex items-center gap-1 bg-indigo-600 text-white text-xs font-medium px-3 py-1.5 rounded-lg hover:bg-indigo-700">
                <GitMerge size={14} /> Merge
              </button>
              <button onClick={() => setSelected([])} className="text-xs text-indigo-600 hover:text-indigo-800 px-2">Cancel</button>
            </div>
          </div>
        )}

        {error && <p className="text-sm text-red-600">{error}</p>}

        {isLoading && <div className="flex justify-center py-12"><Spinner /></div>}

        {!isLoading && pending.length === 0 && skipped.length === 0 && (
          <div className="text-center py-16 text-gray-400">
            <ScanLine size={48} className="mx-auto mb-3 opacity-40" />
            <p className="font-medium">Nothing to review</p>
            <p className="text-sm mt-1">Scan some items or check back after your next session</p>
          </div>
        )}

        <div className="space-y-3">
          {pending.map((d) => renderDraftCard(d))}
        </div>

        {skipped.length > 0 && (
          <details className="bg-white rounded-xl border border-gray-200 p-4">
            <summary className="text-sm font-medium text-gray-600 cursor-pointer">
              Skipped this session ({skipped.length}) — already in this collection
            </summary>
            <div className="space-y-3 mt-3">
              {skipped.map((d) => renderDraftCard(d, true))}
            </div>
          </details>
        )}
      </div>
    </AppLayout>
  );
}

interface DraftCardProps {
  draft: ScanDraft;
  sessionId: number;
  busy: boolean;
  editing: boolean;
  selected: boolean;
  addAnywayLabel?: boolean;
  onEdit: () => void;
  onSave: (fields: {
    title: string; category: MediaCategory; format: string; publisher: string;
    subtitle: string; description: string; releaseYear: number | undefined;
  }) => Promise<void>;
  onApprove: () => void;
  onDiscard: () => void;
  onToggleSelect: () => void;
  onDeletePhoto: (photoId: number) => Promise<void>;
  onSetCover: (coverUrl: string) => Promise<unknown>;
}

function DraftCard({ draft, sessionId, busy, editing, selected, addAnywayLabel, onEdit, onSave, onApprove, onDiscard, onToggleSelect, onDeletePhoto, onSetCover }: DraftCardProps) {
  const { addDraftPhotos, updateDraftPhotoAngle, reorderDraftPhotos } = useScanSessionStore();
  const [title, setTitle] = useState(draft.title ?? '');
  const [category, setCategory] = useState<MediaCategory>(draft.category ?? 'OTHER');
  const [format, setFormat] = useState(draft.format ?? '');
  const [publisher, setPublisher] = useState(draft.publisher ?? '');
  const [subtitle, setSubtitle] = useState(draft.subtitle ?? '');
  const [description, setDescription] = useState(draft.description ?? '');
  const [releaseYear, setReleaseYear] = useState(draft.releaseYear?.toString() ?? '');
  const [deletingPhotoId, setDeletingPhotoId] = useState<number | null>(null);
  const [busyPhotoId, setBusyPhotoId] = useState<number | null>(null);
  const [settingCover, setSettingCover] = useState(false);
  const [lightboxIndex, setLightboxIndex] = useState<number | null>(null);
  const [cropTarget, setCropTarget] = useState<{ id: number; url: string; angle: PhotoAngle } | null>(null);

  const handleDeletePhoto = async (photoId: number) => {
    setDeletingPhotoId(photoId);
    try {
      await onDeletePhoto(photoId);
    } finally {
      setDeletingPhotoId(null);
    }
  };

  const handleSetCover = async (url: string) => {
    if (url === draft.coverUrl) return;
    setSettingCover(true);
    try {
      await onSetCover(url);
    } finally {
      setSettingCover(false);
    }
  };

  const handleAngleChange = async (photoId: number, angle: PhotoAngle) => {
    setBusyPhotoId(photoId);
    try {
      await updateDraftPhotoAngle(sessionId, draft.id, photoId, angle);
    } finally {
      setBusyPhotoId(null);
    }
  };

  const handleMovePhoto = async (index: number, direction: -1 | 1) => {
    const newIndex = index + direction;
    if (newIndex < 0 || newIndex >= draft.photos.length) return;
    const reordered = [...draft.photos];
    [reordered[index], reordered[newIndex]] = [reordered[newIndex], reordered[index]];
    setBusyPhotoId(draft.photos[index].id);
    try {
      await reorderDraftPhotos(sessionId, draft.id, reordered.map((p) => p.id));
    } finally {
      setBusyPhotoId(null);
    }
  };

  // Same "no in-place replace" constraint as ItemFormPage/AddPhotosPage's equivalent — upload the
  // crop result as a new photo, delete the original, re-point coverUrl if it was the cover.
  const handleCropped = async (base64: string) => {
    if (!cropTarget) return;
    const wasCover = cropTarget.url === draft.coverUrl;
    // The crop result replaces the original by delete+re-add (no in-place update endpoint),
    // which lands it at a new array position — if the lightbox was open, follow it there rather
    // than leave the viewer sitting on whatever unrelated photo now occupies the old index.
    const followInLightbox = lightboxIndex !== null;
    setBusyPhotoId(cropTarget.id);
    try {
      const updated = await addDraftPhotos(sessionId, draft.id, [
        { imageBase64: base64, imageMimeType: 'image/jpeg', angle: cropTarget.angle },
      ]);
      const newPhoto = updated.photos.at(-1);
      await onDeletePhoto(cropTarget.id);
      if (wasCover && newPhoto) {
        await onSetCover(newPhoto.url);
      }
      if (followInLightbox && newPhoto) {
        const finalPhotos = useScanSessionStore.getState().drafts.find((d) => d.id === draft.id)?.photos;
        const newIndex = finalPhotos?.findIndex((p) => p.id === newPhoto.id) ?? -1;
        setLightboxIndex(newIndex >= 0 ? newIndex : null);
      }
    } finally {
      setBusyPhotoId(null);
      setCropTarget(null);
    }
  };

  const variantFlag = draft.matchKind === 'VARIANT_MISMATCH';
  const unmatchedFlag = draft.matchKind === 'UNMATCHED' || draft.matchKind === 'MANUAL';
  const usingGenericImage = !!draft.coverUrl && draft.photos.length === 0 && (variantFlag || unmatchedFlag);
  // Whatever's actually designated as the cover (draft.coverUrl — editable via the photo strip's
  // "use as cover" star below) wins over just guessing "the first photo", now that there's a
  // real way to choose which of possibly several photos represents the item.
  const image = mediaUrl(draft.coverUrl) || mediaUrl(draft.photos[0]?.url);
  const extraRows = extraFieldRows(draft.metadata);

  return (
    <div className={`bg-white rounded-xl border p-4 space-y-2 ${selected ? 'border-indigo-400 ring-1 ring-indigo-300' : 'border-gray-200'}`}>
      <div className="flex gap-3">
        <input type="checkbox" checked={selected} onChange={onToggleSelect} className="mt-1.5" title="Select for merge" />
        {image ? (
          <img src={image} alt="" className="w-14 h-14 rounded-lg object-cover shrink-0 bg-gray-100" />
        ) : (
          <div className="w-14 h-14 rounded-lg bg-gray-100 shrink-0" />
        )}
        <div className="flex-1 min-w-0">
          {!editing ? (
            <>
              <p className="font-medium text-gray-900 truncate">{draft.title || 'Untitled item'}</p>
              {draft.subtitle && <p className="text-xs text-gray-500 truncate">{draft.subtitle}</p>}
              <p className="text-xs text-gray-500">{draft.format || '—'} {draft.releaseYear ? `· ${draft.releaseYear}` : ''}</p>
            </>
          ) : (
            <div className="space-y-2">
              <input value={title} onChange={(e) => setTitle(e.target.value)} placeholder="Title"
                className="w-full border border-gray-300 rounded-lg px-2 py-1 text-sm" />
              <input value={subtitle} onChange={(e) => setSubtitle(e.target.value)} placeholder="Subtitle / edition"
                className="w-full border border-gray-300 rounded-lg px-2 py-1 text-sm" />
              <div className="grid grid-cols-2 gap-2">
                <select value={category} onChange={(e) => setCategory(e.target.value as MediaCategory)}
                  className="border border-gray-300 rounded-lg px-2 py-1 text-sm">
                  {(Object.keys(CATEGORY_LABELS) as MediaCategory[]).map((c) => (
                    <option key={c} value={c}>{CATEGORY_LABELS[c]}</option>
                  ))}
                </select>
                <select value={format} onChange={(e) => setFormat(e.target.value)}
                  className="border border-gray-300 rounded-lg px-2 py-1 text-sm">
                  {(FORMATS_BY_CATEGORY[category] ?? []).map((f) => (
                    <option key={f} value={f}>{f}</option>
                  ))}
                </select>
              </div>
              <div className="grid grid-cols-2 gap-2">
                <input value={publisher} onChange={(e) => setPublisher(e.target.value)} placeholder="Publisher / label"
                  className="border border-gray-300 rounded-lg px-2 py-1 text-sm" />
                <input value={releaseYear} onChange={(e) => setReleaseYear(e.target.value)} placeholder="Year" type="number"
                  className="border border-gray-300 rounded-lg px-2 py-1 text-sm" />
              </div>
              <textarea value={description} onChange={(e) => setDescription(e.target.value)} placeholder="Description" rows={2}
                className="w-full border border-gray-300 rounded-lg px-2 py-1 text-sm resize-y" />
            </div>
          )}
          <div className="flex gap-1.5 mt-1.5 flex-wrap">
            {variantFlag && <span className="text-xs px-2 py-0.5 rounded-full bg-amber-100 text-amber-700">Possible different edition</span>}
            {unmatchedFlag && <span className="text-xs px-2 py-0.5 rounded-full bg-gray-100 text-gray-600">Unmatched — needs review</span>}
            {usingGenericImage && <span className="text-xs px-2 py-0.5 rounded-full bg-blue-100 text-blue-700">Generic online image</span>}
            {draft.duplicateOfDraftId && <span className="text-xs px-2 py-0.5 rounded-full bg-purple-100 text-purple-700">Scanned twice this session</span>}
          </div>
        </div>
      </div>

      {/* No barcode source can ever catch "same movie, different edition" — different editions
          have different barcodes. This is a title match against the user's own catalogue instead,
          purely informational: it never blocks approving, just makes sure buying a second edition
          on purpose stays a deliberate choice instead of an accident. */}
      {draft.relatedEditions && draft.relatedEditions.length > 0 && (
        <div className="flex items-start gap-2 bg-indigo-50 border border-indigo-200 rounded-lg px-3 py-2 text-xs text-indigo-800">
          <Layers size={14} className="mt-0.5 shrink-0" />
          <div>
            You already own {draft.relatedEditions.length === 1 ? 'an edition' : `${draft.relatedEditions.length} editions`} of this:{' '}
            {draft.relatedEditions.map((e, i) => (
              <span key={e.itemId}>
                {i > 0 && ', '}
                {e.format ?? 'unknown format'}{e.releaseYear ? ` (${e.releaseYear})` : ''}
              </span>
            ))}
          </div>
        </div>
      )}

      {/* Every photo taken for this item (plus fetched online reference images, if any) — kept
          regardless of match confidence; drop anything unwanted here without discarding the
          whole draft, or tap the image icon to make that photo the item's cover — whatever's
          currently the cover (draft.coverUrl) gets a highlighted ring so it's obvious which one
          it is instead of always silently being "the first photo". */}
      {draft.photos.length > 0 && (
        <div className="flex gap-2 overflow-x-auto pb-1">
          {draft.photos.map((p, i) => (
            <PhotoThumbnail
              key={p.id}
              src={mediaUrl(p.url) ?? ''}
              angle={p.angle}
              isCover={p.url === draft.coverUrl}
              busy={deletingPhotoId === p.id || busyPhotoId === p.id || settingCover}
              onClick={() => setLightboxIndex(i)}
              onAngleChange={(angle) => void handleAngleChange(p.id, angle)}
              onCrop={() => setCropTarget({ id: p.id, url: p.url, angle: p.angle })}
              onSetCover={p.url === draft.coverUrl ? undefined : () => handleSetCover(p.url)}
              onDelete={() => void handleDeletePhoto(p.id)}
              onMoveLeft={i > 0 ? () => void handleMovePhoto(i, -1) : undefined}
              onMoveRight={i < draft.photos.length - 1 ? () => void handleMovePhoto(i, 1) : undefined}
            />
          ))}
        </div>
      )}

      {lightboxIndex !== null && (
        <PhotoLightbox
          photos={draft.photos.map((p) => ({
            id: p.id, src: mediaUrl(p.url) ?? '', angle: p.angle, isCover: p.url === draft.coverUrl,
          }))}
          index={lightboxIndex}
          onIndexChange={setLightboxIndex}
          onClose={() => setLightboxIndex(null)}
          onDelete={(id) => handleDeletePhoto(id as number)}
          deletingId={deletingPhotoId}
          onAngleChange={(id, angle) => handleAngleChange(id as number, angle)}
          onSetCover={(id) => {
            const p = draft.photos.find((ph) => ph.id === id);
            if (p) return handleSetCover(p.url);
          }}
          onCrop={(id) => {
            const p = draft.photos.find((ph) => ph.id === id);
            if (p) setCropTarget({ id: p.id, url: p.url, angle: p.angle });
          }}
          onMoveLeft={(id) => {
            const i = draft.photos.findIndex((ph) => ph.id === id);
            if (i >= 0) return handleMovePhoto(i, -1);
          }}
          onMoveRight={(id) => {
            const i = draft.photos.findIndex((ph) => ph.id === id);
            if (i >= 0) return handleMovePhoto(i, 1);
          }}
        />
      )}

      {cropTarget && (
        <PhotoCropModal
          src={mediaUrl(cropTarget.url) ?? ''}
          onCancel={() => setCropTarget(null)}
          onCropped={(base64) => void handleCropped(base64)}
        />
      )}

      {extraRows.length > 0 && (
        <details className="border-t border-gray-100 pt-2">
          <summary className="text-xs font-medium text-gray-500 cursor-pointer">Extra details</summary>
          <dl className="grid grid-cols-2 gap-x-4 gap-y-1 text-xs mt-2">
            {extraRows.map(({ key, label, render, value }) => (
              <Fragment key={key}>
                <dt className="text-gray-400">{label}</dt>
                <dd className="text-gray-800">{render(value)}</dd>
              </Fragment>
            ))}
          </dl>
        </details>
      )}

      <div className="flex gap-2 pt-1">
        {editing ? (
          <>
            <button
              onClick={() => onSave({
                title, category, format, publisher, subtitle, description,
                releaseYear: releaseYear ? Number(releaseYear) : undefined,
              })}
              className="flex-1 bg-indigo-600 text-white rounded-lg py-1.5 text-sm font-medium hover:bg-indigo-700"
            >
              Save
            </button>
            <button onClick={onEdit} className="px-3 py-1.5 text-sm text-gray-500 hover:bg-gray-50 rounded-lg">Cancel</button>
          </>
        ) : (
          <>
            <button
              onClick={onApprove}
              disabled={busy}
              className="flex-1 flex items-center justify-center gap-1 bg-indigo-600 text-white rounded-lg py-1.5 text-sm font-medium hover:bg-indigo-700 disabled:opacity-50"
            >
              <Check size={14} /> {addAnywayLabel ? 'Add anyway' : 'Approve'}
            </button>
            <Link
              to={`/scan/${sessionId}/drafts/${draft.id}/photos`}
              title="Add more photos or re-run AI vision"
              className="p-2 rounded-lg text-gray-400 hover:text-gray-700 hover:bg-gray-50"
            >
              <Camera size={14} />
            </Link>
            <button onClick={onEdit} className="p-2 rounded-lg text-gray-400 hover:text-gray-700 hover:bg-gray-50"><Pencil size={14} /></button>
            <button onClick={onDiscard} disabled={busy} className="p-2 rounded-lg text-gray-400 hover:text-red-500 hover:bg-red-50"><Trash2 size={14} /></button>
          </>
        )}
      </div>
    </div>
  );
}
