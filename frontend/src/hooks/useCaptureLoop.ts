import { useCallback, useEffect, useRef, useState } from 'react';
import { App as CapacitorApp } from '@capacitor/app';
import { Camera, CameraResultType, CameraSource } from '@capacitor/camera';
import { useCamera } from './useCamera';
import { useScannerBarcodeDetector } from './useScannerBarcodeDetector';
import { apiClient } from '../api/apiClient';
import { apiError } from '../utils/apiError';
import { useScanSessionStore } from '../store/scanSessionStore';
import { isNativePlatform } from '../utils/platform';
import { downscaleJpeg } from '../utils/downscaleImage';
import type { Rotation } from '../utils/drawRotatedFrame';
import type { ExtractResponse, LookupResult } from '../types/scan';
import type { MatchKind, PhotoAngle, ScanDraft, ScanDraftInput, ScanDraftPhotoInput } from '../types/scanSession';
import type { MediaCategory } from '../types';

// Full-resolution frames are wasted on a vision model — most VL models internally resize to a
// few hundred/thousand px anyway, and every extra pixel is tokens the (often small) context
// window has to pay for. This is what blew a batched multi-photo request past 4096 tokens.
const VISION_MAX_DIMENSION = 1024;

export type FindingSource = 'BARCODE' | 'VISION';

export interface FindingData {
  title?: string;
  subtitle?: string;
  description?: string;
  publisher?: string;
  releaseYear?: number;
  category?: MediaCategory;
  format?: string;
  barcode?: string;
  coverUrl?: string;
  existingItemId?: number;
  ownedInCollections?: number[];
  // Structured "extra details" JSON (director/cast/tracklist/etc.) from the barcode source —
  // see backend TmdbService/DiscogsService/MusicBrainzService/OpenLibraryService. Carried through
  // mergeFindings like any other field so it ends up on the draft/item, not just in `raw`.
  metadata?: string;
  // The barcode lookup provider that produced this finding (TMDB/OPEN_LIBRARY/MUSICBRAINZ/
  // DISCOGS) — undefined for CATALOGUE (matched our own catalogue, not an external source),
  // NOT_FOUND, and VISION findings (see externalSourceOf's original filtering, mirrored here so
  // it flows through the standard FIELD_KEYS merge onto the draft/item's own externalSource).
  externalSource?: string;
}

export interface Finding {
  id: string;
  source: FindingSource;
  createdAt: number;
  confidence?: 'HIGH' | 'MEDIUM' | 'LOW';
  rejected: boolean;
  data: FindingData;
  raw?: unknown; // full raw API response — kept for the metadata debug trail
}

// description and metadata are handled specially below, not via plain first-wins — see mergeFindings.
const FIELD_KEYS: (keyof FindingData)[] = [
  'title', 'subtitle', 'publisher', 'releaseYear',
  'category', 'format', 'barcode', 'coverUrl', 'existingItemId', 'ownedInCollections', 'externalSource',
];

/**
 * Barcode findings outrank vision findings for identity fields (title, category, etc. — barcode
 * data is the more reliable source there); within a source, the most recently added finding
 * wins. Rejected findings are excluded entirely. Pure function so it's trivial to preview "what
 * if I reject this one".
 *
 * Two fields get smarter treatment instead of plain source-priority, because a strict
 * first-source-wins rule was actively throwing away good data:
 * - `description`: whichever candidate is actually longer wins, regardless of source — a
 *   barcode-sourced one-line blurb shouldn't permanently block a much richer vision-read
 *   description just because barcode normally goes first.
 * - `metadata` (the "extra details" JSON — director/cast/tracklist/etc.): every source's JSON is
 *   shallow-merged together instead of one source's whole blob winning outright, so a barcode
 *   match with rich TMDB details and a vision read that separately flagged e.g. "special edition
 *   sticker" (see analyzePhotos' aiNotes) both survive in the same object, keys from
 *   higher-priority sources winning only on an actual collision.
 */
