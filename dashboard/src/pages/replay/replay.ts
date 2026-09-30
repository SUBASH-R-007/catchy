import type { PolicyReplayResult, ReplayResult } from '../../api/rest';
import type { PolicyType } from '../../api/types';
import { formatInteger, formatPercent } from '../../lib/format';
import { SERIES_STYLE, type SeriesKind } from '../../theme/policy';

/** Default replay capacity (SPEC 10.5). */
export const DEFAULT_CAPACITY = 1000;
export const MIN_CAPACITY = 1;
/** A trace holds at most 1,000,000 rows (SPEC 9.5); a bigger cache could never fill. */
export const MAX_CAPACITY = 1_000_000;

/** Largest accepted upload (SPEC 9.5); checked client-side too, for a faster answer. */
export const MAX_TRACE_BYTES = 20 * 1024 * 1024;

/** Error text for a capacity field value, or null when it is valid. */
export function capacityError(raw: string): string | null {
  const text = raw.trim().replace(/[,_\s]/g, '');
  if (text === '') return 'Enter a capacity.';
  if (!/^\d+$/.test(text)) return 'Capacity must be a whole number.';
  const n = Number(text);
  if (n < MIN_CAPACITY || n > MAX_CAPACITY) {
    return `Capacity must be between ${formatInteger(MIN_CAPACITY)} and ${formatInteger(MAX_CAPACITY)}.`;
  }
  return null;
}

/** The capacity field value as a number (call capacityError first). */
export function parseCapacity(raw: string): number {
  return Number(raw.trim().replace(/[,_\s]/g, ''));
}

/** Client-side check of a chosen file; null when it may be uploaded. */
export function traceFileError(file: { name: string; size: number }): string | null {
  if (file.size === 0) return `“${file.name}” is empty. A trace needs one key per line.`;
  if (file.size > MAX_TRACE_BYTES) {
    return `“${file.name}” is ${(file.size / 1024 / 1024).toFixed(1)} MB; the limit is 20 MB.`;
  }
  return null;
}

export interface ReplayBar {
  kind: SeriesKind;
  label: string;
  /** 0-1. */
  hitRate: number;
}

/** Bars for the results chart: each replayed policy in the standard order, then the optimal. */
export function replayBars(result: ReplayResult): ReplayBar[] {
  const order: readonly PolicyType[] = ['LRU', 'LFU', 'LFU_DECAY'];
  const policies = [...result.results].sort(
    (a, b) => order.indexOf(a.policy) - order.indexOf(b.policy),
  );
  return [
    ...policies.map((r) => ({
      kind: r.policy as SeriesKind,
      label: SERIES_STYLE[r.policy].label,
      hitRate: r.hitRate,
    })),
    { kind: 'OPTIMAL', label: 'Optimal (Bélády)', hitRate: result.optimalHitRate },
  ];
}

/** The replayed policy with the highest hit rate (first wins on a tie), or null. */
export function bestPolicy(result: ReplayResult): PolicyReplayResult | null {
  let best: PolicyReplayResult | null = null;
  for (const r of result.results) if (!best || r.hitRate > best.hitRate) best = r;
  return best;
}

/** Plain-language summary of the replay, shown under the chart. */
export function replaySummary(result: ReplayResult): string {
  const best = bestPolicy(result);
  const parts = replayBars(result).map((b) => `${b.label} ${formatPercent(b.hitRate)}`);
  const head = `Hit rate over ${formatInteger(result.rows)} rows at capacity ${formatInteger(result.capacity)}: ${parts.join(', ')}.`;
  if (!best) return head;
  const gap = Math.max(0, (result.optimalHitRate - best.hitRate) * 100);
  return `${head} Best policy: ${SERIES_STYLE[best.policy].label}, ${gap.toFixed(1)} points below the optimal.`;
}

function csvCell(value: string | number): string {
  const text = String(value);
  return /[",\n]/.test(text) ? `"${text.replace(/"/g, '""')}"` : text;
}

/** The replay results as CSV: one row per policy plus an OPTIMAL row (counts left empty). */
export function replayCsv(result: ReplayResult): string {
  const header = ['traceId', 'rows', 'capacity', 'policy', 'hitRate', 'hits', 'misses', 'evictions'];
  const meta = [result.traceId, result.rows, result.capacity];
  const lines = [
    header,
    ...result.results.map((r) => [...meta, r.policy, r.hitRate, r.hits, r.misses, r.evictions]),
    [...meta, 'OPTIMAL', result.optimalHitRate, '', '', ''],
  ];
  return `${lines.map((l) => l.map(csvCell).join(',')).join('\n')}\n`;
}

/** The replay results as pretty-printed JSON. */
export function replayJson(result: ReplayResult): string {
  return `${JSON.stringify(result, null, 2)}\n`;
}

/** "replay-t-1-cap1000.csv": a file name without characters that are unsafe on disk. */
export function reportFileName(result: ReplayResult, extension: 'csv' | 'json'): string {
  const id = result.traceId.replace(/[^a-zA-Z0-9_-]/g, '_');
  return `replay-${id}-cap${result.capacity}.${extension}`;
}

/** Saves text as a file through a temporary Blob URL. */
export function downloadText(text: string, fileName: string, type: string): void {
  const url = URL.createObjectURL(new Blob([text], { type }));
  const link = document.createElement('a');
  link.href = url;
  link.download = fileName;
  document.body.appendChild(link);
  link.click();
  link.remove();
  // Some browsers start the download asynchronously; release the URL a moment later.
  window.setTimeout(() => URL.revokeObjectURL(url), 1000);
}
