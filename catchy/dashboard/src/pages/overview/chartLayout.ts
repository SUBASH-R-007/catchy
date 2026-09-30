/** Pure geometry for the hit-rate chart's direct labels, kept separate so it can be tested. */

export interface EndLabelInput {
  key: string;
  /** Last value, 0–1. */
  value: number;
}

/**
 * Pixel tops for the direct end labels of a 0–1 chart, spread apart by at least {@code minGap}
 * so close lines never produce overlapping labels. Labels keep their value order.
 */
export function endLabelTops(
  inputs: readonly EndLabelInput[],
  plotTop: number,
  plotHeight: number,
  minGap: number,
): Map<string, number> {
  const placed = inputs
    .map((i) => ({ key: i.key, y: plotTop + (1 - Math.min(1, Math.max(0, i.value))) * plotHeight }))
    .sort((a, b) => a.y - b.y);
  for (let i = 1; i < placed.length; i++) {
    const prev = placed[i - 1];
    const cur = placed[i];
    if (prev && cur && cur.y - prev.y < minGap) cur.y = prev.y + minGap;
  }
  // If pushing down overflowed the plot, shift the whole stack back up.
  const last = placed[placed.length - 1];
  const overflow = last ? last.y - (plotTop + plotHeight) : 0;
  if (overflow > 0) placed.forEach((p) => (p.y -= overflow));
  return new Map(placed.map((p) => [p.key, p.y]));
}

export interface MarkerInput {
  ts: number;
  caption: string;
}

export interface MarkerLabel extends MarkerInput {
  /** 1-based marker number, also used in the text summary. */
  n: number;
  /** 0 or 1: labels alternate between two rows to double the room. */
  row: number;
  /** Caption shortened to fit before the next marker in the same row. */
  text: string;
}

/**
 * Fits each phase caption into the horizontal room it has: from its marker to the next marker on
 * the same row (rows alternate), or to the plot's right edge.
 */
export function markerLabels(
  markers: readonly MarkerInput[],
  domain: readonly [number, number],
  plotWidth: number,
  charWidth = 7.2,
): MarkerLabel[] {
  const [min, max] = domain;
  const span = max - min;
  const x = (ts: number) => (span > 0 ? ((ts - min) / span) * plotWidth : 0);
  return markers.map((m, i) => {
    const nextSameRow = markers[i + 2];
    const room = (nextSameRow ? x(nextSameRow.ts) : plotWidth) - x(m.ts) - 12;
    const maxChars = Math.floor(room / charWidth) - 3; // "3" leaves room for the "n " prefix
    return { ...m, n: i + 1, row: i % 2, text: fit(m.caption, maxChars) };
  });
}

function fit(caption: string, maxChars: number): string {
  if (maxChars < 4) return '';
  return caption.length <= maxChars ? caption : `${caption.slice(0, maxChars - 1).trimEnd()}…`;
}
