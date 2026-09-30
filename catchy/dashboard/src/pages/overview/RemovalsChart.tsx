import { useId } from 'react';
import { Bar, BarChart, CartesianGrid, ResponsiveContainer, Tooltip, XAxis, YAxis } from 'recharts';
import { formatPerSecond, removalSummary, type RemovalRate } from './removals';

const HEIGHT = 280;
const AXIS_TICK = { fill: 'var(--text-muted)', fontSize: 14 };

/*
 * Bar colours are theme tokens that are neither policy nor status colours. The two tokens are too
 * close in hue to separate by colour alone (the palette validator fails them on both themes), so
 * the cause is also carried by texture: evictions are solid, expirations are hatched and outlined.
 */
const EVICTION_FILL = 'var(--trace-glow)';
const EXPIRATION_COLOR = 'var(--pad)';

export interface RemovalsChartProps {
  rates: readonly RemovalRate[];
}

/** Grouped bars: evictions/s (solid) vs expirations/s (hatched) per cache, last 10 s (SPEC 10.5). */
export function RemovalsChart({ rates }: RemovalsChartProps) {
  const hatchId = `hatch-${useId().replace(/[^a-zA-Z0-9_-]/g, '')}`;
  const hatch = `url(#${hatchId})`;

  return (
    <figure className="m-0">
      <svg width="0" height="0" aria-hidden="true" className="absolute">
        <defs>
          <pattern
            id={hatchId}
            width="6"
            height="6"
            patternUnits="userSpaceOnUse"
            patternTransform="rotate(45)"
          >
            <rect width="6" height="6" fill={EXPIRATION_COLOR} fillOpacity={0.18} />
            <line x1="0" y1="0" x2="0" y2="6" stroke={EXPIRATION_COLOR} strokeWidth="3" />
          </pattern>
        </defs>
      </svg>
      <ul className="mb-2 flex flex-wrap gap-x-6 gap-y-2" aria-label="Legend">
        <li className="flex items-center gap-2 text-sm text-text">
          <Swatch fill={EVICTION_FILL} />
          Evictions/s (solid) — removed to make room
        </li>
        <li className="flex items-center gap-2 text-sm text-text">
          <Swatch fill={hatch} stroke={EXPIRATION_COLOR} />
          Expirations/s (hatched) — TTL ran out
        </li>
      </ul>
      <div style={{ height: HEIGHT }}>
        <ResponsiveContainer width="100%" height={HEIGHT}>
          <BarChart
            data={rates as RemovalRate[]}
            margin={{ top: 8, right: 8, bottom: 0, left: 0 }}
            barGap={4}
          >
            <CartesianGrid stroke="var(--trace)" strokeOpacity={0.45} vertical={false} />
            <XAxis
              dataKey="name"
              tick={AXIS_TICK}
              tickLine={false}
              axisLine={{ stroke: 'var(--trace)' }}
              interval={0}
            />
            <YAxis
              allowDecimals={false}
              tickFormatter={(v: number) => formatPerSecond(v)}
              tick={AXIS_TICK}
              tickLine={false}
              axisLine={false}
              width={64}
            />
            <Tooltip
              isAnimationActive={false}
              cursor={{ fill: 'var(--pcb-surface-2)', fillOpacity: 0.6 }}
              content={(p) => (
                <RemovalsTooltip active={p.active} label={p.label} rates={rates} hatch={hatch} />
              )}
            />
            <Bar
              dataKey="evictionsPerSec"
              name="Evictions/s"
              fill={EVICTION_FILL}
              maxBarSize={40}
              isAnimationActive={false}
            />
            <Bar
              dataKey="expirationsPerSec"
              name="Expirations/s"
              fill={hatch}
              stroke={EXPIRATION_COLOR}
              strokeWidth={1.5}
              maxBarSize={40}
              isAnimationActive={false}
            />
          </BarChart>
        </ResponsiveContainer>
      </div>
      <figcaption className="mt-4 text-sm text-muted">{removalSummary(rates)}</figcaption>
    </figure>
  );
}

function Swatch({ fill, stroke }: { fill: string; stroke?: string }) {
  return (
    <svg width="14" height="14" aria-hidden="true" className="shrink-0">
      <rect
        x="0.75"
        y="0.75"
        width="12.5"
        height="12.5"
        fill={fill}
        stroke={stroke}
        strokeWidth={stroke ? 1.5 : 0}
      />
    </svg>
  );
}

function RemovalsTooltip({
  active,
  label,
  rates,
  hatch,
}: {
  active?: boolean;
  label?: unknown;
  rates: readonly RemovalRate[];
  hatch: string;
}) {
  const rate = rates.find((r) => r.name === label);
  if (!active || !rate) return null;
  return (
    <div className="rounded-md border border-trace bg-surface-2 px-3 py-2 text-sm shadow-lg">
      <p className="mb-1 font-mono text-text">{rate.name}</p>
      <ul className="space-y-1">
        <li className="flex items-center gap-2 text-text">
          <Swatch fill={EVICTION_FILL} />
          Evictions
          <span className="ml-auto pl-4 font-mono tabular-nums">
            {formatPerSecond(rate.evictionsPerSec)}
          </span>
        </li>
        <li className="flex items-center gap-2 text-text">
          <Swatch fill={hatch} stroke={EXPIRATION_COLOR} />
          Expirations
          <span className="ml-auto pl-4 font-mono tabular-nums">
            {formatPerSecond(rate.expirationsPerSec)}
          </span>
        </li>
      </ul>
    </div>
  );
}
