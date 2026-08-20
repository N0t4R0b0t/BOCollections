/**
 * Locally-stored scan photos are served from an authenticated `/media/{key}` endpoint — plain
 * `<img src>` tags can't attach our Authorization header, so the backend also accepts the token
 * as a `?token=` query param for this path (see JwtAuthenticationFilter). External cover URLs
 * (TMDB/Discogs/OpenLibrary CDNs) are untouched — they're never auth-protected.
 *
 * The backend returns this path as bare `/media/{key}` (no context path) — every other request
 * in this app goes through axios's `/api` baseURL (or, in dev, Vite's `/api` proxy), so an
 * un-prefixed `/media/...` `<img src>` never reaches the backend at all; it 404s against
 * whatever's serving the frontend itself. Prepend `/api` here rather than changing what the
 * backend returns, since that path is also what `assertPhotoAccessible` etc. key off of.
 */
export function mediaUrl(url: string | undefined): string | undefined {
  if (!url || !url.startsWith('/media/')) return url;
  const token = localStorage.getItem('accessToken');
  const prefixed = `/api${url}`;
  return token ? `${prefixed}?token=${encodeURIComponent(token)}` : prefixed;
}
