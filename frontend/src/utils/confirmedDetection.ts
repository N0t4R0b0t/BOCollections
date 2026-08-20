/**
 * Barcode scanners here (both native ML Kit and the web zxing fallback) read the *entire* camera
 * frame, not just what's inside the on-screen reticle — the reticle is purely a visual aiming
 * guide, not an actual scan boundary. In a cluttered scene (a shelf full of other items, each
 * with their own barcode) that means a single stray glimpse of a background barcode can register
 * as a confident, completely wrong match. Requiring the same value to be read `requiredHits`
 * times in a row within `windowMs` filters that out — a barcode you're deliberately holding
 * steady re-reads almost instantly on a continuous scanner, while a background item glimpsed for
 * one frame as the camera moves past it won't repeat.
 */
export function createRepeatConfirmer(requiredHits = 2, windowMs = 1000) {
  let value: string | null = null;
  let hits = 0;
  let firstSeenAt = 0;

  return (candidate: string): boolean => {
    const now = Date.now();
    if (candidate !== value || now - firstSeenAt > windowMs) {
      value = candidate;
      hits = 1;
      firstSeenAt = now;
      return requiredHits <= 1;
    }
    hits += 1;
    return hits >= requiredHits;
  };
}
