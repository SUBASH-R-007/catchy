import { useState } from 'react';
import { api } from '../api/endpoints';
import type { AuditAction, AuditLogEntry } from '../api/types';
import { AUDIT_ACTIONS } from '../api/types';
import { ApplicationSelect } from '../components/ApplicationSelect';
import { Badge, OutcomeBadge, RoleBadge } from '../components/Badge';
import { Button } from '../components/Button';
import { Card } from '../components/Card';
import { Field } from '../components/Field';
import { PageHeader } from '../components/PageHeader';
import { EmptyState, ErrorState, LoadingBlock } from '../components/States';
import { Table, type Column } from '../components/Table';
import { useFetch } from '../hooks/usePolling';
import { formatDateTime, humanizeEnum } from '../lib/format';

const LIMITS = [50, 100, 250, 500] as const;

const columns: Column<AuditLogEntry>[] = [
  { key: 'time', header: 'Time', className: 'nowrap num', cell: (e) => <time dateTime={e.timestamp}>{formatDateTime(e.timestamp)}</time> },
  { key: 'actor', header: 'Actor', cell: (e) => <strong>{e.actor}</strong> },
  {
    key: 'role',
    header: 'Role',
    cell: (e) =>
      e.actorRole === 'SYSTEM' ? (
        <Badge tone="neutral" title="Performed automatically by the service, not by a person">SYSTEM</Badge>
      ) : e.actorRole ? (
        <RoleBadge role={e.actorRole} />
      ) : (
        <span className="faint">—</span>
      ),
  },
  { key: 'action', header: 'Action', cell: (e) => <Badge tone="neutral"><span className="mono">{e.action}</span></Badge> },
  {
    key: 'target',
    header: 'Target',
    cell: (e) =>
      e.targetType ? (
        <span>
          {humanizeEnum(e.targetType)} {e.targetId ? <code>{e.targetId}</code> : null}
        </span>
      ) : (
        <span className="faint">—</span>
      ),
  },
  { key: 'app', header: 'App', align: 'right', className: 'num', cell: (e) => (e.applicationId != null ? e.applicationId : <span className="faint">—</span>) },
  { key: 'details', header: 'Details', className: 'col-wide', cell: (e) => e.details ?? <span className="faint">—</span> },
  { key: 'outcome', header: 'Outcome', cell: (e) => <OutcomeBadge outcome={e.outcome} /> },
];

/** ADMIN only: audit trail, newest first, filterable by action and application. Details never contain keys, values or secrets. */
export function AuditLogPage() {
  const [action, setAction] = useState<AuditAction | ''>('');
  const [applicationId, setApplicationId] = useState<number | null>(null);
  const [limit, setLimit] = useState<number>(100);
  const apps = useFetch(() => api.listApplications(), []);
  const logs = useFetch(() => api.auditLogs({ limit, action, applicationId }), [limit, action, applicationId], { keepPrevious: true });

  const rows = logs.data ?? [];
  const filtering = action !== '' || applicationId !== null;

  let body;
  if (logs.error && !logs.data) body = <ErrorState error={logs.error} onRetry={() => void logs.refresh()} title="Could not load the audit log" />;
  else if (logs.loading || !logs.data) body = <LoadingBlock label="Loading audit log" lines={6} height={22} />;
  else if (rows.length === 0) {
    body = (
      <EmptyState title={filtering ? 'No audit entries match these filters' : 'No audit entries yet'} icon="shield">
        {filtering ? 'Try another action or application.' : 'Sensitive actions (logins, key changes, approvals, simulations) are recorded here.'}
      </EmptyState>
    );
  } else {
    body = (
      <div className={logs.refreshing ? 'is-dimmed' : undefined}>
        <Table columns={columns} rows={rows} rowKey={(e) => e.id} caption="Audit log, newest first" />
      </div>
    );
  }

  return (
    <div className="page">
      <PageHeader
        title="Audit log"
        subtitle="Who did what, and whether it was allowed. Newest first. Details never contain keys, values or secrets."
        actions={
          <Button icon="refresh" onClick={() => void logs.refresh()} loading={logs.refreshing}>
            Refresh
          </Button>
        }
      />
      <div className="toolbar" role="group" aria-label="Audit log filters">
        <Field label="Action">
          {(p) => (
            <select {...p} className="select" value={action} onChange={(e) => setAction(e.target.value as AuditAction | '')}>
              <option value="">All actions</option>
              {AUDIT_ACTIONS.map((a) => (
                <option key={a} value={a}>
                  {a}
                </option>
              ))}
            </select>
          )}
        </Field>
        <ApplicationSelect applications={apps.data ?? []} value={applicationId} onChange={setApplicationId} allowAll disabled={!apps.data} />
        <Field label="Show">
          {(p) => (
            <select {...p} className="select" value={limit} onChange={(e) => setLimit(Number(e.target.value))}>
              {LIMITS.map((l) => (
                <option key={l} value={l}>
                  Latest {l}
                </option>
              ))}
            </select>
          )}
        </Field>
        <p className="toolbar__count muted" aria-live="polite">
          {logs.data ? `${rows.length} entr${rows.length === 1 ? 'y' : 'ies'}` : ''}
        </p>
      </div>
      <Card>{body}</Card>
    </div>
  );
}
