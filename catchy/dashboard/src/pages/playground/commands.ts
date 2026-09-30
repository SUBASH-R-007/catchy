import type { GetResult } from '../../api/rest';
import { formatDurationMs } from '../../lib/format';

/** Pure helpers for the Playground: input parsing, TTL countdowns and result sentences. */

export const CACHE_NAME_PATTERN = /^[A-Za-z0-9_-]{1,40}$/;
export const MAX_CAPACITY = 1_000_000;
export const MAX_KEY_LENGTH = 200;
export const HISTORY_LIMIT = 10;

export type Parsed<T> = { ok: true; value: T } | { ok: false; error: string };

/**
 * An optional TTL typed in seconds → milliseconds. Empty means "not set" (null). Decimals are
 * allowed; the result is at least 1 ms.
 */
export function parseTtlSeconds(text: string): Parsed<number | null> {
  const t = text.trim();
  if (t === '') return { ok: true, value: null };
  const seconds = Number(t);
  if (!Number.isFinite(seconds) || seconds <= 0) {
    return { ok: false, error: 'TTL must be a positive number of seconds.' };
  }
  return { ok: true, value: Math.max(1, Math.round(seconds * 1000)) };
}

export function parseCapacity(text: string): Parsed<number> {
  const n = Number(text.trim());
  if (!Number.isInteger(n) || n < 1 || n > MAX_CAPACITY) {
    return { ok: false, error: `Capacity must be a whole number from 1 to 1,000,000.` };
  }
  return { ok: true, value: n };
}

export function validateCacheName(name: string): string | null {
  return CACHE_NAME_PATTERN.test(name) ? null : 'Use 1–40 letters, digits, hyphens or underscores.';
}

export function validateKey(key: string): string | null {
  if (key.length === 0) return 'Enter a key.';
  if (key.length > MAX_KEY_LENGTH) return 'Keys are at most 200 characters.';
  if (key.includes('/')) return 'Keys cannot contain "/".';
  return null;
}

/** "play-1" → "play-2"; "orders" → "orders-2". */
export function nextCacheName(name: string): string {
  const match = /^(.*?)(\d+)$/.exec(name);
  if (match) return `${match[1] ?? ''}${Number(match[2]) + 1}`;
  return `${name}-2`;
}

/**
 * Remaining TTL at {@code now}, interpolated from the value the server reported at
 * {@code fetchedAt}. null means the entry never expires; 0 means it has run out.
 */
export function ttlRemainingAt(
  ttlRemainingMs: number | null,
  fetchedAt: number,
  now: number,
): number | null {
  if (ttlRemainingMs === null) return null;
  return Math.max(0, ttlRemainingMs - Math.max(0, now - fetchedAt));
}

/** Countdown text: null → "never", 0 → "expired", 4321 → "4.4 s", 90_000 → "1.5 min". */
export function formatTtl(ms: number | null): string {
  if (ms === null) return 'never';
  if (ms <= 0) return 'expired';
  if (ms < 60_000) return `${(Math.ceil(ms / 100) / 10).toFixed(1)} s`;
  return formatDurationMs(ms);
}

function expiresIn(ttlRemainingMs: number | null): string {
  return ttlRemainingMs === null ? 'never expires' : `expires in ${formatTtl(ttlRemainingMs)}`;
}

export type CommandOp = 'GET' | 'PUT' | 'DELETE';
export type Outcome = 'hit' | 'miss' | 'stored' | 'removed' | 'not-found';

export interface CommandResult {
  op: CommandOp;
  key: string;
  outcome: Outcome;
  /** The sentence shown in the result panel. */
  message: string;
  /** Short form for the history list. */
  short: string;
}

export function describeGet(key: string, result: GetResult): CommandResult {
  if (result.hit) {
    return {
      op: 'GET',
      key,
      outcome: 'hit',
      message: `HIT — value "${result.value ?? ''}", ${expiresIn(result.ttlRemainingMs)}.`,
      short: 'HIT',
    };
  }
  return {
    op: 'GET',
    key,
    outcome: 'miss',
    message: 'MISS — the key is not in the cache (or it expired).',
    short: 'MISS',
  };
}

export function describePut(key: string, ttlMs: number | null, defaultTtlMs: number | null) {
  const ttl = ttlMs ?? defaultTtlMs;
  const life =
    ttl === null
      ? 'it never expires'
      : `it expires in ${formatTtl(ttl)}${ttlMs === null ? ' (the cache default)' : ''}`;
  return {
    op: 'PUT',
    key,
    outcome: 'stored',
    message: `Stored "${key}"; ${life}.`,
    short: 'stored',
  } satisfies CommandResult;
}

export function describeDelete(key: string, removed: boolean): CommandResult {
  return removed
    ? {
        op: 'DELETE',
        key,
        outcome: 'removed',
        message: `Removed "${key}".`,
        short: 'removed',
      }
    : {
        op: 'DELETE',
        key,
        outcome: 'not-found',
        message: `Not found — "${key}" was not in the cache.`,
        short: 'not found',
      };
}

export interface HistoryEntry extends CommandResult {
  id: number;
  ts: number;
}

/** Newest first, at most {@code limit} entries. */
export function pushHistory(
  history: readonly HistoryEntry[],
  entry: HistoryEntry,
  limit = HISTORY_LIMIT,
): HistoryEntry[] {
  return [entry, ...history].slice(0, limit);
}
