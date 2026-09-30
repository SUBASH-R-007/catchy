import { useCallback, useState, type KeyboardEvent, type PointerEvent } from 'react';
import { formatClock, formatClockShort } from '../lib/format';
import { clamp, formatAxisValue, niceTicks, spreadIndexes } from './scale';
import { useChartWidth } from './useChartWidth';

export interface TimelineSeries {
  key: string;
  label: string;
  /** CSS color, e.g. `var(--series-hit)`. */
  color: string;
  values: number[];
  /** Formats the tooltip value (default: integer with separators). */
  format?: (value: number) => string;
}

interface TimelineChartProps {
  /** ISO timestamps, oldest first; one per data point. */
  labels: string[];
  series: TimelineSeries[];
  height?: number;
  /** Fixed y-axis maximum (e.g. 100 for percentages). */
  yMax?: number;
  yFormat?: (value: number) => string;
  /** Fill the first series with a ~10% wash. */
  area?: boolean;
  ariaLabel: string;
  emptyText?: string;
}

const MARGIN = { top: 12, bottom: 26, left: 44 };

/**
 * Multi-series line chart on ONE y-axis (never dual-axis). 2px lines, hairline grid, end dots with
 * a surface ring, direct end-labels when they fit, and a crosshair tooltip that works with the
 * pointer and with the keyboard (focus the chart, then ←/→, Home/End, Esc).
 */
