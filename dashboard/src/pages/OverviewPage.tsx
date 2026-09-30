import { useState } from 'react';
import { Link } from 'react-router-dom';
import { api } from '../api/endpoints';
import type { Overview } from '../api/types';
import { Sparkline } from '../charts/Sparkline';
import { AlertList } from '../components/AlertList';
import { ApplicationCard } from '../components/ApplicationCard';
import { Card } from '../components/Card';
import { HitMissDonut } from '../components/HealthPanel';
import { Icon } from '../components/Icon';
import { LiveIndicator } from '../components/LiveIndicator';
import { PageHeader } from '../components/PageHeader';
import { RecommendationCard } from '../components/RecommendationCard';
import { RegionTable } from '../components/RegionTable';
import { EmptyState, ErrorState, LoadingBlock, TileSkeletons } from '../components/States';
import { DEFAULT_TIMELINE_RANGE, TimelinePanel, type TimelineRange } from '../components/TimelinePanel';
import { Tile } from '../components/Tile';
import { useAuth } from '../context/AuthContext';
import { usePolling } from '../hooks/usePolling';
import { formatBytes, formatCompact, formatInt, formatPercent, formatRelativeTime } from '../lib/format';
import { hasRole } from '../lib/roles';
import { paths } from '../lib/routes';

function Kpis({ data, hitRateSeries }: { data: Overview; hitRateSeries: number[] }) {
  const t = data.totals;
  const alerts = data.activeAlerts;
  const worst = alerts.some((a) => a.severity === 'CRITICAL') ? 'critical' : alerts.length > 0 ? 'warning' : 'good';
  return (
    <div className="grid-kpi grid-kpi--4" aria-label="Key metrics">
      <Tile label="Projects monitored" value={formatInt(data.projectsMonitored)} />
      <Tile label="Applications monitored" value={formatInt(data.applicationsMonitored)} />
      <Tile label="Cache regions monitored" value={formatInt(data.cacheRegionsMonitored)} />
      <Tile label="Overall hit rate" value={formatPercent(t.hitRate)} sub={`${formatInt(t.hits)} hits`}>
        <Sparkline values={hitRateSeries} domain={[0, 100]} ariaLabel={`Hit rate over the last 15 minutes, now ${formatPercent(t.hitRate, 1)}`} width={140} />
      </Tile>
      <Tile label="Overall miss rate" value={formatPercent(t.missRate)} sub={`${formatInt(t.misses)} misses`} />
      <Tile
        label="Total estimated memory usage"
        value={formatBytes(t.estimatedMemoryUsageBytes)}
        sub={`of ${formatBytes(t.maximumMemoryBytes)} limit · ${formatPercent(t.memoryUtilizationPercent, 1)}`}
      />
      <Tile label="Total source calls avoided" value={formatCompact(t.sourceCallsAvoided)} sub={`${formatInt(t.sourceCallsAvoided)} calls served from cache`} />
      <Tile
        label="Active alerts"
        value={formatInt(alerts.length)}
        tone={worst}
        sub={alerts.length === 0 ? 'All regions within targets' : `${alerts.filter((a) => a.severity === 'CRITICAL').length} critical`}
      />
    </div>
  );
}

