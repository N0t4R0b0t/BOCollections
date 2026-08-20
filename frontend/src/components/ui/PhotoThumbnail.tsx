import { Check, ChevronLeft, ChevronRight, Crop, Loader2, Trash2 } from 'lucide-react';
import { ANGLE_LABEL, TAGGABLE_ANGLES } from '../../types/scanSession';
import type { PhotoAngle } from '../../types/scanSession';

interface Props {
  src: string;
  isCover?: boolean;
  busy?: boolean;
  /** Omit for photo galleries with no angle taxonomy (thrift sighting photos — see CLAUDE.md's
   * "Photo galleries" section). */
  angle?: PhotoAngle;
  onAngleChange?: (angle: PhotoAngle) => void;
  onCrop?: () => void;
  onSetCover?: () => void;
  onDelete?: () => void;
  onClick?: () => void;
  /** Omit at the first/last position respectively — see ItemFormPage/AddPhotosPage/
   * ScanReviewPage's reorder handlers. */
  onMoveLeft?: () => void;
  onMoveRight?: () => void;
}

/** One saved-photo thumbnail with its action cluster (angle picker, crop, set-cover, delete,
 * reorder) — shared across ItemFormPage's inline gallery and AddPhotosPage's draft/item/sighting
 * targets so the three don't drift into separate bespoke implementations of the same interaction.
 * Every action prop is optional so callers omit what doesn't apply to their target. */
export function PhotoThumbnail({ src, isCover, busy, angle, onAngleChange, onCrop, onSetCover, onDelete, onClick, onMoveLeft, onMoveRight }: Props) {
  return (
    <div className={`relative shrink-0 w-24 rounded-lg overflow-hidden border ${isCover ? 'border-2 border-indigo-500' : 'border-gray-200'}`}>
      <button type="button" onClick={onClick} className="block w-full" disabled={!onClick} title={onClick ? 'View photo' : undefined}>
        <img src={src} alt="" className={`w-full h-28 object-cover bg-gray-100 ${onClick ? 'cursor-zoom-in' : ''}`} />
      </button>

      {busy && (
        <div className="absolute inset-0 bg-black/40 flex items-center justify-center">
          <Loader2 size={18} className="text-white animate-spin" />
        </div>
      )}

      {isCover && (
        <span className="absolute top-1 left-1 bg-indigo-600 text-white text-[10px] font-medium px-1.5 py-0.5 rounded">
          Cover
        </span>
      )}

      {(onMoveLeft || onMoveRight) && (
        <div className="absolute inset-x-1 top-1/2 -translate-y-1/2 flex items-center justify-between pointer-events-none">
          {onMoveLeft && (
            <button type="button" onClick={onMoveLeft} title="Move earlier" disabled={busy}
              className="pointer-events-auto bg-black/60 text-white rounded-full p-1 hover:bg-black/80 disabled:opacity-40">
              <ChevronLeft size={14} />
            </button>
          )}
          <span className="flex-1" />
          {onMoveRight && (
            <button type="button" onClick={onMoveRight} title="Move later" disabled={busy}
              className="pointer-events-auto bg-black/60 text-white rounded-full p-1 hover:bg-black/80 disabled:opacity-40">
              <ChevronRight size={14} />
            </button>
          )}
        </div>
      )}

      <div className="absolute top-1 right-1 flex gap-1">
        {onCrop && (
          <button type="button" onClick={onCrop} title="Crop" disabled={busy}
            className="bg-black/60 text-white rounded p-1 hover:bg-black/80 disabled:opacity-50">
            <Crop size={12} />
          </button>
        )}
        {onSetCover && !isCover && (
          <button type="button" onClick={onSetCover} title="Use as cover" disabled={busy}
            className="bg-black/60 text-white rounded p-1 hover:bg-black/80 disabled:opacity-50">
            <Check size={12} />
          </button>
        )}
        {onDelete && (
          <button type="button" onClick={onDelete} title="Delete" disabled={busy}
            className="bg-black/60 text-white rounded p-1 hover:bg-red-600 disabled:opacity-50">
            <Trash2 size={12} />
          </button>
        )}
      </div>

      {/* REFERENCE is never user-assignable (see TAGGABLE_ANGLES' doc comment) — a <select> whose
          value doesn't match any of its own <option>s (TAGGABLE_ANGLES excludes REFERENCE) just
          silently displays the first option instead, which mislabeled every fetched reference
          photo as "Front" here. Always render REFERENCE as the plain read-only label instead. */}
      {onAngleChange && angle && angle !== 'REFERENCE' && (
        <select
          value={angle}
          onChange={(e) => onAngleChange(e.target.value as PhotoAngle)}
          disabled={busy}
          className="absolute bottom-0 left-0 right-0 bg-black/70 text-white text-[10px] text-center py-0.5 border-0 focus:outline-none disabled:opacity-50"
        >
          {TAGGABLE_ANGLES.map((a) => <option key={a} value={a}>{ANGLE_LABEL[a]}</option>)}
        </select>
      )}
      {(!onAngleChange || angle === 'REFERENCE') && angle && (
        <span className="absolute bottom-0 left-0 right-0 bg-black/60 text-white text-[10px] text-center py-0.5">
          {ANGLE_LABEL[angle] ?? angle}
        </span>
      )}
    </div>
  );
}
