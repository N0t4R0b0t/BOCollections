import { useEffect, useRef, useState } from 'react';
import { X, ChevronLeft, ChevronRight, Trash2, Crop, Check, MoveLeft, MoveRight } from 'lucide-react';
import { ANGLE_LABEL, TAGGABLE_ANGLES } from '../../types/scanSession';
import type { PhotoAngle } from '../../types/scanSession';

export interface LightboxPhoto {
  id: string | number;
  src: string;
  label?: string;
  /** Omit for galleries with no angle taxonomy (thrift sighting photos) or not-yet-uploaded
   * pending photos where cover-selection doesn't apply. */
  angle?: PhotoAngle;
  isCover?: boolean;
}

interface Props {
  photos: LightboxPhoto[];
  index: number;
  onIndexChange: (index: number) => void;
  onClose: () => void;
  /** Omit to hide the delete action entirely (e.g. a read-only viewer). */
  onDelete?: (id: string | number) => void | Promise<void>;
  deletingId?: string | number | null;
  /** Any of these three being present pulls up the same action cluster PhotoThumbnail shows on
   * its own thumbnails — so a photo opened fullscreen isn't missing anything the small thumbnail
   * could already do. */
  onCrop?: (id: string | number) => void;
  onSetCover?: (id: string | number) => void | Promise<void>;
  onAngleChange?: (id: string | number, angle: PhotoAngle) => void | Promise<void>;
  /** Reorders this photo earlier/later in the gallery — omit either at the first/last position. */
  onMoveLeft?: (id: string | number) => void | Promise<void>;
  onMoveRight?: (id: string | number) => void | Promise<void>;
}

// Swipe past this many px horizontally (and more horizontal than vertical movement) to treat a
// touch gesture as prev/next rather than an accidental drag/scroll.
const SWIPE_THRESHOLD_PX = 50;

/** Near-fullscreen photo viewer shared by every "strip of small thumbnails" spot in the app —
 * review drafts, in-progress scan captures, item/thrift photo management. Tapping a thumbnail
 * opens here instead of leaving zooming/deleting to a 64px-tall image. */
