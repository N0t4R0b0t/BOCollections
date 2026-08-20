import { useState, useEffect } from 'react';

/**
 * Reactively tracks a CSS media query.
 *
 * Initialises synchronously from window.matchMedia so there's no flash of the
 * wrong layout on first render. Cleans up the listener on unmount.
 *
 * @example
 *   const isMobile = useMediaQuery('(max-width: 767px)');
 */
export function useMediaQuery(query: string): boolean {
  const [matches, setMatches] = useState(
    () => typeof window !== 'undefined' && window.matchMedia(query).matches
  );

  useEffect(() => {
    const mql = window.matchMedia(query);
    const handler = (e: MediaQueryListEvent) => setMatches(e.matches);
    mql.addEventListener('change', handler);
    return () => mql.removeEventListener('change', handler);
  }, [query]);

  return matches;
}
