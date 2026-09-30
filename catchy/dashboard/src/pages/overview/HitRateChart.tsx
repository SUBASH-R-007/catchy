import { useState } from 'react';
import {
  CartesianGrid,
  Line,
  LineChart,
  ReferenceLine,
  ResponsiveContainer,
  Tooltip,
  XAxis,
  YAxis,
} from 'recharts';
import type { HitRateRow, PhaseMarker, SeriesInfo } from '../../api/streamReducer';
import { PolicyBadge } from '../../components';
import { formatClock, formatPercent } from '../../lib/format';
import { SERIES_STYLE } from '../../theme/policy';
import { endLabelTops, markerLabels, type MarkerLabel } from './chartLayout';

const HEIGHT = 320;
const MARGIN = { top: 48, right: 120, bottom: 0, left: 0 };
const X_AXIS_HEIGHT = 30;
const Y_AXIS_WIDTH = 56;
const PLOT_HEIGHT = HEIGHT - MARGIN.top - MARGIN.bottom - X_AXIS_HEIGHT;
const AXIS_TICK = { fill: 'var(--text-muted)', fontSize: 14 };

export interface HitRateChartProps {
  rows: readonly HitRateRow[];
  series: readonly SeriesInfo[];
  markers: readonly PhaseMarker[];
}

/**
 * Windowed (10 s) hit rate per cache over the last 60 s: policy colour AND line style per series,
 * direct end labels, a crosshair tooltip and captioned phase markers (SPEC 10.5).
 */
export function HitRateChart({ rows, series, markers }: HitRateChartProps) {
  const [width, setWidth] = useState(0);
  const first = rows[0];
  const last = rows[rows.length - 1];
  const domain: [number, number] = [first?.ts ?? 0, last?.ts ?? 1];
  const plotWidth = Math.max(0, width - MARGIN.left - MARGIN.right - Y_AXIS_WIDTH);

  const labels = markerLabels(markers, domain, plotWidth);
  const endTops = endLabelTops(
    series.map((s) => ({ key: s.name, value: last?.[s.name] ?? 0 })),
    MARGIN.top,
    PLOT_HEIGHT,
    22,
  );

  return (
    <figure className="m-0">
      <Legend series={series} />
      <div className="relative" style={{ height: HEIGHT }}>
        <ResponsiveContainer width="100%" height={HEIGHT} onResize={(w) => setWidth(w)}>
          <LineChart data={rows as HitRateRow[]} margin={MARGIN}>
            <CartesianGrid stroke="var(--trace)" strokeOpacity={0.45} vertical={false} />
            <XAxis
              dataKey="ts"
              type="number"
              domain={['dataMin', 'dataMax']}
              tickFormatter={formatClock}
              tick={AXIS_TICK}
              tickLine={false}
              axisLine={{ stroke: 'var(--trace)' }}
              height={X_AXIS_HEIGHT}
              minTickGap={56}
            />
            <YAxis
              domain={[0, 1]}
              ticks={[0, 0.25, 0.5, 0.75, 1]}
              tickFormatter={(v: number) => formatPercent(v, 0)}
              tick={AXIS_TICK}
              tickLine={false}
              axisLine={false}
              width={Y_AXIS_WIDTH}
            />
            <Tooltip
              isAnimationActive={false}
              cursor={{ stroke: 'var(--trace-glow)', strokeWidth: 1 }}
              content={(p) => (
                <ChartTooltip
                  active={p.active}
                  label={p.label}
                  payload={p.payload}
                  series={series}
                />
              )}
            />
            {labels.map((m) => (
              <ReferenceLine
                key={m.ts}
                x={m.ts}
                stroke="var(--text-muted)"
                strokeDasharray="2 4"
                label={(p: { viewBox?: unknown }) => <MarkerTag marker={m} viewBox={p.viewBox} />}
              />
            ))}
            {series.map((s) => (
              <Line
                key={s.name}
                type="monotone"
                dataKey={s.name}
                name={s.name}
                stroke={SERIES_STYLE[s.policy].color}
                strokeDasharray={SERIES_STYLE[s.policy].dash}
                strokeWidth={2}
                dot={false}
                activeDot={{ r: 4, stroke: 'var(--pcb-surface)', strokeWidth: 2 }}
                isAnimationActive={false}
              />
            ))}
          </LineChart>
        </ResponsiveContainer>
        {last && (
          <ul
            aria-hidden="true"
            className="pointer-events-none absolute top-0 right-0"
            style={{ width: MARGIN.right - 8 }}
          >
            {series.map((s) => (
              <li
                key={s.name}
                className="absolute left-0 flex items-center gap-2 font-mono text-sm whitespace-nowrap text-text tabular-nums"
                style={{ top: (endTops.get(s.name) ?? 0) - 10 }}
              >
                <LineGlyph policy={s.policy} />
                {s.name} {formatPercent(last[s.name] ?? 0, 0)}
              </li>
            ))}
          </ul>
        )}
      </div>
      <figcaption className="mt-4 text-sm text-muted">
        <ChartSummary rows={rows} series={series} markers={labels} />
      </figcaption>
    </figure>
  );
}

