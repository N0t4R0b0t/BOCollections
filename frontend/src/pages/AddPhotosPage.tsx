import { Fragment, useCallback, useEffect, useRef, useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import { X, Camera as CameraIcon, RotateCw, Sparkles, ImagePlus, Check, Crop } from 'lucide-react';
import { AppLayout } from '../components/layout/AppLayout';
import { CameraPreview } from '../components/scanner/CameraPreview';
import { Spinner } from '../components/ui/Spinner';
import { PhotoLightbox } from '../components/ui/PhotoLightbox';
import { PhotoCropModal } from '../components/ui/PhotoCropModal';
import { PhotoThumbnail } from '../components/ui/PhotoThumbnail';
import { useCamera } from '../hooks/useCamera';
import { apiClient } from '../api/apiClient';
import { apiError } from '../utils/apiError';
import { mediaUrl } from '../utils/mediaUrl';
import { useScanSessionStore } from '../store/scanSessionStore';
import { useThriftSessionStore } from '../store/thriftSessionStore';
import { extraFieldRows, parseExtraMetadata } from '../utils/extraMetadata';
import type { Rotation } from '../utils/drawRotatedFrame';
import { ANGLE_LABEL, TAGGABLE_ANGLES } from '../types/scanSession';
import type { PhotoAngle, ScanDraftInput } from '../types/scanSession';
import type { ThriftSightingUpdateInput } from '../types/thriftSession';
import type { Item } from '../types';
import type { ExtractResponse } from '../types/scan';

interface GalleryPhoto { id: number; url: string; angle?: string; }
interface PendingPhoto { id: string; data: string; angle: PhotoAngle; }

/** Shared entity shape between a still-pending draft and an already-saved item — just enough for
 * this screen to render a gallery, accept edits, and apply a re-extraction suggestion, without
 * needing to know which one it's actually looking at beyond the callbacks below. */
interface Target {
  title: string;
  category?: string;
  photos: GalleryPhoto[];
  metadata?: string;
}

/** Lets a reviewer go back into the camera for an item still under review (a scan draft), already
 * saved to the catalogue, or a thrift sighting from a past trip — add more shots, or re-run AI
 * vision against everything gathered so far. Doesn't touch the native ML Kit barcode scanner at
 * all (this route never runs concurrently with it — see ScanCapturePage), so a plain getUserMedia
 * preview is safe here with none of that screen's camera-hardware-contention concerns. */
export function AddPhotosPage() {
  const { sessionId, draftId, itemId, sightingId } = useParams<{
    sessionId?: string; draftId?: string; itemId?: string; sightingId?: string;
  }>();
  const navigate = useNavigate();
  const isDraftMode = sessionId != null && draftId != null;
  const isSightingMode = sessionId != null && sightingId != null;
  const sid = Number(sessionId);
  const did = Number(draftId);
  const iid = Number(itemId);
  const sgid = Number(sightingId);

  const { drafts, fetchDrafts, addDraftPhotos, deleteDraftPhoto, updateDraftPhotoAngle, reextractDraft, updateDraft } = useScanSessionStore();
  const draft = isDraftMode ? drafts.find((d) => d.id === did) : undefined;
  const { sightingsBySession, fetchSightings } = useThriftSessionStore();
  const sighting = isSightingMode ? sightingsBySession[sid]?.find((s) => s.id === sgid) : undefined;
  const [item, setItem] = useState<Item | null>(null);
  const [loadError, setLoadError] = useState<string | null>(null);

  useEffect(() => {
    if (isDraftMode) {
      fetchDrafts(sid);
    } else if (isSightingMode) {
      fetchSightings(sid);
    } else {
      apiClient.getItem(iid).then((r) => setItem(r.data)).catch((e: unknown) => setLoadError(apiError(e, 'Could not load this item')));
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [isDraftMode, isSightingMode, sid, iid]);

  const target: Target | null = isDraftMode
    ? (draft ? { title: draft.title || 'Untitled draft', category: draft.category, photos: draft.photos, metadata: draft.metadata } : null)
    : isSightingMode
    ? (sighting ? { title: sighting.title, category: sighting.category, photos: sighting.photos, metadata: undefined } : null)
    : (item ? { title: item.title, category: item.category, photos: item.photos ?? [], metadata: item.metadata } : null);

  const backHref = isDraftMode ? `/scan/${sid}/review` : isSightingMode ? '/thrift' : `/items/${iid}`;

  // --- Camera ---
  const videoRef = useRef<HTMLVideoElement>(null);
  const { ready: cameraReady, error: cameraError, start: startCamera, stop: stopCamera, captureFrame } = useCamera(videoRef);
  const [rotation, setRotation] = useState<Rotation>(0);
  const cycleRotation = useCallback(() => setRotation((r) => ((r + 90) % 360) as Rotation), []);

  useEffect(() => {
    startCamera('environment');
    return () => stopCamera();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const [pending, setPending] = useState<PendingPhoto[]>([]);
  // No maxDimension — these become permanent gallery photos (draft/item/sighting), not a
  // vision-model input directly; re-analysis (analyze() below) is a separate backend-driven
  // reextract over whatever's already in storage, not a synchronous upload of this exact frame.
  const capture = () => {
    const frame = captureFrame(0.92, rotation);
    if (frame) setPending((prev) => [...prev, { id: crypto.randomUUID(), data: frame, angle: TAGGABLE_ANGLES[prev.length % TAGGABLE_ANGLES.length] }]);
  };
  const removePending = (id: string) => setPending((prev) => prev.filter((p) => p.id !== id));
  const setPendingAngle = (id: string, angle: PhotoAngle) =>
    setPending((prev) => prev.map((p) => (p.id === id ? { ...p, angle } : p)));
  const setPendingData = (id: string, data: string) =>
    setPending((prev) => prev.map((p) => (p.id === id ? { ...p, data } : p)));
  const [croppingId, setCroppingId] = useState<string | null>(null);

  // --- Save / delete gallery photos ---
  const [saving, setSaving] = useState(false);
  const [statusMessage, setStatusMessage] = useState<string | null>(null);
  const [deletingId, setDeletingId] = useState<number | null>(null);
  const [pendingLightboxIndex, setPendingLightboxIndex] = useState<number | null>(null);
  const [galleryLightboxIndex, setGalleryLightboxIndex] = useState<number | null>(null);

  const savePending = async () => {
    if (pending.length === 0) return;
    setSaving(true);
    setStatusMessage(null);
    try {
      if (isDraftMode) {
        const input = pending.map((p) => ({ imageBase64: p.data, imageMimeType: 'image/jpeg', angle: p.angle }));
        await addDraftPhotos(sid, did, input);
      } else if (isSightingMode) {
        const input = pending.map((p) => ({ imageBase64: p.data, imageMimeType: 'image/jpeg' }));
        await apiClient.addThriftSightingPhotos(sid, sgid, input);
        await fetchSightings(sid);
      } else {
        const input = pending.map((p) => ({ imageBase64: p.data, imageMimeType: 'image/jpeg', angle: p.angle }));
        const res = await apiClient.addItemPhotos(iid, input);
        setItem(res.data);
      }
      setStatusMessage(`Saved ${pending.length} photo${pending.length > 1 ? 's' : ''}.`);
      setPending([]);
    } catch (e) {
      setStatusMessage(apiError(e, 'Could not save those photos'));
    } finally {
      setSaving(false);
    }
  };

  const [settingCover, setSettingCover] = useState(false);
  // Only meaningful for an already-saved item — a draft's cover is picked from the Review Drafts
  // page instead (see ScanReviewPage), and a thrift sighting has no coverUrl concept at all.
  const handleSetCover = async (url: string) => {
    if (isDraftMode || isSightingMode || !item || url === item.coverUrl) return;
    setSettingCover(true);
    try {
      const res = await apiClient.patchItem(iid, { coverUrl: url });
      setItem(res.data);
    } finally {
      setSettingCover(false);
    }
  };

  const removeGalleryPhoto = async (photoId: number) => {
    setDeletingId(photoId);
    try {
      if (isDraftMode) {
        await deleteDraftPhoto(sid, did, photoId);
      } else if (isSightingMode) {
        await apiClient.deleteThriftSightingPhoto(sid, sgid, photoId);
        await fetchSightings(sid);
      } else {
        const res = await apiClient.deleteItemPhoto(iid, photoId);
        setItem(res.data);
      }
    } finally {
      setDeletingId(null);
    }
  };

  // Thrift sighting photos have no angle taxonomy (see CLAUDE.md's "Photo galleries") — this
  // action never appears there.
  const handleGalleryAngleChange = async (photoId: number, angle: PhotoAngle) => {
    setDeletingId(photoId);
    try {
      if (isDraftMode) {
        await updateDraftPhotoAngle(sid, did, photoId, angle);
      } else {
        const res = await apiClient.updateItemPhotoAngle(iid, photoId, angle);
        setItem(res.data);
      }
    } finally {
      setDeletingId(null);
    }
  };

  // Sighting photos have no sort_order/reorder endpoint (same scope as the angle taxonomy — see
  // CLAUDE.md's "Photo galleries") so this only ever runs for draft/item mode.
  const handleMoveGalleryPhoto = async (index: number, direction: -1 | 1) => {
    const photos = target?.photos;
    if (!photos) return;
    const newIndex = index + direction;
    if (newIndex < 0 || newIndex >= photos.length) return;
    const reordered = [...photos];
    [reordered[index], reordered[newIndex]] = [reordered[newIndex], reordered[index]];
    const photoIds = reordered.map((p) => p.id);
    setDeletingId(photos[index].id);
    try {
      if (isDraftMode) {
        await apiClient.reorderScanDraftPhotos(sid, did, photoIds);
        await fetchDrafts(sid);
      } else {
        const res = await apiClient.reorderItemPhotos(iid, photoIds);
        setItem(res.data);
      }
    } finally {
      setDeletingId(null);
    }
  };

  const [savedCropTarget, setSavedCropTarget] = useState<{ id: number; url: string; angle?: string } | null>(null);
  // Same "no in-place replace" constraint as ItemFormPage's equivalent — upload the crop result
  // as a new photo, delete the original, re-point coverUrl immediately if it was the cover.
  const handleSavedCropped = async (base64: string) => {
    if (!savedCropTarget) return;
    const wasCover = !isDraftMode && !isSightingMode && item != null && savedCropTarget.url === item.coverUrl;
    setDeletingId(savedCropTarget.id);
    // The crop result replaces the original by delete+re-add (no in-place update endpoint),
    // which lands it at a new array position — if the lightbox was open, follow it there rather
    // than leave the viewer sitting on whatever unrelated photo now occupies the old index.
    const followInLightbox = galleryLightboxIndex !== null;
    try {
      let newPhotoId: number | undefined;
      let finalPhotos: { id: number }[] | undefined;

      if (isDraftMode) {
        const added = await addDraftPhotos(sid, did, [{ imageBase64: base64, imageMimeType: 'image/jpeg', angle: (savedCropTarget.angle as PhotoAngle) ?? 'FRONT' }]);
        newPhotoId = added.photos.at(-1)?.id;
        await deleteDraftPhoto(sid, did, savedCropTarget.id);
        finalPhotos = useScanSessionStore.getState().drafts.find((d) => d.id === did)?.photos;
      } else if (isSightingMode) {
        const added = await apiClient.addThriftSightingPhotos(sid, sgid, [{ imageBase64: base64, imageMimeType: 'image/jpeg' }]);
        newPhotoId = added.data.photos.at(-1)?.id;
        await apiClient.deleteThriftSightingPhoto(sid, sgid, savedCropTarget.id);
        await fetchSightings(sid);
        finalPhotos = useThriftSessionStore.getState().sightingsBySession[sid]?.find((s) => s.id === sgid)?.photos;
      } else {
        const added = await apiClient.addItemPhotos(iid, [{ imageBase64: base64, imageMimeType: 'image/jpeg', angle: (savedCropTarget.angle as PhotoAngle) ?? 'FRONT' }]);
        newPhotoId = added.data.photos?.at(-1)?.id;
        const delRes = await apiClient.deleteItemPhoto(iid, savedCropTarget.id);
        let finalItem = delRes.data;
        const justAddedUrl = added.data.photos?.at(-1)?.url;
        if (wasCover && justAddedUrl) {
          finalItem = (await apiClient.patchItem(iid, { coverUrl: justAddedUrl })).data;
        }
        setItem(finalItem);
        finalPhotos = finalItem.photos;
      }

      if (followInLightbox && newPhotoId != null && finalPhotos) {
        const newIndex = finalPhotos.findIndex((p) => p.id === newPhotoId);
        setGalleryLightboxIndex(newIndex >= 0 ? newIndex : null);
      }
    } catch (e) {
      setStatusMessage(apiError(e, 'Could not save the cropped photo'));
    } finally {
      setDeletingId(null);
      setSavedCropTarget(null);
    }
  };

  // --- Re-run AI vision ---
  const [hint, setHint] = useState('');
  const [analyzing, setAnalyzing] = useState(false);
  const [suggestion, setSuggestion] = useState<ExtractResponse | null>(null);
  const [applying, setApplying] = useState(false);

  const analyze = async () => {
    // Whatever's still only in the local capture buffer wouldn't be visible to the vision model
    // yet (it only ever looks at what's actually stored) — save it first so "Re-run AI vision"
    // always reflects everything on screen, not just what happened to be saved already.
    if (pending.length > 0) await savePending();
    setAnalyzing(true);
    setStatusMessage(null);
    setSuggestion(null);
    try {
      const result = isDraftMode ? await reextractDraft(sid, did, hint)
        : isSightingMode ? (await apiClient.reextractThriftSighting(sid, sgid, hint)).data
        : (await apiClient.reextractItem(iid, hint)).data;
      setSuggestion(result);
    } catch (e) {
      setStatusMessage(apiError(e, 'Could not run AI vision'));
    } finally {
      setAnalyzing(false);
    }
  };

  const applySuggestion = async () => {
    if (!suggestion || !target) return;
    setApplying(true);
    try {
      if (isSightingMode) {
        // ThriftSighting has no subtitle/description/metadata fields — only the ones a sighting
        // actually tracks are patchable here.
        const sightingPatch: ThriftSightingUpdateInput = {
          ...(suggestion.category ? { category: suggestion.category } : {}),
          ...(suggestion.format ? { format: suggestion.format } : {}),
          ...(suggestion.title ? { title: suggestion.title } : {}),
          ...(suggestion.publisher ? { publisher: suggestion.publisher } : {}),
          ...(suggestion.releaseYear ? { releaseYear: suggestion.releaseYear } : {}),
        };
        await apiClient.updateThriftSighting(sid, sgid, sightingPatch);
        await fetchSightings(sid);
      } else {
        const mergedMetadata = suggestion.metadata
          ? JSON.stringify({ ...(parseExtraMetadata(target.metadata) ?? {}), ...(parseExtraMetadata(suggestion.metadata) ?? {}) })
          : undefined;
        // Vision-read barcodes are unreliable (small models routinely misread digits) — never let
        // a re-extraction suggestion overwrite whatever the barcode source already established.
        const patch: Partial<ScanDraftInput> & Partial<Item> = {
          ...(suggestion.category ? { category: suggestion.category } : {}),
          ...(suggestion.format ? { format: suggestion.format } : {}),
          ...(suggestion.title ? { title: suggestion.title } : {}),
          ...(suggestion.subtitle ? { subtitle: suggestion.subtitle } : {}),
          ...(suggestion.description ? { description: suggestion.description } : {}),
          ...(suggestion.publisher ? { publisher: suggestion.publisher } : {}),
          ...(suggestion.releaseYear ? { releaseYear: suggestion.releaseYear } : {}),
          ...(mergedMetadata ? { metadata: mergedMetadata } : {}),
        };
        if (isDraftMode) {
          await updateDraft(sid, did, patch);
        } else {
          // patchItem (not updateItem/PUT) — PUT replaces the whole item, and this patch
          // deliberately omits fields like barcode, so a PUT here would silently null them out.
          const res = await apiClient.patchItem(iid, patch);
          setItem(res.data);
        }
      }
      setSuggestion(null);
      setStatusMessage('Applied.');
    } catch (e) {
      setStatusMessage(apiError(e, 'Could not apply those changes'));
    } finally {
      setApplying(false);
    }
  };

  const suggestionRows = suggestion ? extraFieldRows(suggestion.metadata) : [];
  const suggestionHasCoreFields = !!suggestion && [
    suggestion.title, suggestion.subtitle, suggestion.description, suggestion.publisher,
    suggestion.releaseYear, suggestion.category, suggestion.format,
  ].some((v) => v !== undefined && v !== null && v !== '');

  if (loadError) {
    return <AppLayout><div className="p-6 text-gray-500">{loadError}</div></AppLayout>;
  }

  return (
    <AppLayout>
      <div className="p-4 max-w-lg mx-auto space-y-3">
        <div className="flex items-center justify-between">
          <div className="min-w-0">
            <h1 className="text-lg font-bold text-gray-900 truncate">{target ? `Add photos — ${target.title}` : 'Add photos'}</h1>
            <p className="text-xs text-gray-500">
              {isDraftMode ? 'Still under review' : isSightingMode ? 'Thrift trip sighting' : 'Already in your catalogue'}
            </p>
          </div>
          <button onClick={() => navigate(backHref)} className="text-gray-400 hover:text-gray-600 p-1 shrink-0">
            <X size={20} />
          </button>
        </div>

        {!target && !loadError && <div className="flex justify-center py-16"><Spinner /></div>}

        {target && (
          <div className="space-y-3">
            {cameraError && (
              <div className="bg-red-50 border border-red-200 rounded-xl p-3 text-sm text-red-700">Camera error: {cameraError}</div>
            )}

            <div className="relative">
              <CameraPreview ref={videoRef} scanning={false} rotation={rotation} />
              <button
                onClick={cycleRotation}
                title="Rotate to match how you're holding the phone"
                className="absolute top-2 right-2 bg-black/50 text-white rounded-full p-2 hover:bg-black/70 transition-colors"
              >
                <RotateCw size={18} />
              </button>
            </div>

            <button
              onClick={capture}
              disabled={!cameraReady}
              className="w-full flex items-center justify-center gap-2 bg-gray-900 text-white rounded-xl py-3 font-medium hover:bg-gray-800 disabled:opacity-40 transition-colors"
            >
              <CameraIcon size={18} /> Capture
            </button>

            {pending.length > 0 && (
              <div className="space-y-2">
                <div className="flex gap-2 overflow-x-auto pb-1">
                  {pending.map((p, i) => (
                    <div key={p.id} className="relative shrink-0 space-y-1">
                      <div className="relative">
                        <img
                          src={`data:image/jpeg;base64,${p.data}`}
                          alt={`Capture ${i + 1}`}
                          onClick={() => setPendingLightboxIndex(i)}
                          className="h-16 w-auto rounded-lg border-2 border-gray-300 cursor-zoom-in"
                        />
                        <button
                          onClick={() => removePending(p.id)}
                          title="Remove"
                          className="absolute -top-1.5 -right-1.5 bg-gray-900 text-white rounded-full w-4 h-4 flex items-center justify-center text-[10px] leading-none"
                        >
                          ×
                        </button>
                        <button
                          onClick={() => setCroppingId(p.id)}
                          title="Crop this photo"
                          className="absolute bottom-0.5 right-0.5 bg-black/60 text-white rounded p-0.5"
                        >
                          <Crop size={10} />
                        </button>
                      </div>
                      <select
                        value={p.angle}
                        onChange={(e) => setPendingAngle(p.id, e.target.value as PhotoAngle)}
                        className="w-full text-[10px] border border-gray-200 rounded px-0.5 py-0.5 bg-white text-gray-600"
                      >
                        {TAGGABLE_ANGLES.map((a) => <option key={a} value={a}>{ANGLE_LABEL[a]}</option>)}
                      </select>
                    </div>
                  ))}
                </div>
                <button
                  onClick={savePending}
                  disabled={saving}
                  className="w-full flex items-center justify-center gap-2 bg-indigo-600 text-white rounded-xl py-2.5 text-sm font-medium hover:bg-indigo-700 disabled:opacity-50"
                >
                  {saving ? <Spinner size="sm" /> : <ImagePlus size={16} />} Save {pending.length} photo{pending.length > 1 ? 's' : ''}
                </button>
              </div>
            )}

            {pendingLightboxIndex !== null && (
              <PhotoLightbox
                photos={pending.map((p, i) => ({ id: p.id, src: `data:image/jpeg;base64,${p.data}`, label: `Capture ${i + 1}`, angle: p.angle }))}
                index={pendingLightboxIndex}
                onIndexChange={setPendingLightboxIndex}
                onClose={() => setPendingLightboxIndex(null)}
                onDelete={(id) => removePending(id as string)}
                onAngleChange={(id, angle) => setPendingAngle(id as string, angle)}
                onCrop={(id) => setCroppingId(id as string)}
              />
            )}

            {croppingId && (
              <PhotoCropModal
                src={`data:image/jpeg;base64,${pending.find((p) => p.id === croppingId)?.data ?? ''}`}
                onCancel={() => setCroppingId(null)}
                onCropped={(data) => { setPendingData(croppingId, data); setCroppingId(null); }}
              />
            )}

            {statusMessage && <p className="text-sm text-gray-600">{statusMessage}</p>}

            {target.photos.length > 0 && (
              <div className="border-t border-gray-100 pt-3">
                <p className="text-xs font-medium text-gray-500 mb-2">Already saved</p>
                <div className="flex gap-2 overflow-x-auto pb-1">
                  {target.photos.map((p, i) => {
                    const isCover = !isDraftMode && !isSightingMode && item != null && p.url === item.coverUrl;
                    return (
                      <PhotoThumbnail
                        key={p.id}
                        src={mediaUrl(p.url) ?? ''}
                        angle={!isSightingMode ? (p.angle as PhotoAngle | undefined) : undefined}
                        isCover={isCover}
                        busy={deletingId === p.id || settingCover}
                        onClick={() => setGalleryLightboxIndex(i)}
                        onAngleChange={isSightingMode ? undefined : (angle) => void handleGalleryAngleChange(p.id, angle)}
                        onCrop={() => setSavedCropTarget({ id: p.id, url: p.url, angle: p.angle })}
                        onSetCover={!isDraftMode && !isSightingMode && !isCover ? () => handleSetCover(p.url) : undefined}
                        onDelete={() => void removeGalleryPhoto(p.id)}
                        onMoveLeft={!isSightingMode && i > 0 ? () => void handleMoveGalleryPhoto(i, -1) : undefined}
                        onMoveRight={!isSightingMode && i < target.photos.length - 1 ? () => void handleMoveGalleryPhoto(i, 1) : undefined}
                      />
                    );
                  })}
                </div>

                {galleryLightboxIndex !== null && (
                  <PhotoLightbox
                    photos={target.photos.map((p) => ({
                      id: p.id,
                      src: mediaUrl(p.url) ?? '',
                      label: p.angle ? (ANGLE_LABEL[p.angle as PhotoAngle] ?? p.angle) : undefined,
                      angle: !isSightingMode ? (p.angle as PhotoAngle | undefined) : undefined,
                      isCover: !isDraftMode && !isSightingMode && item != null && p.url === item.coverUrl,
                    }))}
                    index={galleryLightboxIndex}
                    onIndexChange={setGalleryLightboxIndex}
                    onClose={() => setGalleryLightboxIndex(null)}
                    onDelete={(id) => removeGalleryPhoto(id as number)}
                    deletingId={deletingId}
                    onAngleChange={isSightingMode ? undefined : (id, angle) => handleGalleryAngleChange(id as number, angle)}
                    onSetCover={!isDraftMode && !isSightingMode ? (id) => {
                      const p = target.photos.find((ph) => ph.id === id);
                      if (p) void handleSetCover(p.url);
                    } : undefined}
                    onCrop={(id) => {
                      const p = target.photos.find((ph) => ph.id === id);
                      if (p) setSavedCropTarget({ id: p.id, url: p.url, angle: p.angle });
                    }}
                    onMoveLeft={isSightingMode ? undefined : (id) => {
                      const i = target.photos.findIndex((ph) => ph.id === id);
                      if (i >= 0) return handleMoveGalleryPhoto(i, -1);
                    }}
                    onMoveRight={isSightingMode ? undefined : (id) => {
                      const i = target.photos.findIndex((ph) => ph.id === id);
                      if (i >= 0) return handleMoveGalleryPhoto(i, 1);
                    }}
                  />
                )}
                {savedCropTarget && (
                  <PhotoCropModal
                    src={mediaUrl(savedCropTarget.url) ?? ''}
                    onCancel={() => setSavedCropTarget(null)}
                    onCropped={(base64) => void handleSavedCropped(base64)}
                  />
                )}
              </div>
            )}

            <div className="border-t border-gray-100 pt-3 space-y-2">
              <input
                value={hint}
                onChange={(e) => setHint(e.target.value)}
                placeholder="Optional hint for analysis (e.g. first edition)…"
                className="w-full border border-gray-300 rounded-lg px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-indigo-500"
              />
              <button
                onClick={analyze}
                disabled={analyzing || target.photos.length + pending.length === 0}
                className="w-full flex items-center justify-center gap-2 bg-indigo-600 text-white rounded-xl py-2.5 text-sm font-medium hover:bg-indigo-700 disabled:opacity-40"
              >
                {analyzing ? <Spinner size="sm" /> : <Sparkles size={16} />} Re-run AI vision
              </button>
            </div>

            {suggestion && (
              <div className="border border-indigo-200 bg-indigo-50 rounded-xl p-3 space-y-2">
                {!suggestion.visionAvailable ? (
                  <p className="text-sm text-indigo-800">{suggestion.notes || 'Vision is unavailable right now.'}</p>
                ) : !suggestionHasCoreFields && suggestionRows.length === 0 ? (
                  <p className="text-sm text-indigo-800">Vision didn't find anything new to suggest.</p>
                ) : (
                  <>
                    <p className="text-xs font-medium text-indigo-700">Suggested update</p>
                    <dl className="grid grid-cols-2 gap-x-4 gap-y-1 text-xs">
                      {suggestion.title && <><dt className="text-indigo-400">Title</dt><dd className="text-indigo-900">{suggestion.title}</dd></>}
                      {suggestion.subtitle && <><dt className="text-indigo-400">Subtitle</dt><dd className="text-indigo-900">{suggestion.subtitle}</dd></>}
                      {suggestion.format && <><dt className="text-indigo-400">Format</dt><dd className="text-indigo-900">{suggestion.format}</dd></>}
                      {suggestion.publisher && <><dt className="text-indigo-400">Publisher</dt><dd className="text-indigo-900">{suggestion.publisher}</dd></>}
                      {suggestion.releaseYear && <><dt className="text-indigo-400">Year</dt><dd className="text-indigo-900">{suggestion.releaseYear}</dd></>}
                      {suggestion.description && <><dt className="text-indigo-400">Description</dt><dd className="text-indigo-900 col-span-1">{suggestion.description}</dd></>}
                      {suggestionRows.map(({ key, label, render, value }) => (
                        <Fragment key={key}>
                          <dt className="text-indigo-400">{label}</dt>
                          <dd className="text-indigo-900">{render(value)}</dd>
                        </Fragment>
                      ))}
                    </dl>
                    {suggestion.notes && <p className="text-xs text-indigo-600 italic">{suggestion.notes}</p>}
                    <div className="flex gap-2 pt-1">
                      <button
                        onClick={applySuggestion}
                        disabled={applying}
                        className="flex-1 flex items-center justify-center gap-1 bg-indigo-600 text-white rounded-lg py-1.5 text-sm font-medium hover:bg-indigo-700 disabled:opacity-50"
                      >
                        {applying ? <Spinner size="sm" /> : <Check size={14} />} Apply
                      </button>
                      <button onClick={() => setSuggestion(null)} className="px-3 py-1.5 text-sm text-gray-500 hover:bg-gray-50 rounded-lg">
                        Discard
                      </button>
                    </div>
                  </>
                )}
              </div>
            )}
          </div>
        )}
      </div>
    </AppLayout>
  );
}