export function mergeFindings(findings: Finding[]): FindingData {
  const active = findings.filter((f) => !f.rejected);
  const barcode = active.filter((f) => f.source === 'BARCODE').slice().reverse();
  const vision = active.filter((f) => f.source === 'VISION').slice().reverse();
  const ordered = [...barcode, ...vision]; // highest priority first

  const merged: FindingData = {};
  for (const finding of ordered) {
    for (const key of FIELD_KEYS) {
      const value = finding.data[key];
      if (value !== undefined && value !== null && value !== '' && merged[key] === undefined) {
        (merged as Record<string, unknown>)[key] = value;
      }
    }
  }

  let bestDescription: string | undefined;
  for (const finding of ordered) {
    const d = finding.data.description;
    if (d && (!bestDescription || d.length > bestDescription.length)) bestDescription = d;
  }
  if (bestDescription) merged.description = bestDescription;

  const mergedExtra: Record<string, unknown> = {};
  for (const finding of [...ordered].reverse()) { // lowest priority first, so higher priority overwrites on collision
    Object.assign(mergedExtra, parseJsonObject(finding.data.metadata));
  }
  if (Object.keys(mergedExtra).length > 0) merged.metadata = JSON.stringify(mergedExtra);

  return merged;
}

// Default guess for a newly-captured photo's tag, cycling FRONT -> BACK -> SPINE -> DISC -> DISC…
// — always overridable per-photo (see setPhotoAngle), this just saves a tap for the common case
// of shooting front/back/spine in that order.
const DEFAULT_ANGLES: PhotoAngle[] = ['FRONT', 'BACK', 'SPINE', 'DISC'];

export interface CapturedPhoto {
  id: string;
  data: string; // base64 JPEG
  angle: PhotoAngle;
}

interface ItemSnapshot {
  findings: Finding[];
  photos: CapturedPhoto[];
}

// Native photo capture hands the camera to a separate OS Activity (see capturePhoto below) —
// if Android reclaims this app's backgrounded process under memory pressure while that's
// happening, everything in React state is gone when it comes back, and only the just-returned
// photo (which Capacitor's plugin bridge restores from its own saved-call state) survives.
// Mirroring the in-progress item into sessionStorage means a resurrection restores the rest too,
// instead of silently keeping only the latest photo.
function captureStateKey(sessionId: number) {
  return `boc-capture-state-${sessionId}`;
}

function parseJsonObject(raw: string | undefined): Record<string, unknown> {
  if (!raw) return {};
  try {
    const parsed: unknown = JSON.parse(raw);
    return parsed && typeof parsed === 'object' && !Array.isArray(parsed) ? (parsed as Record<string, unknown>) : {};
  } catch {
    return {};
  }
}

// Identifies which in-progress item a running AI analysis belongs to, so pressing Next doesn't
// have to wait for analysis to finish (it can take a couple of minutes). Each item in progress
// gets one Batch; analyzePhotos captures the current batch when it starts, and — if the user has
// already moved on to the next item by the time the vision call resolves — routes the result into
// the draft that batch became (via draftPromise, resolved once next() finishes creating it)
// instead of the new item's in-progress findings.
interface Batch {
  id: number;
  draftPromise: Promise<ScanDraft | null>;
  resolveDraft: (d: ScanDraft | null) => void;
}
function makeBatch(id: number): Batch {
  let resolveDraft!: (d: ScanDraft | null) => void;
  const draftPromise = new Promise<ScanDraft | null>((resolve) => { resolveDraft = resolve; });
  return { id, draftPromise, resolveDraft };
}

function loadCaptureState(sessionId: number): { findings: Finding[]; photos: CapturedPhoto[]; hint: string } | null {
  try {
    const raw = sessionStorage.getItem(captureStateKey(sessionId));
    return raw ? JSON.parse(raw) : null;
  } catch {
    return null;
  }
}

