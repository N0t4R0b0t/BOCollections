import { useRef, useEffect, useCallback, useState } from 'react';

const SAMPLE_SIZE = 16;        // downscaled to a tiny grid — cheap to read every frame
const SAMPLE_INTERVAL_MS = 150; // presence doesn't need 60fps
const DIFF_THRESHOLD = 18;      // mean-luminance delta (0-255) that counts as "something changed"
const DEBOUNCE_MS = 400;        // candidate state must hold this long before flipping `present`
const BASELINE_ALPHA = 0.05;    // how fast the "empty background" baseline adapts
// Higher than DIFF_THRESHOLD — this compares against one specific item's own luminance rather
// than a slow-adapting empty-background baseline, so it needs a bigger, more confident jump to
// avoid false positives from the same item just shifting slightly in frame.
const SWAP_THRESHOLD = 24;

/**
 * Cheap, client-side "is something new in front of the camera" signal, used to know when to
 * start racing barcode/OCR identification and when to finalize a draft on item transition.
 * Deliberately best-effort (see bulk-scan-mode spec Non-goals) — compares a tiny downscaled
 * grayscale sample against a rolling baseline of the empty background, not object detection.
 *
 * `itemToken` increments on every new distinct occupancy episode — both the normal case (empty
 * background seen in between, `present` cycles false→true) and a fast swap where one item is
 * traded for another with no empty gap (`present` stays continuously true throughout, detected
 * instead via a big luminance jump against the previously-locked item's own reading). Consumers
 * that only watched `present`'s false→true edge would otherwise never notice the second case.
 */
export function usePresenceDetector(videoRef: React.RefObject<HTMLVideoElement | null>) {
  const canvasRef = useRef<HTMLCanvasElement | null>(null);
  const rafRef = useRef<number | null>(null);
  const lastSampleAtRef = useRef(0);
  const baselineRef = useRef<number | null>(null);
  const candidateRef = useRef<{ value: boolean; since: number }>({ value: false, since: 0 });
  const swapCandidateRef = useRef<{ value: boolean; since: number }>({ value: false, since: 0 });
  const lockedMeanRef = useRef<number | null>(null); // current item's own luminance, once settled
  const presentRef = useRef(false); // mirrors `present` synchronously — the rAF loop's closure can't see fresh state
  const runningRef = useRef(false);

  const [present, setPresent] = useState(false);
  const [itemToken, setItemToken] = useState(0);

  useEffect(() => {
    canvasRef.current = document.createElement('canvas');
    canvasRef.current.width = SAMPLE_SIZE;
    canvasRef.current.height = SAMPLE_SIZE;
  }, []);

  const sample = useCallback((): number | null => {
    const video = videoRef.current;
    const canvas = canvasRef.current;
    if (!video || !canvas || video.readyState < 2 || video.videoWidth === 0) return null;

    const ctx = canvas.getContext('2d', { willReadFrequently: true });
    if (!ctx) return null;
    ctx.drawImage(video, 0, 0, SAMPLE_SIZE, SAMPLE_SIZE);

    const { data } = ctx.getImageData(0, 0, SAMPLE_SIZE, SAMPLE_SIZE);
    let sum = 0;
    for (let i = 0; i < data.length; i += 4) {
      sum += 0.299 * data[i] + 0.587 * data[i + 1] + 0.114 * data[i + 2];
    }
    return sum / (data.length / 4);
  }, [videoRef]);

  /** One frame's worth of work — a plain function, not a hook, so the rAF loop below can call itself. */
  const processFrame = useCallback((now: number) => {
    if (now - lastSampleAtRef.current < SAMPLE_INTERVAL_MS) return;
    lastSampleAtRef.current = now;

    const mean = sample();
    if (mean === null) return;

    if (baselineRef.current === null) {
      baselineRef.current = mean;
    }

    const diff = Math.abs(mean - baselineRef.current);
    const candidate = diff > DIFF_THRESHOLD;

    if (candidate !== candidateRef.current.value) {
      candidateRef.current = { value: candidate, since: now };
    }
    if (now - candidateRef.current.since >= DEBOUNCE_MS && candidate !== presentRef.current) {
      presentRef.current = candidate;
      setPresent(candidate);
      setItemToken((t) => t + 1);
      lockedMeanRef.current = candidate ? mean : null;
      swapCandidateRef.current = { value: false, since: now };
    }

    // Only drift the baseline while confidently empty — otherwise a lingering item
    // would slowly get "absorbed" into the background.
    if (!candidate) {
      baselineRef.current = baselineRef.current * (1 - BASELINE_ALPHA) + mean * BASELINE_ALPHA;
    }

    // Still occupied, but does this frame look like a different item than the one we locked
    // onto? Catches a fast swap with no empty gap in between, which the baseline-diff check
    // above would otherwise never notice (it only fires on an empty→occupied edge).
    if (presentRef.current && lockedMeanRef.current !== null) {
      const swapDiff = Math.abs(mean - lockedMeanRef.current);
      const swapCandidate = swapDiff > SWAP_THRESHOLD;
      if (swapCandidate !== swapCandidateRef.current.value) {
        swapCandidateRef.current = { value: swapCandidate, since: now };
      }
      if (swapCandidate && now - swapCandidateRef.current.since >= DEBOUNCE_MS) {
        setItemToken((t) => t + 1);
        lockedMeanRef.current = mean;
        swapCandidateRef.current = { value: false, since: now };
      }
    }
  }, [sample]);

  const start = useCallback(() => {
    if (runningRef.current) return;
    runningRef.current = true;
    baselineRef.current = null;
    candidateRef.current = { value: false, since: 0 };
    swapCandidateRef.current = { value: false, since: 0 };
    lockedMeanRef.current = null;
    presentRef.current = false;
    const loop = (now: number) => {
      if (!runningRef.current) return;
      processFrame(now);
      rafRef.current = requestAnimationFrame(loop);
    };
    rafRef.current = requestAnimationFrame(loop);
  }, [processFrame]);

  const stop = useCallback(() => {
    runningRef.current = false;
    if (rafRef.current) cancelAnimationFrame(rafRef.current);
    rafRef.current = null;
    lockedMeanRef.current = null;
    presentRef.current = false;
    setPresent(false);
  }, []);

  useEffect(() => () => stop(), [stop]);

  return { present, itemToken, start, stop };
}