function Legend({ series }: { series: readonly SeriesInfo[] }) {
  return (
    <ul className="mb-2 flex flex-wrap gap-x-6 gap-y-2" aria-label="Legend">
      {series.map((s) => (
        <li key={s.name} className="flex items-center gap-2 text-sm text-text">
          <PolicyBadge policy={s.policy} />
          <span className="font-mono">{s.name}</span>
        </li>
      ))}
    </ul>
  );
}

function LineGlyph({ policy }: { policy: SeriesInfo['policy'] }) {
  const style = SERIES_STYLE[policy];
  return (
    <svg width="20" height="8" aria-hidden="true" className="shrink-0">
      <line
        x1="0"
        y1="4"
        x2="20"
        y2="4"
        stroke={style.color}
        strokeWidth="2"
        strokeDasharray={style.dash}
      />
    </svg>
  );
}

function MarkerTag({ marker, viewBox }: { marker: MarkerLabel; viewBox: unknown }) {
  const box = viewBox as { x?: number; y?: number } | undefined;
  const x = box?.x ?? 0;
  const y = (box?.y ?? MARGIN.top) - 28 + marker.row * 18;
  return (
    <g>
      <title>{`Phase ${marker.n}: ${marker.caption}`}</title>
      <text x={x + 4} y={y} fontSize={14} fill="var(--text-muted)">
        <tspan fontWeight={600} fill="var(--trace-glow)">
          {marker.n}
        </tspan>
        {marker.text ? ` ${marker.text}` : ''}
      </text>
    </g>
  );
}

interface TooltipEntry {
  dataKey?: unknown;
  value?: unknown;
}

function ChartTooltip({
  active,
  label,
  payload,
  series,
}: {
  active?: boolean;
  label?: unknown;
  payload?: readonly TooltipEntry[];
  series: readonly SeriesInfo[];
}) {
  if (!active || !payload || payload.length === 0 || typeof label !== 'number') return null;
  return (
    <div className="rounded-md border border-trace bg-surface-2 px-3 py-2 text-sm shadow-lg">
      <p className="mb-1 font-mono text-muted tabular-nums">{formatClock(label)}</p>
      <ul className="space-y-1">
        {series.map((s) => {
          const entry = payload.find((e) => e.dataKey === s.name);
          const value = typeof entry?.value === 'number' ? entry.value : null;
          return (
            <li key={s.name} className="flex items-center gap-2 text-text">
              <LineGlyph policy={s.policy} />
              <span className="font-mono">{s.name}</span>
              <span className="ml-auto pl-4 font-mono tabular-nums">
                {value === null ? '—' : formatPercent(value)}
              </span>
            </li>
          );
        })}
      </ul>
    </div>
  );
}

/** Plain-language summary of the chart for screen readers (and anyone else). */
function ChartSummary({
  rows,
  series,
  markers,
}: {
  rows: readonly HitRateRow[];
  series: readonly SeriesInfo[];
  markers: readonly MarkerLabel[];
}) {
  const last = rows[rows.length - 1];
  const parts = series.map((s) => {
    const values = rows.map((r) => r[s.name]).filter((v): v is number => typeof v === 'number');
    if (!last || values.length === 0) return `${s.name}: no data yet.`;
    const style = SERIES_STYLE[s.policy];
    return `${s.name} (${style.label}, ${style.lineStyle} line): ${formatPercent(last[s.name] ?? 0)} now, between ${formatPercent(Math.min(...values))} and ${formatPercent(Math.max(...values))} over the last ${Math.round((last.ts - (rows[0]?.ts ?? last.ts)) / 1000)} s.`;
  });
  return (
    <>
      <span>{parts.join(' ')}</span>
      {markers.length > 0 && (
        <span className="mt-1 block">
          Phase markers:{' '}
          {markers.map((m) => `${m.n} at ${formatClock(m.ts)} — ${m.caption}`).join('; ')}.
        </span>
      )}
    </>
  );
}
