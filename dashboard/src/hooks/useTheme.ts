import { useCallback, useEffect, useState } from 'react';

export type ThemeChoice = 'light' | 'dark';
const STORAGE_KEY = 'catchy.theme';

function readStored(): ThemeChoice | null {
  try {
    const v = window.localStorage.getItem(STORAGE_KEY);
    return v === 'light' || v === 'dark' ? v : null;
  } catch {
    return null;
  }
}

function systemPrefersDark(): boolean {
  try {
    return typeof window.matchMedia === 'function' && window.matchMedia('(prefers-color-scheme: dark)').matches;
  } catch {
    return false;
  }
}

function effectiveTheme(stored: ThemeChoice | null): ThemeChoice {
  return stored ?? (systemPrefersDark() ? 'dark' : 'light');
}

/**
 * Manual theme toggle layered on top of `prefers-color-scheme`. Until the viewer clicks the toggle
 * nothing is stamped on <html>, so the OS setting applies; afterwards `data-theme` wins.
 * The choice is persisted in localStorage (every access is guarded).
 */
export function useTheme(): { theme: ThemeChoice; toggle: () => void } {
  const [stored, setStored] = useState<ThemeChoice | null>(readStored);
  const [, setSystemTick] = useState(0);

  useEffect(() => {
    if (stored) document.documentElement.setAttribute('data-theme', stored);
    else document.documentElement.removeAttribute('data-theme');
  }, [stored]);

  useEffect(() => {
    if (typeof window.matchMedia !== 'function') return undefined;
    const mq = window.matchMedia('(prefers-color-scheme: dark)');
    const onChange = () => setSystemTick((n) => n + 1);
    mq.addEventListener?.('change', onChange);
    return () => mq.removeEventListener?.('change', onChange);
  }, []);

  const toggle = useCallback(() => {
    const next: ThemeChoice = effectiveTheme(stored) === 'dark' ? 'light' : 'dark';
    setStored(next);
    try {
      window.localStorage.setItem(STORAGE_KEY, next);
    } catch {
      /* preference just will not persist */
    }
  }, [stored]);

  return { theme: effectiveTheme(stored), toggle };
}
