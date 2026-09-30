import type { ReactNode } from 'react';
import type { Tone } from '../lib/health';

interface TileProps {
  label: string;
  value: ReactNode;
  /** Secondary line under the value. */
  sub?: ReactNode;
  /** Tiny qualifier next to the label, e.g. "estimated". */
  qualifier?: string;
  tone?: Tone;
  /** Optional visual (sparkline, meter) rendered under the value. */
  children?: ReactNode;
  className?: string;
}

/** KPI tile: label, proportional-figure value, optional sub line and a small visual. */
export function Tile({ label, value, sub, qualifier, tone, children, className }: TileProps) {
  return (
    <div className={`tile${tone ? ` tile--${tone}` : ''}${className ? ` ${className}` : ''}`} role="group" aria-label={label}>
      <div className="tile__label">
        {label}
        {qualifier ? <span className="tile__qualifier">{qualifier}</span> : null}
      </div>
      <div className="tile__value">{value}</div>
      {sub ? <div className="tile__sub">{sub}</div> : null}
      {children ? <div className="tile__visual">{children}</div> : null}
    </div>
  );
}
