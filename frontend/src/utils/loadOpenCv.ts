// Lazy-loads opencv.js (~9MB WASM, served same-origin from public/vendor/ — see
// scripts/copy-opencv.js) the first time it's needed (PhotoCropModal opening), not as part of
// the main app bundle. Standard Emscripten readiness pattern: the script tag synchronously
// defines a `cv` factory object, but WASM compilation/instantiation finishes asynchronously —
// `cv['onRuntimeInitialized']` fires once `cv.Mat` etc. actually exist.
// opencv.js's WASM binding has hundreds of exported functions with no official types — `any` is
// the honest type here rather than maintaining a partial surface of a third-party binding.
// eslint-disable-next-line @typescript-eslint/no-explicit-any
type OpenCv = any;

declare global {
  interface Window {
    cv?: OpenCv;
  }
}

let cvPromise: Promise<void> | null = null;

export function loadOpenCv(): Promise<void> {
  if (window.cv?.Mat) return Promise.resolve();
  if (cvPromise) return cvPromise;

  cvPromise = new Promise((resolve, reject) => {
    const script = document.createElement('script');
    script.src = '/vendor/opencv.js';
    script.async = true;
    script.onerror = () => {
      cvPromise = null;
      reject(new Error('Failed to load crop tools'));
    };
    script.onload = () => {
      if (!window.cv) {
        cvPromise = null;
        reject(new Error('Failed to load crop tools'));
        return;
      }
      if (window.cv.Mat) {
        resolve();
        return;
      }
      window.cv.onRuntimeInitialized = () => resolve();
    };
    document.head.appendChild(script);
  });

  return cvPromise;
}
