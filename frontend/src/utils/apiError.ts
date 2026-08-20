/** Extracts a human-readable message from an Axios error response. */
export function apiError(e: unknown, fallback = 'Something went wrong'): string {
  return (e as { response?: { data?: { message?: string } } })?.response?.data?.message ?? fallback;
}
