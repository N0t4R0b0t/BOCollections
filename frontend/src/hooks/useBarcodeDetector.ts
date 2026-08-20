import { useRef, useEffect, useCallback, useState } from 'react';
import {
  MultiFormatReader,
  BinaryBitmap,
  HybridBinarizer,
  HTMLCanvasElementLuminanceSource,
  DecodeHintType,
  BarcodeFormat,
  NotFoundException,
} from '@zxing/library';
import { drawRotatedFrame, type Rotation } from '../utils/drawRotatedFrame';
import { createRepeatConfirmer } from '../utils/confirmedDetection';

const HINTS = new Map<DecodeHintType, unknown>([
  [DecodeHintType.POSSIBLE_FORMATS, [
    BarcodeFormat.EAN_13, BarcodeFormat.EAN_8,
    BarcodeFormat.UPC_A, BarcodeFormat.UPC_E,
    BarcodeFormat.CODE_128, BarcodeFormat.CODE_39,
    BarcodeFormat.QR_CODE,
  ]],
  // Barcodes rarely land perfectly horizontal when hand-held — this costs some CPU but matters
  // a lot for detecting a 1D barcode that's tilted or close to vertical in frame.
  [DecodeHintType.TRY_HARDER, true],
]);

export type BarcodeDetectorStatus = 'idle' | 'scanning' | 'paused';

interface Options {
  onDetected: (barcode: string, format: string) => void;
  debounceMs?: number;
  /** Matches whatever the live preview is currently rotated to — see drawRotatedFrame. */
  rotation?: Rotation;
}

export function useBarcodeDetector(
  videoRef: React.RefObject<HTMLVideoElement | null>,
  { onDetected, debounceMs = 1500, rotation = 0 }: Options
) {
  const readerRef = useRef<MultiFormatReader | null>(null);
  const canvasRef = useRef<HTMLCanvasElement | null>(null);
  const rafRef = useRef<number | null>(null);
  const lastDetectedRef = useRef<{ value: string; ts: number } | null>(null);
  const confirmRef = useRef(createRepeatConfirmer());
  const [status, setStatus] = useState<BarcodeDetectorStatus>('idle');
  // The rAF loop below is set up once in start()/resume() and keeps calling the same `scan`
  // closure it captured at that time — mirroring rotation into a ref (rather than a `scan` dep)
  // means a mid-session flip takes effect on the very next frame with no need to restart the loop.
  const rotationRef = useRef(rotation);
  useEffect(() => { rotationRef.current = rotation; }, [rotation]);

  useEffect(() => {
    const reader = new MultiFormatReader();
    reader.setHints(HINTS);
    readerRef.current = reader;
    canvasRef.current = document.createElement('canvas');
  }, []);

  const scan = useCallback(() => {
    const video = videoRef.current;
    const reader = readerRef.current;
    const canvas = canvasRef.current;
    if (!video || !reader || !canvas || video.readyState < 2 || video.videoWidth === 0) return;

    const ctx = drawRotatedFrame(video, canvas, rotationRef.current);
    if (!ctx) return;

    try {
      const source = new HTMLCanvasElementLuminanceSource(canvas);
      const bitmap = new BinaryBitmap(new HybridBinarizer(source));
      const result = reader.decode(bitmap);

      const rawValue = result.getText();
      if (!confirmRef.current(rawValue)) return;

      const format = BarcodeFormat[result.getBarcodeFormat()].toLowerCase();
      const now = Date.now();
      const last = lastDetectedRef.current;

      if (!last || last.value !== rawValue || now - last.ts > debounceMs) {
        lastDetectedRef.current = { value: rawValue, ts: now };
        onDetected(rawValue, format);
      }
    } catch (e) {
      if (!(e instanceof NotFoundException)) {
        // Unexpected — log once so it's visible in DevTools
        console.warn('[scanner]', e);
      }
    }
  }, [videoRef, onDetected, debounceMs]);

  const start = useCallback(() => {
    if (status === 'scanning') return;
    confirmRef.current = createRepeatConfirmer();
    setStatus('scanning');
    const loop = () => {
      scan();
      rafRef.current = requestAnimationFrame(loop);
    };
    rafRef.current = requestAnimationFrame(loop);
  }, [status, scan]);

  const pause = useCallback(() => {
    if (rafRef.current) cancelAnimationFrame(rafRef.current);
    rafRef.current = null;
    setStatus('paused');
  }, []);

  const resume = useCallback(() => {
    if (status !== 'paused') return;
    setStatus('scanning');
    const loop = () => {
      scan();
      rafRef.current = requestAnimationFrame(loop);
    };
    rafRef.current = requestAnimationFrame(loop);
  }, [status, scan]);

  useEffect(() => () => {
    if (rafRef.current) cancelAnimationFrame(rafRef.current);
  }, []);

  // Always 'ready' — zxing runs entirely client-side, no on-device model to download (unlike
  // the native ML Kit path, see useNativeBarcodeDetector's moduleStatus). Present here purely so
  // useScannerBarcodeDetector's return type is consistent regardless of which one is active.
  return { isSupported: true, status, moduleStatus: 'ready' as const, start, pause, resume };
}
