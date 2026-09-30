import { ArrowDownRight, ArrowUpRight, Minus } from 'lucide-react';
import type { CSSProperties } from 'react';
import './components.css';
import { cx } from './cx';
import { InfoPopover } from './InfoPopover';

export interface MetricDelta {
  text: string;
  direction: 'up' | 'down' | 'flat';
  /** Whether this change is good news; undefined keeps the delta neutral. */
  good?: boolean;
}

export interface MetricTileProps {
  label: string;
  /** Pre-formatted value, e.g. "84.2%". */
  value: string;
  /** One plain sentence explaining the metric. */
  info: string;
  /** CSS colour for the value glow. Defaults to var(--trace-glow). */
  accent?: string;
  delta?: MetricDelta;
  sublabel?: string;
  className?: string;
}

const DIRECTION_TEXT: Record<MetricDelta['direction'], string> = {
  up: 'Up',
  down: 'Down',
  flat: 'Unchanged',
};

function DeltaIcon({ direction }: { direction: MetricDelta['direction'] }) {
  const props = { 'aria-hidden': true, size: 16, strokeWidth: 2.25 } as const;
  if (direction === 'up') return <ArrowUpRight {...props} />;
  if (direction === 'down') return <ArrowDownRight {...props} />;
  return <Minus {...props} />;
}

function deltaTone(delta: MetricDelta): string {
  if (delta.direction === 'flat' || delta.good === undefined) return 'text-muted';
  return delta.good ? 'text-good' : 'text-critical';
}

/** A KPI readout: large mono value with a faint glow, label, optional delta and an info popover. */
export function MetricTile({
  label,
  value,
  info,
  accent = 'var(--trace-glow)',
  delta,
  sublabel,
  className,
}: MetricTileProps) {
  const accentStyle = { '--metric-accent': accent } as CSSProperties;

  return (
    <div
      role="group"
      aria-label={`${label}: ${value}`}
      className={cx('rounded border border-trace bg-surface p-4', className)}
    >
      <div className="flex items-start justify-between gap-2">
        <p className="text-sm text-muted">{label}</p>
        <InfoPopover label={label}>{info}</InfoPopover>
      </div>
      <p
        className="metric-value mt-2 font-mono text-2xl tabular-nums text-text"
        style={accentStyle}
      >
        {value}
      </p>
      {delta || sublabel ? (
        <div className="mt-2 flex flex-wrap items-center gap-x-4 gap-y-1 text-sm">
          {delta ? (
            <span
              className={cx(
                'inline-flex items-center gap-1 font-mono tabular-nums',
                deltaTone(delta),
              )}
            >
              <DeltaIcon direction={delta.direction} />
              <span className="sr-only">{DIRECTION_TEXT[delta.direction]}: </span>
              {delta.text}
            </span>
          ) : null}
          {sublabel ? <span className="text-muted">{sublabel}</span> : null}
        </div>
      ) : null}
    </div>
  );
}
