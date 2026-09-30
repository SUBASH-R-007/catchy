import type { ReactNode } from 'react';

export interface StackSegment {
  key: string;
  label: string;
  value: number;
  color: string;
  valueLabel?: string;
}

export interface StackRow {
  key: string;
  label: ReactNode;
  segments: StackSegment[];
  /** Text at the right end of the row (e.g. the total). */
  totalLabel?: string;
}

interface StackedBarsProps {
  rows: StackRow[];
  ariaLabel: string;
  emptyText?: string;
}

/**
 * 100% stacked horizontal bars (part-to-whole per row). Segments are separated by a 2px surface gap
 * and each one has a native tooltip; exact values live in the tooltip and the text in `totalLabel`.
 */
export function StackedBars({ rows, ariaLabel, emptyText = 'No data yet' }: StackedBarsProps) {
  if (rows.length === 0) return <p className="chart-empty">{emptyText}</p>;
  return (
    <div className="stacked" role="group" aria-label={ariaLabel}>
      {rows.map((row) => {
        const total = row.segments.reduce((s, seg) => s + Math.max(0, seg.value), 0);
        let acc = 0;
        const desc = row.segments.map((s) => `${s.label} ${s.valueLabel ?? s.value}`).join(', ');
        return (
          <div key={row.key} className="stacked__row">
            <div className="stacked__label">{row.label}</div>
            <svg className="stacked__svg" height={20} width="100%" role="img" aria-label={desc} focusable="false">
              <rect x="0" y="2" width="100%" height="16" rx="4" fill="var(--chart-track)" />
              {total > 0 &&
                row.segments.map((seg) => {
                  const w = (Math.max(0, seg.value) / total) * 100;
                  const x = (acc / total) * 100;
                  acc += Math.max(0, seg.value);
                  if (w <= 0) return null;
                  return (
                    <rect key={seg.key} x={`${x}%`} y="2" width={`${w}%`} height="16" fill={seg.color} stroke="var(--surface)" strokeWidth="2">
                      <title>{`${seg.label}: ${seg.valueLabel ?? seg.value} (${w.toFixed(1)}%)`}</title>
                    </rect>
                  );
                })}
            </svg>
            <div className="stacked__total num">{row.totalLabel ?? ''}</div>
          </div>
        );
      })}
    </div>
  );
}
