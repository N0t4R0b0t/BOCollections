/** Produces a downscaled companion copy of an already-captured JPEG, purely for a vision-model
 * upload — the caller's own (full-resolution) copy is untouched and is what actually gets stored.
 * See captureFrame's maxDimension param, which this replaces at call sites that need the captured
 * frame to serve double duty as both a permanent gallery photo and a vision-model input: applying
 * that downscale at capture time was silently throwing away resolution on the stored copy too. */
export function downscaleJpeg(base64: string, maxDimension: number, quality = 0.85): Promise<string> {
  return new Promise((resolve, reject) => {
    const img = new Image();
    img.onload = () => {
      const scale = Math.min(1, maxDimension / Math.max(img.naturalWidth, img.naturalHeight));
      if (scale >= 1) { resolve(base64); return; } // already small enough, no re-encode needed
      const canvas = document.createElement('canvas');
      canvas.width = Math.round(img.naturalWidth * scale);
      canvas.height = Math.round(img.naturalHeight * scale);
      const ctx = canvas.getContext('2d');
      if (!ctx) { reject(new Error('Could not get 2d context')); return; }
      ctx.drawImage(img, 0, 0, canvas.width, canvas.height);
      const dataUrl = canvas.toDataURL('image/jpeg', quality);
      resolve(dataUrl.slice(dataUrl.indexOf(',') + 1));
    };
    img.onerror = () => reject(new Error('Failed to load image for downscale'));
    img.src = `data:image/jpeg;base64,${base64}`;
  });
}
