import { useCallback, useRef, useState } from 'react';
import { Check, Loader2, Wand2, X } from 'lucide-react';
import jscanify from 'jscanify/client';
import { loadOpenCv } from '../../utils/loadOpenCv';

interface Props {
  /** Full data URI (`data:image/jpeg;base64,...`) or a same-origin media URL of the photo to
   * crop. Same-origin is required for the auto-detect/extract canvas ops — cross-origin sources
   * (e.g. an external reference cover image) would taint the canvas. */
  src: string;
  onCancel: () => void;
  /** Raw base64 (no `data:` prefix) of the cropped+straightened JPEG — matches how captured
   * photos are stored everywhere else in the app (see CapturedPhoto/PendingPhoto `.data`). */
  onCropped: (base64: string) => void;
}

type Corner = 'tl' | 'tr' | 'bl' | 'br';
type Handle = 'move' | Corner;
interface Point { x: number; y: number; }
// Fractions of the *rendered* image box, not natural pixels — resolution-independent, matches
// the coordinate convention the original axis-aligned version used.
interface Quad { tl: Point; tr: Point; bl: Point; br: Point; }

const CORNERS: Corner[] = ['tl', 'tr', 'bl', 'br'];
const CURSOR: Record<Corner, string> = { tl: 'nwse-resize', tr: 'nesw-resize', bl: 'nesw-resize', br: 'nwse-resize' };
const DEFAULT_QUAD: Quad = { tl: { x: 0.1, y: 0.1 }, tr: { x: 0.9, y: 0.1 }, bl: { x: 0.1, y: 0.9 }, br: { x: 0.9, y: 0.9 } };
const JPEG_QUALITY = 0.85;
const LOUPE_SIZE = 96;
const LOUPE_ZOOM = 3;

const clamp = (v: number, lo: number, hi: number) => Math.min(hi, Math.max(lo, v));

// Guards against jscanify locking onto a spurious internal high-contrast region (e.g. a patch of
// cover art) on photos that have no real background border to detect against — confirmed against
// a real already-tight reference-image draft photo during testing, where it returned a small
// sub-box instead of "nothing to crop, this is already framed". Below this fraction of the
// frame's area, treat it as no detection rather than surprise the user with a tiny box.
const MIN_DETECTED_AREA_FRACTION = 0.15;

// A background this saturated (0-255 scale) is a deliberate colored backdrop (a thrift-shelf
// photo mat, a colored bag/cloth), not a neutral surface — only then is color-distance
// segmentation (below) worth attempting. Below this, stay on jscanify's edge-based detection;
// running color segmentation against a low-saturation wood/gray/white background reproduces
// the exact failure this app's original heuristic-based cropper had (see CLAUDE.md) — with a
// weak color signal, "not background-colored" stops meaningfully separating object from surface.
const BACKGROUND_SATURATION_THRESHOLD = 60;

const scanner = new jscanify();

/** Shoelace formula, fractional coordinates. */
function quadArea(q: Quad): number {
  const pts = [q.tl, q.tr, q.br, q.bl];
  let sum = 0;
  for (let i = 0; i < pts.length; i++) {
    const a = pts[i];
    const b = pts[(i + 1) % pts.length];
    sum += a.x * b.y - b.x * a.y;
  }
  return Math.abs(sum) / 2;
}

function cornersToQuad(
  corners: { topLeftCorner?: Point; topRightCorner?: Point; bottomLeftCorner?: Point; bottomRightCorner?: Point },
  w: number, h: number
): Quad | null {
  const { topLeftCorner, topRightCorner, bottomLeftCorner, bottomRightCorner } = corners;
  if (!topLeftCorner || !topRightCorner || !bottomLeftCorner || !bottomRightCorner) return null;
  return {
    tl: { x: clamp(topLeftCorner.x / w, 0, 1), y: clamp(topLeftCorner.y / h, 0, 1) },
    tr: { x: clamp(topRightCorner.x / w, 0, 1), y: clamp(topRightCorner.y / h, 0, 1) },
    bl: { x: clamp(bottomLeftCorner.x / w, 0, 1), y: clamp(bottomLeftCorner.y / h, 0, 1) },
    br: { x: clamp(bottomRightCorner.x / w, 0, 1), y: clamp(bottomRightCorner.y / h, 0, 1) },
  };
}