export function TimelineChart({
  labels,
  series,
  height = 240,
  yMax,
  yFormat = formatAxisValue,
  area = false,
  ariaLabel,
  emptyText = 'No data in this window yet',
}: TimelineChartProps) {
  const [ref, width] = useChartWidth<HTMLDivElement>(640);
  const [hover, setHover] = useState<number | null>(null);

  const n = labels.length;
  const showDirectLabels = width >= 560 && series.length > 1 && series.length <= 4;
  const right = showDirectLabels ? 84 : 14;
  const innerW = Math.max(10, width - MARGIN.left - right);
  const innerH = Math.max(10, height - MARGIN.top - MARGIN.bottom);

  const dataMax = Math.max(0, ...series.flatMap((s) => s.values));
  const ticks = yMax !== undefined ? [0, yMax / 4, yMax / 2, (yMax * 3) / 4, yMax] : niceTicks(dataMax, 4);
  const top = ticks[ticks.length - 1] || 1;

  const x = useCallback((i: number) => MARGIN.left + (n <= 1 ? innerW / 2 : (i * innerW) / (n - 1)), [n, innerW]);
  const y = useCallback((v: number) => MARGIN.top + innerH - (clamp(v, 0, top) / top) * innerH, [innerH, top]);

  const indexFromPointer = (e: PointerEvent<SVGSVGElement>): number => {
    const rect = e.currentTarget.getBoundingClientRect();
    const px = e.clientX - rect.left;
    const i = Math.round(((px - MARGIN.left) / innerW) * (n - 1));
    return clamp(i, 0, n - 1);
  };

  const onKeyDown = (e: KeyboardEvent<SVGSVGElement>) => {
    if (n === 0) return;
    if (e.key === 'ArrowRight') {
      e.preventDefault();
      setHover((h) => clamp((h ?? -1) + 1, 0, n - 1));
    } else if (e.key === 'ArrowLeft') {
      e.preventDefault();
      setHover((h) => clamp((h ?? n) - 1, 0, n - 1));
    } else if (e.key === 'Home') {
      e.preventDefault();
      setHover(0);
    } else if (e.key === 'End') {
      e.preventDefault();
      setHover(n - 1);
    } else if (e.key === 'Escape') {
      setHover(null);
    }
  };

  if (n === 0) {
    return (
      <div ref={ref} className="timeline timeline--empty" style={{ minHeight: height }}>
        <p className="chart-empty">{emptyText}</p>
      </div>
    );
  }

  const allZero = dataMax === 0;
  const xTicks = spreadIndexes(n, width < 420 ? 3 : width < 640 ? 4 : 6);

  // Direct end-labels, dropped (not nudged) when they would collide.
  const endLabels: Array<{ key: string; y: number; label: string; color: string }> = [];
  if (showDirectLabels) {
    const sorted = series
      .map((s) => ({ s, y: y(s.values[n - 1] ?? 0) }))
      .sort((a, b) => a.y - b.y);
    let lastY = -Infinity;
    for (const { s, y: yy } of sorted) {
      if (yy - lastY >= 15) {
        endLabels.push({ key: s.key, y: yy, label: s.label, color: s.color });
        lastY = yy;
      }
    }
  }

  const hoverX = hover !== null ? x(hover) : 0;
  const tooltipLeft = hover !== null ? (hoverX > width * 0.62 ? hoverX - 12 : hoverX + 12) : 0;
  const tooltipAlignRight = hover !== null && hoverX > width * 0.62;

  return (
    <div ref={ref} className="timeline" style={{ height }}>
      <svg
        width={width}
        height={height}
        viewBox={`0 0 ${width} ${height}`}
        className="timeline__svg"
        role="group"
        aria-label={`${ariaLabel}. Focus the chart and use the left and right arrow keys to read values, or switch to table view.`}
        tabIndex={0}
        onKeyDown={onKeyDown}
        onBlur={() => setHover(null)}
        onPointerMove={(e) => setHover(indexFromPointer(e))}
        onPointerLeave={() => setHover(null)}
      >
        {ticks.map((t) => (
          <g key={t}>
            <line x1={MARGIN.left} x2={width - right} y1={y(t)} y2={y(t)} stroke={t === 0 ? 'var(--chart-axis)' : 'var(--chart-grid)'} strokeWidth={1} />
            <text x={MARGIN.left - 8} y={y(t) + 4} textAnchor="end" className="axis-text num">
              {yFormat(t)}
            </text>
          </g>
        ))}
        {xTicks.map((i) => (
          <text key={i} x={x(i)} y={height - 8} textAnchor={i === 0 ? 'start' : i === n - 1 ? 'end' : 'middle'} className="axis-text num">
            {formatClockShort(labels[i] as string)}
          </text>
        ))}

        {area && series[0] ? (
          <path
            d={`${series[0].values.map((v, i) => `${i === 0 ? 'M' : 'L'}${x(i).toFixed(1)},${y(v).toFixed(1)}`).join(' ')} L${x(n - 1).toFixed(1)},${y(0)} L${x(0).toFixed(1)},${y(0)} Z`}
            fill={series[0].color}
            opacity={0.1}
          />
        ) : null}

        {series.map((s) => (
          <path
            key={s.key}
            d={s.values.map((v, i) => `${i === 0 ? 'M' : 'L'}${x(i).toFixed(1)},${y(v).toFixed(1)}`).join(' ')}
            fill="none"
            stroke={s.color}
            strokeWidth={2}
            strokeLinejoin="round"
            strokeLinecap="round"
          />
        ))}

        {hover !== null ? (
          <line x1={hoverX} x2={hoverX} y1={MARGIN.top} y2={MARGIN.top + innerH} stroke="var(--text-3)" strokeWidth={1} />
        ) : null}

        {series.map((s) => {
          const i = hover ?? n - 1;
          const v = s.values[i] ?? 0;
          return <circle key={s.key} cx={x(i)} cy={y(v)} r={4} fill={s.color} stroke="var(--surface)" strokeWidth={2} />;
        })}

        {endLabels.map((l) => (
          <text key={l.key} x={width - right + 10} y={l.y + 4} className="axis-text axis-text--label">
            {l.label}
          </text>
        ))}

        {allZero ? (
          <text x={MARGIN.left + innerW / 2} y={MARGIN.top + innerH / 2} textAnchor="middle" className="axis-text">
            No activity in this window
          </text>
        ) : null}
      </svg>

      {hover !== null ? (
        <div
          className={`chart-tooltip${tooltipAlignRight ? ' chart-tooltip--left' : ''}`}
          style={{ left: tooltipLeft, top: MARGIN.top }}
          role="status"
        >
          <div className="chart-tooltip__time num">{formatClock(labels[hover] as string)}</div>
          {series.map((s) => (
            <div key={s.key} className="chart-tooltip__row">
              <span className="legend__key legend__key--line" style={{ background: s.color }} aria-hidden="true" />
              <span className="chart-tooltip__value num">{(s.format ?? ((v: number) => Math.round(v).toLocaleString('en-US')))(s.values[hover] ?? 0)}</span>
              <span className="chart-tooltip__label">{s.label}</span>
            </div>
          ))}
        </div>
      ) : null}
    </div>
  );
}
