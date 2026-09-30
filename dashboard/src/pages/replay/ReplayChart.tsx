import { useId } from 'react';
import {
  Bar,
  BarChart,
  CartesianGrid,
  ResponsiveContainer,
  Tooltip,
  XAxis,
  YAxis,
  type BarShapeProps,
} from 'recharts';
import type { ReplayResult } from '../../api/rest';
import { InfoPopover } from '../../components';
import { formatPercent } from '../../lib/format';
import { SERIES_STYLE } from '../../theme/policy';
import { OPTIMAL_INFO } from '../overview/HitRateChart';
import { replayBars, replaySummary, type ReplayBar } from './replay';

const HEIGHT = 280;
const AXIS_TICK = { fill: 'var(--text-muted)', fontSize: 14 };
const RADIUS = 4;

/**
 * Hit rate per replayed policy plus the optimal (SPEC 10.5 item 5). Policies wear their colour and
 * their name on the axis; the optimal bar is grey AND hatched with an outline, so it never relies
 * on colour alone. Each bar carries its value as a direct label.
 */
export function ReplayChart({ result }: { result: ReplayResult }) {
  const hatchId = `optimal-hatch-${useId().replace(/[^a-zA-Z0-9_-]/g, '')}`;
  const hatch = `url(#${hatchId})`;
  const bars = replayBars(result);

  const renderBar = (props: BarShapeProps) => {
    const bar = props.payload as ReplayBar | undefined;
    const { x, y, width, height } = props;
    if (!bar || height <= 0 || width <= 0) return null;
    const r = Math.min(RADIUS, height, width / 2);
    const d = `M${x},${y + height}V${y + r}Q${x},${y} ${x + r},${y}H${x + width - r}Q${x + width},${y} ${x + width},${y + r}V${y + height}Z`;
    const optimal = bar.kind === 'OPTIMAL';
    const color = SERIES_STYLE[bar.kind].color;
    return (
      <g>
        <path
          d={d}
          fill={optimal ? hatch : color}
          stroke={optimal ? color : 'none'}
          strokeWidth={optimal ? 1.5 : 0}
          strokeDasharray={optimal ? SERIES_STYLE.OPTIMAL.dash : undefined}
        />
        <text
          x={x + width / 2}
          y={y - 8}
          textAnchor="middle"
          fontSize={14}
          fill="var(--text)"
          className="font-mono tabular-nums"
        >
          {formatPercent(bar.hitRate)}
        </text>
      </g>
    );
  };

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
            <rect width="6" height="6" fill={SERIES_STYLE.OPTIMAL.color} fillOpacity={0.15} />
            <line x1="0" y1="0" x2="0" y2="6" stroke={SERIES_STYLE.OPTIMAL.color} strokeWidth="3" />
          </pattern>
        </defs>
      </svg>
      <ul className="mb-2 flex flex-wrap items-center gap-x-6 gap-y-2" aria-label="Legend">
        {bars.map((b) => (
          <li key={b.kind} className="flex items-center gap-2 text-sm text-text">
            <Swatch
              fill={b.kind === 'OPTIMAL' ? hatch : SERIES_STYLE[b.kind].color}
              stroke={b.kind === 'OPTIMAL' ? SERIES_STYLE.OPTIMAL.color : undefined}
            />
            {b.kind === 'OPTIMAL' ? (
              <>
                <span>{b.label} — hatched, an upper bound</span>
                <InfoPopover label={b.label} align="start">
                  {OPTIMAL_INFO}
                </InfoPopover>
              </>
            ) : (
              <span>{b.label}</span>
            )}
          </li>
        ))}
      </ul>
      <div style={{ height: HEIGHT }}>
        <ResponsiveContainer width="100%" height={HEIGHT}>
          <BarChart data={bars} margin={{ top: 28, right: 8, bottom: 0, left: 0 }}>
            <CartesianGrid stroke="var(--trace)" strokeOpacity={0.45} vertical={false} />
            <XAxis
              dataKey="label"
              tick={AXIS_TICK}
              tickLine={false}
              axisLine={{ stroke: 'var(--trace)' }}
              interval={0}
            />
            <YAxis
              domain={[0, 1]}
              ticks={[0, 0.25, 0.5, 0.75, 1]}
              tickFormatter={(v: number) => formatPercent(v, 0)}
              tick={AXIS_TICK}
              tickLine={false}
              axisLine={false}
              width={56}
            />
            <Tooltip
              isAnimationActive={false}
              cursor={{ fill: 'var(--pcb-surface-2)', fillOpacity: 0.6 }}
              content={(p) => <BarTooltip active={p.active} label={p.label} bars={bars} />}
            />
            <Bar
              dataKey="hitRate"
              name="Hit rate"
              maxBarSize={72}
              isAnimationActive={false}
              shape={renderBar}
            />
          </BarChart>
        </ResponsiveContainer>
      </div>
      <figcaption className="mt-4 text-sm text-muted">{replaySummary(result)}</figcaption>
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
        rx="2"
        fill={fill}
        stroke={stroke}
        strokeWidth={stroke ? 1.5 : 0}
      />
    </svg>
  );
}

function BarTooltip({
  active,
  label,
  bars,
}: {
  active?: boolean;
  label?: unknown;
  bars: readonly ReplayBar[];
}) {
  const bar = bars.find((b) => b.label === label);
  if (!active || !bar) return null;
  return (
    <div className="rounded-md border border-trace bg-surface-2 px-3 py-2 text-sm shadow-lg">
      <p className="text-text">{bar.label}</p>
      <p className="font-mono text-text tabular-nums">{formatPercent(bar.hitRate)} hit rate</p>
    </div>
  );
}