/** Mean HSV of a border strip around the frame, sampled every 3rd pixel — cheap and assumes
 * (reasonably, for a scan/thrift photo) that the image edges are background, not the object.
 * Hue is circular-averaged (0/180 wrap in OpenCV's 0-180 hue range) so a backdrop whose sampled
 * hues straddle the wrap point doesn't average out to a nonsense mid-range hue. */
// eslint-disable-next-line @typescript-eslint/no-explicit-any
function sampleBackgroundHsv(cv: any, hsv: any): { hue: number; sat: number } {
  const w = hsv.cols, h = hsv.rows;
  const stripFrac = 0.06;
  const sw = Math.max(4, Math.round(w * stripFrac));
  const sh = Math.max(4, Math.round(h * stripFrac));
  const rects = [
    new cv.Rect(0, 0, w, sh), new cv.Rect(0, h - sh, w, sh),
    new cv.Rect(0, 0, sw, h), new cv.Rect(w - sw, 0, sw, h),
  ];
  let sSum = 0, n = 0, sinSum = 0, cosSum = 0;
  for (const r of rects) {
    const roi = hsv.roi(r);
    for (let y = 0; y < roi.rows; y += 3) {
      for (let x = 0; x < roi.cols; x += 3) {
        const px = roi.ucharPtr(y, x);
        const rad = (px[0] / 180) * Math.PI * 2;
        sinSum += Math.sin(rad);
        cosSum += Math.cos(rad);
        sSum += px[1];
        n++;
      }
    }
    roi.delete();
  }
  const hue = ((Math.atan2(sinSum / n, cosSum / n) / (Math.PI * 2)) * 180 + 180) % 180;
  return { hue, sat: sSum / n };
}

/** |hue - target| with 0-180 wraparound. Caller owns `hue`. */
// eslint-disable-next-line @typescript-eslint/no-explicit-any
function circularHueDelta(cv: any, hue: any, targetHue: number): any {
  const diff = new cv.Mat();
  const targetMat = new cv.Mat(hue.rows, hue.cols, hue.type(), new cv.Scalar(targetHue));
  cv.absdiff(hue, targetMat, diff);
  const inv = new cv.Mat();
  const fullMat = new cv.Mat(hue.rows, hue.cols, hue.type(), new cv.Scalar(180));
  cv.subtract(fullMat, diff, inv);
  cv.min(diff, inv, diff);
  targetMat.delete(); inv.delete(); fullMat.delete();
  return diff;
}

/** Detects the object by segmenting out whatever color the frame's border is (see
 * BACKGROUND_SATURATION_THRESHOLD) rather than edge detection. jscanify's stock Canny-based
 * findPaperContour was verified (against real photos on a solid-but-crinkled/reflective colored
 * background — see PR discussion) to fail two different ways depending on how it ranks candidate
 * contours: by raw contourArea it locks onto small internal high-contrast regions of the object's
 * own artwork; even ranked by convex-hull area (robust to self-intersecting edge-fragment
 * contours) it still never finds the true object boundary, because a glossy/reflective surface
 * breaks that edge into disconnected fragments that Canny+findContours never joins into one
 * closed contour in the first place — no ranking of the candidates that *were* found fixes that.
 * Color segmentation sidesteps the problem entirely: the object is whatever isn't background-
 * colored, independent of how broken its own edge is. Returns null on any failure (caller falls
 * back to jscanify, then to the default centered box). */
