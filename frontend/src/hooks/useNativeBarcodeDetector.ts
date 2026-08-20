import { useRef, useEffect, useCallback, useState } from 'react';
import { BarcodeScanner, BarcodeFormat, GoogleBarcodeScannerModuleInstallState } from '@capacitor-mlkit/barcode-scanning';
import type { PluginListenerHandle } from '@capacitor/core';
import type { BarcodeDetectorStatus } from './useBarcodeDetector';
import { createRepeatConfirmer } from '../utils/confirmedDetection';

interface Options {
  onDetected: (barcode: string, format: string) => void;
  debounceMs?: number;
}

const FORMATS = [
  BarcodeFormat.Ean13, BarcodeFormat.Ean8, BarcodeFormat.UpcA, BarcodeFormat.UpcE,
  BarcodeFormat.Code128, BarcodeFormat.Code39, BarcodeFormat.QrCode,
];

export type ModuleStatus = 'unknown' | 'checking' | 'installing' | 'ready' | 'unavailable';

/**
 * Native ML Kit barcode scanner (Android/iOS only) — drives its own native camera session with
 * its own autofocus, entirely bypassing the getUserMedia focus problems useBarcodeDetector fights.
 * `startScan()` makes the WebView background transparent so the native camera preview shows
 * through; see the `.native-scan-active` rule in global.css for the web side of that.
 */
