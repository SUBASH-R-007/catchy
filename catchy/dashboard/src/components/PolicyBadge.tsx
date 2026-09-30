import type { PolicyType } from '../api/types';
import { SERIES_STYLE } from '../theme/policy';
import { cx } from './cx';

export interface PolicyBadgeProps {
  policy: PolicyType | 'OPTIMAL';
  className?: string;
}

/**
 * Policy name in its Okabe-Ito colour plus its chart line style, so colour is never the only cue.
 * The badge sits on the darkest board colour so every policy colour keeps AA contrast.
 */
export function PolicyBadge({ policy, className }: PolicyBadgeProps) {
  const style = SERIES_STYLE[policy];

  return (
    <span
      className={cx(
        'inline-flex items-center gap-2 rounded-sm border bg-bg px-2 py-1 font-mono text-sm leading-none',
        className,
      )}
      style={{
        color: style.color,
        borderColor: `color-mix(in srgb, ${style.color} 45%, transparent)`,
      }}
      data-policy={policy}
    >
      <svg aria-hidden="true" width="32" height="8" viewBox="0 0 32 8" className="shrink-0">
        <line
          x1="1"
          y1="4"
          x2="31"
          y2="4"
          stroke="currentColor"
          strokeWidth="2"
          strokeDasharray={style.dash}
        />
      </svg>
      <span>{style.label}</span>
      <span className="sr-only"> ({style.lineStyle} line)</span>
    </span>
  );
}