export function PhotoLightbox({ photos, index, onIndexChange, onClose, onDelete, deletingId, onCrop, onSetCover, onAngleChange, onMoveLeft, onMoveRight }: Props) {
  const [deleting, setDeleting] = useState(false);
  const [busy, setBusy] = useState(false);
  const touchStart = useRef<{ x: number; y: number } | null>(null);
  const photo = photos[index];

  const goPrev = () => onIndexChange((index - 1 + photos.length) % photos.length);
  const goNext = () => onIndexChange((index + 1) % photos.length);

  useEffect(() => {
    const handleKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') onClose();
      else if (e.key === 'ArrowLeft' && photos.length > 1) goPrev();
      else if (e.key === 'ArrowRight' && photos.length > 1) goNext();
    };
    window.addEventListener('keydown', handleKey);
    return () => window.removeEventListener('keydown', handleKey);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [index, photos.length]);

  // Closing (photos.length drops to 0 after the last delete) or the current index falling off
  // the end (deleted the last photo in the list) both need to resolve to *something* sane —
  // clamp back onto the new last photo rather than rendering past the end of the array.
  useEffect(() => {
    if (photos.length === 0) { onClose(); return; }
    if (index >= photos.length) onIndexChange(photos.length - 1);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [photos.length]);

  if (!photo) return null;

  const handleDelete = async () => {
    if (!onDelete) return;
    setDeleting(true);
    try {
      await onDelete(photo.id);
    } finally {
      setDeleting(false);
    }
  };

  const handleSetCover = async () => {
    if (!onSetCover) return;
    setBusy(true);
    try {
      await onSetCover(photo.id);
    } finally {
      setBusy(false);
    }
  };

  const handleAngleChange = async (angle: PhotoAngle) => {
    if (!onAngleChange) return;
    setBusy(true);
    try {
      await onAngleChange(photo.id, angle);
    } finally {
      setBusy(false);
    }
  };

  // Follows the moved photo to its new position rather than leaving the viewer on whatever
  // unrelated photo now occupies the old index.
  const handleMoveLeft = async () => {
    if (!onMoveLeft) return;
    setBusy(true);
    try {
      await onMoveLeft(photo.id);
      onIndexChange(Math.max(0, index - 1));
    } finally {
      setBusy(false);
    }
  };

  const handleMoveRight = async () => {
    if (!onMoveRight) return;
    setBusy(true);
    try {
      await onMoveRight(photo.id);
      onIndexChange(Math.min(photos.length - 1, index + 1));
    } finally {
      setBusy(false);
    }
  };

  const handleTouchStart = (e: React.TouchEvent) => {
    const t = e.touches[0];
    touchStart.current = { x: t.clientX, y: t.clientY };
  };

  const handleTouchEnd = (e: React.TouchEvent) => {
    const start = touchStart.current;
    touchStart.current = null;
    if (!start || photos.length <= 1) return;
    const t = e.changedTouches[0];
    const dx = t.clientX - start.x;
    const dy = t.clientY - start.y;
    if (Math.abs(dx) > SWIPE_THRESHOLD_PX && Math.abs(dx) > Math.abs(dy)) {
      if (dx > 0) goPrev(); else goNext();
    }
  };

  const isDeleting = deleting || deletingId === photo.id;
  const hasActions = onDelete || onCrop || onSetCover || onAngleChange || onMoveLeft || onMoveRight;

  return (
    <div
      className="fixed inset-0 z-50 bg-black/95 flex flex-col"
      style={{ paddingTop: 'env(safe-area-inset-top, 0px)', paddingBottom: 'env(safe-area-inset-bottom, 0px)' }}
      onClick={onClose}
    >
      <div className="flex items-center justify-between px-4 py-3 shrink-0" onClick={(e) => e.stopPropagation()}>
        <span className="text-white/70 text-sm">
          {photos.length > 1 ? `${index + 1} / ${photos.length}` : null}
          {photo.label && <span className="ml-2 text-white/90 font-medium">{photo.label}</span>}
          {photo.isCover && <span className="ml-2 text-indigo-300 font-medium">· Cover</span>}
        </span>
        <button type="button" onClick={onClose} className="text-white/70 hover:text-white p-1" title="Close">
          <X size={22} />
        </button>
      </div>

      <div
        className="flex-1 relative min-h-0 flex items-center justify-center"
        onClick={(e) => e.stopPropagation()}
        onTouchStart={handleTouchStart}
        onTouchEnd={handleTouchEnd}
      >
        <img src={photo.src} alt={photo.label ?? ''} className="max-w-full max-h-full object-contain" />

        {photos.length > 1 && (
          <>
            <button
              type="button"
              onClick={goPrev}
              className="absolute left-2 top-1/2 -translate-y-1/2 bg-black/50 text-white rounded-full p-2 hover:bg-black/70"
              title="Previous photo"
            >
              <ChevronLeft size={22} />
            </button>
            <button
              type="button"
              onClick={goNext}
              className="absolute right-2 top-1/2 -translate-y-1/2 bg-black/50 text-white rounded-full p-2 hover:bg-black/70"
              title="Next photo"
            >
              <ChevronRight size={22} />
            </button>
          </>
        )}
      </div>

      {hasActions && (
        <div className="flex items-center justify-center gap-2 py-3 px-3 shrink-0 flex-wrap" onClick={(e) => e.stopPropagation()}>
          {onAngleChange && photo.angle && (
            <select
              value={photo.angle}
              onChange={(e) => void handleAngleChange(e.target.value as PhotoAngle)}
              disabled={busy}
              className="bg-white/10 text-white text-sm rounded-full px-3 py-2 border border-white/20 focus:outline-none disabled:opacity-50"
            >
              {TAGGABLE_ANGLES.map((a) => <option key={a} value={a} className="text-gray-900">{ANGLE_LABEL[a]}</option>)}
            </select>
          )}
          {onMoveLeft && index > 0 && (
            <button
              type="button"
              onClick={() => void handleMoveLeft()}
              disabled={busy}
              title="Move earlier in gallery"
              className="flex items-center gap-2 bg-white/10 text-white rounded-full px-4 py-2 text-sm font-medium hover:bg-white/20 disabled:opacity-50"
            >
              <MoveLeft size={16} /> Move earlier
            </button>
          )}
          {onMoveRight && index < photos.length - 1 && (
            <button
              type="button"
              onClick={() => void handleMoveRight()}
              disabled={busy}
              title="Move later in gallery"
              className="flex items-center gap-2 bg-white/10 text-white rounded-full px-4 py-2 text-sm font-medium hover:bg-white/20 disabled:opacity-50"
            >
              <MoveRight size={16} /> Move later
            </button>
          )}
          {onCrop && (
            <button
              type="button"
              onClick={() => onCrop(photo.id)}
              disabled={busy}
              className="flex items-center gap-2 bg-white/10 text-white rounded-full px-4 py-2 text-sm font-medium hover:bg-white/20 disabled:opacity-50"
            >
              <Crop size={16} /> Crop
            </button>
          )}
          {onSetCover && !photo.isCover && (
            <button
              type="button"
              onClick={() => void handleSetCover()}
              disabled={busy}
              className="flex items-center gap-2 bg-white/10 text-white rounded-full px-4 py-2 text-sm font-medium hover:bg-white/20 disabled:opacity-50"
            >
              <Check size={16} /> Use as cover
            </button>
          )}
          {onDelete && (
            <button
              type="button"
              onClick={() => void handleDelete()}
              disabled={isDeleting}
              className="flex items-center gap-2 bg-red-600/90 text-white rounded-full px-4 py-2 text-sm font-medium hover:bg-red-600 disabled:opacity-50"
            >
              <Trash2 size={16} /> {isDeleting ? 'Removing…' : 'Remove'}
            </button>
          )}
        </div>
      )}
    </div>
  );
}
