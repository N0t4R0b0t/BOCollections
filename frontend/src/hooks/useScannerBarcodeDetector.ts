import { useBarcodeDetector } from './useBarcodeDetector';
import { useNativeBarcodeDetector } from './useNativeBarcodeDetector';
import { isNativePlatform } from '../utils/platform';
import type { Rotation } from '../utils/drawRotatedFrame';

interface Options {
  onDetected: (barcode: string, format: string) => void;
  debounceMs?: number;
  rotation?: Rotation;
}

/**
 * Picks the native ML Kit scanner inside the Android/iOS app shell, or the existing
 * zxing/getUserMedia scanner in a plain browser tab — same web experience either way.
 * Both underlying hooks are always mounted (hook-call-order rules), but only the active one's
 * start/pause/resume ever gets invoked by the caller, so the inactive one just sits idle.
 */
export function useScannerBarcodeDetector(videoRef: React.RefObject<HTMLVideoElement | null>, options: Options) {
  const web = useBarcodeDetector(videoRef, options);
  const native = useNativeBarcodeDetector(options);
  return isNativePlatform() ? native : web;
}
