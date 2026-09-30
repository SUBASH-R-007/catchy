import type { ReactNode } from 'react';

export interface BarRow {
  key: string;
  label: ReactNode;
  value: number;
  /** CSS color (series token). */
  color: string;
  /** Text at the bar tip. Defaults to the raw value. */
  valueLabel?: string;
  /** Tooltip / description (kept out of the visual label). */
  description?: string;
  /** Optional reference tick (e.g. the live hit rate) drawn across the bar. */
  marker?: { value: number; label: string };
}

export interface BarGroup {
  label?: string;
  rows: BarRow[];
}

interface HBarsProps {
  groups: BarGroup[];
  /** Axis maximum (default: 100 for percentages when `percent`, else the largest value). */
  max?: number;
  percent?: boolean;
  ariaLabel: string;
  emptyText?: string;
}

/**
 * Horizontal bars with the value at the tip. Bars are ≤16px thick with a 4px rounded data end and a
 * square baseline; labels sit outside the bar so they can never be clipped.
 */
export function HBars({ groups, max, percent = false, ariaLabel, emptyText = 'No data yet' }: HBarsProps) {
  const rows = groups.flatMap((g) => g.rows);
  if (rows.length === 0) return <p className="chart-empty">{emptyText}</p>;
  const top = max ?? (percent ? 100 : Math.max(1, ...rows.map((r) => r.value)));
  return (
    <div className="hbars" role="group" aria-label={ariaLabel}>
      {groups.map((g, gi) => (
        <div key={g.label ?? gi} className="hbars__group">
          {g.label ? <div className="hbars__group-label">{g.label}</div> : null}
          {g.rows.map((row) => {
            const pct = Math.min(100, Math.max(0, (row.value / top) * 100));
            return (
              <div key={row.key} className="hbars__row" title={row.description}>
                <div className="hbars__label">{row.label}</div>
                <svg className="hbars__svg" height={20} width="100%" role="presentation" focusable="false">
                  <rect x="0" y="2" width="100%" height="16" rx="4" fill="var(--chart-track)" />
                  {pct > 0 ? (
                    <>
                      <rect x="0" y="2" width={`${pct}%`} height="16" rx="4" fill={row.color} />
                      <rect x="0" y="2" width="6" height="16" fill={row.color} />
                    </>
                  ) : null}
                  {row.marker ? (
                    <line
                      x1={`${Math.min(100, Math.max(0, (row.marker.value / top) * 100))}%`}
                      x2={`${Math.min(100, Math.max(0, (row.marker.value / top) * 100))}%`}
                      y1="0"
                      y2="20"
                      stroke="var(--text)"
                      strokeWidth="2"
                    >
                      <title>{row.marker.label}</title>
                    </line>
                  ) : null}
                </svg>
                <div className="hbars__value num">{row.valueLabel ?? String(row.value)}</div>
              </div>
            );
          })}
        </div>
      ))}
    </div>
  );
}
