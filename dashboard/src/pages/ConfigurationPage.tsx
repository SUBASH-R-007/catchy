import { useSearchParams } from 'react-router-dom';
import { api } from '../api/endpoints';
import type { Application } from '../api/types';
import { ApplicationSelect } from '../components/ApplicationSelect';
import { PolicyBadge, RiskBadge } from '../components/Badge';
import { Card } from '../components/Card';
import { ConfigEditor, ConfigRows, PendingNotice } from '../components/ConfigPanels';
import { Field } from '../components/Field';
import { LiveIndicator } from '../components/LiveIndicator';
import { PageHeader } from '../components/PageHeader';
import { EmptyState, ErrorState, LoadingBlock } from '../components/States';
import { useFetch, usePolling } from '../hooks/usePolling';
import { formatDateTime } from '../lib/format';

function pickApp(apps: Application[], requested: number | null): number | null {
  if (requested !== null && apps.some((a) => a.id === requested)) return requested;
  return apps.find((a) => a.regionCount > 0)?.id ?? apps[0]?.id ?? null;
}

/** ADMIN: choose application and region, see reported vs desired tuning, and request a change. */
export function ConfigurationPage() {
  const [params, setParams] = useSearchParams();
  const apps = useFetch(() => api.listApplications(), []);
  const requestedApp = params.get('app');
  const requested = requestedApp !== null && Number.isInteger(Number(requestedApp)) ? Number(requestedApp) : null;
  const appId = apps.data ? pickApp(apps.data, requested) : null;

  const regions = useFetch(() => api.applicationRegions(appId as number), [appId], { enabled: appId !== null });
  const regionNames = (regions.data ?? []).map((r) => r.cacheRegion).sort();
  const requestedRegion = params.get('region');
  const region = requestedRegion && regionNames.includes(requestedRegion) ? requestedRegion : (regionNames[0] ?? null);

  const config = usePolling(() => api.getRegionConfig(appId as number, region as string), [appId, region], { enabled: appId !== null && region !== null });

  const select = (nextApp: number | null, nextRegion?: string | null) => {
    const q: Record<string, string> = {};
    if (nextApp !== null) q.app = String(nextApp);
    if (nextRegion) q.region = nextRegion;
    setParams(q, { replace: true });
  };

  let body;
  if (apps.error && !apps.data) body = <ErrorState error={apps.error} onRetry={() => void apps.refresh()} title="Could not load applications" />;
  else if (apps.loading || !apps.data) body = <LoadingBlock label="Loading applications" lines={4} />;
  else if (apps.data.length === 0) {
    body = (
      <EmptyState title="No applications yet" icon="layers">
        Register an application first.
      </EmptyState>
    );
  } else if (regions.loading) body = <LoadingBlock label="Loading regions" lines={3} />;
  else if (regions.error) body = <ErrorState error={regions.error} onRetry={() => void regions.refresh()} title="Could not load regions" />;
  else if (region === null) {
    body = (
      <EmptyState title="This application has no cache regions yet" icon="pulse">
        No telemetry yet — start a demo service or run a simulation. Configuration is available once a region has reported.
      </EmptyState>
    );
  } else if (config.error && !config.data) body = <ErrorState error={config.error} onRetry={() => void config.refresh()} title="Could not load the configuration" />;
  else if (config.loading || !config.data) body = <LoadingBlock label="Loading configuration" lines={5} />;
  else {
    const c = config.data;
    body = (
      <div className="grid-2">
        <Card
          title="Reported vs desired"
          subtitle="Reported values come from the SDK; desired values are what an admin asked for."
          actions={<LiveIndicator updatedAt={config.updatedAt} error={config.error} paused={config.paused} loading={config.loading} />}
        >
          <div className="stack-sm">
            <div className="row">
              <RiskBadge level={c.riskLevel} />
              {c.reported.activePolicy ? <PolicyBadge policy={c.reported.activePolicy} /> : null}
              {c.tuningVersion ? <span className="faint">Tuning version {c.tuningVersion}</span> : null}
            </div>
            <ConfigRows config={c} />
            <PendingNotice config={c} />
            {c.updatedBy ? (
              <p className="faint">
                Last changed by {c.updatedBy}
                {c.updatedAt ? ` · ${formatDateTime(c.updatedAt)}` : ''}
              </p>
            ) : (
              <p className="faint">No override has been set for this region.</p>
            )}
          </div>
        </Card>
        <Card title="Request a change" subtitle="Changes are delivered to the SDK on its next control poll and audited.">
          <ConfigEditor applicationId={appId as number} config={c} onSaved={() => void config.refresh()} />
        </Card>
      </div>
    );
  }

  return (
    <div className="page">
      <PageHeader
        title="Configuration"
        subtitle="Tune a region's maximum entries, estimated memory limit and default TTL. Memory is entered in MB and TTL in seconds."
      />
      <div className="toolbar" role="group" aria-label="Choose application and region">
        <ApplicationSelect applications={apps.data ?? []} value={appId} onChange={(id) => select(id)} disabled={!apps.data} />
        <Field label="Cache region">
          {(p) => (
            <select {...p} className="select" value={region ?? ''} disabled={regionNames.length === 0} onChange={(e) => select(appId, e.target.value)}>
              {regionNames.length === 0 ? <option value="">No regions</option> : null}
              {regionNames.map((r) => (
                <option key={r} value={r}>
                  {r}
                </option>
              ))}
            </select>
          )}
        </Field>
      </div>
      {body}
    </div>
  );
}