function detectQuadByColorSegmentation(
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  cv: any, src: any, w: number, h: number
): Quad | null {
  const hsv = new cv.Mat();
  cv.cvtColor(src, hsv, cv.COLOR_RGBA2RGB);
  cv.cvtColor(hsv, hsv, cv.COLOR_RGB2HSV);

  const bg = sampleBackgroundHsv(cv, hsv);
  if (bg.sat < BACKGROUND_SATURATION_THRESHOLD) {
    hsv.delete();
    return null;
  }

  const channels = new cv.MatVector();
  cv.split(hsv, channels);
  const hue = channels.get(0);
  const sat = channels.get(1);

  const hueDelta = circularHueDelta(cv, hue, bg.hue);
  const satDiff = new cv.Mat();
  const satTargetMat = new cv.Mat(sat.rows, sat.cols, sat.type(), new cv.Scalar(bg.sat));
  cv.absdiff(sat, satTargetMat, satDiff);

  // "background" = close to the sampled border color in both hue and saturation; the object is
  // everything else. Tolerances picked empirically against real test photos (see PR discussion).
  const hueMask = new cv.Mat();
  cv.threshold(hueDelta, hueMask, 12, 255, cv.THRESH_BINARY_INV);
  const satMask = new cv.Mat();
  cv.threshold(satDiff, satMask, 60, 255, cv.THRESH_BINARY_INV);
  const bgMask = new cv.Mat();
  cv.bitwise_and(hueMask, satMask, bgMask);
  const fgMask = new cv.Mat();
  cv.bitwise_not(bgMask, fgMask);

  // CLOSE fills small holes inside the object (glare, a light-colored label); OPEN (a bigger
  // kernel) sheds thin bridges/tabs left over from background noise — without it the object's
  // convex hull balloons out to include noise blobs barely touching its edge (confirmed live:
  // dropped a ~30% look-alike hull down to a tight fit on real test photos).
  const kernelClose = cv.getStructuringElement(cv.MORPH_RECT, new cv.Size(15, 15));
  const closed = new cv.Mat();
  cv.morphologyEx(fgMask, closed, cv.MORPH_CLOSE, kernelClose);
  const kernelOpen = cv.getStructuringElement(cv.MORPH_RECT, new cv.Size(21, 21));
  const cleaned = new cv.Mat();
  cv.morphologyEx(closed, cleaned, cv.MORPH_OPEN, kernelOpen);

  const contours = new cv.MatVector();
  const hierarchy = new cv.Mat();
  cv.findContours(cleaned, contours, hierarchy, cv.RETR_EXTERNAL, cv.CHAIN_APPROX_SIMPLE);

  let bestArea = 0, bestIdx = -1;
  for (let i = 0; i < contours.size(); i++) {
    const a = cv.contourArea(contours.get(i));
    if (a > bestArea) { bestArea = a; bestIdx = i; }
  }

  let result: Quad | null = null;
  if (bestIdx >= 0) {
    const winner = contours.get(bestIdx);
    const hull = new cv.Mat();
    cv.convexHull(winner, hull, false, true);
    const corners = scanner.getCornerPoints(hull);
    result = cornersToQuad(corners, w, h);
    hull.delete();
  }

  hsv.delete(); hue.delete(); sat.delete(); hueDelta.delete(); satDiff.delete(); satTargetMat.delete();
  hueMask.delete(); satMask.delete(); bgMask.delete(); fgMask.delete(); channels.delete();
  kernelClose.delete(); closed.delete(); kernelOpen.delete(); contours.delete(); hierarchy.delete();

  return result && quadArea(result) >= MIN_DETECTED_AREA_FRACTION ? result : null;
}

/** Detects the photographed object's quad, converting to a fractional Quad (0-1 of the natural
 * image size). Tries color-segmentation first (only against a sufficiently colorful background —
 * see BACKGROUND_SATURATION_THRESHOLD), then falls back to jscanify's edge-based detection.
 * Returns null if neither finds anything — callers fall back to the default centered quad.
 *
 * `img` MUST be an off-DOM image (never inserted/laid out), not the on-screen preview — confirmed
 * live that `cv.imread()` reads a CSS-styled, in-document `<img>` at its *rendered* box size
 * (e.g. 394×700 under `max-h-[70vh]`), not its natural resolution (e.g. 576×1024), while an
 * `Image()` that was never inserted into the DOM has no rendered size to fall back to and reads
 * at full natural resolution. Passing the visible preview element here (or to extractPaper below)
 * silently samples out of the smaller Mat's bounds for anything beyond ~70% of the frame, which
 * showed up as a real bug: the straightened output was mostly blank past that point. */
