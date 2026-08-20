import { useCallback, useEffect, useReducer, useRef, useState } from 'react';
import { useCamera } from './useCamera';
import { useScannerBarcodeDetector } from './useScannerBarcodeDetector';
import { usePresenceDetector } from './usePresenceDetector';
import { apiClient } from '../api/apiClient';
import { withSlowNotice } from '../utils/withSlowNotice';
import { isNativePlatform } from '../utils/platform';
import type { OwnedStatus } from '../types/thrift';
import type { MediaCategory } from '../types';

const OCR_TRIGGER_DELAY_MS = 1200;
const STILL_WORKING_MESSAGE = 'Still working — this can take a while with the current AI model…';
// Downscaled the same way bulk-scan mode's capture loop does — plenty for the vision model, a
// fraction of the upload/inference cost of a full-resolution phone photo.
const VISION_MAX_DIMENSION = 1024;
// Matches useCaptureLoop's own settle delay between stopping getUserMedia and resuming the
// native scanner — empirically needed for the OS to actually release the camera.
const NATIVE_CAMERA_SETTLE_MS = 400;

// No GUIDED_CAPTURE/FINALIZING here — unlike bulk-scan-mode's useCaptureLoop, held-item mode
// never creates a draft, it just identifies and narrates. RESULT doubles as the
// "waiting for the item to be taken away" state — there's nothing further to wait for after it.
type Phase = 'IDLE' | 'IDENTIFYING' | 'CONFIRMING' | 'CLASSIFYING' | 'RESULT';

interface HeldItemResult {
  ownedStatus: OwnedStatus;
  title: string;
  itemId?: number;
  artistOrAuthor?: string;
  category?: MediaCategory;
  format?: string;
  confidence?: 'HIGH' | 'MEDIUM' | 'LOW';
}

interface State {
  phase: Phase;
  statusMessage: string;
  result: HeldItemResult | null;
}

type Action = { type: 'SET'; phase: Phase; statusMessage: string; result?: HeldItemResult | null };

function reducer(state: State, action: Action): State {
  switch (action.type) {
    case 'SET':
      return {
        phase: action.phase,
        statusMessage: action.statusMessage,
        result: action.result !== undefined ? action.result : state.result,
      };
    default:
      return state;
  }
}

const initialState: State = { phase: 'IDLE', statusMessage: 'Hold an item up to the camera', result: null };

interface ClassifyPayload {
  title: string;
  artistOrAuthor?: string;
  category?: MediaCategory;
  format?: string;
  publisher?: string;
  releaseYear?: number;
  existingItemId?: number;
  ownedInCollections?: number[];
  confidence?: 'HIGH' | 'MEDIUM' | 'LOW';
  imageBase64?: string;
  imageMimeType?: string;
}

