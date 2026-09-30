import { useEffect, useId, useRef } from 'react';
import type { CacheMetrics } from '../api/types';
import { formatInteger, formatPercent } from '../lib/format';
import {
  BUS_HEIGHT,
  BUS_WIDTH,
  busCaption,
  CHIP_CENTERS,
  CHIP_HEIGHT,
  CHIP_TOP,
  CHIP_WIDTH,
  EMPTY_BUS,
  electronPosition,
  LANE_BACK,
  LANE_OUT,
  MAX_ELECTRONS,
  mulberry32,
  requestsPerElectron,
  stepBus,
  type BusState,
} from './busMath';
import { useMotionAllowed } from './useMotionAllowed';

/** Frames further apart than this (a stalled or hidden tab) advance the bus by this much only. */
const MAX_FRAME_SEC = 0.1;
const ELECTRON_SEED = 20260930;
const HIT_CLASS = 'fill-good';
const MISS_CLASS = 'fill-warn';
const ARROW_COLORS = ['trace-glow', 'good', 'warn'] as const;
const SLOTS = Array.from({ length: MAX_ELECTRONS }, (_, i) => i);

export interface DataBusProps {
  /** The cache selected on the Overview; its live rate and 10 s hit rate drive the electrons. */
  cache: Pick<CacheMetrics, 'name' | 'opsPerSec' | 'hitRateWindow10s'>;
}

/**
 * The Data bus (SPEC 10.6): CLIENT, CACHE and DB chips joined by copper traces. Each electron is a
 * request: green ones turn around in the cache (hits), orange ones travel on to the database
 * (misses). The requestAnimationFrame loop writes transforms straight to a fixed pool of SVG
 * circles, so React never re-renders per frame. With reduced motion or the "Circuit animation"
 * toggle off, static arrows show the hit and miss shares instead.
 */
export function DataBus({ cache }: DataBusProps) {
  const id = useId();
  const motion = useMotionAllowed();
  const electronsRef = useRef<SVGGElement>(null);
  const inputRef = useRef({ opsPerSec: cache.opsPerSec, hitRate: cache.hitRateWindow10s });

  useEffect(() => {
    inputRef.current = { opsPerSec: cache.opsPerSec, hitRate: cache.hitRateWindow10s };
  }, [cache.opsPerSec, cache.hitRateWindow10s]);

  useEffect(() => {
    if (!motion) return;
    const random = mulberry32(ELECTRON_SEED);
    let state: BusState = EMPTY_BUS;
    let frame = 0;
    let last: number | null = null;

    const draw = () => {
      const nodes = electronsRef.current?.children;
      if (!nodes) return;
      for (let i = 0; i < nodes.length; i++) {
        const node = nodes[i];
        const electron = state.electrons[i];
        if (!node) continue;
        if (!electron) {
          node.setAttribute('visibility', 'hidden');
          continue;
        }
        const { x, y } = electronPosition(electron);
        node.setAttribute('transform', `translate(${x.toFixed(1)} ${y.toFixed(1)})`);
        node.setAttribute('class', electron.hit ? HIT_CLASS : MISS_CLASS);
        node.removeAttribute('visibility');
      }
    };

    const loop = (ts: number) => {
      const dt = last === null ? 0 : Math.min(MAX_FRAME_SEC, (ts - last) / 1000);
      last = ts;
      state = stepBus(state, { dt, ...inputRef.current, random });
      draw();
      frame = requestAnimationFrame(loop);
    };
    const start = () => {
      if (frame !== 0 || document.hidden) return;
      last = null;
      frame = requestAnimationFrame(loop);
    };
    const stop = () => {
      cancelAnimationFrame(frame);
      frame = 0;
    };
    const onVisibility = () => (document.hidden ? stop() : start());

    document.addEventListener('visibilitychange', onVisibility);
    start();
    return () => {
      stop();
      document.removeEventListener('visibilitychange', onVisibility);
    };
  }, [motion]);

  const hitRate = Math.min(1, Math.max(0, cache.hitRateWindow10s));
  const hitPct = formatPercent(hitRate, 0);
  const missPct = formatPercent(1 - hitRate, 0);
  const perElectron = requestsPerElectron(cache.opsPerSec);
  const caption = busCaption(hitRate, cache.opsPerSec);
  const titleId = `${id}-title`;
  const markerId = `${id.replace(/:/g, '')}-arrow`;

  return (
    <figure className="m-0">
      <svg
        viewBox={`0 0 ${BUS_WIDTH} ${BUS_HEIGHT}`}
        role="img"
        aria-labelledby={titleId}
        className="block h-auto w-full max-h-56"
        data-testid="data-bus"
        data-motion={motion ? 'on' : 'off'}
      >
        <title id={titleId}>
          {`Data bus for ${cache.name}: ${hitPct} of requests are hits served by the cache, ${missPct} are misses that go on to the database.`}
        </title>
        <defs>
          {ARROW_COLORS.map((color) => (
            <marker
              key={color}
              id={`${markerId}-${color}`}
              viewBox="0 0 10 10"
              refX="8"
              refY="5"
              markerWidth="6"
              markerHeight="6"
              orient="auto"
            >
              <path d="M0 0 L10 5 L0 10 z" fill={`var(--${color})`} />
            </marker>
          ))}
        </defs>

        {/* Copper traces: outbound on the upper lane, return on the lower lane. */}
        <g fill="none" stroke="var(--trace)" strokeWidth={4} strokeLinecap="round">
          <line x1={CHIP_CENTERS.client} y1={LANE_OUT} x2={CHIP_CENTERS.db} y2={LANE_OUT} />
          <line x1={CHIP_CENTERS.client} y1={LANE_BACK} x2={CHIP_CENTERS.db} y2={LANE_BACK} />
        </g>

        {motion ? (
          <g ref={electronsRef} aria-hidden="true">
            {SLOTS.map((i) => (
              <circle key={i} r={5} cx={0} cy={0} visibility="hidden" className={HIT_CLASS} />
            ))}
          </g>
        ) : (
          <StaticArrows markerId={markerId} hitPct={hitPct} missPct={missPct} />
        )}

        <Chip x={CHIP_CENTERS.client} label="CLIENT" />
        <Chip x={CHIP_CENTERS.cache} label="CACHE" sub={cache.name} />
        <Chip x={CHIP_CENTERS.db} label="DB" />
      </svg>

      <ul
        className="mt-2 flex flex-wrap gap-x-6 gap-y-1 text-sm text-text"
        aria-label="Data bus legend"
      >
        <li className="flex items-center gap-2">
          <span aria-hidden="true" className="size-3 rounded-full bg-good" />
          Hit · back from the cache ({hitPct})
        </li>
        <li className="flex items-center gap-2">
          <span aria-hidden="true" className="size-3 rounded-full bg-warn" />
          Miss · on to the database ({missPct})
        </li>
        {motion ? (
          <li className="font-mono text-muted tabular-nums">
            1 electron ≈ {formatInteger(perElectron)}{' '}
            {perElectron === 1 ? 'request' : 'requests'}
          </li>
        ) : null}
      </ul>
      <figcaption className="mt-2 font-heading text-lg text-text">{caption}</figcaption>
    </figure>
  );
}

