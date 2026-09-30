import { Link } from 'react-router-dom';
import type { Alert } from '../api/types';
import { useNow } from '../hooks/useNow';
import { formatRelativeTime } from '../lib/format';
import { paths } from '../lib/routes';
import { SeverityBadge } from './Badge';
import { EmptyState } from './States';

interface AlertListProps {
  alerts: Alert[];
}

/** Active alerts: severity, message, application/region and a link to the affected region. */
export function AlertList({ alerts }: AlertListProps) {
  const now = useNow(1000);
  if (alerts.length === 0) {
    return (
      <EmptyState title="No active alerts" icon="check">
        Every reporting cache region is currently within its health targets.
      </EmptyState>
    );
  }
  return (
    <ul className="alert-list">
      {alerts.map((a) => (
        <li key={a.id} className={`alert-item alert-item--${a.severity.toLowerCase()}`}>
          <SeverityBadge severity={a.severity} />
          <div className="alert-item__body">
            <p className="alert-item__msg">{a.message}</p>
            <p className="alert-item__meta">
              <Link to={paths.region(a.applicationId, a.cacheRegion)}>
                {a.applicationName} / {a.cacheRegion}
              </Link>
              <span className="faint"> · since {formatRelativeTime(a.since, now)}</span>
            </p>
          </div>
        </li>
      ))}
    </ul>
  );
}
