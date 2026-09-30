import '@testing-library/jest-dom/vitest';
import { afterEach } from 'vitest';
import { cleanup } from '@testing-library/react';
import { clearToken } from './src/api/session';

afterEach(() => {
  cleanup();
  clearToken(); // also drops the in-memory fallback copy
  try {
    window.sessionStorage.clear();
    window.localStorage.clear();
  } catch {
    /* storage unavailable in this environment */
  }
});
