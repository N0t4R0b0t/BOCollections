// jscanify ships no types; the "./client" subpath (src/jscanify.js) is the browser build that
// reads the global `cv` (opencv.js) at call time — see utils/loadOpenCv.ts for how that's loaded.
declare module 'jscanify/client' {
  export interface JscanifyCornerPoint { x: number; y: number; }

  export interface JscanifyCornerPoints {
    topLeftCorner: JscanifyCornerPoint;
    topRightCorner: JscanifyCornerPoint;
    bottomLeftCorner: JscanifyCornerPoint;
    bottomRightCorner: JscanifyCornerPoint;
  }

  // An opaque OpenCV.js cv.Mat-backed contour — `unknown` here meant `if (!contour) return null`
  // narrowed it to `{}` afterward (TypeScript's approximation of "truthy unknown"), which has no
  // `delete` method to call for cleanup. Typing the one member callers actually use fixes that.
  export interface JscanifyContour {
    delete?(): void;
  }

  export default class jscanify {
    // Returns an opaque contour (cv.Mat-backed) or null if nothing was detected.
    findPaperContour(img: unknown): JscanifyContour | null;
    // cornerPoints are in the *natural pixel* space of `image`.
    getCornerPoints(contour: JscanifyContour): Partial<JscanifyCornerPoints>;
    highlightPaper(image: HTMLImageElement, options?: { color?: string; thickness?: number }): HTMLCanvasElement;
    extractPaper(
      image: HTMLImageElement,
      resultWidth: number,
      resultHeight: number,
      cornerPoints?: JscanifyCornerPoints,
    ): HTMLCanvasElement | null;
  }
}
