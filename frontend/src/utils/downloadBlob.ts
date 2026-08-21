import type { AxiosResponse } from 'axios';

/** Pulls the filename the backend picked (Content-Disposition) rather than inventing our own —
 * falls back to a sensible default if it's ever missing. */
export function filenameFromResponse(res: AxiosResponse<Blob>, fallback: string): string {
  const disposition = res.headers['content-disposition'] as string | undefined;
  const match = disposition?.match(/filename\*?=(?:UTF-8'')?"?([^";]+)"?/i);
  return match ? decodeURIComponent(match[1]) : fallback;
}

export function downloadBlob(blob: Blob, filename: string) {
  const url = URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = url;
  a.download = filename;
  document.body.appendChild(a);
  a.click();
  a.remove();
  URL.revokeObjectURL(url);
}
