import { forwardRef, useState } from 'react';
import clsx from 'clsx';
import type { Rotation } from '../../utils/drawRotatedFrame';

interface Props {
  scanning: boolean;
  className?: string;
  /** 0/180 keep the video's native (landscape) aspect; 90/270 swap the container to portrait. */
  rotation?: Rotation;
  /** Tap-to-focus — omit to disable the tap handler entirely (e.g. camera has no focus control at all). */
  onFocus?: () => void;
  /**
   * True while the native ML Kit scanner (Android/iOS app only) owns the camera — its preview
   * renders *behind* the WebView, so instead of our own <video> element (which would just show
   * black, and would fight the native session for the camera) this becomes a transparent hole
   * for that native feed to show through. See the `.native-scan-active` rule in global.css.
   */
  transparent?: boolean;
  /**
   * True when the caller is positioning this element itself (e.g. `absolute inset-0` filling a
   * full-screen parent, as ScanCapturePage's native photo-burst view does) — skips the default
   * `aspect-video`/`aspect-3/4` class in that case. Those classes conflict with an absolutely-
   * positioned fill: both try to control the box's dimensions, and stacking them produced a
   * visibly broken render on a real device (looked like two different crops of the same scene
   * stacked on top of each other) rather than a clean CSS override of one by the other.
   */
  fill?: boolean;
  /** CSS `filter` applied to the live <video> (e.g. LOW_LIGHT_FILTER from useCamera) — only
   * meaningful for a getUserMedia-backed preview; the native ML Kit scan view renders behind the
   * WebView via a separate hardware surface this element never actually shows, so a filter here
   * has nothing to visibly affect while `transparent` is set. */
  filter?: string;
}

/** Live video element. The ref is forwarded so the parent can feed it to hooks. */
export const CameraPreview = forwardRef<HTMLVideoElement, Props>(({ scanning, className, rotation = 0, onFocus, transparent, fill, filter }, ref) => {
  const portrait = rotation === 90 || rotation === 270;
  const [pulse, setPulse] = useState<{ x: number; y: number } | null>(null);

  const handleTap = (e: React.MouseEvent<HTMLDivElement>) => {
    if (!onFocus) return;
    const rect = e.currentTarget.getBoundingClientRect();
    setPulse({ x: e.clientX - rect.left, y: e.clientY - rect.top });
    setTimeout(() => setPulse(null), 600);
    onFocus();
  };

  return (
    <div
      onClick={handleTap}
      className={clsx(
        // Tailwind's generated stylesheet defines `.relative` after `.absolute`, so when both
        // classes land on the same element they tie on specificity and `.relative` always wins
        // the cascade — a caller passing `fill` (which hands in `absolute inset-0` via
        // `className` below) would silently stay `position: relative` and never actually fill
        // its parent. Only apply the default `relative` when the caller isn't overriding position.
        !fill && 'relative',
        'overflow-hidden rounded-xl',
        transparent ? 'bg-transparent' : 'bg-black',
        !fill && (portrait ? 'aspect-3/4' : 'aspect-video'),
        onFocus && 'cursor-crosshair',
        // When transparent (native ML Kit feed showing through), this box is purely a visual
        // hole — nothing to tap here. Android's WebView has been observed routing touches to a
        // hardware-decoded <video> surface's *last-known* on-screen position even after its CSS
        // box has moved/shrunk on a subsequent relayout (e.g. the drawer resizing this container
        // when a photo burst ends) — pointer-events-none here means this element, wherever the
        // hardware surface actually thinks it is, can never swallow a tap meant for something
        // else on the page.
        transparent && 'pointer-events-none',
        className,
      )}
    >
      {/* Always mounted, even in transparent mode — a caller that later flips `transparent` to
          false (e.g. entering a native photo-capture burst, see ScanCapturePage) needs the same
          DOM node to still be there so the stream `useCamera.start()` may have already attached
          via this ref doesn't get silently dropped by an unmount/remount. Invisible via opacity
          rather than removed, so the transparent container's background still shows the native
          camera behind it (see .native-scan-active in global.css) instead of a blank video. */}
      <video
        ref={ref}
        playsInline
        muted
        // object-cover's crop math runs before a CSS transform is applied, so rotating a
        // w-full/h-full/object-cover video 90° crops it against the *pre-rotation* box and looks
        // wrong. Instead: size the element to the source's own (unrotated) aspect ratio, centre
        // it, then rotate — always correctly oriented and uncropped, even if it doesn't fill
        // every corner of a portrait box edge-to-edge.
        className={clsx(
          transparent && 'opacity-0 pointer-events-none',
          portrait ? 'absolute top-1/2 left-1/2 h-full w-auto' : 'w-full h-full object-cover',
        )}
        style={{ ...(portrait ? { transform: `translate(-50%, -50%) rotate(${rotation}deg)` } : {}), filter }}
      />

      {/* Tap-to-focus feedback ring */}
      {pulse && (
        <div
          className="absolute w-14 h-14 -ml-7 -mt-7 rounded-full border-2 border-white/80 pointer-events-none animate-ping"
          style={{ left: pulse.x, top: pulse.y }}
        />
      )}

      {/* Targeting reticle — a centred aiming rectangle */}
      {scanning && (
        <div className="absolute inset-0 flex items-center justify-center pointer-events-none">
          <div className="relative w-56 h-32">
            {/* Corner accents */}
            {['top-0 left-0', 'top-0 right-0', 'bottom-0 left-0', 'bottom-0 right-0'].map((pos, i) => (
              <span
                key={i}
                className={clsx(
                  'absolute w-5 h-5 border-indigo-400',
                  pos,
                  i === 0 && 'border-t-2 border-l-2 rounded-tl',
                  i === 1 && 'border-t-2 border-r-2 rounded-tr',
                  i === 2 && 'border-b-2 border-l-2 rounded-bl',
                  i === 3 && 'border-b-2 border-r-2 rounded-br',
                )}
              />
            ))}
            {/* Animated scan line */}
            <div className="absolute inset-x-0 h-0.5 bg-indigo-400/60 animate-[scanline_2s_ease-in-out_infinite]" style={{ top: '50%' }} />
          </div>
        </div>
      )}
    </div>
  );
});

CameraPreview.displayName = 'CameraPreview';
