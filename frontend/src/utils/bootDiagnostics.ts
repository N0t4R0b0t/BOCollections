/**
 * Temporary — reports app boot progress and any crash straight to the backend log
 * (see backend DebugController), for cases like a native-app black screen where there's no
 * console access. Uses raw fetch (not apiClient) so it has no dependency on anything else in
 * the app actually working. Remove once the native-app camera/boot investigation is done.
 */
// Absolute, not relative — a bundled (non-live-reload) native build's WebView origin is
// https://localhost, where a relative /api/... path resolves to nothing. Hardcoded on purpose:
// this whole module is a temporary diagnostic tool, not something that needs to follow the
// app's normal environment-aware API base URL.
const DEBUG_LOG_URL = 'https://boc-dev.nj-server2.local/api/debug/log';

function report(data: Record<string, unknown>) {
  try {
    fetch(DEBUG_LOG_URL, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ ...data, href: location.href, userAgent: navigator.userAgent, ts: Date.now() }),
    }).catch(() => {});
  } catch {
    // fetch itself throwing synchronously would be extremely unusual — nothing more to do
  }
}

report({ event: 'boot-start' });

window.addEventListener('error', (e) => {
  report({ event: 'window-error', message: e.message, filename: e.filename, lineno: e.lineno, colno: e.colno, stack: e.error?.stack });
});

window.addEventListener('unhandledrejection', (e) => {
  const reason = e.reason;
  report({
    event: 'unhandled-rejection',
    reason: reason instanceof Error ? reason.message : String(reason),
    stack: reason instanceof Error ? reason.stack : undefined,
  });
});

export function reportBootDiagnostic(data: Record<string, unknown>) {
  report(data);
}
