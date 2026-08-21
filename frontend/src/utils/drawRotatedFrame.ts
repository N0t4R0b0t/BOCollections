export type Rotation = 0 | 90 | 180 | 270;

/**
 * Draws a <video> frame onto a canvas with a rotation applied — shared by the barcode decoder,
 * photo capture, and the live preview so all three always agree on "which way is up", no matter
 * how the phone's held. For 90/270 the canvas is sized to the *rotated* (swapped) dimensions.
 */
export function drawRotatedFrame(
  video: HTMLVideoElement, canvas: HTMLCanvasElement, rotation: Rotation, filter = 'none',
) {
  const swapped = rotation === 90 || rotation === 270;
  canvas.width = swapped ? video.videoHeight : video.videoWidth;
  canvas.height = swapped ? video.videoWidth : video.videoHeight;

  const ctx = canvas.getContext('2d', { willReadFrequently: true });
  if (!ctx) return null;

  ctx.save();
  ctx.translate(canvas.width / 2, canvas.height / 2);
  ctx.rotate((rotation * Math.PI) / 180);
  // Baking the same CSS filter used for the live low-light preview into the drawImage call means
  // a captured photo matches what the user actually saw and framed, instead of reverting to the
  // raw (dim) sensor output the instant the shutter fires.
  ctx.filter = filter;
  ctx.drawImage(video, -video.videoWidth / 2, -video.videoHeight / 2);
  ctx.restore();
  return ctx;
}