export function useHeldItemLoop(sessionId: number, collectionIds: number[]) {
  const isNative = isNativePlatform();
  const videoRef = useRef<HTMLVideoElement>(null);
  const {
    ready: cameraReady, error: cameraError, start: startCamera, stop: stopCamera, captureFrame,
  } = useCamera(videoRef);
  // Presence detection needs a continuously live frame to diff — on native that would mean
  // running getUserMedia *alongside* the always-on native ML-Kit scanner, the exact
  // camera-hardware-contention bug bulk-scan mode had to solve. There's no brief pause/resume
  // window here since presence has to be continuous to be useful, so it's simply not run on
  // native at all (see the OCR fallback button below for how that gap is covered instead).
  const { present, start: startPresence, stop: stopPresence } = usePresenceDetector(videoRef);

  const [state, dispatch] = useReducer(reducer, initialState);
  const phaseRef = useRef<Phase>('IDLE');
  useEffect(() => { phaseRef.current = state.phase; }, [state.phase]);
  const getPhase = (): Phase => phaseRef.current;

  const ocrTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  const ocrFiredRef = useRef(false);
  // The only feedback a shot was taken — there's no shutter sound and, on native, the feed is
  // transparent/showing the ML Kit preview during the capture anyway. Mirrors bulk-scan mode's
  // capture-flash overlay.
  const [flash, setFlash] = useState(false);
  // useScannerBarcodeDetector's onDetected is wired once, below, before the actual handler (which
  // closes over classify/set, both of which change identity across renders) is even defined —
  // routed through a ref, the standard "always call the latest closure" pattern, so the
  // underlying scanner never needs restarting just because a dependency changed.
  const onBarcodeDetectedRef = useRef<(barcode: string) => Promise<void>>(async () => {});

  const set = useCallback((phase: Phase, statusMessage: string, result?: HeldItemResult | null) => {
    dispatch({ type: 'SET', phase, statusMessage, result });
  }, []);

  const clearOcrTimer = () => {
    if (ocrTimerRef.current) clearTimeout(ocrTimerRef.current);
    ocrTimerRef.current = null;
  };

  const classify = useCallback(async (payload: ClassifyPayload) => {
    set('CLASSIFYING', 'Checking your collection…');
    try {
      const res = await withSlowNotice(
        apiClient.classifyThriftItem(sessionId, {
          title: payload.title,
          category: payload.category,
          format: payload.format,
          publisher: payload.publisher,
          releaseYear: payload.releaseYear,
          existingItemId: payload.existingItemId,
          ownedInCollections: payload.ownedInCollections,
          confidence: payload.confidence,
          imageBase64: payload.imageBase64,
          imageMimeType: payload.imageBase64 ? 'image/jpeg' : undefined,
          collectionIds,
        }),
        () => set('CLASSIFYING', STILL_WORKING_MESSAGE),
      );
      const { ownedStatus, itemId } = res.data;
      const message =
        ownedStatus === 'OWNED' ? `You already have "${payload.title}" — move on to the next item.`
        : ownedStatus === 'DIFFERENT_VERSION' ? `You own a different edition of "${payload.title}" — move on to the next item.`
        : ownedStatus === 'INTERESTING' ? `"${payload.title}" isn't in your collection — looks like a good find! Move on to the next item.`
        : `"${payload.title}" isn't in your collection — move on to the next item.`;
      set('RESULT', message, {
        ownedStatus, title: payload.title, itemId,
        artistOrAuthor: payload.artistOrAuthor, category: payload.category, format: payload.format,
        confidence: payload.confidence,
      });
    } catch {
      set('RESULT', 'Could not check this item — move on and try again.', null);
    }
  }, [collectionIds, sessionId, set]);

  const { start: startDetector, pause: pauseDetector, resume: resumeDetector, status: detectorStatus, moduleStatus } =
    useScannerBarcodeDetector(videoRef, { onDetected: (barcode) => { void onBarcodeDetectedRef.current(barcode); }, debounceMs: 1500 });

  /** Single on-demand capture, event-triggered (a detected barcode, or an explicit tap) rather
   * than continuous polling — safe on native the same way bulk-scan mode's photo-burst capture
   * is: a brief pause of the native scanner, one getUserMedia frame, then resume. On web,
   * getUserMedia is already running continuously, so this is just a plain frame grab. */
  const capturePhoto = useCallback(async (): Promise<string | null> => {
    let frame: string | null;
    if (!isNative) {
      frame = captureFrame(0.85, 0, VISION_MAX_DIMENSION);
    } else {
      try { await pauseDetector(); } catch { /* already paused/idle — fine */ }
      await new Promise((resolve) => setTimeout(resolve, NATIVE_CAMERA_SETTLE_MS));
      await startCamera('environment');
      frame = captureFrame(0.85, 0, VISION_MAX_DIMENSION);
      stopCamera();
      try { await resumeDetector(); } catch (e) {
        console.warn('[held-item] could not resume native scanner after capture', e);
      }
    }
    if (frame) {
      setFlash(true);
      setTimeout(() => setFlash(false), 300);
    }
    return frame;
  }, [isNative, captureFrame, pauseDetector, startCamera, stopCamera, resumeDetector]);

  const runOcr = useCallback(async () => {
    if (ocrFiredRef.current) return;
    ocrFiredRef.current = true;

    set('IDENTIFYING', 'Taking a closer look…');
    const frame = await capturePhoto();
    if (!frame) { set('IDLE', 'Hold an item up to the camera'); return; }

    try {
      const result = (await withSlowNotice(
        apiClient.extractFromImages([frame], ''),
        () => set('IDENTIFYING', STILL_WORKING_MESSAGE),
      )).data;
      if (getPhase() !== 'IDENTIFYING') return; // barcode path already won the race
      if (result.title) {
        await classify({
          title: result.title,
          category: result.category,
          format: result.format,
          publisher: result.publisher,
          releaseYear: result.releaseYear,
          confidence: result.confidence,
          imageBase64: frame,
        });
      } else {
        set('IDLE', "Couldn't identify this — try a clearer angle");
      }
    } catch {
      set('IDLE', "Couldn't identify this — try again");
    }
  }, [capturePhoto, classify, set]);

  const onBarcodeDetected = useCallback(async (barcode: string) => {
    if (getPhase() !== 'IDLE' && getPhase() !== 'IDENTIFYING') return;
    clearOcrTimer();
    ocrFiredRef.current = true; // barcode won the race — suppress OCR for this item
    set('IDENTIFYING', `Barcode ${barcode} — looking up…`);
    const frame = await capturePhoto();

    try {
      const lookup = (await apiClient.lookupBarcode(barcode)).data;
      if (lookup.source === 'NOT_FOUND') {
        set('IDLE', "No online match for that barcode — try showing me the item's cover instead");
        return;
      }
      set('CONFIRMING', `Found: ${lookup.title} — checking your collection…`);
      await classify({
        title: lookup.title ?? 'Unknown item',
        category: lookup.category,
        format: lookup.format,
        publisher: lookup.publisher,
        releaseYear: lookup.releaseYear,
        existingItemId: lookup.existingItemId,
        ownedInCollections: lookup.ownedInCollections,
        imageBase64: frame ?? undefined,
      });
    } catch {
      set('IDLE', 'Lookup failed — try again');
    }
  }, [capturePhoto, classify, set]);

  useEffect(() => { onBarcodeDetectedRef.current = onBarcodeDetected; }, [onBarcodeDetected]);

  // Presence drives everything on web: sustained presence with no barcode auto-fires OCR;
  // withdrawal resets to idle and re-arms the barcode detector. Not run on native at all — see
  // the module comment on usePresenceDetector's destructuring above.
  useEffect(() => {
    if (isNative) return;
    if (present) {
      if (getPhase() === 'IDLE') {
        set('IDENTIFYING', 'I see something — show me the barcode');
        ocrFiredRef.current = false;
        clearOcrTimer();
        ocrTimerRef.current = setTimeout(runOcr, OCR_TRIGGER_DELAY_MS);
      }
    } else {
      clearOcrTimer();
      if (getPhase() !== 'IDLE') {
        set('IDLE', 'Hold an item up to the camera', null);
        void resumeDetector();
      }
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [present, isNative]);

  const moveOn = useCallback(() => {
    ocrFiredRef.current = false;
    clearOcrTimer();
    void resumeDetector();
    if (isNative) {
      set('IDLE', 'Hold an item up to the camera', null);
      return;
    }
    // If the item is still in frame (presence never dropped between items — e.g. the user swapped
    // items fast enough to stay within usePresenceDetector's debounce), the `present`-transition
    // effect won't re-fire to schedule OCR since `present` never actually changed value. Re-arm it
    // manually here so the loop doesn't get stuck waiting on an event that isn't coming.
    if (present) {
      set('IDENTIFYING', 'I see something — show me the barcode');
      ocrTimerRef.current = setTimeout(runOcr, OCR_TRIGGER_DELAY_MS);
    } else {
      set('IDLE', 'Hold an item up to the camera', null);
    }
  }, [isNative, present, resumeDetector, runOcr, set]);

  /** Native has no continuous presence signal to auto-fire OCR from, so items with no readable
   * barcode need an explicit way in — same runOcr() path, just user-triggered instead of
   * automatic. Web doesn't need this (presence + timer already covers it). */
  const identifyWithoutBarcode = useCallback(() => {
    if (getPhase() !== 'IDLE') return;
    ocrFiredRef.current = false;
    void runOcr();
  }, [runOcr]);

  useEffect(() => {
    // The native ML Kit scanner drives its own camera session — starting getUserMedia here too
    // would just fight it for the camera, so it's only started continuously on web, and only
    // ever briefly (via capturePhoto) on native.
    if (isNative) return;
    startCamera('environment');
    return () => stopCamera();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  useEffect(() => {
    if (isNative) {
      startDetector();
      return;
    }
    if (cameraReady) {
      startDetector();
      startPresence();
    }
    return () => stopPresence();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [isNative, cameraReady]);

  return {
    videoRef,
    cameraReady: isNative ? detectorStatus === 'scanning' : cameraReady,
    cameraError,
    isNative,
    moduleStatus,
    phase: state.phase,
    statusMessage: state.statusMessage,
    result: state.result,
    flash,
    moveOn,
    identifyWithoutBarcode,
  };
}
