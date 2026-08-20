import { useEffect, useState } from 'react';
import { useParams, useNavigate, Link } from 'react-router-dom';
import { ArrowLeft, Camera, Plus, Upload, X } from 'lucide-react';
import { apiClient } from '../api/apiClient';
import { apiError } from '../utils/apiError';
import { AppLayout } from '../components/layout/AppLayout';
import { Spinner } from '../components/ui/Spinner';
import { PhotoLightbox } from '../components/ui/PhotoLightbox';
import { PhotoThumbnail } from '../components/ui/PhotoThumbnail';
import { PhotoCropModal } from '../components/ui/PhotoCropModal';
import { mediaUrl } from '../utils/mediaUrl';
import { parseExtraMetadata } from '../utils/extraMetadata';
import type { ExtraMetadata } from '../utils/extraMetadata';
import type { MediaCategory, Item, ItemPhoto } from '../types';
import { CATEGORY_LABELS, FORMATS_BY_CATEGORY } from '../types';
import { TAGGABLE_ANGLES } from '../types/scanSession';
import type { PhotoAngle } from '../types/scanSession';

type DiscTitle = NonNullable<ExtraMetadata['titles']>[number];

/** Reads a File as base64 (stripping the "data:image/jpeg;base64," prefix apiClient.addItemPhotos expects). */
function fileToBase64(file: File): Promise<string> {
  return new Promise((resolve, reject) => {
    const reader = new FileReader();
    reader.onload = () => resolve((reader.result as string).split(',')[1] ?? '');
    reader.onerror = reject;
    reader.readAsDataURL(file);
  });
}

const EMPTY: Partial<Item> = {
  category: 'PRINT',
  format: 'Book',
  title: '',
  subtitle: '',
  description: '',
  barcode: '',
  barcodeType: 'ISBN13',
  coverUrl: '',
  releaseYear: undefined,
  publisher: '',
};

