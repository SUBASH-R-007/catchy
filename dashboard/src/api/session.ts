/**
 * Bearer-token storage. The token lives in sessionStorage (cleared when the tab closes).
 * Every storage access is wrapped: the app must keep working (with an in-memory token)
 * when storage throws — private windows, blocked site data, sandboxed previews.
 */
const TOKEN_KEY = 'catchy.token';

let memoryToken: string | null = null;

export function getToken(): string | null {
  try {
    const stored = window.sessionStorage.getItem(TOKEN_KEY);
    if (stored) return stored;
  } catch {
    /* storage unavailable — fall through to the in-memory copy */
  }
  return memoryToken;
}

export function setToken(token: string): void {
  memoryToken = token;
  try {
    window.sessionStorage.setItem(TOKEN_KEY, token);
  } catch {
    /* in-memory copy is enough for this tab */
  }
}

export function clearToken(): void {
  memoryToken = null;
  try {
    window.sessionStorage.removeItem(TOKEN_KEY);
  } catch {
    /* nothing to clear */
  }
}
