interface Props {
  photoUrl: string;
  bbox?: { x: number; y: number; w: number; h: number };
  className?: string;
}

/** Small square preview of an item cropped out of its source photo, for browsing a results list
 * by "something that catches my eye" rather than reading titles. Crops a padded SQUARE region
 * centered on the bbox (not the bbox's own aspect ratio) so the preview never looks stretched —
 * a DVD spine's bbox is usually much taller than wide, and a non-square crop inside a square
 * thumbnail would otherwise distort it. Pure CSS (percentage-sized/positioned `<img>` inside an
 * `overflow-hidden` box) — no server-side cropping endpoint needed. */
export function BboxThumbnail({ photoUrl, bbox, className }: Props) {
  if (!bbox) {
    return <img src={photoUrl} alt="" className={`object-cover ${className ?? ''}`} />;
  }

  const size = Math.min(1, Math.max(bbox.w, bbox.h) * 1.6);
  const x = Math.min(Math.max(bbox.x + bbox.w / 2 - size / 2, 0), 1 - size);
  const y = Math.min(Math.max(bbox.y + bbox.h / 2 - size / 2, 0), 1 - size);

  return (
    <div className={`relative overflow-hidden ${className ?? ''}`}>
      <img
        src={photoUrl}
        alt=""
        className="absolute max-w-none"
        style={{
          width: `${100 / size}%`,
          height: `${100 / size}%`,
          left: `${(-x * 100) / size}%`,
          top: `${(-y * 100) / size}%`,
        }}
      />
    </div>
  );
}