export function ItemFormPage() {
  const { id } = useParams<{ id: string }>();
  const isEdit = Boolean(id);
  const navigate = useNavigate();

  const [form, setForm] = useState<Partial<Item>>(EMPTY);
  const [loading, setLoading] = useState(isEdit);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [uploading, setUploading] = useState(false);
  const [photoBusyId, setPhotoBusyId] = useState<number | null>(null);
  const [lightboxIndex, setLightboxIndex] = useState<number | null>(null);
  const [cropTarget, setCropTarget] = useState<ItemPhoto | null>(null);
  const [discTitles, setDiscTitles] = useState<DiscTitle[]>([]);

  useEffect(() => {
    if (isEdit) {
      apiClient.getItem(Number(id))
        .then((r) => {
          setForm(r.data);
          setDiscTitles(parseExtraMetadata(r.data.metadata)?.titles ?? []);
        })
        .finally(() => setLoading(false));
    }
  }, [id, isEdit]);

  const addDiscTitle = () => setDiscTitles((rows) => [...rows, { title: '' }]);
  const updateDiscTitle = (i: number, patch: Partial<DiscTitle>) =>
    setDiscTitles((rows) => rows.map((row, idx) => (idx === i ? { ...row, ...patch } : row)));
  const removeDiscTitle = (i: number) => setDiscTitles((rows) => rows.filter((_, idx) => idx !== i));

  /** Folds the disc-titles editor's state back into the metadata JSON blob, dropping the `titles`
   * key entirely if the list is empty (rather than persisting `titles: []` on every plain item). */
  const buildMetadataWithTitles = (): string | undefined => {
    const existing = parseExtraMetadata(form.metadata) ?? {};
    const clean = discTitles.map((t) => ({ ...t, title: t.title.trim() })).filter((t) => t.title);
    const next: ExtraMetadata = { ...existing };
    if (clean.length > 0) next.titles = clean;
    else delete next.titles;
    return Object.keys(next).length > 0 ? JSON.stringify(next) : undefined;
  };

  const set = (field: keyof Item, value: unknown) =>
    setForm((f) => ({ ...f, [field]: value }));

  const handleUploadPhotos = async (files: FileList | null) => {
    if (!files || files.length === 0 || !isEdit) return;
    setUploading(true);
    try {
      // angle is required server-side (@NotBlank) — cycle through the taggable angles the same
      // way the capture flows default new shots, rather than leaving it out and having every
      // upload here fail validation.
      const photos = await Promise.all(
        Array.from(files).map(async (file, i) => ({
          imageBase64: await fileToBase64(file),
          imageMimeType: file.type || 'image/jpeg',
          angle: TAGGABLE_ANGLES[i % TAGGABLE_ANGLES.length],
        })),
      );
      const res = await apiClient.addItemPhotos(Number(id), photos);
      setForm(res.data);
    } catch (err: unknown) {
      setError(apiError(err, 'Could not upload those photos'));
    } finally {
      setUploading(false);
    }
  };

  const handleSetCover = (url: string) => set('coverUrl', url);

  const handleRemovePhoto = async (photoId: number) => {
    if (!isEdit) return;
    setPhotoBusyId(photoId);
    try {
      const res = await apiClient.deleteItemPhoto(Number(id), photoId);
      setForm(res.data);
    } finally {
      setPhotoBusyId(null);
    }
  };

  const handleAngleChange = async (photoId: number, angle: PhotoAngle) => {
    if (!isEdit) return;
    setPhotoBusyId(photoId);
    try {
      const res = await apiClient.updateItemPhotoAngle(Number(id), photoId, angle);
      setForm(res.data);
    } finally {
      setPhotoBusyId(null);
    }
  };

  const handleMovePhoto = async (index: number, direction: -1 | 1) => {
    if (!isEdit || !form.photos) return;
    const newIndex = index + direction;
    if (newIndex < 0 || newIndex >= form.photos.length) return;
    const reordered = [...form.photos];
    [reordered[index], reordered[newIndex]] = [reordered[newIndex], reordered[index]];
    setPhotoBusyId(form.photos[index].id);
    try {
      const res = await apiClient.reorderItemPhotos(Number(id), reordered.map((p) => p.id));
      setForm(res.data);
    } finally {
      setPhotoBusyId(null);
    }
  };

  // Re-crop an already-saved photo: PhotoCropModal has no in-place "replace" concept (it only
  // ever hands back a fresh cropped image), so this uploads the crop result as a new photo,
  // deletes the original, and — since the original's storage key is now gone — immediately
  // re-points coverUrl at the new photo if the original was the cover, rather than leaving a
  // window where the persisted item points at a deleted file until the user hits Save.
  const handleCropped = async (base64: string) => {
    if (!cropTarget || !isEdit) return;
    const wasCover = cropTarget.url === form.coverUrl;
    setPhotoBusyId(cropTarget.id);
    try {
      const addRes = await apiClient.addItemPhotos(Number(id), [
        { imageBase64: base64, imageMimeType: 'image/jpeg', angle: cropTarget.angle as PhotoAngle },
      ]);
      const newPhoto = addRes.data.photos?.at(-1);
      const delRes = await apiClient.deleteItemPhoto(Number(id), cropTarget.id);
      let finalItem = delRes.data;
      if (wasCover && newPhoto) {
        finalItem = (await apiClient.patchItem(Number(id), { coverUrl: newPhoto.url })).data;
      }
      setForm(finalItem);
      // The crop result replaces the original by delete+re-add (no in-place update endpoint),
      // which lands it at a new array position — if the lightbox was open, follow it there rather
      // than leave the viewer sitting on whatever unrelated photo now occupies the old index.
      if (lightboxIndex !== null && newPhoto) {
        const newIndex = finalItem.photos?.findIndex((p) => p.id === newPhoto.id) ?? -1;
        setLightboxIndex(newIndex >= 0 ? newIndex : null);
      }
    } catch (err: unknown) {
      setError(apiError(err, 'Could not save the cropped photo'));
    } finally {
      setPhotoBusyId(null);
      setCropTarget(null);
    }
  };

  const handleCategoryChange = (cat: MediaCategory) => {
    setForm((f) => ({ ...f, category: cat, format: FORMATS_BY_CATEGORY[cat][0] }));
  };

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    setSaving(true);
    setError(null);
    try {
      const payload = { ...form, metadata: buildMetadataWithTitles() };
      if (isEdit) {
        await apiClient.updateItem(Number(id), payload);
        navigate(`/items/${id}`);
      } else {
        const res = await apiClient.createItem(payload);
        navigate(`/items/${res.data.id}`);
      }
    } catch (err: unknown) {
      setError(apiError(err, 'Save failed'));
    } finally {
      setSaving(false);
    }
  };

  const formats = FORMATS_BY_CATEGORY[form.category as MediaCategory] ?? [];

  if (loading) return <AppLayout><div className="flex justify-center py-24"><Spinner size="lg" /></div></AppLayout>;

  return (
    <AppLayout>
      <div className="p-4 sm:p-6 max-w-2xl mx-auto">
        <div className="flex items-center gap-3 mb-6">
          <Link to={isEdit ? `/items/${id}` : '/items'} className="text-gray-400 hover:text-gray-600">
            <ArrowLeft size={18} />
          </Link>
          <h1 className="text-xl font-bold text-gray-900">{isEdit ? 'Edit item' : 'Add item to catalogue'}</h1>
        </div>

        <form onSubmit={handleSubmit} className="bg-white rounded-xl border border-gray-200 p-6 space-y-4">
          {error && <p className="text-red-600 text-sm bg-red-50 px-3 py-2 rounded-lg">{error}</p>}

          <div className="grid grid-cols-2 gap-4">
            <div>
              <label className="block text-sm font-medium text-gray-700 mb-1">Category</label>
              <select
                value={form.category}
                onChange={(e) => handleCategoryChange(e.target.value as MediaCategory)}
                className="w-full border border-gray-300 rounded-lg px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-indigo-500"
              >
                {(Object.keys(CATEGORY_LABELS) as MediaCategory[]).map((c) => (
                  <option key={c} value={c}>{CATEGORY_LABELS[c]}</option>
                ))}
              </select>
            </div>
            <div>
              <label className="block text-sm font-medium text-gray-700 mb-1">Format</label>
              <select
                value={form.format}
                onChange={(e) => set('format', e.target.value)}
                className="w-full border border-gray-300 rounded-lg px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-indigo-500"
              >
                {formats.map((f) => <option key={f} value={f}>{f}</option>)}
              </select>
            </div>
          </div>

          <div>
            <label className="block text-sm font-medium text-gray-700 mb-1">Title *</label>
            <input required value={form.title ?? ''} onChange={(e) => set('title', e.target.value)}
              className="w-full border border-gray-300 rounded-lg px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-indigo-500" />
          </div>

          <div>
            <label className="block text-sm font-medium text-gray-700 mb-1">Subtitle</label>
            <input value={form.subtitle ?? ''} onChange={(e) => set('subtitle', e.target.value)}
              className="w-full border border-gray-300 rounded-lg px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-indigo-500" />
          </div>

          <div>
            <label className="block text-sm font-medium text-gray-700 mb-1">Publisher / Label / Studio</label>
            <input value={form.publisher ?? ''} onChange={(e) => set('publisher', e.target.value)}
              className="w-full border border-gray-300 rounded-lg px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-indigo-500" />
          </div>

          <div className="grid grid-cols-2 gap-4">
            <div>
              <label className="block text-sm font-medium text-gray-700 mb-1">Release year</label>
              <input type="number" min={1800} max={2100}
                value={form.releaseYear ?? ''}
                onChange={(e) => set('releaseYear', e.target.value ? Number(e.target.value) : undefined)}
                className="w-full border border-gray-300 rounded-lg px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-indigo-500" />
            </div>
            <div>
              <label className="block text-sm font-medium text-gray-700 mb-1">Barcode type</label>
              <select value={form.barcodeType ?? 'ISBN13'} onChange={(e) => set('barcodeType', e.target.value)}
                className="w-full border border-gray-300 rounded-lg px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-indigo-500">
                {['ISBN13', 'ISBN10', 'UPC', 'EAN13', 'CATALOG_NUMBER'].map((t) => <option key={t}>{t}</option>)}
              </select>
            </div>
          </div>

          <div>
            <label className="block text-sm font-medium text-gray-700 mb-1">Barcode / ISBN</label>
            <input value={form.barcode ?? ''} onChange={(e) => set('barcode', e.target.value)}
              placeholder="Leave blank if unknown"
              className="w-full border border-gray-300 rounded-lg px-3 py-2 text-sm font-mono focus:outline-none focus:ring-2 focus:ring-indigo-500" />
          </div>

          <div>
            <label className="block text-sm font-medium text-gray-700 mb-1">Photos</label>
            {!isEdit ? (
              <p className="text-sm text-gray-400 bg-gray-50 border border-gray-200 rounded-lg px-3 py-2">
                Save this item first, then you can add and manage photos.
              </p>
            ) : (
              <div className="space-y-2">
                {form.photos && form.photos.length > 0 && (
                  <div className="flex gap-2 overflow-x-auto pb-1">
                    {form.photos.map((p, i) => (
                      <PhotoThumbnail
                        key={p.id}
                        src={mediaUrl(p.url) ?? ''}
                        angle={p.angle as PhotoAngle}
                        isCover={p.url === form.coverUrl}
                        busy={photoBusyId === p.id}
                        onClick={() => setLightboxIndex(i)}
                        onAngleChange={(angle) => void handleAngleChange(p.id, angle)}
                        onCrop={() => setCropTarget(p)}
                        onSetCover={p.url === form.coverUrl ? undefined : () => handleSetCover(p.url)}
                        onDelete={() => void handleRemovePhoto(p.id)}
                        onMoveLeft={i > 0 ? () => void handleMovePhoto(i, -1) : undefined}
                        onMoveRight={i < form.photos!.length - 1 ? () => void handleMovePhoto(i, 1) : undefined}
                      />
                    ))}
                  </div>
                )}
                {lightboxIndex !== null && form.photos && (
                  <PhotoLightbox
                    photos={form.photos.map((p) => ({
                      id: p.id, src: mediaUrl(p.url) ?? '', angle: p.angle as PhotoAngle, isCover: p.url === form.coverUrl,
                    }))}
                    index={lightboxIndex}
                    onIndexChange={setLightboxIndex}
                    onClose={() => setLightboxIndex(null)}
                    onDelete={(photoId) => handleRemovePhoto(photoId as number)}
                    deletingId={photoBusyId}
                    onAngleChange={(photoId, angle) => handleAngleChange(photoId as number, angle)}
                    onSetCover={(photoId) => {
                      const p = form.photos?.find((ph) => ph.id === photoId);
                      if (p) handleSetCover(p.url);
                    }}
                    onCrop={(photoId) => {
                      const p = form.photos?.find((ph) => ph.id === photoId);
                      if (p) setCropTarget(p);
                    }}
                    onMoveLeft={(photoId) => {
                      const i = form.photos?.findIndex((ph) => ph.id === photoId) ?? -1;
                      if (i >= 0) return handleMovePhoto(i, -1);
                    }}
                    onMoveRight={(photoId) => {
                      const i = form.photos?.findIndex((ph) => ph.id === photoId) ?? -1;
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
                <div className="flex gap-2">
                  <label className="flex items-center gap-1.5 text-sm text-gray-600 border border-gray-300 rounded-lg px-3 py-1.5 cursor-pointer hover:bg-gray-50">
                    {uploading ? <Spinner size="sm" /> : <Upload size={14} />}
                    Upload from file
                    <input
                      type="file"
                      accept="image/*"
                      multiple
                      disabled={uploading}
                      onChange={(e) => { void handleUploadPhotos(e.target.files); e.target.value = ''; }}
                      className="hidden"
                    />
                  </label>
                  <Link
                    to={`/items/${id}/photos`}
                    className="flex items-center gap-1.5 text-sm text-gray-600 border border-gray-300 rounded-lg px-3 py-1.5 hover:bg-gray-50"
                  >
                    <Camera size={14} /> Open camera
                  </Link>
                </div>
              </div>
            )}
          </div>

          <div>
            <label className="block text-sm font-medium text-gray-700 mb-1">
              Other titles on this disc <span className="text-gray-400 font-normal">(optional — e.g. a triple-feature)</span>
            </label>
            <div className="space-y-2">
              {discTitles.map((t, i) => (
                <div key={i} className="flex gap-2">
                  <input
                    value={t.title}
                    onChange={(e) => updateDiscTitle(i, { title: e.target.value })}
                    placeholder="Title"
                    className="flex-1 border border-gray-300 rounded-lg px-3 py-1.5 text-sm focus:outline-none focus:ring-2 focus:ring-indigo-500"
                  />
                  <input
                    type="number"
                    value={t.year ?? ''}
                    onChange={(e) => updateDiscTitle(i, { year: e.target.value ? Number(e.target.value) : undefined })}
                    placeholder="Year"
                    className="w-20 border border-gray-300 rounded-lg px-2 py-1.5 text-sm focus:outline-none focus:ring-2 focus:ring-indigo-500"
                  />
                  <input
                    value={t.genre ?? ''}
                    onChange={(e) => updateDiscTitle(i, { genre: e.target.value || undefined })}
                    placeholder="Genre"
                    className="w-28 border border-gray-300 rounded-lg px-2 py-1.5 text-sm focus:outline-none focus:ring-2 focus:ring-indigo-500"
                  />
                  <button type="button" onClick={() => removeDiscTitle(i)} title="Remove"
                    className="text-gray-400 hover:text-red-600 px-1">
                    <X size={16} />
                  </button>
                </div>
              ))}
              <button type="button" onClick={addDiscTitle}
                className="flex items-center gap-1.5 text-sm text-indigo-600 hover:text-indigo-800">
                <Plus size={14} /> Add another title on this disc
              </button>
            </div>
          </div>

          <div>
            <label className="block text-sm font-medium text-gray-700 mb-1">Description</label>
            <textarea rows={3} value={form.description ?? ''} onChange={(e) => set('description', e.target.value)}
              className="w-full border border-gray-300 rounded-lg px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-indigo-500" />
          </div>

          <div className="flex gap-3 pt-2">
            <button type="submit" disabled={saving}
              className="bg-indigo-600 text-white px-5 py-2 rounded-lg text-sm font-medium hover:bg-indigo-700 disabled:opacity-50">
              {saving ? 'Saving…' : isEdit ? 'Save changes' : 'Add to catalogue'}
            </button>
            <Link to={isEdit ? `/items/${id}` : '/items'}
              className="px-5 py-2 rounded-lg text-sm font-medium text-gray-600 hover:bg-gray-100">
              Cancel
            </Link>
          </div>
        </form>
      </div>
    </AppLayout>
  );
}
