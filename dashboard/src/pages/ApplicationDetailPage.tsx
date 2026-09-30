import { useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import { api } from '../api/endpoints';
import type { Recommendation } from '../api/types';
import { StackedBars } from '../charts/StackedBars';
import { MemoryMeter } from '../charts/Meter';
import { ApiKeysPanel } from '../components/ApiKeysPanel';
import { capitalize, lastTelemetryText } from '../components/ApplicationCard';
import { Badge, HealthBadge, PolicyBadge } from '../components/Badge';
import { Card } from '../components/Card';
import { EvictionXray } from '../components/EvictionXray';
import { HealthPanel, HitMissDonut } from '../components/HealthPanel';
import { LiveIndicator } from '../components/LiveIndicator';
import { isNotFound, NotFoundNotice } from '../components/NotFoundNotice';
import { PageHeader } from '../components/PageHeader';
import { PolicyRequestsPanel } from '../components/PolicyRequestsPanel';
import { RecommendationCard } from '../components/RecommendationCard';
import { RegionTable } from '../components/RegionTable';
import { EmptyState, ErrorState, LoadingBlock, TileSkeletons } from '../components/States';
import { DEFAULT_TIMELINE_RANGE, TimelinePanel, type TimelineRange } from '../components/TimelinePanel';
import { Tile } from '../components/Tile';
import { useAuth } from '../context/AuthContext';
import { useGroupSelection } from '../hooks/useGroupSelection';
import { useNow } from '../hooks/useNow';
import { usePolling, useFetch } from '../hooks/usePolling';
import { worstRegion } from '../lib/applications';
import { formatCompact, formatInt, formatPercent } from '../lib/format';
import { hasRole } from '../lib/roles';
import { paths } from '../lib/routes';

export function ApplicationDetailPage() {
  const { appId } = useParams();
  const id = Number(appId);
  if (!Number.isInteger(id) || id <= 0) return <NotFoundNotice what="Application" backTo={paths.applications()} backLabel="All applications" />;
  return <ApplicationDetail key={id} id={id} />;
}

function ApplicationDetail({ id }: { id: number }) {
  const { user } = useAuth();
  const now = useNow(1000);
  const [range, setRange] = useState<TimelineRange>(DEFAULT_TIMELINE_RANGE);
  const xray = useGroupSelection();

  const meta = useFetch(() => api.getApplication(id), [id]);
  const metrics = usePolling(() => api.applicationMetrics(id), [id]);
  const timeline = usePolling(() => api.applicationTimeline(id, { minutes: range.minutes, bucketSeconds: range.bucketSeconds }), [id, range.minutes], { keepPrevious: true });
  const recs = usePolling(() => api.listRecommendations(id), [id]);
  const requests = usePolling(() => api.listPolicyRequests(id), [id]);
  const events = usePolling(() => api.applicationEvents(id, { limit: 100, actions: xray.actions }), [id, xray.actionsKey], { keepPrevious: true });

  const isAdmin = hasRole(user?.role, 'ADMIN');
  const isEngineer = hasRole(user?.role, 'ENGINEER');

  if (isNotFound(meta.error) || isNotFound(metrics.error)) {
    return <NotFoundNotice what="Application" backTo={paths.applications()} backLabel="All applications" />;
  }

  const m = metrics.data;
  const app = meta.data;
  const name = m?.displayName || app?.displayName || app?.name || 'Application';
  const noTelemetry = m !== undefined && m.lastTelemetryAt == null && m.regions.length === 0;
  const worst = m ? worstRegion(m.regions) : null;

  return (
    <div className="page">
      <PageHeader
        crumbs={[{ label: 'Applications', to: paths.applications() }, { label: name }]}
        title={name}
        subtitle={
          <>
            {m ? `${m.projectName} · ` : ''}Environment: {capitalize(m?.environment ?? app?.environment ?? '…')}
            {app?.description ? ` · ${app.description}` : ''}
          </>
        }
        badges={m ? <HealthBadge status={m.health.status} /> : undefined}
        actions={
          <>
            <LiveIndicator updatedAt={metrics.updatedAt} error={metrics.error} paused={metrics.paused} loading={metrics.loading} />
            <Link to={paths.policyArena(id)} className="btn btn--secondary btn--sm">
              Policy Arena
            </Link>
            {isEngineer ? (
              <Link to={paths.simulations(id)} className="btn btn--secondary btn--sm">
                Simulations
              </Link>
            ) : null}
          </>
        }
      />

      {metrics.error && !m ? (
        <ErrorState error={metrics.error} onRetry={() => void metrics.refresh()} title="Could not load this application" />
      ) : !m ? (
        <div className="stack" aria-busy="true">
          <TileSkeletons count={8} />
          <LoadingBlock label="Loading application" lines={6} height={18} />
        </div>
      ) : (
        <div className="stack">
          {noTelemetry ? (
            <Card>
              <EmptyState
                title="No telemetry yet"
                icon="pulse"
                action={
                  <div className="row">
                    {isEngineer ? (
                      <Link to={paths.simulations(id)} className="btn btn--primary">
                        Run a simulation
                      </Link>
                    ) : null}
                  </div>
                }
              >
                No telemetry yet — start a demo service or run a simulation.{' '}
                {isAdmin ? 'Create an API key below and configure the SDK with it.' : 'An ADMIN can create the API key the SDK needs.'}
              </EmptyState>
            </Card>
          ) : (
            <>
              <div className="grid-kpi grid-kpi--4">
                <Tile label="Hit rate" value={formatPercent(m.totals.hitRate)} sub={`${formatInt(m.totals.hits)} hits`} />
                <Tile label="Miss rate" value={formatPercent(m.totals.missRate)} sub={`${formatInt(m.totals.misses)} misses`} />
                <Tile label="Cache regions" value={formatInt(m.regionCount)} sub={<>Policy: <PolicyBadge policy={m.policyLabel} /></>} />
                <Tile label="Evictions" value={formatInt(m.totals.evictions)} sub={`${formatInt(m.totals.evictionsDueToEntryLimit)} entry · ${formatInt(m.totals.evictionsDueToMemoryLimit)} memory`} />
                <Tile label="Expirations" value={formatInt(m.totals.expirations)} />
                <Tile label="Source calls avoided" value={formatCompact(m.totals.sourceCallsAvoided)} sub={`${formatInt(m.totals.sourceCallsAvoided)} calls`} />
                <Tile label="Recommendation" value={<span className="tile__text">{m.recommendationSummary}</span>} />
                <Tile label="Last telemetry" value={<span className="tile__text">{lastTelemetryText(m, now)}</span>} />
              </div>

              <div className="grid-2">
                <Card title="Cache health" subtitle="Worst health across this application's regions, with the exact reasons.">
                  <HealthPanel health={m.health} note={worst && (worst.health.status === 'WARNING' || worst.health.status === 'CRITICAL') ? `Worst region: ${worst.cacheRegion}` : undefined} />
                </Card>
                <Card title="Hit vs miss and memory">
                  <div className="split">
                    <HitMissDonut hits={m.totals.hits} misses={m.totals.misses} hitRate={m.totals.hitRate} size={150} />
                    <div className="stack-sm split__side">
                      <h3>Memory (estimated)</h3>
                      <MemoryMeter
                        usedBytes={m.totals.estimatedMemoryUsageBytes}
                        maxBytes={m.totals.maximumMemoryBytes}
                        percent={m.totals.memoryUtilizationPercent}
                        showTicks
                        showSeverity
                      />
                      <p className="faint">Estimated cache memory, not exact JVM heap. Meter colors escalate at 80%, 90% and 98%.</p>
                    </div>
                  </div>
                </Card>
              </div>

              <Card>
                <TimelinePanel
                  title="Traffic timeline (all regions)"
                  timeline={timeline.data}
                  loading={timeline.loading}
                  error={timeline.error}
                  refreshing={timeline.refreshing}
                  onRetry={() => void timeline.refresh()}
                  range={range}
                  onRangeChange={setRange}
                />
              </Card>

              <Card title="Cache regions" subtitle="Select a region for its full Eviction X-ray, charts and configuration.">
                <RegionTable regions={m.regions} singleApplication caption={`Cache regions of ${name}`} />
              </Card>

              {m.regions.length > 0 ? (
                <Card title="Request mix by region" subtitle="Share of hits vs misses per region.">
                  <StackedBars
                    ariaLabel="Hits and misses by region"
                    rows={m.regions.map((r) => ({
                      key: r.cacheRegion,
                      label: <Link to={paths.region(id, r.cacheRegion)}>{r.cacheRegion}</Link>,
                      totalLabel: formatPercent(r.hitRate, 1),
                      segments: [
                        { key: 'hits', label: 'Hits', value: r.hits, color: 'var(--series-hit)', valueLabel: formatInt(r.hits) },
                        { key: 'misses', label: 'Misses', value: r.misses, color: 'var(--series-miss)', valueLabel: formatInt(r.misses) },
                      ],
                    }))}
                  />
                  <ul className="legend legend--inline">
                    <li className="legend__item">
                      <span className="legend__key legend__key--swatch" style={{ background: 'var(--series-hit)' }} aria-hidden="true" />
                      <span className="legend__label">Hits</span>
                    </li>
                    <li className="legend__item">
                      <span className="legend__key legend__key--swatch" style={{ background: 'var(--series-miss)' }} aria-hidden="true" />
                      <span className="legend__label">Misses</span>
                    </li>
                    <li className="legend__item faint">Right-hand figure: hit rate</li>
                  </ul>
                </Card>
              ) : null}
            </>
          )}

          <div className="grid-2">
            <Card
              title="Recommendations"
              subtitle="Advisory only — an engineer must approve every policy change."
              actions={
                <Link to={paths.policyArena(id)} className="inline-link">
                  Policy Arena
                </Link>
              }
            >
              <RecommendationList recs={recs.data} loading={recs.loading} error={recs.error} onRetry={() => void recs.refresh()} />
            </Card>
            <Card title="Policy-change requests">
              <PolicyRequestsPanel
                requests={requests.data}
                loading={requests.loading}
                error={requests.error}
                onRetry={() => void requests.refresh()}
                onChanged={() => {
                  void requests.refresh();
                  void recs.refresh();
                  void metrics.refresh();
                }}
              />
            </Card>
          </div>

          {!noTelemetry ? (
            <Card title="Eviction X-ray (all regions)" subtitle="Newest first. Each row explains one cache decision.">
              <EvictionXray
                events={events.data}
                loading={events.loading}
                error={events.error}
                refreshing={events.refreshing}
                onRetry={() => void events.refresh()}
                selected={xray.selected}
                onToggleGroup={xray.toggle}
                onClearGroups={xray.clear}
                applicationId={id}
                showRegion
              />
            </Card>
          ) : null}

          <Card
            title="API keys"
            subtitle="Keys let an application's SDK send telemetry. The full key is shown once, at creation."
            actions={<Badge tone="serious" icon="shield">ADMIN</Badge>}
          >
            {isAdmin ? (
              <ApiKeysPanel applicationId={id} />
            ) : (
              <EmptyState title="API keys are managed by admins" icon="lock">
                Sign in with the ADMIN role to create or revoke keys for this application.
              </EmptyState>
            )}
          </Card>
        </div>
      )}
    </div>
  );
}

function RecommendationList({
  recs,
  loading,
  error,
  onRetry,
}: {
  recs: Recommendation[] | undefined;
  loading: boolean;
  error: Error | null;
  onRetry: () => void;
}) {
  if (error && !recs) return <ErrorState error={error} onRetry={onRetry} title="Could not load recommendations" />;
  if (loading || !recs) return <LoadingBlock label="Loading recommendations" lines={4} />;
  if (recs.length === 0) {
    return (
      <EmptyState title="No recommendations yet" icon="scale">
        Regions need shadow (LRU vs LFU) data before the Policy Arena can compare policies.
      </EmptyState>
    );
  }
  return (
    <div className="stack">
      {recs.map((r) => (
        <RecommendationCard key={r.id} rec={r} />
      ))}
    </div>
  );
}
