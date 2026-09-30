/**
 * Client-side savings estimates for the Overview cost panel (SPEC 9.3). The server reports
 * dbCallsAvoided and latencySavedMs; the cost is recomputed here from the user's own price.
 */

/** Default price of 1,000 database calls, in dollars (SPEC 9.3). */
export const DEFAULT_PRICE_PER_1000 = 0.05;

/** The simulated database draws each call's latency uniformly from 5-20 ms (SPEC 9.3). */
export const DB_LATENCY_MIN_MS = 5;
export const DB_LATENCY_MAX_MS = 20;
export const MEAN_DB_LATENCY_MS = (DB_LATENCY_MIN_MS + DB_LATENCY_MAX_MS) / 2;

/** Upper bound for the editable price, to keep the input sane. */
export const MAX_PRICE_PER_1000 = 1_000_000;

/** The assumptions behind every number in the cost panel, shown in its InfoPopover. */
export const COST_ASSUMPTIONS =
  'Estimates, not measurements. Every cache hit is one database call avoided. The simulated database takes 5–20 ms per call (uniform, mean 12.5 ms), so latency saved = hits × 12.5 ms. Cost saved = calls avoided ÷ 1,000 × your price per 1,000 calls.';

export const PRICE_STORAGE_KEY = 'cachelab.pricePer1000';

/** Estimated cost saved: calls avoided ÷ 1,000 × price per 1,000 calls (never negative). */
export function estimateCostSaved(dbCallsAvoided: number, pricePer1000: number): number {
  if (!Number.isFinite(dbCallsAvoided) || !Number.isFinite(pricePer1000)) return 0;
  return (Math.max(0, dbCallsAvoided) / 1000) * Math.max(0, pricePer1000);
}

export type PriceParse = { ok: true; value: number } | { ok: false; error: string };

/** Parses the price input: a number from 0 to MAX_PRICE_PER_1000. */
export function parsePrice(text: string): PriceParse {
  const trimmed = text.trim();
  if (trimmed === '') return { ok: false, error: 'Enter a price, e.g. 0.05.' };
  const value = Number(trimmed);
  if (!Number.isFinite(value)) return { ok: false, error: 'The price must be a number.' };
  if (value < 0) return { ok: false, error: 'The price cannot be negative.' };
  if (value > MAX_PRICE_PER_1000)
    return { ok: false, error: 'That price is unrealistically high.' };
  return { ok: true, value };
}

/** The remembered price, or the default when none is stored (or storage is blocked). */
export function readStoredPrice(): number {
  try {
    const stored = window.localStorage.getItem(PRICE_STORAGE_KEY);
    if (stored === null) return DEFAULT_PRICE_PER_1000;
    const parsed = parsePrice(stored);
    return parsed.ok ? parsed.value : DEFAULT_PRICE_PER_1000;
  } catch {
    return DEFAULT_PRICE_PER_1000;
  }
}

export function writeStoredPrice(price: number): void {
  try {
    window.localStorage.setItem(PRICE_STORAGE_KEY, String(price));
  } catch {
    // Storage blocked (private mode, sandbox): the price simply is not remembered.
  }
}
