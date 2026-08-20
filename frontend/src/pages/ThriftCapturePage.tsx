import { useEffect, useReducer, useRef, useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import { X, ShoppingBag, Camera as CameraIcon, Sparkles, ChevronLeft, ChevronRight } from 'lucide-react';
import { AppLayout } from '../components/layout/AppLayout';
import { CameraPreview } from '../components/scanner/CameraPreview';
import { ThriftResultOverlay } from '../components/thrift/ThriftResultOverlay';
import { BboxThumbnail } from '../components/thrift/BboxThumbnail';
import { ThriftItemCard } from '../components/thrift/ThriftItemCard';
import { Spinner } from '../components/ui/Spinner';
import { useCamera } from '../hooks/useCamera';
import { useHeldItemLoop } from '../hooks/useHeldItemLoop';
import { useCollectionStore } from '../store/collectionStore';
import { apiClient } from '../api/apiClient';
import { apiError } from '../utils/apiError';
import { withSlowNotice } from '../utils/withSlowNotice';
import { mediaUrl } from '../utils/mediaUrl';
import { OWNED_STATUS_COLOR, OWNED_STATUS_LABEL } from '../types/thrift';
import type { ThriftItem } from '../types/thrift';
import type { ThriftSession, ThriftSighting } from '../types/thriftSession';

type Mode = 'SHELF' | 'HELD_ITEM';
type ShelfPhase = 'LIVE' | 'ANALYZING' | 'RESULTS';
const STILL_WORKING_MESSAGE = 'Still working — this can take a while with the current AI model…';
// Downscaled the same way bulk-scan mode's capture loop does — plenty for the vision model, a
// fraction of the upload/inference cost of a full-resolution phone photo, which matters more
// here than anywhere else: every extra second per shelf is real browsing time lost in-store.
const VISION_MAX_DIMENSION = 1024;
// A brief settle delay before grabbing the camera on mount — mirrors useHeldItemLoop's own
// handoff discipline. Switching tabs from held-item mode (or a fast re-entry into shelf mode) may
// not have fully released the camera hardware yet; "could not start video source" is Android's
// raw NotReadableError surfacing exactly that race (see CLAUDE.md's native camera section).
const CAMERA_SETTLE_MS = 400;

export function ThriftCapturePage() {
  const { sessionId } = useParams<{ sessionId: string }>();
  const navigate = useNavigate();
  const id = Number(sessionId);

  const { collections, fetchCollections } = useCollectionStore();
  const [session, setSession] = useState<ThriftSession | null>(null);
  const [mode, setMode] = useState<Mode>('SHELF');

  useEffect(() => {
    fetchCollections();
    apiClient.getThriftSession(id).then((r) => setSession(r.data)).catch(() => {});
  }, [id, fetchCollections]);

  const collectionIds = collections.map((c) => c.id);

  return (
    <AppLayout>
      <div className="flex flex-col h-full min-h-[calc(100vh-4rem)]">
        <div className="flex items-center justify-between px-4 py-3 border-b border-gray-200 bg-white">
          <div className="flex items-center gap-2 text-gray-900">
            <ShoppingBag size={18} className="text-indigo-600" />
            <span className="font-semibold text-sm">{session?.location || 'Thrifting'}</span>
          </div>
          <button onClick={() => navigate('/thrift')} className="text-gray-400 hover:text-gray-600 p-1">
            <X size={20} />
          </button>
        </div>

        <div className="flex border-b border-gray-200 bg-white">
          <button
            onClick={() => setMode('SHELF')}
            className={`flex-1 py-2.5 text-sm font-medium transition-colors ${mode === 'SHELF' ? 'text-indigo-600 border-b-2 border-indigo-600' : 'text-gray-500'}`}
          >
            Shelf mode
          </button>
          <button
            onClick={() => setMode('HELD_ITEM')}
            className={`flex-1 py-2.5 text-sm font-medium transition-colors ${mode === 'HELD_ITEM' ? 'text-indigo-600 border-b-2 border-indigo-600' : 'text-gray-500'}`}
          >
            Held item mode
          </button>
        </div>

        {mode === 'SHELF'
          ? <ShelfModePanel sessionId={id} collectionIds={collectionIds} />
          : <HeldItemModePanel sessionId={id} collectionIds={collectionIds} />}
      </div>
    </AppLayout>
  );
}

interface Shot {
  id: string;
  dataUrl: string;
  base64: string;
}

interface ShelfState {
  phase: ShelfPhase;
  shots: Shot[];
  results: ThriftSighting[];
  selectedSightingId: number | null;
  selectedPhotoIndex: number;
  errorMsg: string | null;
  slowNotice: boolean;
  // No shutter sound — just a visual flash. Kept in the reducer rather than a separate useState so
  // it composes cleanly with the other capture-driven state transitions below.
  flash: boolean;
}

type ShelfAction =
  | { type: 'ADD_SHOT'; shot: Shot }
  | { type: 'REMOVE_SHOT'; id: string }
  | { type: 'FLASH_OFF' }
  | { type: 'START_ANALYZE' }
  | { type: 'ANALYZE_SUCCESS'; results: ThriftSighting[] }
  | { type: 'ANALYZE_ERROR'; errorMsg: string }
  | { type: 'SLOW_NOTICE' }
  | { type: 'SELECT_SIGHTING'; id: number | null }
  | { type: 'SELECT_PHOTO'; index: number }
  | { type: 'SHOOT_MORE' };

const initialShelfState: ShelfState = {
  phase: 'LIVE', shots: [], results: [], selectedSightingId: null, selectedPhotoIndex: 0,
  errorMsg: null, slowNotice: false, flash: false,
};

/** Newly-touched sightings replace their prior entry (fresh score/photos) or get appended;
 * anything from an earlier analyze pass this session that wasn't touched again stays put. Always
 * re-sorted by matchScore desc so "shoot more" naturally re-ranks the combined list. */
function mergeResults(existing: ThriftSighting[], incoming: ThriftSighting[]): ThriftSighting[] {
  const byId = new Map(existing.map((s) => [s.id, s]));
  for (const s of incoming) byId.set(s.id, s);
  return [...byId.values()].sort((a, b) => (b.matchScore ?? 0) - (a.matchScore ?? 0));
}

function shelfReducer(state: ShelfState, action: ShelfAction): ShelfState {
  switch (action.type) {
    case 'ADD_SHOT':
      return { ...state, shots: [...state.shots, action.shot], flash: true };
    case 'REMOVE_SHOT':
      return { ...state, shots: state.shots.filter((s) => s.id !== action.id) };
    case 'FLASH_OFF':
      return { ...state, flash: false };
    case 'START_ANALYZE':
      return { ...state, phase: 'ANALYZING', errorMsg: null, slowNotice: false };
    case 'ANALYZE_SUCCESS':
      return { ...state, phase: 'RESULTS', results: mergeResults(state.results, action.results), shots: [] };
    case 'ANALYZE_ERROR':
      return { ...state, phase: 'LIVE', errorMsg: action.errorMsg };
    case 'SLOW_NOTICE':
      return { ...state, slowNotice: true };
    case 'SELECT_SIGHTING':
      return { ...state, selectedSightingId: action.id, selectedPhotoIndex: 0 };
    case 'SELECT_PHOTO':
      return { ...state, selectedPhotoIndex: action.index };
    case 'SHOOT_MORE':
      return { ...state, phase: 'LIVE', shots: [] };
    default:
      return state;
  }
}

function ShelfModePanel({ sessionId, collectionIds }: { sessionId: number; collectionIds: number[] }) {
  const videoRef = useRef<HTMLVideoElement>(null);
  const { ready: cameraReady, error: cameraError, start: startCamera, stop: stopCamera, captureFrame } = useCamera(videoRef);

  const [{ phase, shots, results, selectedSightingId, selectedPhotoIndex, errorMsg, slowNotice, flash }, dispatch] =
    useReducer(shelfReducer, initialShelfState);

  useEffect(() => {
    const t = setTimeout(() => startCamera('environment'), CAMERA_SETTLE_MS);
    return () => { clearTimeout(t); stopCamera(); };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const shoot = () => {
    const base64 = captureFrame(0.85, 0, VISION_MAX_DIMENSION);
    if (!base64) return;
    dispatch({ type: 'ADD_SHOT', shot: { id: crypto.randomUUID(), dataUrl: `data:image/jpeg;base64,${base64}`, base64 } });
    setTimeout(() => dispatch({ type: 'FLASH_OFF' }), 300);
  };

  const analyze = async () => {
    if (shots.length === 0) return;
    dispatch({ type: 'START_ANALYZE' });
    try {
      const res = await withSlowNotice(
        apiClient.analyzeShelf(sessionId, shots.map((s) => ({ imageBase64: s.base64, imageMimeType: 'image/jpeg' })), collectionIds),
        () => dispatch({ type: 'SLOW_NOTICE' }),
      );
      dispatch({ type: 'ANALYZE_SUCCESS', results: res.data });
    } catch (e: unknown) {
      dispatch({ type: 'ANALYZE_ERROR', errorMsg: apiError(e, 'Shelf analysis failed') });
    }
  };

  const selectedSighting = results.find((r) => r.id === selectedSightingId) ?? null;
  const selectedPhoto = selectedSighting?.photos[selectedPhotoIndex] ?? null;
  const overlayItem: ThriftItem | null = selectedSighting && selectedPhoto?.bboxW != null
    ? {
        title: selectedSighting.title,
        artistOrAuthor: selectedSighting.artistOrAuthor,
        category: selectedSighting.category,
        format: selectedSighting.format,
        bbox: { x: selectedPhoto.bboxX ?? 0, y: selectedPhoto.bboxY ?? 0, w: selectedPhoto.bboxW ?? 0.1, h: selectedPhoto.bboxH ?? 0.1 },
        confidence: selectedSighting.confidence ?? 'MEDIUM',
        ownedStatus: selectedSighting.ownedStatus,
        itemId: selectedSighting.itemId,
      }
    : null;

  return (
    <div className="flex-1 min-h-0 flex flex-col">
      {phase !== 'RESULTS' && (
        <div className="relative flex-1 min-h-0 bg-black overflow-hidden">
          <CameraPreview ref={videoRef} scanning={false} fill className="absolute inset-0 rounded-none" />

          {flash && <div className="absolute inset-0 bg-white pointer-events-none animate-[capture-flash_300ms_ease-out] z-10" />}

          {cameraError && (
            <div className="absolute inset-0 flex items-center justify-center bg-gray-900 p-6">
              <p className="text-red-400 text-sm text-center">{cameraError}</p>
            </div>
          )}

          {errorMsg && (
            <div className="absolute inset-x-4 top-4 bg-red-50 border border-red-200 rounded-xl p-3 text-sm text-red-700">
              {errorMsg}
            </div>
          )}

          {phase === 'ANALYZING' && (
            <div className="absolute inset-0 flex items-center justify-center bg-black/60">
              <div className="flex items-center gap-2 bg-black/70 rounded-full px-4 py-2 text-white text-sm">
                <Spinner size="sm" /> {slowNotice ? STILL_WORKING_MESSAGE : `Analysing ${shots.length} photo${shots.length > 1 ? 's' : ''}…`}
              </div>
            </div>
          )}

          {phase === 'LIVE' && (
            <>
              <button
                onClick={shoot}
                disabled={!cameraReady}
                title="Shoot"
                className="absolute bottom-24 left-1/2 -translate-x-1/2 w-16 h-16 rounded-full bg-white border-4 border-white/40 disabled:opacity-40 active:scale-95 transition-transform"
              />

              <div className="absolute bottom-0 left-0 right-0 bg-black/70 px-4 py-2.5">
                {shots.length > 0 && (
                  <div className="flex gap-2 overflow-x-auto pb-2 mb-1">
                    {shots.map((s) => (
                      <div key={s.id} className="relative shrink-0">
                        <img src={s.dataUrl} alt="" className="h-14 w-14 object-cover rounded-lg border-2 border-white/40" />
                        <button
                          onClick={() => dispatch({ type: 'REMOVE_SHOT', id: s.id })}
                          className="absolute -top-1.5 -right-1.5 bg-gray-900 text-white rounded-full w-4 h-4 flex items-center justify-center text-[10px] leading-none"
                        >
                          ×
                        </button>
                      </div>
                    ))}
                  </div>
                )}
                <div className="flex items-center justify-between gap-2">
                  <span className="text-white text-sm">
                    {shots.length === 0 ? 'Shoot a few shelf photos, then analyze' : `${shots.length} photo${shots.length > 1 ? 's' : ''} ready`}
                  </span>
                  {shots.length > 0 && (
                    <button
                      onClick={() => void analyze()}
                      className="flex items-center gap-1.5 bg-indigo-600 text-white rounded-full px-4 py-1.5 text-sm font-medium hover:bg-indigo-700"
                    >
                      <Sparkles size={14} /> Analyze
                    </button>
                  )}
                </div>
              </div>
            </>
          )}
        </div>
      )}

      {phase === 'RESULTS' && (
        <div className="flex-1 min-h-0 overflow-y-auto bg-gray-50 p-4">
          <div className="flex items-center justify-between mb-3">
            <p className="text-sm text-gray-600">
              {results.length} item{results.length !== 1 ? 's' : ''} found, sorted by match
            </p>
            <button
              onClick={() => dispatch({ type: 'SHOOT_MORE' })}
              className="flex items-center gap-1.5 text-sm text-indigo-600 hover:text-indigo-800"
            >
              <CameraIcon size={14} /> Shoot more
            </button>
          </div>

          {results.length === 0 ? (
            <p className="text-sm text-gray-400 text-center py-8">Nothing identified in those photos.</p>
          ) : (
            <div className="space-y-2">
              {results.map((sighting) => {
                const previewPhoto = sighting.photos[0];
                const previewBbox = previewPhoto?.bboxW != null
                  ? { x: previewPhoto.bboxX ?? 0, y: previewPhoto.bboxY ?? 0, w: previewPhoto.bboxW, h: previewPhoto.bboxH ?? 0.1 }
                  : undefined;
                return (
                  <button
                    key={sighting.id}
                    onClick={() => dispatch({ type: 'SELECT_SIGHTING', id: sighting.id })}
                    className="w-full flex items-center gap-3 bg-white border border-gray-200 rounded-xl p-3 text-left hover:bg-gray-50"
                  >
                    <div className="w-14 h-14 rounded-lg bg-gray-100 shrink-0 overflow-hidden">
                      {previewPhoto && (
                        <BboxThumbnail
                          photoUrl={mediaUrl(previewPhoto.url) ?? ''}
                          bbox={previewBbox}
                          className="w-full h-full"
                        />
                      )}
                    </div>
                    <span className="w-2.5 h-2.5 rounded-full shrink-0" style={{ backgroundColor: OWNED_STATUS_COLOR[sighting.ownedStatus] }} />
                    <div className="flex-1 min-w-0">
                      <p className="font-medium text-gray-900 truncate">{sighting.title}</p>
                      <p className="text-xs text-gray-500">
                        {OWNED_STATUS_LABEL[sighting.ownedStatus]}
                        {sighting.photos.length > 1 ? ` · seen in ${sighting.photos.length} photos` : ''}
                      </p>
                    </div>
                  </button>
                );
              })}
            </div>
          )}
        </div>
      )}

      {selectedSighting && (
        <div className="fixed inset-0 z-40 bg-black/90 flex flex-col">
          <div className="flex items-center justify-between px-4 py-3 shrink-0">
            <span className="text-white/80 text-sm truncate">{selectedSighting.title}</span>
            <button onClick={() => dispatch({ type: 'SELECT_SIGHTING', id: null })} className="text-white/70 hover:text-white p-1 shrink-0">
              <X size={20} />
            </button>
          </div>

          <div className="flex-1 min-h-0 relative flex items-center justify-center">
            {selectedPhoto ? (
              overlayItem ? (
                <ThriftResultOverlay imageDataUrl={mediaUrl(selectedPhoto.url) ?? ''} items={[overlayItem]} selectedIndex={0} onSelect={() => {}} />
              ) : (
                <img src={mediaUrl(selectedPhoto.url)} alt="" className="max-w-full max-h-full object-contain" />
              )
            ) : (
              <p className="text-white/60 text-sm">No photo evidence for this sighting.</p>
            )}

            {selectedSighting.photos.length > 1 && (
              <>
                <button
                  onClick={() => dispatch({ type: 'SELECT_PHOTO', index: (selectedPhotoIndex - 1 + selectedSighting.photos.length) % selectedSighting.photos.length })}
                  className="absolute left-2 top-1/2 -translate-y-1/2 bg-black/50 text-white rounded-full p-2"
                >
                  <ChevronLeft size={20} />
                </button>
                <button
                  onClick={() => dispatch({ type: 'SELECT_PHOTO', index: (selectedPhotoIndex + 1) % selectedSighting.photos.length })}
                  className="absolute right-2 top-1/2 -translate-y-1/2 bg-black/50 text-white rounded-full p-2"
                >
                  <ChevronRight size={20} />
                </button>
                <div className="absolute bottom-2 left-0 right-0 text-center text-white/70 text-xs">
                  {selectedPhotoIndex + 1} / {selectedSighting.photos.length}
                </div>
              </>
            )}
          </div>

          <ThriftItemCard item={selectedSighting} onClose={() => dispatch({ type: 'SELECT_SIGHTING', id: null })} />
        </div>
      )}
    </div>
  );
}

function HeldItemModePanel({ sessionId, collectionIds }: { sessionId: number; collectionIds: number[] }) {
  const {
    videoRef, cameraError, isNative, moduleStatus, phase, statusMessage, result, flash, moveOn, identifyWithoutBarcode,
  } = useHeldItemLoop(sessionId, collectionIds);

  // On native, the ML Kit feed shows through (transparent hole) except during the brief
  // getUserMedia handoff a barcode-confirmation/OCR capture needs — see capturePhoto() in
  // useHeldItemLoop. On web there's nothing to be transparent to, so it's always opaque there.
  const transparent = isNative && phase !== 'IDENTIFYING';

  return (
    <div className="p-4 max-w-lg mx-auto w-full space-y-3">
      <div className="relative">
        <CameraPreview
          ref={videoRef}
          scanning={phase === 'IDLE' || phase === 'IDENTIFYING'}
          transparent={transparent}
          className="aspect-video"
        />
        {flash && <div className="absolute inset-0 bg-white pointer-events-none animate-[capture-flash_300ms_ease-out] rounded-xl" />}
      </div>

      {cameraError && (
        <div className="bg-red-50 border border-red-200 rounded-xl p-3 text-sm text-red-700">
          Camera error: {cameraError}
        </div>
      )}

      {(moduleStatus === 'checking' || moduleStatus === 'installing') && (
        <div className="flex items-center gap-2 bg-amber-50 border border-amber-200 rounded-xl p-3 text-sm text-amber-700">
          <Spinner size="sm" />
          Preparing barcode scanner (one-time download)…
        </div>
      )}
      {moduleStatus === 'unavailable' && (
        <div className="bg-red-50 border border-red-200 rounded-xl p-3 text-sm text-red-700">
          Couldn't set up the barcode scanner on this device.
        </div>
      )}

      <div className="flex items-start gap-2 bg-gray-100 rounded-xl px-4 py-2.5">
        {(phase === 'IDENTIFYING' || phase === 'CONFIRMING' || phase === 'CLASSIFYING') && <Spinner size="sm" />}
        <span className="text-sm text-gray-700">{statusMessage}</span>
      </div>

      {/* Native has no continuous presence signal to auto-fire OCR from (see useHeldItemLoop) —
          an item with no readable barcode needs an explicit way in instead of the web's automatic
          presence+timer trigger. */}
      {isNative && phase === 'IDLE' && (
        <button
          onClick={identifyWithoutBarcode}
          className="w-full border border-gray-300 text-gray-700 rounded-lg py-2 text-sm font-medium hover:bg-gray-50 transition-colors"
        >
          Can't read a barcode — tap to identify
        </button>
      )}

      {phase === 'RESULT' && result && (
        <ThriftItemCard item={result} onClose={moveOn} />
      )}
    </div>
  );
}
