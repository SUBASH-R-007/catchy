import type { Health } from '../api/types';
import { Donut } from '../charts/Donut';
import { formatInt, formatPercent } from '../lib/format';
import { HEALTH_PRESENTATION } from '../lib/health';
import { HealthBadge } from './Badge';
import { Icon } from './Icon';

interface HealthPanelProps {
  health: Health;
  /** Optional context line, e.g. "Worst region: member-eligibility". */
  note?: string;
}

/** Health status, score and the exact reasons the backend reported (verbatim, never paraphrased). */
export function HealthPanel({ health, note }: HealthPanelProps) {
  const p = HEALTH_PRESENTATION[health.status];
  const failing = health.status === 'WARNING' || health.status === 'CRITICAL' || health.status === 'UNKNOWN';
  return (
    <div className="health-panel">
      <div className="health-panel__head">
        <HealthBadge status={health.status} />
        {health.status !== 'UNKNOWN' ? (
          <span className="health-panel__score">
            Score <strong className="num">{health.score}</strong>
            <span className="faint">/100</span>
          </span>
        ) : null}
      </div>
      {note ? <p className="faint">{note}</p> : null}
      <ul className="reasons" aria-label={`${p.label} health reasons`}>
        {health.reasons.map((reason) => (
          <li key={reason} className={failing ? 'reasons__item reasons__item--fail' : 'reasons__item'}>
            <Icon name={failing ? 'alert' : 'check'} size={14} className="reasons__icon" />
            <span>{reason}</span>
          </li>
        ))}
      </ul>
    </div>
  );
}

interface HitMissDonutProps {
  hits: number;
  misses: number;
  hitRate: number;
  size?: number;
}

/** Hit vs miss ring. Hits and misses use the first two categorical slots (blue / orange). */
export function HitMissDonut({ hits, misses, hitRate, size }: HitMissDonutProps) {
  return (
    <Donut
      size={size}
      segments={[
        { key: 'hits', label: 'Hits', value: hits, color: 'var(--series-hit)', valueLabel: formatInt(hits) },
        { key: 'misses', label: 'Misses', value: misses, color: 'var(--series-miss)', valueLabel: formatInt(misses) },
      ]}
      centerLabel={formatPercent(hitRate, 1)}
      centerSub="hit rate"
      emptyLabel="No requests yet"
    />
  );
}