export function useCaptureLoop(sessionId: number, collectionId: number, categoryHint?: MediaCategory) {
  const isNative = isNativePlatform();
  const videoRef = useRef<HTMLVideoElement>(null);
  const {
    ready: cameraReady, error: cameraError, start: startCamera, stop: stopCamera, captureFrame,
    refocus, supportedFocusModes, focusDistanceRange, focusDistanceValue, setFocusDistance,
    lowLight, toggleLowLight,
  } = useCamera(videoRef);
  const { createDraft, updateDraft, discardDraft } = useScanSessionStore();
  const currentBatchRef = useRef<Batch>(makeBatch(0));

  const restored = isNative ? loadCaptureState(sessionId) : null;
  const [findings, setFindings] = useState<Finding[]>(restored?.findings ?? []);
  // Every photo captured for the item in progress — kept and saved to the draft regardless of
  // whether a later AI analysis of them gets rejected. Take-all-the-shots-first, analyse after,
  // per the workflow this replaced the old auto-fire-on-presence heuristic with.
  const [photos, setPhotos] = useState<CapturedPhoto[]>(restored?.photos ?? []);
  const [hint, setHint] = useState(restored?.hint ?? '');
  const [statusMessage, setStatusMessage] = useState('Show me the barcode, or capture a few photos.');
  const [visionBusy, setVisionBusy] = useState(false);
  const [finalizing, setFinalizing] = useState(false);
  const [previousDraft, setPreviousDraft] = useState<{ id: number; snapshot: ItemSnapshot } | null>(null);
  // Manual flip, since device-orientation auto-detection is unreliable across phones/browsers —
  // applies identically to the live preview, barcode decode, and photo capture (see drawRotatedFrame).
  const [rotation, setRotation] = useState<Rotation>(0);
  const cycleRotation = useCallback(() => {
    setRotation((r) => ((r + 90) % 360) as Rotation);
  }, []);

  const findingsRef = useRef<Finding[]>([]);
  useEffect(() => { findingsRef.current = findings; }, [findings]);
  const photosRef = useRef<CapturedPhoto[]>([]);
  useEffect(() => { photosRef.current = photos; }, [photos]);

  useEffect(() => {
    if (!isNative) return;
    try {
      sessionStorage.setItem(captureStateKey(sessionId), JSON.stringify({ findings, photos, hint }));
    } catch {
      // sessionStorage full (a lot of full-res-ish photos) — worst case a resurrection loses
      // state same as before this existed, not worth failing the capture over.
    }
  }, [isNative, sessionId, findings, photos, hint]);

  const addFinding = useCallback((f: Omit<Finding, 'id' | 'createdAt' | 'rejected'>) => {
    setFindings((prev) => [...prev, { ...f, id: crypto.randomUUID(), createdAt: Date.now(), rejected: false }]);
  }, []);

  /** External source ('DISCOGS'/'MUSICBRAINZ'/'TMDB'/'OPEN_LIBRARY') that produced a barcode
   * finding — kept only in `raw` (the original LookupResult), not on the Finding itself. */
  const externalSourceOf = (f: Finding): string | undefined => {
    const source = (f.raw as LookupResult | undefined)?.source;
    return source && source !== 'CATALOGUE' && source !== 'NOT_FOUND' ? source : undefined;
  };

  const retryBarcodeLookup = useCallback(async (barcode: string, excludeSources: string[], excludeExternalIds: string[]) => {
    setStatusMessage(`That wasn't it — checking other matches for ${barcode}…`);
    try {
      const lookup: LookupResult = (await apiClient.lookupBarcode(barcode, excludeSources, excludeExternalIds)).data;
      if (lookup.source === 'NOT_FOUND') {
        setStatusMessage(`No other online match for ${barcode} — try a vision capture, or fill in manually.`);
        return;
      }
      addFinding({
        source: 'BARCODE',
        confidence: 'HIGH',
        data: {
          title: lookup.title, subtitle: lookup.subtitle, description: lookup.description,
          publisher: lookup.publisher, releaseYear: lookup.releaseYear, category: lookup.category,
          format: lookup.format, barcode, coverUrl: lookup.coverUrl,
          existingItemId: lookup.existingItemId, ownedInCollections: lookup.ownedInCollections, metadata: lookup.metadata,
          // 'NOT_FOUND' is already excluded by the early return above — only 'CATALOGUE' (matched
          // our own catalogue, not an external source) still needs filtering out here.
          externalSource: lookup.source !== 'CATALOGUE' ? lookup.source : undefined,
        },
        raw: lookup,
      });
      setStatusMessage(`Found another match: ${lookup.title ?? barcode}`);
    } catch (e) {
      setStatusMessage(apiError(e, 'Retry lookup failed'));
    }
  }, [addFinding]);

  const rejectFinding = useCallback((id: string, rejected: boolean) => {
    const target = findingsRef.current.find((f) => f.id === id);
    setFindings((prev) => prev.map((f) => (f.id === id ? { ...f, rejected } : f)));

    // Rejecting a barcode match means the waterfall that produced it should resume from the
    // next candidate, not just discard the finding. A wrong TMDB match is usually the *title
    // search* picking the wrong movie (e.g. barcode for "Dredd" 2012 matching "Judge Dredd"
    // 1995) rather than TMDB being the wrong source — so a rejected TMDB finding excludes just
    // that specific movie/show id and tries TMDB again, instead of skipping TMDB altogether.
    // Every other source (Discogs/MusicBrainz/OpenLibrary) has no candidate list to page
    // through here, so those get excluded wholesale, same as before.
    if (rejected && target?.source === 'BARCODE' && target.data.barcode) {
      const barcode = target.data.barcode;
      const rejectedForBarcode = findingsRef.current
        .map((f) => (f.id === id ? { ...f, rejected: true } : f))
        .filter((f) => f.source === 'BARCODE' && f.rejected && f.data.barcode === barcode);

      const excludeExternalIds = [...new Set(
        rejectedForBarcode
          .filter((f) => externalSourceOf(f) === 'TMDB')
          .map((f) => (f.raw as LookupResult | undefined)?.externalId)
          .filter((extId): extId is string => !!extId)
      )];
      const excludeSources = [...new Set(
        rejectedForBarcode
          .map(externalSourceOf)
          .filter((s): s is string => !!s && s !== 'TMDB')
      )];

      if (excludeSources.length > 0 || excludeExternalIds.length > 0) {
        void retryBarcodeLookup(barcode, excludeSources, excludeExternalIds);
      }
    }
  }, [retryBarcodeLookup]);

  const onBarcodeDetected = useCallback(async (barcode: string) => {
    // Same barcode already seen for this item — don't re-fire a plain (unexcluded) lookup, even
    // if that finding was since rejected. The item typically sits in frame for several seconds,
    // so the scanner keeps re-detecting the same barcode every ~1.5s; without this, rejecting a
    // wrong match just meant the very next re-detection immediately re-added the exact same wrong
    // (now-stale-cached) match again, forcing the user to reject the same thing over and over
    // instead of the rejection's exclusion actually being tried. Only the explicit Reject button
    // (see rejectFinding/retryBarcodeLookup) is allowed to look this barcode up again.
    if (findingsRef.current.some((f) => f.source === 'BARCODE' && f.data.barcode === barcode)) return;

    setStatusMessage(`Barcode ${barcode} — looking up…`);
    try {
      const lookup: LookupResult = (await apiClient.lookupBarcode(barcode)).data;
      if (lookup.source === 'NOT_FOUND') {
        setStatusMessage(`No online match for ${barcode} — try a vision capture, or fill in manually.`);
        return;
      }
      addFinding({
        source: 'BARCODE',
        confidence: 'HIGH',
        data: {
          title: lookup.title, subtitle: lookup.subtitle, description: lookup.description,
          publisher: lookup.publisher, releaseYear: lookup.releaseYear, category: lookup.category,
          format: lookup.format, barcode, coverUrl: lookup.coverUrl,
          existingItemId: lookup.existingItemId, ownedInCollections: lookup.ownedInCollections, metadata: lookup.metadata,
          // 'NOT_FOUND' is already excluded by the early return above — only 'CATALOGUE' (matched
          // our own catalogue, not an external source) still needs filtering out here.
          externalSource: lookup.source !== 'CATALOGUE' ? lookup.source : undefined,
        },
        raw: lookup,
      });
      setStatusMessage(`Found: ${lookup.title ?? barcode}`);
    } catch (e) {
      setStatusMessage(apiError(e, 'Barcode lookup failed'));
    }
  }, [addFinding]);

  const {
    start: startDetector, pause: pauseDetector, resume: resumeDetector, status: detectorStatus, moduleStatus,
    refocusScanner,
  } = useScannerBarcodeDetector(videoRef, { onDetected: onBarcodeDetected, debounceMs: 1500, rotation });

  // True from the first photo of a capture burst until resumeScanning() (see below) explicitly
  // ends it — lets capturePhoto skip the pause+delay handoff for every photo after the first, so
  // shooting several photos of one item back-to-back doesn't pay the ~400ms camera-release delay
  // and a full round-trip to the barcode scanner each time. Also tells the app-resume handler
  // below that the scanner being paused right now is intentional, not something to "fix". Mirrored
  // into state (inPhotoBurstRef for synchronous checks inside async flows, the state for the UI
  // to show a "resume scanning" control) rather than just one or the other.
  const inPhotoBurstRef = useRef(false);
  const [inPhotoBurst, setInPhotoBurstState] = useState(false);
  const setInPhotoBurst = useCallback((v: boolean) => {
    inPhotoBurstRef.current = v;
    setInPhotoBurstState(v);
  }, []);

  const resumeScanning = useCallback(async () => {
    if (!inPhotoBurstRef.current) return;
    setInPhotoBurst(false);
    stopCamera();
    await new Promise((resolve) => setTimeout(resolve, 400));
    try {
      await resumeDetector();
    } catch {
      try {
        await startDetector();
      } catch (e) {
        setStatusMessage(apiError(e, 'Could not restart the barcode scanner — leave and re-enter this screen'));
      }
    }
  }, [stopCamera, resumeDetector, startDetector, setInPhotoBurst]);

  useEffect(() => {
    if (!isNative) return;
    // Android can silently release the camera when the app is backgrounded (app switcher, lock
    // screen, a system permission dialog, etc.) — our JS-side `status` has no way to find out
    // this happened, so on return it still thinks it's "scanning" while the native session is
    // actually dead, leaving a black/frozen preview until the screen is manually left and
    // re-entered. Forcing a full stop+restart on every resume, regardless of what state thinks
    // it's in, means a real resume always gets a live camera back. Skipped entirely during a
    // photo-capture burst: launching the system camera app for a photo *is* an app
    // background/resume cycle, and forcing a scanner restart on every single one of those would
    // fight capturePhoto's own handoff and undo the point of not resuming between shots.
    const listenerPromise = CapacitorApp.addListener('appStateChange', ({ isActive }) => {
      if (!isActive || inPhotoBurstRef.current) return;
      void (async () => {
        try { await pauseDetector(); } catch { /* already stopped/idle — fine */ }
        await new Promise((resolve) => setTimeout(resolve, 400));
        try { await startDetector(); } catch (e) {
          setStatusMessage(apiError(e, 'Could not restart the barcode scanner — leave and re-enter this screen'));
        }
      })();
    });
    return () => { void listenerPromise.then((l) => l.remove()); };
  }, [isNative, pauseDetector, startDetector]);

  // Tapping "Capture" the first time should just open the live preview — not also take a photo
  // on that same tap, which was surprising (you'd get a photo you didn't frame yet). Actually
  // taking a shot is a separate, explicit action (the on-screen shutter or "Capture another").
  const enterPhotoMode = useCallback(async () => {
    if (inPhotoBurstRef.current) return;

    // Hand the camera from the native ML Kit scanner to getUserMedia once for the whole burst,
    // not once per photo. Same class of hardware race every other camera handoff in this file
    // has to account for (see the app-resume handler above) — getUserMedia and the ML Kit
    // scanner are still two separate camera stacks — just paid twice per burst instead of twice
    // per photo. Probes with a throwaway captureFrame() to confirm the feed actually came up
    // before committing to burst mode; falls back to the OS camera for one photo instead of
    // leaving the user stuck with a dead capture button if it didn't.
    await pauseDetector();
    await new Promise((resolve) => setTimeout(resolve, 400));
    await startCamera('environment');
    let probeOk = captureFrame(0.85, 0, VISION_MAX_DIMENSION) !== null;
    if (!probeOk) {
      await new Promise((resolve) => setTimeout(resolve, 500));
      probeOk = captureFrame(0.85, 0, VISION_MAX_DIMENSION) !== null;
    }

    if (probeOk) {
      setInPhotoBurst(true);
      setStatusMessage('Live preview ready — tap the shutter to take photos.');
      return;
    }

    stopCamera();
    setStatusMessage('Live preview unavailable — using the camera app for this photo instead.');
    try {
      // No width/height cap — this becomes a permanent gallery photo like every other capture
      // path here, not just a vision-model input (see capturePhoto/analyzePhotos above).
      const photo = await Camera.getPhoto({
        quality: 90,
        resultType: CameraResultType.Base64,
        source: CameraSource.Camera,
        saveToGallery: false,
      });
      if (photo.base64String) {
        const data = photo.base64String;
        setPhotos((prev) => [...prev, { id: crypto.randomUUID(), data, angle: DEFAULT_ANGLES[prev.length % DEFAULT_ANGLES.length] }]);
      }
    } catch (e) {
      // Cancelling the native camera UI (back button, etc.) rejects too — not an error.
      const message = e instanceof Error ? e.message : String(e);
      if (!/cancel/i.test(message)) setStatusMessage(apiError(e, 'Could not take that photo'));
    } finally {
      // Never entered burst mode (inPhotoBurstRef is still false) — this was effectively a
      // one-off single-shot capture, so the scanner needs to come back now.
      try {
        await resumeDetector();
      } catch {
        try {
          await startDetector();
        } catch (e) {
          setStatusMessage(apiError(e, 'Could not restart the barcode scanner — leave and re-enter this screen'));
        }
      }
    }
  }, [pauseDetector, startCamera, stopCamera, captureFrame, resumeDetector, startDetector, setInPhotoBurst]);

  const capturePhoto = useCallback(async () => {
    if (isNative) {
      if (!inPhotoBurstRef.current) {
        // Capture was somehow invoked before the live preview opened (shouldn't happen — the UI
        // gates this button on inPhotoBurst) — enter capture mode instead of silently no-op-ing.
        await enterPhotoMode();
        return;
      }
      // No maxDimension here — this frame becomes the item's own permanent gallery photo (see
      // analyzePhotos, which derives its own downscaled copy just for the vision upload), not
      // just a vision-model input, so it shouldn't be capped down to VISION_MAX_DIMENSION.
      const frame = captureFrame(0.92, 0);
      if (frame) setPhotos((prev) => [...prev, { id: crypto.randomUUID(), data: frame, angle: DEFAULT_ANGLES[prev.length % DEFAULT_ANGLES.length] }]);
      else setStatusMessage("Couldn't read a frame from the live preview — try again.");
      return;
    }
    const frame = captureFrame(0.92, rotation);
    if (frame) setPhotos((prev) => [...prev, { id: crypto.randomUUID(), data: frame, angle: DEFAULT_ANGLES[prev.length % DEFAULT_ANGLES.length] }]);
  }, [isNative, captureFrame, rotation, enterPhotoMode]);

  const removePhoto = useCallback((id: string) => {
    setPhotos((prev) => prev.filter((p) => p.id !== id));
  }, []);

  const setPhotoAngle = useCallback((id: string, angle: PhotoAngle) => {
    setPhotos((prev) => prev.map((p) => (p.id === id ? { ...p, angle } : p)));
  }, []);

  // Replaces a not-yet-uploaded photo's raw base64 with a cropped version — see PhotoCropModal.
  // Only meaningful pre-upload; once a photo is saved, cropping it would need a backend endpoint
  // to persist the edit, which doesn't exist yet.
  const setPhotoData = useCallback((id: string, data: string) => {
    setPhotos((prev) => prev.map((p) => (p.id === id ? { ...p, data } : p)));
  }, []);

  // Fills in whatever a background-completed analysis found onto an already-created draft — only
  // fields the draft doesn't already have (a barcode-confirmed field always wins over a slower
  // vision guess, same priority AI findings already lose to when analysis finishes in time).
  // Best-effort: a failure here just leaves the draft with whatever it already had.
  const applyVisionToDraft = useCallback(async (draft: ScanDraft, result: ExtractResponse) => {
    const patch: Partial<ScanDraftInput> = {};
    if (!draft.title && result.title) patch.title = result.title;
    if (!draft.subtitle && result.subtitle) patch.subtitle = result.subtitle;
    if (!draft.description && result.description) patch.description = result.description;
    if (!draft.publisher && result.publisher) patch.publisher = result.publisher;
    if (!draft.releaseYear && result.releaseYear) patch.releaseYear = result.releaseYear;
    if (!draft.category && result.category) patch.category = result.category;
    if (!draft.format && result.format) patch.format = result.format;
    if (!draft.externalSource) patch.externalSource = 'AI_VISION';
    const visionExtra = (result.notes || result.metadata)
      ? { ...parseJsonObject(result.metadata), ...(result.notes ? { aiNotes: result.notes } : {}) }
      : null;
    if (visionExtra) patch.metadata = JSON.stringify({ ...parseJsonObject(draft.metadata), ...visionExtra });
    if (Object.keys(patch).length === 0) return;
    try {
      await updateDraft(sessionId, draft.id, patch);
    } catch {
      // Background fill-in — nothing in the UI is waiting on this, so there's nothing useful to surface.
    }
  }, [sessionId, updateDraft]);

  const analyzePhotos = useCallback(async () => {
    if (photosRef.current.length === 0) return;
    // Captured now, before any await — this ties the eventual result back to whichever item was
    // in progress when Analyse was pressed, even if the user has already hit Next by the time the
    // vision call resolves (it can take a couple of minutes). See the Batch/makeBatch doc comment.
    const batch = currentBatchRef.current;
    const isCurrent = () => batch === currentBatchRef.current;
    if (isCurrent()) {
      if (visionBusy) return;
      setVisionBusy(true);
      setStatusMessage('Analysing your photos…');
    }
    const photosSnapshot = photosRef.current;
    try {
      const hintText = hint || (categoryHint ? `this is a ${categoryHint.toLowerCase()} item` : '');
      // Downscaled companions only for the upload — the captured photo itself (the permanent
      // gallery copy) is never touched. A full-resolution frame is wasted tokens for no accuracy
      // benefit here, same reasoning captureFrame's own maxDimension doc comment gives.
      const visionImages = await Promise.all(
        photosSnapshot.map((p) => downscaleJpeg(p.data, VISION_MAX_DIMENSION)),
      );
      const result = (await apiClient.extractFromImages(visionImages, hintText)).data;

      if (!result.visionAvailable) {
        if (isCurrent()) setStatusMessage('AI vision is currently unreachable — try the barcode, or fill in details manually.');
        return;
      }

      if (!isCurrent()) {
        // The user already moved on — feed the result into the draft this batch became (once it
        // exists) instead of the new item's in-progress findings, which would misattribute it.
        const draft = await batch.draftPromise;
        if (draft) await applyVisionToDraft(draft, result);
        return;
      }

      addFinding({
        source: 'VISION',
        confidence: result.confidence,
        data: {
          title: result.title, subtitle: result.subtitle, description: result.description,
          publisher: result.publisher, releaseYear: result.releaseYear, category: result.category,
          format: result.format,
          // Never trust a vision model's guess at a barcode — it's OCR-off-a-photo at best and
          // outright fabrication at worst (confirmed on a real item: it invented two different
          // "barcode" strings, neither matching anything real). The scanner is the only source
          // that should ever populate this field; keeping it out of `data` (still visible in
          // `raw` for the debug trail) means mergeFindings can never let it win over — or
          // silently fill in for — an actual scanned barcode.
          //
          // `result.metadata` is everything vision read off the box beyond the core fields —
          // edition, disc count, region, special features, credits, pressing details, etc. (see
          // backend VisualScanService's extract prompt) — already assembled server-side as JSON.
          // `notes` rides along as `aiNotes` inside the same blob rather than a dedicated field,
          // so it merges through the same shallow-merge as every other "extra detail" (see
          // mergeFindings) and survives even when a barcode match wins every other field.
          metadata: (result.notes || result.metadata)
            ? JSON.stringify({ ...parseJsonObject(result.metadata), ...(result.notes ? { aiNotes: result.notes } : {}) })
            : undefined,
          externalSource: 'AI_VISION',
        },
        raw: result,
      });
      setStatusMessage(result.title ? `AI sees: ${result.title}` : "Couldn't identify from those photos — try a clearer/closer shot, or rely on the barcode.");
    } catch (e) {
      if (isCurrent()) setStatusMessage(apiError(e, 'Analysis failed'));
    } finally {
      if (isCurrent()) setVisionBusy(false);
    }
  }, [visionBusy, hint, categoryHint, addFinding, applyVisionToDraft]);

  const next = useCallback(async () => {
    if (finalizing) return;
    // Moving to a new item — resume barcode scanning if a photo burst left it paused, so it's
    // ready for whatever the user scans next instead of staying silently paused indefinitely.
    void resumeScanning();
    // Rotate to a fresh batch immediately — any analyzePhotos call still in flight for the item
    // being finalized below keeps its own reference to the outgoing batch (captured at call time),
    // so it can route its result into the draft this creates once it's done, instead of the new
    // item's findings.
    const batch = currentBatchRef.current;
    currentBatchRef.current = makeBatch(batch.id + 1);
    // visionBusy tracks analysis for whatever item is currently in view — the outgoing batch's
    // own analyzePhotos call (if any is still running) now targets a background PATCH instead
    // (see the isCurrent() check there) and stops touching this flag, so the new item's Analyse
    // button needs to be freed up here rather than waiting for that call to finish.
    setVisionBusy(false);
    const currentFindings = findingsRef.current;
    const currentPhotos = photosRef.current;
    const merged = mergeFindings(currentFindings);
    const alreadyOwned = merged.existingItemId != null && (merged.ownedInCollections ?? []).includes(collectionId);
    const hasBarcode = currentFindings.some((f) => f.source === 'BARCODE' && !f.rejected);
    const hasVision = currentFindings.some((f) => f.source === 'VISION' && !f.rejected);
    const matchKind: MatchKind = alreadyOwned ? 'ALREADY_OWNED' : hasBarcode ? 'CONFIDENT' : hasVision ? 'UNMATCHED' : 'MANUAL';
    const leadConfidence = currentFindings.find((f) => !f.rejected)?.confidence;

    // Every photo taken is kept regardless of whether the AI's reading of it was rejected —
    // the picture is still good evidence for review even when the AI's guess about it wasn't.
    // Angle is whatever the user tagged it as (or the FRONT/BACK/SPINE/DISC default guess),
    // editable per-photo in the capture UI before Next is pressed.
    const draftPhotos: ScanDraftPhotoInput[] = currentPhotos.map((photo) => ({
      imageBase64: photo.data, imageMimeType: 'image/jpeg', angle: photo.angle,
    }));

    setFinalizing(true);
    try {
      const draft: ScanDraft = await createDraft(sessionId, {
        matchKind,
        existingItemId: merged.existingItemId,
        barcode: merged.barcode,
        category: merged.category,
        format: merged.format,
        title: merged.title,
        subtitle: merged.subtitle,
        description: merged.description,
        coverUrl: merged.coverUrl,
        releaseYear: merged.releaseYear,
        publisher: merged.publisher,
        confidence: leadConfidence,
        externalSource: merged.externalSource,
        // The barcode source's structured "extra details" (director/cast/tracklist/etc., see
        // FindingData.metadata) is what ItemDetailPage renders, so it has to be the top-level
        // JSON here — the full findings audit trail (rejected findings + raw API responses,
        // useful for debugging) rides along underneath a `_debug` key instead of overwriting it.
        metadata: JSON.stringify({ ...parseJsonObject(merged.metadata), _debug: { findings: currentFindings } }),
        photos: draftPhotos,
      });
      batch.resolveDraft(draft);
      setPreviousDraft({ id: draft.id, snapshot: { findings: currentFindings, photos: currentPhotos } });
      setFindings([]);
      setPhotos([]);
      setHint('');
      setStatusMessage(alreadyOwned
        ? 'Already in your collection — skipped. Show me the next item.'
        : 'Saved — show me the next item.');
    } catch (e) {
      batch.resolveDraft(null);
      setStatusMessage(apiError(e, 'Could not save that item'));
    } finally {
      setFinalizing(false);
    }
  }, [finalizing, collectionId, createDraft, sessionId, resumeScanning]);

  const previous = useCallback(async () => {
    if (!previousDraft || finalizing) return;
    setFinalizing(true);
    try {
      await discardDraft(sessionId, previousDraft.id);
      setFindings(previousDraft.snapshot.findings.map((f) => ({ ...f, rejected: false })));
      setPhotos(previousDraft.snapshot.photos);
      setPreviousDraft(null);
      setStatusMessage('Restored — reject anything wrong, then press Next again.');
    } catch (e) {
      setStatusMessage(apiError(e, 'Could not undo that item'));
    } finally {
      setFinalizing(false);
    }
  }, [previousDraft, finalizing, sessionId, discardDraft]);

  useEffect(() => {
    // The native ML Kit scanner drives its own camera session (started below) — starting
    // getUserMedia here too would just fight it for the camera, so skip it entirely on native.
    if (isNative) return;
    startCamera('environment');
    return () => stopCamera();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  // Leaving the screen mid-burst (X button, hardware back, or "Done scanning — go to review" —
  // none of which go through resumeScanning() the way Next does) would otherwise strand the
  // getUserMedia stream held open and the ML Kit scanner paused for whenever this screen is next
  // entered. resumeScanning() already no-ops when there's no burst in progress.
  useEffect(() => {
    if (!isNative) return;
    return () => { void resumeScanning(); };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  useEffect(() => {
    if (isNative || cameraReady) startDetector();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [cameraReady]);

  return {
    videoRef,
    // On native, getUserMedia's `ready` never becomes true outside of a photo burst (it's never
    // started for the continuous preview — see above), so "ready" instead means the native scan
    // session is live — except *during* a burst, where getUserMedia is the thing actually live
    // and the ML Kit session is deliberately paused, so checking its status would (and did)
    // incorrectly disable the capture button for the whole burst.
    cameraReady: isNative ? (inPhotoBurst ? cameraReady : detectorStatus === 'scanning') : cameraReady,
    cameraError,
    isNative,
    moduleStatus,
    findings,
    merged: mergeFindings(findings),
    photos,
    hint,
    setHint,
    statusMessage,
    visionBusy,
    finalizing,
    canUndo: previousDraft !== null,
    rotation,
    cycleRotation,
    refocus,
    refocusScanner,
    supportedFocusModes,
    focusDistanceRange,
    focusDistanceValue,
    setFocusDistance,
    lowLight,
    toggleLowLight,
    capturePhoto,
    enterPhotoMode,
    removePhoto,
    setPhotoAngle,
    setPhotoData,
    analyzePhotos,
    rejectFinding,
    next,
    previous,
    inPhotoBurst,
    resumeScanning,
  };
}
