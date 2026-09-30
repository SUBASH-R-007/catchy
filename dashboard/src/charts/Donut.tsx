import { useState } from 'react';
import { Legend, type LegendItem } from './ChartFrame';

export interface DonutSegment {
  key: string;
  label: string;
  value: number;
  /** CSS color (series token). */
  color: string;
  /** Text shown in the legend / tooltip, e.g. "9,140". */
  valueLabel?: string;
}

interface DonutProps {
  segments: DonutSegment[];
  size?: number;
  thickness?: number;
  /** Big text in the hole (e.g. the hit rate). */
  centerLabel?: string;
  centerSub?: string;
  /** Accessible summary; defaults to a generated sentence. */
  ariaLabel?: string;
  emptyLabel?: string;
  /** Show the legend with values under the ring (default true). */
  legend?: boolean;
}

const GAP_PX = 2;

function percentText(value: number, total: number): string {
  return `${((value / total) * 100).toFixed(1)}%`;
}

/**
 * Part-to-whole ring for two or three segments (hit vs miss). Segments are separated by a 2px surface
 * gap, the legend repeats every value in text, and each arc has a native tooltip.
 */
export function Donut({
  segments,
  size = 168,
  thickness = 18,
  centerLabel,
  centerSub,
  ariaLabel,
  emptyLabel = 'No data yet',
  legend = true,
}: DonutProps) {
  const [active, setActive] = useState<string | null>(null);
  const total = segments.reduce((sum, s) => sum + Math.max(0, s.value), 0);
  const r = (size - thickness) / 2;
  const c = 2 * Math.PI * r;
  const cx = size / 2;

  let offset = 0;
  const arcs = segments.map((s) => {
    const share = total > 0 ? Math.max(0, s.value) / total : 0;
    const length = share * c;
    const visible = share >= 0.999 ? length : Math.max(0, length - GAP_PX);
    const arc = { ...s, share, dash: `${visible} ${c - visible}`, offset: -offset };
    offset += length;
    return arc;
  });

  const summary =
    ariaLabel ??
    (total === 0
      ? emptyLabel
      : segments.map((s) => `${s.label} ${s.valueLabel ?? s.value} (${percentText(s.value, total)})`).join(', '));

  const legendItems: LegendItem[] = segments.map((s) => ({
    label: s.label,
    color: s.color,
    value: total > 0 ? `${s.valueLabel ?? s.value} · ${percentText(s.value, total)}` : undefined,
  }));

  return (
    <div className="donut">
      <svg
        width={size}
        height={size}
        viewBox={`0 0 ${size} ${size}`}
        role="img"
        aria-label={summary}
        className="donut__svg"
      >
        <circle cx={cx} cy={cx} r={r} fill="none" stroke="var(--chart-track)" strokeWidth={thickness} />
        {total > 0 &&
          arcs.map((a) =>
            a.share > 0 ? (
              <circle
                key={a.key}
                cx={cx}
                cy={cx}
                r={r}
                fill="none"
                stroke={a.color}
                strokeWidth={active === a.key ? thickness + 3 : thickness}
                strokeDasharray={a.dash}
                strokeDashoffset={a.offset}
                transform={`rotate(-90 ${cx} ${cx})`}
                onPointerEnter={() => setActive(a.key)}
                onPointerLeave={() => setActive(null)}
              >
                <title>{`${a.label}: ${a.valueLabel ?? a.value} (${percentText(a.value, total)})`}</title>
              </circle>
            ) : null,
          )}
        <text x={cx} y={centerSub ? cx - 2 : cx + 6} textAnchor="middle" className="donut__center">
          {total === 0 ? '—' : centerLabel}
        </text>
        {centerSub ? (
          <text x={cx} y={cx + 18} textAnchor="middle" className="donut__sub">
            {total === 0 ? emptyLabel : centerSub}
          </text>
        ) : null}
      </svg>
      {legend ? <Legend items={legendItems} /> : null}
    </div>
  );
}
