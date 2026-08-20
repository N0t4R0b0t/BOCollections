const STORAGE_KEY = 'apiServerUrl';

/** Strips whitespace and any trailing slash so it composes cleanly with a leading-slash path. */
function normalize(url: string): string {
  return url.trim().replace(/\/+$/, '');
}

export function getServerUrl(): string | null {
  return localStorage.getItem(STORAGE_KEY);
}

export function setServerUrl(url: string): string {
  const normalized = normalize(url);
  localStorage.setItem(STORAGE_KEY, normalized);
  return normalized;
}

export function clearServerUrl(): void {
  localStorage.removeItem(STORAGE_KEY);
}

/**
 * Confirms something that looks like a BOCollections backend is actually listening at this
 * address before we save it — /api/actuator/health is permitAll (see SecurityConfiguration) and
 * cheap, so this doubles as both a reachability and an "is this really our API" check.
 */
export async function testServerUrl(url: string): Promise<boolean> {
  const normalized = normalize(url);
  try {
    const controller = new AbortController();
    const timeout = setTimeout(() => controller.abort(), 5000);
    const res = await fetch(`${normalized}/api/actuator/health`, { signal: controller.signal });
    clearTimeout(timeout);
    return res.ok;
  } catch {
    return false;
  }
}