export function OverviewPage() {
  const { user } = useAuth();
  const [range, setRange] = useState<TimelineRange>(DEFAULT_TIMELINE_RANGE);
  const overview = usePolling(() => api.overview(), []);
  const timeline = usePolling(() => api.globalTimeline({ minutes: range.minutes, bucketSeconds: range.bucketSeconds }), [range.minutes], { keepPrevious: true });

  const data = overview.data;
  const noTelemetry = data !== undefined && data.cacheRegionsMonitored === 0 && data.regions.length === 0;
  const hitRateSeries = (timeline.data?.points ?? []).filter((p) => p.hits + p.misses > 0).map((p) => p.hitRate);

  return (
    <div className="page">
      <PageHeader
        title="Overview"
        subtitle="Cache health across every monitored Acentra service. Memory figures are estimates of cache size, never exact JVM heap."
        actions={<LiveIndicator updatedAt={overview.updatedAt} error={overview.error} paused={overview.paused} loading={overview.loading} />}
      />

      {overview.error && data ? (
        <p className="notice notice--warning" role="status">
          <Icon name="alert" size={16} />
          <span>
            Showing the last data received {formatRelativeTime(overview.updatedAt)} — the telemetry service is not responding. Retrying every few seconds.
          </span>
        </p>
      ) : null}

      {overview.error && !data ? (
        <ErrorState error={overview.error} onRetry={() => void overview.refresh()} title="Could not load the overview" />
      ) : overview.loading || !data ? (
        <div className="stack" aria-busy="true">
          <TileSkeletons />
          <Card title="Loading">
            <LoadingBlock label="Loading overview" lines={6} height={18} />
          </Card>
        </div>
      ) : (
        <div className="stack">
          <Kpis data={data} hitRateSeries={hitRateSeries} />

          {noTelemetry ? (
            <Card>
              <EmptyState
                title="No telemetry yet"
                icon="pulse"
                action={
                  <div className="row">
                    {hasRole(user?.role, 'ENGINEER') ? (
                      <Link to={paths.simulations()} className="btn btn--primary">
                        Run a simulation
                      </Link>
                    ) : null}
                    <Link to={paths.applications()} className="btn btn--secondary">
                      View applications
                    </Link>
                  </div>
                }
              >
                No telemetry yet — start a demo service or run a simulation. Once an SDK reports, health, hit rates and recommendations appear here automatically.
              </EmptyState>
            </Card>
          ) : null}

          <div className="grid-2">
            <Card title="Active alerts" subtitle="One alert per failing health reason of every Warning or Critical region.">
              <AlertList alerts={data.activeAlerts} />
            </Card>
            <Card
              title="Latest recommendations"
              subtitle="Advisory only — an engineer must approve every policy change."
              actions={
                <Link to={paths.recommendations()} className="inline-link">
                  View all <Icon name="arrow-right" size={13} />
                </Link>
              }
            >
              {data.latestRecommendations.length === 0 ? (
                <EmptyState title="No recommendations yet" icon="scale">
                  Recommendations appear once regions report shadow (LRU vs LFU) data.
                </EmptyState>
              ) : (
                <div className="stack">
                  {data.latestRecommendations.slice(0, 3).map((r) => (
                    <RecommendationCard key={r.id} rec={r} showApplication showArenaLink />
                  ))}
                </div>
              )}
            </Card>
          </div>

          <section aria-labelledby="apps-heading" className="stack-sm">
            <div className="section-head">
              <h2 id="apps-heading">Applications</h2>
              <Link to={paths.applications()} className="inline-link">
                All applications <Icon name="arrow-right" size={13} />
              </Link>
            </div>
            {data.applications.length === 0 ? (
              <Card>
                <EmptyState title="No applications are reporting yet" icon="layers">
                  Register an application under a project, create an API key and point the SDK at this service.
                </EmptyState>
              </Card>
            ) : (
              <div className="grid-cards">
                {data.applications.map((a) => (
                  <ApplicationCard key={a.applicationId} summary={a} />
                ))}
              </div>
            )}
          </section>

          <div className="grid-2-1">
            <Card>
              <TimelinePanel
                title="Hits and misses (all applications)"
                timeline={timeline.data}
                loading={timeline.loading}
                error={timeline.error}
                refreshing={timeline.refreshing}
                onRetry={() => void timeline.refresh()}
                range={range}
                onRangeChange={setRange}
              />
            </Card>
            <Card title="Hit vs miss" subtitle="Cumulative since the SDKs started">
              <HitMissDonut hits={data.totals.hits} misses={data.totals.misses} hitRate={data.totals.hitRate} />
            </Card>
          </div>

          <Card title="Cache regions" subtitle="Sort, filter and search every region across all applications.">
            <RegionTable regions={data.regions} caption="All cache regions" />
          </Card>
        </div>
      )}
    </div>
  );
}
