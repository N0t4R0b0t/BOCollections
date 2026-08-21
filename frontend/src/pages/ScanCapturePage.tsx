import { useEffect, useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import { App as CapacitorApp } from '@capacitor/app';
import { X, ScanLine, Camera, Sparkles, Undo2, ArrowRight, RotateCw, ChevronUp, ChevronDown, Crop, SunMedium, Flashlight, FlashlightOff } from 'lucide-react';
import { AppLayout } from '../components/layout/AppLayout';
import { CameraPreview } from '../components/scanner/CameraPreview';
import { Spinner } from '../components/ui/Spinner';
import { PhotoLightbox } from '../components/ui/PhotoLightbox';
import { PhotoCropModal } from '../components/ui/PhotoCropModal';
import { useCaptureLoop, type CapturedPhoto, type Finding, type FindingData } from '../hooks/useCaptureLoop';
import { LOW_LIGHT_FILTER } from '../hooks/useCamera';
import { useCollectionStore } from '../store/collectionStore';
import { apiClient } from '../api/apiClient';
import { apiError } from '../utils/apiError';
import { mediaUrl } from '../utils/mediaUrl';
import type { PhotoAngle, ScanSession } from '../types/scanSession';

const SOURCE_LABEL: Record<string, string> = { BARCODE: 'Barcode', VISION: 'AI vision' };
const ANGLE_LABEL: Record<PhotoAngle, string> = { FRONT: 'Front', BACK: 'Back', SPINE: 'Spine', DISC: 'Disc', REFERENCE: 'Reference' };
const TAGGABLE_ANGLES: PhotoAngle[] = ['FRONT', 'BACK', 'SPINE', 'DISC'];

interface ControlsProps {
  statusMessage: string;
  visionBusy: boolean;
  finalizing: boolean;
  merged: FindingData;
  ownerCollectionId: number;
  findings: Finding[];
  photos: CapturedPhoto[];
  removePhoto: (id: string) => void;
  setPhotoAngle: (id: string, angle: PhotoAngle) => void;
  setPhotoData: (id: string, data: string) => void;
  rejectFinding: (id: string, rejected: boolean) => void;
  hint: string;
  setHint: (v: string) => void;
  capturePhoto: () => void;
  enterPhotoMode: () => void;
  cameraReady: boolean;
  analyzePhotos: () => void;
  canUndo: boolean;
  previous: () => void;
  next: () => void;
  isNative: boolean;
  inPhotoBurst: boolean;
  resumeScanning: () => void;
}

/** The status/best-guess/findings/hint/action-buttons block — identical content whether it's
 * floating over a full-screen native camera or stacked in the normal web page flow below a
 * boxed preview. Kept as one component so the two layouts can't drift apart. */
function ScanControls({
  statusMessage, visionBusy, finalizing, merged, ownerCollectionId, findings, photos, removePhoto, setPhotoAngle, setPhotoData,
  rejectFinding, hint, setHint, capturePhoto, enterPhotoMode, cameraReady, analyzePhotos, canUndo, previous, next,
  isNative, inPhotoBurst, resumeScanning,
}: ControlsProps) {
  const [croppingId, setCroppingId] = useState<string | null>(null);
  // On native, the first tap should just open the live preview — not also take a photo before
  // you've had a chance to frame it. Once the preview's up, this button (relabeled "Capture
  // another") takes photos the same as the on-screen shutter.
  const handleCaptureButton = isNative && !inPhotoBurst ? enterPhotoMode : capturePhoto;
  const [lightboxIndex, setLightboxIndex] = useState<number | null>(null);
  const hasGuess = merged.title || merged.publisher || merged.barcode;
  // Vision-only findings never populate merged.coverUrl (that field only ever comes from an
  // external LookupResult via a barcode hit) — fall back to an actual captured photo so the
  // card doesn't show "no cover" for items with photos but no barcode match.
  const previewPhoto = photos.find((p) => p.angle === 'FRONT') ?? photos[0];
  // What a user would actually notice missing on the shelf-card view: no cover/disc image, or no
  // description to tell items apart. Publisher/year matter less — flagged but not headline.
  const missingFields = hasGuess
    ? [
        !merged.coverUrl && !previewPhoto && 'a cover/disc image',
        !merged.description && 'a description',
        !merged.releaseYear && 'a release year',
      ].filter((v): v is string => !!v)
    : [];
  return (
    <>
      <div className="flex items-start gap-2 bg-gray-100 rounded-xl px-4 py-2.5">
        {(visionBusy || finalizing) && <Spinner size="sm" />}
        <span className="text-sm text-gray-700">{statusMessage}</span>
      </div>

      {/* Live draft preview, merged from every non-rejected finding — barcode data always wins
          over vision for a field. Nothing here is saved until Next is pressed; this is what lets
          you judge, before committing, whether what the barcode/vision found is good enough or
          whether a few more cover/disc photos are worth taking first. */}
      {hasGuess && (
        <div className="border border-indigo-200 bg-indigo-50 rounded-xl p-3 space-y-2">
          <div className="flex gap-3">
            {merged.coverUrl ? (
              <img src={mediaUrl(merged.coverUrl)} alt="" className="w-14 h-14 rounded-lg object-cover border border-indigo-100 shrink-0" />
            ) : previewPhoto ? (
              <img
                src={`data:image/jpeg;base64,${previewPhoto.data}`}
                alt=""
                className="w-14 h-14 rounded-lg object-cover border border-indigo-100 shrink-0"
              />
            ) : (
              <div className="w-14 h-14 rounded-lg border border-dashed border-indigo-300 bg-white shrink-0 flex items-center justify-center text-[10px] text-indigo-300 text-center px-1">
                no cover
              </div>
            )}
            <div className="space-y-0.5 min-w-0">
              {merged.existingItemId != null && merged.ownedInCollections?.includes(ownerCollectionId) && (
                <p className="text-xs font-medium text-indigo-600">Already in this collection</p>
              )}
              <p className="font-medium text-gray-900 truncate">{merged.title ?? 'Untitled'}</p>
              {merged.subtitle && <p className="text-sm text-gray-600 truncate">{merged.subtitle}</p>}
              <p className="text-sm text-gray-500 truncate">
                {[merged.publisher, merged.releaseYear, merged.format].filter(Boolean).join(' · ')}
              </p>
              {merged.barcode && <p className="text-xs text-gray-400">Barcode: {merged.barcode}</p>}
            </div>
          </div>

          {missingFields.length > 0 && (
            <p className="text-xs text-amber-700 bg-amber-50 border border-amber-200 rounded-lg px-2 py-1.5">
              Missing {missingFields.join(', ')} — capture a photo of the cover/disc and press Analyse if you want it filled in before saving.
            </p>
          )}
        </div>
      )}

      {/* Bounded + independently scrollable so a long findings list never pushes the
          camera/action buttons off-screen. */}
      {(photos.length > 0 || findings.length > 0) && (
        <div className="max-h-48 overflow-y-auto space-y-2 border border-gray-100 rounded-xl p-2">
          {photos.length > 0 && (
            <div className="flex gap-2 overflow-x-auto pb-1">
              {photos.map((p, i) => (
                <div key={p.id} className="relative shrink-0 space-y-1">
                  <div className="relative">
                    <img
                      src={`data:image/jpeg;base64,${p.data}`}
                      alt={`Capture ${i + 1}`}
                      onClick={() => setLightboxIndex(i)}
                      className="h-16 w-auto rounded-lg border-2 border-gray-300 cursor-zoom-in"
                    />
                    <button
                      onClick={() => removePhoto(p.id)}
                      title="Remove this photo"
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
                    onChange={(e) => setPhotoAngle(p.id, e.target.value as PhotoAngle)}
                    className="w-full text-[10px] border border-gray-200 rounded px-0.5 py-0.5 bg-white text-gray-600"
                  >
                    {TAGGABLE_ANGLES.map((a) => (
                      <option key={a} value={a}>{ANGLE_LABEL[a]}</option>
                    ))}
                  </select>
                </div>
              ))}
            </div>
          )}

          {/* Every signal gathered so far — vote down anything wrong and the guess above
              recomputes immediately, no need to start over. */}
          {findings.length > 0 && (
            <ul className="space-y-1.5">
              {findings.map((f) => (
                <li
                  key={f.id}
                  className={`flex items-center justify-between gap-2 rounded-lg px-3 py-2 text-sm border ${
                    f.rejected ? 'bg-gray-50 border-gray-200 text-gray-400' : 'bg-white border-gray-200 text-gray-700'
                  }`}
                >
                  <span className={`truncate ${f.rejected ? 'line-through' : ''}`}>
                    <span className="font-medium">{SOURCE_LABEL[f.source]}:</span>{' '}
                    {f.data.title ?? f.data.barcode ?? '(no title found)'}
                    {f.confidence && <span className="text-gray-400"> · {f.confidence}</span>}
                  </span>
                  <button
                    onClick={() => rejectFinding(f.id, !f.rejected)}
                    className="text-xs px-2 py-1 rounded-md border border-gray-200 text-gray-500 hover:bg-gray-100 shrink-0"
                  >
                    {f.rejected ? 'Undo' : 'Reject'}
                  </button>
                </li>
              ))}
            </ul>
          )}
        </div>
      )}

      <input
        value={hint}
        onChange={(e) => setHint(e.target.value)}
        placeholder="Optional hint for analysis (e.g. first edition)…"
        className="w-full border border-gray-300 rounded-lg px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-indigo-500"
      />

      {/* On native, the first Capture pauses barcode scanning for the whole burst of shots
          rather than resuming it between every single photo — resuming is a real round-trip
          through the camera hardware, not worth paying per-photo when taking several in a row.
          Scanning comes back automatically on Next, or right away if tapped here. */}
      {isNative && inPhotoBurst && (
        <div className="flex items-center justify-between gap-2 bg-amber-50 border border-amber-200 rounded-xl px-3 py-2 text-xs text-amber-700">
          <span>Barcode scanning paused while taking photos</span>
          <button onClick={resumeScanning} className="font-medium underline shrink-0">Resume scanning</button>
        </div>
      )}

      {/* Take as many shots as you want first, then Analyse the whole batch together —
          more context for the AI than one frame at a time, and every photo stays attached
          to the draft regardless of what the analysis makes of them. */}
      <div className="flex gap-2">
        <button
          onClick={handleCaptureButton}
          disabled={!cameraReady}
          className="flex-1 flex items-center justify-center gap-2 bg-gray-900 text-white rounded-xl py-3 font-medium hover:bg-gray-800 disabled:opacity-40 transition-colors"
        >
          <Camera size={18} /> {inPhotoBurst ? 'Capture another' : isNative ? 'Open camera' : 'Capture'}
        </button>
        <button
          onClick={analyzePhotos}
          disabled={photos.length === 0 || visionBusy}
          className="flex-1 flex items-center justify-center gap-2 bg-indigo-600 text-white rounded-xl py-3 font-medium hover:bg-indigo-700 disabled:opacity-40 transition-colors"
        >
          <Sparkles size={18} /> Analyse
        </button>
      </div>

      <div className="flex gap-2">
        <button
          onClick={previous}
          disabled={!canUndo || finalizing}
          className="flex-1 flex items-center justify-center gap-2 border border-gray-200 rounded-xl py-2.5 text-sm font-medium text-gray-700 hover:bg-gray-50 disabled:opacity-40 transition-colors"
        >
          <Undo2 size={16} /> Previous
        </button>
        <button
          onClick={next}
          disabled={finalizing}
          className="flex-1 flex items-center justify-center gap-2 bg-indigo-600 text-white rounded-xl py-2.5 text-sm font-medium hover:bg-indigo-700 disabled:opacity-40 transition-colors"
        >
          Next <ArrowRight size={16} />
        </button>
      </div>

      {lightboxIndex !== null && (
        <PhotoLightbox
          photos={photos.map((p, i) => ({ id: p.id, src: `data:image/jpeg;base64,${p.data}`, label: `Capture ${i + 1}`, angle: p.angle }))}
          index={lightboxIndex}
          onIndexChange={setLightboxIndex}
          onClose={() => setLightboxIndex(null)}
          onDelete={(id) => removePhoto(id as string)}
          onAngleChange={(id, angle) => setPhotoAngle(id as string, angle)}
          onCrop={(id) => setCroppingId(id as string)}
        />
      )}

      {croppingId && (
        <PhotoCropModal
          src={`data:image/jpeg;base64,${photos.find((p) => p.id === croppingId)?.data ?? ''}`}
          onCancel={() => setCroppingId(null)}
          onCropped={(data) => { setPhotoData(croppingId, data); setCroppingId(null); }}
        />
      )}
    </>
  );
}

export function ScanCapturePage() {
  const { sessionId } = useParams<{ sessionId: string }>();
  const navigate = useNavigate();
  const id = Number(sessionId);

  const { collections, fetchCollections } = useCollectionStore();
  const [session, setSession] = useState<ScanSession | null>(null);
  const [loadError, setLoadError] = useState<string | null>(null);
  // Collapsed by default in landscape — the floating panel was eating most of the screen there
  // since there's so little vertical space to begin with; portrait has room to spare.
  const [drawerOpen, setDrawerOpen] = useState(() => window.innerWidth < window.innerHeight);
  const [flash, setFlash] = useState(false);

  useEffect(() => {
    fetchCollections();
    apiClient.getScanSession(id).then((r) => setSession(r.data)).catch((e: unknown) => setLoadError(apiError(e, 'Could not load this scan session')));
  }, [id, fetchCollections]);

  const collectionCategory = collections.find((c) => c.id === session?.collectionId)?.primaryCategory;

  // Without an explicit handler, the Android hardware/gesture back button falls through to
  // Capacitor's default behavior on this screen — unpredictable given the full-screen native
  // scan overlay isn't a normal part of the WebView's navigation history. Route it through the
  // same "quit scanning" exit as the X button instead of leaving it to guess.
  useEffect(() => {
    const listener = CapacitorApp.addListener('backButton', () => navigate('/scan'));
    return () => { void listener.then((l) => l.remove()); };
  }, [navigate]);

  const {
    videoRef, cameraReady, cameraError, isNative, moduleStatus, findings, merged, photos, hint, setHint, statusMessage,
    visionBusy, finalizing, canUndo, rotation, cycleRotation, refocus, refocusScanner, supportedFocusModes,
    focusDistanceRange, focusDistanceValue, setFocusDistance, lowLight, toggleLowLight,
    torchAvailable, torchOn, toggleTorch, nativeTorchAvailable, nativeTorchOn, toggleNativeTorch,
    capturePhoto, enterPhotoMode, removePhoto, setPhotoAngle, setPhotoData, analyzePhotos, rejectFinding, next, previous,
    inPhotoBurst, resumeScanning,
  } = useCaptureLoop(id, session?.collectionId ?? -1, collectionCategory);
  const [focusPulse, setFocusPulse] = useState<{ x: number; y: number } | null>(null);
  const handleNativeFocusTap = (e: React.MouseEvent<HTMLDivElement>) => {
    const rect = e.currentTarget.getBoundingClientRect();
    setFocusPulse({ x: e.clientX - rect.left, y: e.clientY - rect.top });
    setTimeout(() => setFocusPulse(null), 600);
    void refocusScanner();
  };

  // Wraps the raw capturePhoto so the flash fires no matter which control took the shot — the
  // on-screen shutter or the drawer's "Capture another" button, both of which take a photo
  // outright once already mid-burst (unlike the first tap, which only opens the live preview
  // via enterPhotoMode and takes no photo, so no flash then).
  const handleCapture = () => {
    setFlash(true);
    setTimeout(() => setFlash(false), 300);
    void capturePhoto();
  };

  // Two very different cameras exist in the wild here: some support a real autofocus sweep
  // (tap-to-focus), others only expose a manual focusDistance range (slider) — confirmed on a
  // real device that only offers the latter. Some offer neither at all.
  //
  // supportedFocusModes only ever gets populated by useCamera's getUserMedia stream, which is
  // exactly what's live both in the plain web flow *and* during a native photo burst (see
  // enterPhotoMode's startCamera call) — it stays null the rest of the time on native (the ML
  // Kit scan session is a separate hardware surface useCamera never touches, and the plugin
  // itself exposes no focus API at all — see refocusScanner's own comment, whose tap target is
  // wired directly in the native branch below instead of through canTapFocus). So these three
  // just key off supportedFocusModes rather than an explicit isNative check — they naturally
  // read as "no control" until a real getUserMedia stream with capabilities is actually up.
  const canTapFocus = supportedFocusModes?.some((m) => m === 'continuous' || m === 'single-shot') ?? false;
  const canSliderFocus = focusDistanceRange !== null;
  const noFocusControl = supportedFocusModes !== null && !canTapFocus && !canSliderFocus;

  if (loadError) {
    return (
      <AppLayout>
        <div className="p-6 text-center text-red-600">{loadError}</div>
      </AppLayout>
    );
  }

  const controlsProps: ControlsProps = {
    statusMessage, visionBusy, finalizing, merged, ownerCollectionId: session?.collectionId ?? -1,
    findings, photos, removePhoto, setPhotoAngle, setPhotoData, rejectFinding, hint, setHint, capturePhoto: handleCapture, enterPhotoMode, cameraReady,
    analyzePhotos, canUndo, previous, next, isNative, inPhotoBurst, resumeScanning,
  };

  // @capacitor-mlkit/barcode-scanning's transparent-overlay mode renders the native camera
  // *behind the entire WebView*, full screen — there's no way to clip it to a small boxed
  // preview the way the web version's getUserMedia <video> can be sized. So on native this is a
  // self-contained full-bleed overlay with its own minimal header, deliberately NOT wrapped in
  // AppLayout: AppLayout switches between a mobile header/tab-bar and a desktop sidebar based on
  // a max-width media query, which flips when the phone is rotated to landscape (landscape width
  // can exceed that breakpoint) — this page's fixed positioning would then be measured against
  // chrome that no longer exists. Standing alone sidesteps that entirely, in any orientation.
  if (isNative) {
    return (
      <div className="fixed inset-0 flex flex-col z-10" style={{ paddingTop: 'env(safe-area-inset-top, 0px)', paddingBottom: 'env(safe-area-inset-bottom, 0px)' }}>
        {/* No backdrop-blur anywhere in this screen — confirmed on a real device that layering
            it over the hardware-decoded live camera feed left the video region behind/near it
            stuck on a stale frame (a known bad interaction between CSS backdrop-filter's GPU
            readback and Android's video hardware overlay compositing), and the freeze persisted
            even after the blurred element was hidden. bg-white/90 is opaque enough on its own. */}
        <div className="flex items-center justify-between mx-3 mt-2 px-4 py-2 bg-white/90 rounded-xl shadow">
          <div className="flex items-center gap-2">
            <ScanLine size={18} className="text-indigo-600" />
            <span className="font-bold text-gray-900 text-sm">{session?.collectionName ?? 'Scanning'}</span>
          </div>
          <button onClick={() => navigate('/scan')} className="text-gray-400 hover:text-gray-600 p-1">
            <X size={18} />
          </button>
        </div>

        {cameraError && (
          <div className="mx-3 mt-2 bg-red-50 border border-red-200 rounded-xl p-3 text-sm text-red-700">
            Camera error: {cameraError}
          </div>
        )}

        {(moduleStatus === 'checking' || moduleStatus === 'installing') && (
          <div className="mx-3 mt-2 flex items-center gap-2 bg-amber-50 border border-amber-200 rounded-xl p-3 text-sm text-amber-700">
            <Spinner size="sm" />
            Preparing barcode scanner (one-time download)…
          </div>
        )}
        {moduleStatus === 'unavailable' && (
          <div className="mx-3 mt-2 bg-red-50 border border-red-200 rounded-xl p-3 text-sm text-red-700">
            Couldn't set up the barcode scanner on this device — try the Capture/Analyse photo flow instead.
          </div>
        )}

        {/* One persistent camera element for the whole screen — transparent (native ML Kit scan
            showing through) outside a photo burst, opaque (live getUserMedia feed) during one.
            Toggling `transparent` rather than swapping between two separate elements matters: see
            CameraPreview's own comment on why the <video> node has to survive the transition. */}
        <div className="flex-1 relative min-h-0">
          <CameraPreview
            ref={videoRef}
            scanning={false}
            transparent={!inPhotoBurst}
            fill
            className="absolute inset-0 rounded-none"
            filter={inPhotoBurst && lowLight ? LOW_LIGHT_FILTER : undefined}
            onFocus={inPhotoBurst && canTapFocus ? refocus : undefined}
          />

          {!inPhotoBurst && (
            <>
              {/* Tap-to-refocus for the native ML Kit view. That camera session is a separate
                  hardware surface the plugin gives us no focus API for at all (see
                  refocusScanner's comment) — restarting the scan session is the only lever
                  available, so this tap area triggers that instead of a real focus call.
                  CameraPreview itself is pointer-events-none while transparent (a deliberate fix
                  for Android routing taps to the hardware surface's stale on-screen position), so
                  the tap target has to live on this always-interactive sibling instead. */}
              <div onClick={handleNativeFocusTap} className="absolute inset-0 cursor-crosshair">
                {focusPulse && (
                  <div
                    className="absolute w-14 h-14 -ml-7 -mt-7 rounded-full border-2 border-white/80 pointer-events-none animate-ping"
                    style={{ left: focusPulse.x, top: focusPulse.y }}
                  />
                )}
              </div>
              <div className="absolute inset-0 flex items-center justify-center pointer-events-none">
                <div className="relative w-56 h-32">
                  {['top-0 left-0', 'top-0 right-0', 'bottom-0 left-0', 'bottom-0 right-0'].map((pos, i) => (
                    <span
                      key={i}
                      className={[
                        'absolute w-5 h-5 border-white/90',
                        pos,
                        i === 0 && 'border-t-2 border-l-2 rounded-tl',
                        i === 1 && 'border-t-2 border-r-2 rounded-tr',
                        i === 2 && 'border-b-2 border-l-2 rounded-bl',
                        i === 3 && 'border-b-2 border-r-2 rounded-br',
                      ].filter(Boolean).join(' ')}
                    />
                  ))}
                </div>
              </div>
            </>
          )}

          {/* Low-light boost — only has anything to visibly affect during a photo burst (real
              getUserMedia feed); tapping it beforehand just pre-arms the boost for whenever the
              burst starts, same as the native scan view has nothing for a CSS filter to touch. */}
          <button
            onClick={toggleLowLight}
            title={lowLight ? 'Low-light boost on' : 'Boost for low light'}
            className={`absolute top-2 left-2 z-10 rounded-full p-2 transition-colors ${lowLight ? 'bg-amber-400 text-black' : 'bg-black/50 text-white'}`}
          >
            <SunMedium size={18} />
          </button>

          {/* Torch/flashlight — a real hardware control, unlike the CSS-only low-light boost.
              Two different APIs depending on which camera session is live: the native ML Kit scan
              view has its own plugin method (enableTorch/toggleTorch — no getUserMedia stream
              exists to attach a MediaTrackConstraint to), while the photo-burst view is a real
              getUserMedia stream and uses the standard torch constraint via useCamera. */}
          {!inPhotoBurst && nativeTorchAvailable && (
            <button
              onClick={() => void toggleNativeTorch()}
              title={nativeTorchOn ? 'Turn off flashlight' : 'Turn on flashlight'}
              className={`absolute top-2 left-14 z-10 rounded-full p-2 transition-colors ${nativeTorchOn ? 'bg-amber-400 text-black' : 'bg-black/50 text-white'}`}
            >
              {nativeTorchOn ? <FlashlightOff size={18} /> : <Flashlight size={18} />}
            </button>
          )}
          {inPhotoBurst && torchAvailable && (
            <button
              onClick={() => void toggleTorch()}
              title={torchOn ? 'Turn off flashlight' : 'Turn on flashlight'}
              className={`absolute top-2 left-14 z-10 rounded-full p-2 transition-colors ${torchOn ? 'bg-amber-400 text-black' : 'bg-black/50 text-white'}`}
            >
              {torchOn ? <FlashlightOff size={18} /> : <Flashlight size={18} />}
            </button>
          )}

          {/* Focus controls for the photo-burst view — this is a real getUserMedia stream (unlike
              the transparent ML Kit scan view above), so the same tap/slider controls the web
              flow gets further down this file apply here too. */}
          {inPhotoBurst && canTapFocus && (
            <p className="absolute bottom-24 inset-x-0 text-center text-xs text-white/70 pointer-events-none">
              Tap the preview to focus
            </p>
          )}
          {inPhotoBurst && canSliderFocus && focusDistanceRange && (
            <div className="absolute bottom-20 inset-x-6 z-10 bg-black/50 rounded-lg px-3 py-1.5">
              <input
                type="range"
                min={focusDistanceRange.min}
                max={focusDistanceRange.max}
                step={focusDistanceRange.step}
                value={focusDistanceValue ?? (focusDistanceRange.min + focusDistanceRange.max) / 2}
                onChange={(e) => setFocusDistance(Number(e.target.value))}
                className="w-full"
              />
              <p className="text-xs text-white/70 text-center">Focus — drag while watching the preview</p>
            </div>
          )}
          {inPhotoBurst && noFocusControl && (
            <p className="absolute bottom-24 inset-x-4 text-center text-xs text-amber-300 pointer-events-none">
              This camera doesn't expose any focus control — hold the item steady at a close, well-lit distance.
            </p>
          )}

          {/* Flash — the only feedback that a shot was actually taken, since there's no shutter
              sound and the live feed itself doesn't visibly change. Pure CSS opacity fade,
              pointer-events-none so it can never itself intercept the next tap. */}
          {flash && <div className="absolute inset-0 bg-white pointer-events-none animate-[capture-flash_300ms_ease-out]" />}

          {/* Shutter overlay during a burst — the drawer below still has its own Capture/Resume
              buttons too, but a thumb-reachable shutter right on the preview is the whole point
              of not bouncing to another screen per photo. Explicit z-10: a plain video element
              shouldn't need it to sit under later siblings, but a hardware-decoded <video> on
              Android has been known to ignore normal DOM stacking order for touch routing, so
              this is a deliberate belt-and-suspenders after that bit us once already. */}
          {inPhotoBurst && (
            <div className="absolute inset-x-0 bottom-6 z-10 flex items-center justify-center gap-6">
              <button
                onClick={() => { setDrawerOpen(true); void resumeScanning(); }}
                className="bg-black/70 text-white text-xs font-medium px-4 py-2 rounded-full"
              >
                Done
              </button>
              <button
                onClick={handleCapture}
                title="Capture"
                className="w-16 h-16 rounded-full bg-white border-4 border-white/50 shadow-lg active:scale-95 transition-transform"
              />
            </div>
          )}
        </div>

        {!session && !loadError && (
          <div className="flex justify-center py-16"><Spinner /></div>
        )}

        {session && (
          <div className="mx-3 mb-3 bg-white rounded-xl shadow-lg overflow-hidden shrink-0">
            <button
              onClick={() => setDrawerOpen((v) => !v)}
              className="w-full flex items-center justify-center gap-2 py-2 text-gray-500"
            >
              {drawerOpen ? <ChevronDown size={16} /> : <ChevronUp size={16} />}
              <span className="text-xs truncate max-w-[70vw]">{drawerOpen ? 'Hide' : statusMessage}</span>
            </button>
            {drawerOpen && (
              <div className="px-3 pb-3 space-y-3 max-h-[45vh] overflow-y-auto">
                <ScanControls {...controlsProps} />
                <button
                  onClick={() => navigate(`/scan/${id}/review`)}
                  className="w-full text-sm text-indigo-600 hover:text-indigo-800 py-1 text-center"
                >
                  Done scanning — go to review
                </button>
              </div>
            )}
          </div>
        )}
      </div>
    );
  }

  return (
    <AppLayout>
      <div className="p-4 max-w-lg mx-auto space-y-3">
        <div className="flex items-center justify-between">
          <div className="flex items-center gap-2">
            <ScanLine size={20} className="text-indigo-600" />
            <h1 className="text-lg font-bold text-gray-900">{session?.collectionName ?? 'Scanning'}</h1>
          </div>
          <button onClick={() => navigate('/scan')} className="text-gray-400 hover:text-gray-600 p-1">
            <X size={20} />
          </button>
        </div>

        {cameraError && (
          <div className="bg-red-50 border border-red-200 rounded-xl p-3 text-sm text-red-700">
            Camera error: {cameraError}
          </div>
        )}

        {!session && !loadError && (
          <div className="flex justify-center py-16"><Spinner /></div>
        )}

        {session && (
          <div className="space-y-3">
            <div className="relative">
              <CameraPreview
                ref={videoRef}
                scanning
                rotation={rotation}
                onFocus={canTapFocus ? refocus : undefined}
                filter={lowLight ? LOW_LIGHT_FILTER : undefined}
              />
              <button
                onClick={toggleLowLight}
                title={lowLight ? 'Low-light boost on' : 'Boost for low light'}
                className={`absolute top-2 left-2 rounded-full p-2 transition-colors ${lowLight ? 'bg-amber-400 text-black' : 'bg-black/50 text-white hover:bg-black/70'}`}
              >
                <SunMedium size={18} />
              </button>
              {torchAvailable && (
                <button
                  onClick={() => void toggleTorch()}
                  title={torchOn ? 'Turn off flashlight' : 'Turn on flashlight'}
                  className={`absolute top-2 left-14 rounded-full p-2 transition-colors ${torchOn ? 'bg-amber-400 text-black' : 'bg-black/50 text-white hover:bg-black/70'}`}
                >
                  {torchOn ? <FlashlightOff size={18} /> : <Flashlight size={18} />}
                </button>
              )}
              <button
                onClick={cycleRotation}
                title="Rotate to match how you're holding the phone"
                className="absolute top-2 right-2 bg-black/50 text-white rounded-full p-2 hover:bg-black/70 transition-colors"
              >
                <RotateCw size={18} />
              </button>
              {canTapFocus && (
                <p className="absolute bottom-2 inset-x-0 text-center text-xs text-white/70 pointer-events-none">
                  Tap the preview to focus
                </p>
              )}
            </div>

            {/* Manual-focus-only cameras (confirmed on a real device — no autofocus sweep at
                all) get a slider instead of tap-to-focus. Drag while watching the preview: since
                which end is "near" vs "far" isn't standardized across devices, the preview itself
                is the only reliable guide. */}
            {canSliderFocus && focusDistanceRange && (
              <div className="px-1">
                <input
                  type="range"
                  min={focusDistanceRange.min}
                  max={focusDistanceRange.max}
                  step={focusDistanceRange.step}
                  value={focusDistanceValue ?? (focusDistanceRange.min + focusDistanceRange.max) / 2}
                  onChange={(e) => setFocusDistance(Number(e.target.value))}
                  className="w-full"
                />
                <p className="text-xs text-gray-400 text-center -mt-1">
                  Focus — drag while watching the preview above
                </p>
              </div>
            )}

            {noFocusControl && (
              <p className="text-xs text-amber-600 text-center">
                This camera doesn't expose any focus control to the browser — hold the item steady at a close, well-lit distance.
              </p>
            )}

            <ScanControls {...controlsProps} />
          </div>
        )}

        <button
          onClick={() => navigate(`/scan/${id}/review`)}
          className="w-full text-sm text-indigo-600 hover:text-indigo-800 py-2 text-center"
        >
          Done scanning — go to review
        </button>
      </div>
    </AppLayout>
  );
}
