import { useRef, useEffect, useCallback, useState } from 'react';
import { drawRotatedFrame, type Rotation } from '../utils/drawRotatedFrame';
import { apiClient } from '../api/apiClient';

/**
 * Manages a single getUserMedia stream attached to a <video> element.
 *
 * @param videoRef - ref to the <video> element that will display the feed
 * @returns ready, error, start(), stop(), captureFrame()
 */
// focusMode/focusDistance aren't in the standard lib.dom.d.ts MediaTrackConstraintSet/Capabilities
// (they're part of the Image Capture extensions, unevenly supported), so we type them ourselves
// rather than fight TS with `as unknown as` everywhere they're touched.
interface FocusCapabilities {
  focusMode?: string[];
  focusDistance?: { min: number; max: number; step: number };
}

export interface FocusDistanceRange { min: number; max: number; step: number; }

export function useCamera(videoRef: React.RefObject<HTMLVideoElement | null>) {
  const streamRef = useRef<MediaStream | null>(null);
  const [ready, setReady] = useState(false);
  const [error, setError] = useState<string | null>(null);
  // null = not checked yet, [] = checked and camera reports no focus control at all (fixed-focus
  // lens, or the browser doesn't expose MediaStreamTrack.getCapabilities — Safari, mostly).
  const [supportedFocusModes, setSupportedFocusModes] = useState<string[] | null>(null);
  // Some cameras (confirmed on a real device here) only expose focusMode: ['manual'] — no
  // continuous/single-shot at all — but back it with a real focusDistance range, which is a much
  // more reliable knob than hoping the browser's autofocus sweep ever kicks in.
  const [focusDistanceRange, setFocusDistanceRange] = useState<FocusDistanceRange | null>(null);
  const [focusDistanceValue, setFocusDistanceValue] = useState<number | null>(null);

  const applyFocusMode = useCallback(async (mode: 'continuous' | 'single-shot') => {
    const track = streamRef.current?.getVideoTracks()[0];
    if (!track) return false;
    try {
      await track.applyConstraints({ advanced: [{ focusMode: mode }] } as unknown as MediaTrackConstraints);
      return true;
    } catch (e) {
      console.warn(`[camera] applyConstraints(focusMode: ${mode}) failed`, e);
      return false;
    }
  }, []);

  const setFocusDistance = useCallback(async (value: number) => {
    const track = streamRef.current?.getVideoTracks()[0];
    if (!track) return false;
    try {
      await track.applyConstraints({ advanced: [{ focusMode: 'manual', focusDistance: value }] } as unknown as MediaTrackConstraints);
      setFocusDistanceValue(value);
      void apiClient.debugLog({ event: 'set-focus-distance', value, applied: true });
      return true;
    } catch (e) {
      console.warn('[camera] applyConstraints(focusDistance) failed', e);
      void apiClient.debugLog({ event: 'set-focus-distance', value, applied: false, error: String(e) });
      return false;
    }
  }, []);

  const start = useCallback(async (facingMode: 'environment' | 'user' = 'environment') => {
    try {
      setError(null);
      const stream = await navigator.mediaDevices.getUserMedia({
        video: { facingMode, width: { ideal: 1920 }, height: { ideal: 1080 } },
        audio: false,
      });
      streamRef.current = stream;

      const track = stream.getVideoTracks()[0];
      const capabilities = track?.getCapabilities?.() as (MediaTrackCapabilities & FocusCapabilities) | undefined;
      const modes = capabilities?.focusMode ?? [];
      console.info('[camera] focus capabilities', capabilities, 'modes:', modes);
      void apiClient.debugLog({ event: 'camera-capabilities', capabilities, focusModes: modes, userAgent: navigator.userAgent });
      setSupportedFocusModes(modes);
      if (modes.includes('continuous')) {
        // Applying it as a live constraint on the track (rather than only in the initial
        // getUserMedia call above) is more reliable across browsers — some only honour
        // focus-related constraints when set this way, after the track already exists.
        const applied = await applyFocusMode('continuous');
        void apiClient.debugLog({ event: 'apply-continuous-focus', applied });
      } else if (modes.includes('manual') && capabilities?.focusDistance) {
        const range = capabilities.focusDistance;
        setFocusDistanceRange(range);
        // We don't actually know which end of the range means "near" vs "far" — that's not
        // standardized and varies by device — so start at the midpoint and let the slider (with
        // the live preview right above it) make it obvious which direction sharpens the image.
        const initial = (range.min + range.max) / 2;
        const applied = await setFocusDistance(initial);
        void apiClient.debugLog({ event: 'apply-initial-focus-distance', range, initial, applied });
      }
      // If neither is in the capability list at all, this camera/browser doesn't support focus
      // control from the web at all — no constraint syntax will change that.

      if (videoRef.current) {
        const video = videoRef.current;
        video.srcObject = stream;
        // srcObject triggers an async load(); wait for it before play() to avoid
        // "play() interrupted by a new load request" AbortError
        await new Promise<void>((resolve) => { video.onloadedmetadata = () => resolve(); });
        if (videoRef.current) {
          await videoRef.current.play();
          setReady(true);
        }
      }
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Camera access denied');
    }
  }, [videoRef, applyFocusMode, setFocusDistance]);

  const stop = useCallback(() => {
    streamRef.current?.getTracks().forEach((t) => t.stop());
    streamRef.current = null;
    setReady(false);
    setSupportedFocusModes(null);
  }, []);

  /**
   * On-demand refocus for cameras that don't support continuous AF (or where it's stuck) —
   * triggers a single autofocus sweep, then re-arms continuous mode afterward if available.
   * Returns false if this camera/browser exposes no focus control at all, so callers can decide
   * whether showing a "tap to focus" affordance is even worth it.
   */
  const refocus = useCallback(async () => {
    const modes = supportedFocusModes ?? [];
    if (modes.includes('single-shot')) {
      const applied = await applyFocusMode('single-shot');
      void apiClient.debugLog({ event: 'refocus', mode: 'single-shot', applied });
      if (modes.includes('continuous')) {
        setTimeout(() => { void applyFocusMode('continuous'); }, 1500);
      }
      return true;
    }
    if (modes.includes('continuous')) {
      // Some browsers re-trigger a focus sweep on re-applying the same constraint.
      const applied = await applyFocusMode('continuous');
      void apiClient.debugLog({ event: 'refocus', mode: 'continuous', applied });
      return true;
    }
    void apiClient.debugLog({ event: 'refocus', mode: 'none-supported', modes });
    return false;
  }, [supportedFocusModes, applyFocusMode]);

  /**
   * Captures a single JPEG frame from the live video feed.
   *
   * Returns null if the camera isn't ready or the video element hasn't received
   * data yet (readyState < HAVE_CURRENT_DATA). The readyState guard is important
   * because calling drawImage on a video with no frame data produces a black image.
   *
   * `rotation` matches whatever the live preview is currently rotated to, so a capture always
   * looks the way the user framed it. `maxDimension`, if given, downscales the *output* only
   * (the rotated capture is re-drawn onto a second, smaller canvas) — useful for vision-model
   * uploads, where a full-resolution frame just burns tokens for no accuracy benefit.
   */
  const captureFrame = useCallback((quality = 0.85, rotation: Rotation = 0, maxDimension?: number): string | null => {
    const video = videoRef.current;
    // HTMLMediaElement.HAVE_CURRENT_DATA = 2 — at least one frame is available. Checked directly
    // on the element rather than via the `ready` state: a caller that awaits start() and then
    // immediately captures (no intervening render) would otherwise see a stale `ready === false`
    // from the closure this callback was created with, even though the video itself already has
    // data — readyState is always current, read straight off the DOM, no React staleness possible.
    if (!video || video.readyState < 2) return null;
    if (video.videoWidth === 0 || video.videoHeight === 0) return null;

    let canvas = document.createElement('canvas');
    const ctx = drawRotatedFrame(video, canvas, rotation);
    if (!ctx) return null;

    if (maxDimension && (canvas.width > maxDimension || canvas.height > maxDimension)) {
      const scale = maxDimension / Math.max(canvas.width, canvas.height);
      const scaled = document.createElement('canvas');
      scaled.width = Math.round(canvas.width * scale);
      scaled.height = Math.round(canvas.height * scale);
      scaled.getContext('2d')?.drawImage(canvas, 0, 0, scaled.width, scaled.height);
      canvas = scaled;
    }

    // Split off the "data:image/jpeg;base64," prefix — callers only need the raw base64
    return canvas.toDataURL('image/jpeg', quality).split(',')[1] ?? null;
  }, [videoRef]);

  useEffect(() => () => { stop(); }, [stop]);

  return {
    ready, error, start, stop, captureFrame, refocus, supportedFocusModes,
    focusDistanceRange, focusDistanceValue, setFocusDistance,
  };
}