function detectQuad(img: HTMLImageElement): Quad | null {
  const cv = window.cv;
  if (!cv || !img.naturalWidth || !img.naturalHeight) return null;

  const w = img.naturalWidth;
  const h = img.naturalHeight;
  const mat = cv.imread(img);
  try {
    const byColor = detectQuadByColorSegmentation(cv, mat, w, h);
    if (byColor) return byColor;

    const contour = scanner.findPaperContour(mat);
    if (!contour) return null;
    const corners = scanner.getCornerPoints(contour);
    contour.delete?.();
    const detected = cornersToQuad(corners, w, h);
    return detected && quadArea(detected) >= MIN_DETECTED_AREA_FRACTION ? detected : null;
  } finally {
    mat.delete();
  }
}

function Loupe({ point, box, src }: { point: Point; box: { width: number; height: number }; src: string }) {
  const bgWidth = box.width * LOUPE_ZOOM;
  const bgHeight = box.height * LOUPE_ZOOM;
  const bgPosX = LOUPE_SIZE / 2 - point.x * bgWidth;
  const bgPosY = LOUPE_SIZE / 2 - point.y * bgHeight;

  const rawLeft = point.x * box.width - LOUPE_SIZE / 2;
  const left = clamp(rawLeft, 0, Math.max(0, box.width - LOUPE_SIZE));
  const preferAbove = point.y * box.height > LOUPE_SIZE + 32;
  const top = preferAbove
    ? point.y * box.height - LOUPE_SIZE - 24
    : Math.min(point.y * box.height + 24, Math.max(0, box.height - LOUPE_SIZE));

  return (
    <div
      className="absolute rounded-full border-2 border-white shadow-lg pointer-events-none overflow-hidden z-20"
      style={{
        width: LOUPE_SIZE, height: LOUPE_SIZE, left, top,
        backgroundImage: `url(${src})`,
        backgroundSize: `${bgWidth}px ${bgHeight}px`,
        backgroundPosition: `${bgPosX}px ${bgPosY}px`,
        backgroundRepeat: 'no-repeat',
      }}
    >
      <div className="absolute left-1/2 top-0 bottom-0 w-px bg-indigo-500/80 -translate-x-1/2" />
      <div className="absolute top-1/2 left-0 right-0 h-px bg-indigo-500/80 -translate-y-1/2" />
    </div>
  );
}

/** Freeform-quad crop + perspective-straighten before a captured-but-not-yet-uploaded photo is
 * saved (or, from Thread B, re-cropping an already-saved photo served same-origin). Corner
 * detection and the final warp both go through jscanify/opencv.js — lazy-loaded on first open,
 * see loadOpenCv.ts — since hand-rolled pixel heuristics didn't hold up against real photos with
 * textured backgrounds (see PR discussion / CLAUDE.md). */
