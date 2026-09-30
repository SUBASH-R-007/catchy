import { useCallback, useEffect, useState } from 'react';

export type ThemeName = 'pcb-blue' | 'pcb-amber';

const THEME_KEY = 'cachelab.theme';
const ANIMATION_KEY = 'cachelab.animation';

function readStorage(key: string): string | null {
  try {
    return window.localStorage.getItem(key);
  } catch {
    return null;
  }
}

function writeStorage(key: string, value: string): void {
  try {
    window.localStorage.setItem(key, value);
  } catch {
    // Storage blocked (private mode, sandbox): the choice simply is not remembered.
  }
}

export function initialTheme(): ThemeName {
  return readStorage(THEME_KEY) === 'pcb-amber' ? 'pcb-amber' : 'pcb-blue';
}

export function initialAnimation(): boolean {
  return readStorage(ANIMATION_KEY) !== 'off';
}

export interface Preferences {
  theme: ThemeName;
  setTheme: (theme: ThemeName) => void;
  animated: boolean;
  setAnimated: (on: boolean) => void;
}

/**
 * Theme ([data-theme] on <html>) and the "Circuit animation" toggle ([data-animation]), both
 * persisted in localStorage (SPEC 10.1, 10.2). index.html applies them before first paint.
 */
export function usePreferences(): Preferences {
  const [theme, setThemeState] = useState<ThemeName>(initialTheme);
  const [animated, setAnimatedState] = useState<boolean>(initialAnimation);

  useEffect(() => {
    document.documentElement.dataset.theme = theme;
  }, [theme]);

  useEffect(() => {
    if (animated) delete document.documentElement.dataset.animation;
    else document.documentElement.dataset.animation = 'off';
  }, [animated]);

  const setTheme = useCallback((next: ThemeName) => {
    setThemeState(next);
    writeStorage(THEME_KEY, next);
  }, []);

  const setAnimated = useCallback((on: boolean) => {
    setAnimatedState(on);
    writeStorage(ANIMATION_KEY, on ? 'on' : 'off');
  }, []);

  return { theme, setTheme, animated, setAnimated };
}
