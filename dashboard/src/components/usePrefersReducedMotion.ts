import { useSyncExternalStore } from 'react';

const QUERY = '(prefers-reduced-motion: reduce)';

function mediaQuery(): MediaQueryList | null {
  // jsdom (tests) and very old browsers have no matchMedia.
  if (typeof window === 'undefined' || typeof window.matchMedia !== 'function') return null;
  return window.matchMedia(QUERY);
}

function subscribe(onChange: () => void): () => void {
  const mql = mediaQuery();
  if (!mql || typeof mql.addEventListener !== 'function') return () => {};
  mql.addEventListener('change', onChange);
  return () => mql.removeEventListener('change', onChange);
}

function getSnapshot(): boolean {
  return mediaQuery()?.matches ?? false;
}

function getServerSnapshot(): boolean {
  return false;
}

/** True when the user asked the OS for reduced motion; tracks changes live. */
export function usePrefersReducedMotion(): boolean {
  return useSyncExternalStore(subscribe, getSnapshot, getServerSnapshot);
}