function Chip({ x, label, sub }: { x: number; label: string; sub?: string }) {
  const left = x - CHIP_WIDTH / 2;
  const pins = [0, 1, 2, 3, 4];
  return (
    <g aria-hidden="true">
      {pins.map((p) => (
        <g key={p} fill="var(--pad)" opacity={0.5}>
          <rect x={left + 14 + p * 20} y={CHIP_TOP - 6} width={8} height={6} rx={1} />
          <rect x={left + 14 + p * 20} y={CHIP_TOP + CHIP_HEIGHT} width={8} height={6} rx={1} />
        </g>
      ))}
      <rect
        x={left}
        y={CHIP_TOP}
        width={CHIP_WIDTH}
        height={CHIP_HEIGHT}
        rx={4}
        fill="var(--pcb-surface-2)"
        stroke="var(--trace-glow)"
        strokeWidth={1.5}
      />
      <circle cx={left + 10} cy={CHIP_TOP + 10} r={3} fill="var(--pad)" opacity={0.6} />
      <text
        x={x}
        y={sub ? CHIP_TOP + 42 : CHIP_TOP + 50}
        textAnchor="middle"
        fill="var(--text)"
        fontFamily="var(--font-mono)"
        fontSize={16}
        fontWeight={600}
        letterSpacing="0.15em"
      >
        {label}
      </text>
      {sub ? (
        <text
          x={x}
          y={CHIP_TOP + 64}
          textAnchor="middle"
          fill="var(--text-muted)"
          fontFamily="var(--font-mono)"
          fontSize={14}
        >
          {sub.length > 14 ? `${sub.slice(0, 13)}…` : sub}
        </text>
      ) : null}
    </g>
  );
}

/** Reduced motion: labelled arrows instead of moving electrons. */
function StaticArrows({
  markerId,
  hitPct,
  missPct,
}: {
  markerId: string;
  hitPct: string;
  missPct: string;
}) {
  const clientEdge = CHIP_CENTERS.client + CHIP_WIDTH / 2;
  const cacheLeft = CHIP_CENTERS.cache - CHIP_WIDTH / 2;
  const cacheRight = CHIP_CENTERS.cache + CHIP_WIDTH / 2;
  const dbLeft = CHIP_CENTERS.db - CHIP_WIDTH / 2;
  const marker = (color: (typeof ARROW_COLORS)[number]) => `url(#${markerId}-${color})`;
  return (
    <g aria-hidden="true" strokeWidth={3} fontFamily="var(--font-mono)" fontSize={14}>
      <line
        x1={clientEdge + 6}
        y1={LANE_OUT}
        x2={cacheLeft - 6}
        y2={LANE_OUT}
        stroke="var(--trace-glow)"
        markerEnd={marker('trace-glow')}
      />
      <line
        x1={cacheLeft - 6}
        y1={LANE_BACK}
        x2={clientEdge + 6}
        y2={LANE_BACK}
        stroke="var(--good)"
        markerEnd={marker('good')}
      />
      <text x={(clientEdge + cacheLeft) / 2} y={LANE_BACK + 24} textAnchor="middle" fill="var(--good)">
        {`hit ${hitPct}`}
      </text>
      <line
        x1={cacheRight + 6}
        y1={LANE_OUT}
        x2={dbLeft - 6}
        y2={LANE_OUT}
        stroke="var(--warn)"
        markerEnd={marker('warn')}
      />
      <line
        x1={dbLeft - 6}
        y1={LANE_BACK}
        x2={cacheRight + 6}
        y2={LANE_BACK}
        stroke="var(--warn)"
        markerEnd={marker('warn')}
      />
      <text x={(cacheRight + dbLeft) / 2} y={LANE_OUT - 12} textAnchor="middle" fill="var(--warn)">
        {`miss ${missPct}`}
      </text>
    </g>
  );
}
