/** Round a positive number up to a "nice" axis maximum (1, 2, 2.5, 5, 10 × 10^k). */
export function niceMax(max: number): number {
  if (!Number.isFinite(max) || max <= 0) return 1;
  const exp = Math.floor(Math.log10(max));
  const base = 10 ** exp;
  const frac = max / base;
  const nice = frac <= 1 ? 1 : frac <= 2 ? 2 : frac <= 2.5 ? 2.5 : frac <= 5 ? 5 : 10;
  return nice * base;
}

/** Evenly spaced ticks from 0 to the nice maximum (inclusive), e.g. `[0, 250, 500, 750, 1000]`. */
export function niceTicks(max: number, segments = 4): number[] {
  const top = niceMax(max);
  return Array.from({ length: segments + 1 }, (_, i) => (top * i) / segments);
}

/** Axis label for counts: 0, 250, 1.2K, 3M. */
export function formatAxisValue(value: number): string {
  const abs = Math.abs(value);
  if (abs >= 1e9) return `${trim(value / 1e9)}B`;
  if (abs >= 1e6) return `${trim(value / 1e6)}M`;
  if (abs >= 1e3) return `${trim(value / 1e3)}K`;
  return trim(value);
}

function trim(n: number): string {
  return Number.isInteger(n) ? String(n) : n.toFixed(1).replace(/\.0$/, '');
}

export function clamp(value: number, min: number, max: number): number {
  return Math.min(max, Math.max(min, value));
}

/** Indexes of roughly `count` evenly spread positions in a list of `length` items (always includes the ends). */
export function spreadIndexes(length: number, count: number): number[] {
  if (length <= 0) return [];
  if (length <= count) return Array.from({ length }, (_, i) => i);
  const out: number[] = [];
  for (let i = 0; i < count; i += 1) out.push(Math.round((i * (length - 1)) / (count - 1)));
  return [...new Set(out)];
}