export function useNativeBarcodeDetector({ onDetected, debounceMs = 1500 }: Options) {
  const [status, setStatus] = useState<BarcodeDetectorStatus>('idle');
  const [moduleStatus, setModuleStatus] = useState<ModuleStatus>('unknown');
  const lastDetectedRef = useRef<{ value: string; ts: number } | null>(null);
  const listenerRef = useRef<PluginListenerHandle | null>(null);
  const confirmRef = useRef(createRepeatConfirmer());
  // start()/resume() gate on the current status, but a caller sequencing pause() then start()
  // back-to-back in the same tick (e.g. forcing a restart on app resume) can't wait for a
  // re-render between them — the `start` closure they already hold still has the pre-pause
  // status baked in. A ref always reads the latest value regardless of which closure called it.
  const statusRef = useRef<BarcodeDetectorStatus>('idle');
  const setStatusTracked = useCallback((s: BarcodeDetectorStatus) => {
    statusRef.current = s;
    setStatus(s);
  }, []);

  const setBodyTransparent = (active: boolean) => {
    document.body.classList.toggle('native-scan-active', active);
  };

  /**
   * The actual on-device detection model is a separate Google Play Services module, not bundled
   * in the app — on a device that's never used it before, startScan() can succeed and run
   * forever without ever detecting anything if this was never downloaded. Confirmed missing on
   * a real device: continuous scanning ran with no crashes but never matched a single barcode.
   */
  const ensureModuleAvailable = useCallback(async (): Promise<boolean> => {
    setModuleStatus('checking');
    try {
      const { available } = await BarcodeScanner.isGoogleBarcodeScannerModuleAvailable();
      if (available) {
        setModuleStatus('ready');
        return true;
      }
    } catch (e) {
      console.warn('[native-scanner] isGoogleBarcodeScannerModuleAvailable failed', e);
    }

    setModuleStatus('installing');
    try {
      const installed = await new Promise<boolean>((resolve) => {
        let handle: PluginListenerHandle | undefined;
        const timeout = setTimeout(() => { handle?.remove(); resolve(false); }, 30_000);
        BarcodeScanner.addListener('googleBarcodeScannerModuleInstallProgress', (event) => {
          if (event.state === GoogleBarcodeScannerModuleInstallState.COMPLETED) {
            clearTimeout(timeout); handle?.remove(); resolve(true);
          } else if (event.state === GoogleBarcodeScannerModuleInstallState.FAILED) {
            clearTimeout(timeout); handle?.remove(); resolve(false);
          }
        }).then((h) => { handle = h; });
        BarcodeScanner.installGoogleBarcodeScannerModule().catch(() => {
          clearTimeout(timeout); handle?.remove(); resolve(false);
        });
      });
      setModuleStatus(installed ? 'ready' : 'unavailable');
      return installed;
    } catch (e) {
      console.warn('[native-scanner] installGoogleBarcodeScannerModule failed', e);
      setModuleStatus('unavailable');
      return false;
    }
  }, []);

  const startSession = useCallback(async () => {
    // Only flip the WebView transparent once the native scan has actually started — doing it
    // beforehand meant a startScan() failure (e.g. camera hardware not yet released by a
    // getUserMedia handoff) left a transparent overlay with no live native camera behind it,
    // which reads as a frozen/black frame with no way to recover short of leaving the screen.
    await BarcodeScanner.startScan({ formats: FORMATS });
    setBodyTransparent(true);
    setStatusTracked('scanning');
  }, [setStatusTracked]);

  const start = useCallback(async () => {
    if (statusRef.current === 'scanning') return;
    const { camera } = await BarcodeScanner.checkPermissions();
    if (camera !== 'granted' && camera !== 'limited') {
      const requested = await BarcodeScanner.requestPermissions();
      if (requested.camera !== 'granted' && requested.camera !== 'limited') {
        setStatusTracked('idle');
        return;
      }
    }

    confirmRef.current = createRepeatConfirmer();
    listenerRef.current = await BarcodeScanner.addListener('barcodesScanned', (event) => {
      const barcode = event.barcodes[0];
      if (!barcode) return;
      const rawValue = barcode.rawValue ?? barcode.displayValue;
      if (!confirmRef.current(rawValue)) return;

      const now = Date.now();
      const last = lastDetectedRef.current;
      if (!last || last.value !== rawValue || now - last.ts > debounceMs) {
        lastDetectedRef.current = { value: rawValue, ts: now };
        onDetected(rawValue, barcode.format);
      }
    });

    // Best-effort either way — some devices misreport availability, so still try to scan even
    // if this comes back false rather than blocking the user out entirely.
    await ensureModuleAvailable();
    await startSession();
  }, [onDetected, debounceMs, startSession, ensureModuleAvailable, setStatusTracked]);

  const pause = useCallback(async () => {
    await BarcodeScanner.stopScan();
    setBodyTransparent(false);
    setStatusTracked('paused');
  }, [setStatusTracked]);

  const resume = useCallback(async () => {
    if (statusRef.current !== 'paused') return;
    try {
      await startSession();
    } catch (e) {
      // A getUserMedia handoff (see useCaptureLoop's native capturePhoto) releases the camera
      // asynchronously — the OS doesn't always have it free the instant stop() resolves, so an
      // immediate restart here can fail. One retry after a short delay covers that race; if it
      // still fails, surface an idle/opaque state (never a frozen transparent overlay) so the
      // caller can fall back to a full start() instead of being stuck.
      console.warn('[native-scanner] resume failed, retrying', e);
      await new Promise((r) => setTimeout(r, 600));
      try {
        await startSession();
      } catch (e2) {
        console.warn('[native-scanner] resume retry failed', e2);
        setBodyTransparent(false);
        setStatusTracked('idle');
        throw e2;
      }
    }
  }, [startSession, setStatusTracked]);

  useEffect(() => () => {
    listenerRef.current?.remove();
    // This hook is always mounted regardless of platform (see useScannerBarcodeDetector's doc
    // comment) — on a plain web tab this plugin method isn't implemented at all, so an unmount
    // there (leaving the scan/thrift capture page) would otherwise throw an unhandled
    // "not available on this platform" rejection on every single visit. Harmless to swallow:
    // on web nothing was ever actually scanning; on native, stopping an already-idle/already-
    // stopped session is a no-op.
    void BarcodeScanner.stopScan().catch(() => {});
    setBodyTransparent(false);
  }, []);

  return { isSupported: true, status, moduleStatus, start, pause, resume };
}
