import { Link } from 'react-router-dom';
import type { ApplicationSummary } from '../api/types';
import { MemoryMeter } from '../charts/Meter';
import { useNow } from '../hooks/useNow';
import { formatPercent, formatRelativeTime, formatSecondsAgo } from '../lib/format';
import { paths } from '../lib/routes';
import { HealthBadge, PolicyBadge } from './Badge';
import { Icon } from './Icon';

export function capitalize(text: string): string {
  return text.length === 0 ? text : text.charAt(0).toUpperCase() + text.slice(1);
}

/** "3 s ago" from the live timestamp when present, else from the server-computed seconds. */
export function lastTelemetryText(summary: Pick<ApplicationSummary, 'lastTelemetryAt' | 'secondsSinceLastTelemetry'>, now: number): string {
  if (summary.lastTelemetryAt) return formatRelativeTime(summary.lastTelemetryAt, now);
  return formatSecondsAgo(summary.secondsSinceLastTelemetry);
}

interface ApplicationCardProps {
  summary: ApplicationSummary;
}

/**
 * One application at a glance: health, hit rate, memory, active policy, recommendation and how
 * fresh its telemetry is. Applications that never reported get an explicit empty state.
 */
export function ApplicationCard({ summary }: ApplicationCardProps) {
  const now = useNow(1000);
  const noTelemetry = summary.lastTelemetryAt == null;
  const t = summary.totals;
  const isSwitch = summary.recommendationSummary.toLowerCase().startsWith('switch');
  return (
    <article className="app-card">
      <header className="app-card__head">
        <div>
          <h3 className="app-card__title">
            <Link to={paths.application(summary.applicationId)}>{summary.displayName || summary.name}</Link>
          </h3>
          <p className="app-card__sub">
            {summary.projectName} · Environment: {capitalize(summary.environment)}
          </p>
        </div>
        <div className="app-card__health">
          <span className="app-card__health-label">Cache health</span>
          <HealthBadge status={summary.health.status} />
        </div>
      </header>

      {noTelemetry ? (
        <div className="app-card__empty">
          <Icon name="clock" size={18} />
          <div>
            <strong>No telemetry yet</strong>
            <p className="muted">Create an API key for this application and start a demo service, or run a simulation to see data here.</p>
          </div>
        </div>
      ) : (
        <dl className="facts">
          <div className="facts__item">
            <dt>Hit rate</dt>
            <dd className="num">{formatPercent(t.hitRate)}</dd>
          </div>
          <div className="facts__item">
            <dt>Active policy</dt>
            <dd>
              <PolicyBadge policy={summary.policyLabel} />
            </dd>
          </div>
          <div className="facts__item facts__item--wide">
            <dt>Memory (estimated)</dt>
            <dd>
              <MemoryMeter usedBytes={t.estimatedMemoryUsageBytes} maxBytes={t.maximumMemoryBytes} percent={t.memoryUtilizationPercent} size="sm" />
            </dd>
          </div>
          <div className="facts__item facts__item--wide">
            <dt>Recommendation</dt>
            <dd className={isSwitch ? 'rec-text rec-text--switch' : 'rec-text'}>{summary.recommendationSummary}</dd>
          </div>
        </dl>
      )}

      <footer className="app-card__foot">
        <span className="faint">
          Last telemetry received:{' '}
          <strong className="app-card__fresh">{noTelemetry ? 'never' : lastTelemetryText(summary, now)}</strong>
        </span>
        <Link to={paths.application(summary.applicationId)} className="app-card__link">
          Open <Icon name="arrow-right" size={13} />
        </Link>
      </footer>
    </article>
  );
}
