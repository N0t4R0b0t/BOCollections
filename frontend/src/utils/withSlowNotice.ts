/**
 * Fires `onSlow` if `promise` hasn't settled within `delayMs` — used to upgrade a status
 * message ("Taking a closer look…") to a "this is genuinely still working" variant so a slow
 * (but healthy) vision-model call doesn't read as a stuck/broken UI.
 */
export function withSlowNotice<T>(promise: Promise<T>, onSlow: () => void, delayMs = 6000): Promise<T> {
  const timer = setTimeout(onSlow, delayMs);
  return promise.finally(() => clearTimeout(timer));
}