export function PhotoCropModal({ src, onCancel, onCropped }: Props) {
  const imgRef = useRef<HTMLImageElement>(null);
  // Off-DOM twin of the preview image, used for every OpenCV call — see detectQuad's doc comment
  // for why the on-screen (CSS-constrained) element can't be used directly. Loaded once and
  // cached; `src` is stable for the modal's lifetime so there's nothing to invalidate it with.
  const fullResImgRef = useRef<HTMLImageElement | null>(null);
  const loadFullResImage = useCallback((): Promise<HTMLImageElement> => {
    if (fullResImgRef.current) return Promise.resolve(fullResImgRef.current);
    return new Promise((resolve, reject) => {
      const img = new Image();
      img.onload = () => { fullResImgRef.current = img; resolve(img); };
      img.onerror = () => reject(new Error('Failed to load image'));
      img.src = src;
    });
  }, [src]);

  const [quad, setQuad] = useState<Quad>(DEFAULT_QUAD);
  const dragRef = useRef<{ handle: Handle; startX: number; startY: number; startQuad: Quad } | null>(null);
  const [activeHandle, setActiveHandle] = useState<Handle | null>(null);
  const [activeBox, setActiveBox] = useState<{ width: number; height: number } | null>(null);
  const [extracting, setExtracting] = useState(false);
  const [detecting, setDetecting] = useState(false);
  const [cvUnavailable, setCvUnavailable] = useState(false);

  const runAutoCrop = useCallback(async () => {
    setDetecting(true);
    try {
      await loadOpenCv();
    } catch {
      // The script itself failed to load (network hiccup, blocked, etc.) — genuinely unavailable
      // for the rest of this modal's lifetime, so disable the button rather than let every future
      // click silently fail the same way.
      setCvUnavailable(true);
      setDetecting(false);
      return;
    }
    try {
      const fullResImg = await loadFullResImage();
      const detected = detectQuad(fullResImg);
      if (detected) setQuad(detected);
    } catch (e) {
      // opencv.js loaded fine but this particular detection attempt threw (a transient decode
      // issue, an image this heuristic just can't read, etc.) — not evidence the whole feature is
      // broken, so leave the button enabled and just no-op rather than bricking Auto permanently.
      console.warn('[crop] auto-detect failed', e);
    } finally {
      setDetecting(false);
    }
  }, [loadFullResImage]);

  // Takes the handle as a direct param (not curried into `(handle) => (e) => ...`) so this is
  // only ever invoked from inside an actual pointer-event handler, never during render itself —
  // `onPointerDown={beginDrag('move')}` called the outer function at render time to produce the
  // inner closure, which is exactly the shape react-hooks' ref-during-render check flags even
  // though the ref write itself only ever runs from the real event.
  const beginDrag = (handle: Handle, e: React.PointerEvent) => {
    e.preventDefault();
    e.stopPropagation();
    (e.target as Element).setPointerCapture(e.pointerId);
    setActiveHandle(handle);
    const box = imgRef.current?.getBoundingClientRect();
    setActiveBox(box ? { width: box.width, height: box.height } : null);
    dragRef.current = { handle, startX: e.clientX, startY: e.clientY, startQuad: quad };
  };

  const handlePointerMove = (e: React.PointerEvent) => {
    const drag = dragRef.current;
    const box = imgRef.current?.getBoundingClientRect();
    if (!drag || !box || box.width === 0 || box.height === 0) return;

    const dx = (e.clientX - drag.startX) / box.width;
    const dy = (e.clientY - drag.startY) / box.height;
    const s = drag.startQuad;

    if (drag.handle === 'move') {
      const xs = [s.tl.x, s.tr.x, s.bl.x, s.br.x];
      const ys = [s.tl.y, s.tr.y, s.bl.y, s.br.y];
      const cdx = clamp(dx, -Math.min(...xs), 1 - Math.max(...xs));
      const cdy = clamp(dy, -Math.min(...ys), 1 - Math.max(...ys));
      setQuad({
        tl: { x: s.tl.x + cdx, y: s.tl.y + cdy },
        tr: { x: s.tr.x + cdx, y: s.tr.y + cdy },
        bl: { x: s.bl.x + cdx, y: s.bl.y + cdy },
        br: { x: s.br.x + cdx, y: s.br.y + cdy },
      });
      return;
    }

    const point = { x: clamp(s[drag.handle].x + dx, 0, 1), y: clamp(s[drag.handle].y + dy, 0, 1) };
    setQuad({ ...s, [drag.handle]: point });
  };

  const endDrag = () => { dragRef.current = null; setActiveHandle(null); setActiveBox(null); };

  const confirmCrop = async () => {
    setExtracting(true);
    try {
      await loadOpenCv();
      const img = await loadFullResImage();

      const w = img.naturalWidth;
      const h = img.naturalHeight;
      const px = (p: Point) => ({ x: p.x * w, y: p.y * h });
      const dist = (a: Point, b: Point) => Math.hypot(a.x - b.x, a.y - b.y);
      const tl = px(quad.tl); const tr = px(quad.tr); const bl = px(quad.bl); const br = px(quad.br);

      const outW = Math.max(20, Math.round((dist(tl, tr) + dist(bl, br)) / 2));
      const outH = Math.max(20, Math.round((dist(tl, bl) + dist(tr, br)) / 2));

      const canvas = scanner.extractPaper(img, outW, outH, {
        topLeftCorner: tl, topRightCorner: tr, bottomLeftCorner: bl, bottomRightCorner: br,
      });
      if (!canvas) return;

      const dataUrl = canvas.toDataURL('image/jpeg', JPEG_QUALITY);
      onCropped(dataUrl.slice(dataUrl.indexOf(',') + 1));
    } finally {
      setExtracting(false);
    }
  };

  const pts = (q: Quad) => `${q.tl.x * 100},${q.tl.y * 100} ${q.tr.x * 100},${q.tr.y * 100} ${q.br.x * 100},${q.br.y * 100} ${q.bl.x * 100},${q.bl.y * 100}`;
  const quadPath = (q: Quad) =>
    `M${q.tl.x * 100},${q.tl.y * 100} L${q.tr.x * 100},${q.tr.y * 100} L${q.br.x * 100},${q.br.y * 100} L${q.bl.x * 100},${q.bl.y * 100} Z`;

  return (
    <div className="fixed inset-0 z-50 bg-black/95 flex flex-col" style={{ paddingTop: 'env(safe-area-inset-top, 0px)', paddingBottom: 'env(safe-area-inset-bottom, 0px)' }}>
      <div className="flex items-center justify-between px-4 py-3 shrink-0">
        <span className="text-white/70 text-sm">Drag the corners to crop</span>
        <button type="button" onClick={onCancel} className="text-white/70 hover:text-white p-1" title="Cancel">
          <X size={22} />
        </button>
      </div>

      <div className="flex-1 min-h-0 flex items-center justify-center p-4">
        <div className="relative inline-block touch-none" onPointerMove={handlePointerMove} onPointerUp={endDrag} onPointerCancel={endDrag}>
          <img
            ref={imgRef}
            src={src}
            alt=""
            className="max-w-full max-h-[70vh] object-contain select-none block"
            draggable={false}
            onLoad={() => void runAutoCrop()}
          />

          <svg className="absolute inset-0 w-full h-full" viewBox="0 0 100 100" preserveAspectRatio="none" style={{ touchAction: 'none' }}>
            <path
              d={`M0,0 H100 V100 H0 Z ${quadPath(quad)}`}
              fill="rgba(0,0,0,0.55)"
              fillRule="evenodd"
              style={{ pointerEvents: 'none' }}
            />
            <polygon
              points={pts(quad)}
              fill="transparent"
              stroke="white"
              strokeWidth="0.6"
              vectorEffect="non-scaling-stroke"
              onPointerDown={(e) => beginDrag('move', e)}
              style={{ cursor: 'move', pointerEvents: 'visibleFill' }}
            />
          </svg>

          {CORNERS.map((c) => (
            <div
              key={c}
              onPointerDown={(e) => beginDrag(c, e)}
              className="absolute w-11 h-11 flex items-center justify-center touch-none -translate-x-1/2 -translate-y-1/2"
              style={{ left: `${quad[c].x * 100}%`, top: `${quad[c].y * 100}%`, cursor: CURSOR[c] }}
            >
              <div className="w-5 h-5 bg-white rounded-full border-2 border-indigo-500 pointer-events-none" />
            </div>
          ))}

          {activeHandle && activeHandle !== 'move' && activeBox && (
            <Loupe point={quad[activeHandle]} box={activeBox} src={src} />
          )}
        </div>
      </div>

      <div className="flex items-center justify-center gap-3 py-3 shrink-0">
        <button type="button" onClick={onCancel} className="px-4 py-2 text-sm font-medium text-white/80 hover:text-white">
          Cancel
        </button>
        <button
          type="button"
          onClick={() => void runAutoCrop()}
          disabled={detecting || cvUnavailable}
          title={cvUnavailable ? 'Crop detection unavailable' : 'Re-detect crop bounds'}
          className="flex items-center gap-2 border border-white/30 text-white/90 rounded-full px-4 py-2 text-sm font-medium hover:bg-white/10 disabled:opacity-40"
        >
          {detecting ? <Loader2 size={16} className="animate-spin" /> : <Wand2 size={16} />} Auto
        </button>
        <button
          type="button"
          onClick={() => void confirmCrop()}
          disabled={extracting}
          className="flex items-center gap-2 bg-indigo-600 text-white rounded-full px-5 py-2 text-sm font-medium hover:bg-indigo-700 disabled:opacity-50"
        >
          <Check size={16} /> Crop
        </button>
      </div>
    </div>
  );
}
