import { useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import { api } from '../api/endpoints';
import { Meter } from '../charts/Meter';
import { HealthBadge, PolicyBadge, RiskBadge } from '../components/Badge';
import { Card } from '../components/Card';
import { RegionConfigPanel } from '../components/ConfigPanels';
import { EvictionXray } from '../components/EvictionXray';
import { HealthPanel, HitMissDonut } from '../components/HealthPanel';
import { LiveIndicator } from '../components/LiveIndicator';
import { isNotFound, NotFoundNotice } from '../components/NotFoundNotice';
import { PageHeader } from '../components/PageHeader';
import { RecommendationCard } from '../components/RecommendationCard';
import {
  EvictionReasonBars,
  ExpirationPanel,
  PolicyComparisonBars,
  RegionMetricTiles,
  SafetyNote,
  StampedePanel,
  VictimPanel,
} from '../components/RegionPanels';
import { EmptyState, ErrorState, LoadingBlock, TileSkeletons } from '../components/States';
import { DEFAULT_TIMELINE_RANGE, TimelinePanel, type TimelineRange } from '../components/TimelinePanel';
import { useAuth } from '../context/AuthContext';
import { useGroupSelection } from '../hooks/useGroupSelection';
import { usePolling } from '../hooks/usePolling';
import { formatBytes, formatInt, formatPercent } from '../lib/format';
import { MEMORY_THRESHOLDS } from '../lib/health';
import { hasRole } from '../lib/roles';
import { paths } from '../lib/routes';

export function RegionDetailPage() {
  const { appId, region } = useParams();
  const id = Number(appId);
  if (!Number.isInteger(id) || id <= 0 || !region) {
    return <NotFoundNotice what="Cache region" backTo={paths.regions()} backLabel="All regions" />;
  }
  return <RegionDetail key={`${id}/${region}`} appId={id} region={region} />;
}

function RegionDetail({ appId, region }: { appId: number; region: string }) {
  const { user } = useAuth();
  const [range, setRange] = useState<TimelineRange>(DEFAULT_TIMELINE_RANGE);
  const xray = useGroupSelection();

  const metrics = usePolling(() => api.regionMetrics(appId, region), [appId, region]);
  const timeline = usePolling(() => api.regionTimeline(appId, region, { minutes: range.minutes, bucketSeconds: range.bucketSeconds }), [appId, region, range.minutes], {
    keepPrevious: true,
  });
  const events = usePolling(() => api.regionEvents(appId, region, { limit: 100, actions: xray.actions }), [appId, region, xray.actionsKey], { keepPrevious: true });
  const config = usePolling(() => api.getRegionConfig(appId, region), [appId, region]);
  const recs = usePolling(() => api.listRecommendations(appId), [appId]);

  if (isNotFound(metrics.error)) {
    return <NotFoundNotice what="Cache region" backTo={paths.application(appId)} backLabel="Back to application" />;
  }

  const m = metrics.data;
  const rec = recs.data?.find((r) => r.cacheRegion === region);
  const isAdmin = hasRole(user?.role, 'ADMIN');

  return (
    <div className="page">
      <PageHeader
        crumbs={[
          { label: 'Applications', to: paths.applications() },
          { label: m?.applicationName ?? 'Application', to: paths.application(appId) },
          { label: region },
        ]}
        title={<span className="mono">{region}</span>}
        subtitle={m ? `${m.applicationName} · Environment: ${m.environment} · ${m.instanceCount} SDK instance${m.instanceCount === 1 ? '' : 's'}` : undefined}
        badges={
          m ? (
            <>
              <RiskBadge level={m.riskLevel} />
              <PolicyBadge policy={m.activePolicy} />
              <HealthBadge status={m.health.status} />
            </>
          ) : undefined
        }
        actions={
          <>
            <LiveIndicator updatedAt={metrics.updatedAt} error={metrics.error} paused={metrics.paused} loading={metrics.loading} />
            <Link to={paths.policyArena(appId)} className="btn btn--secondary btn--sm">
              Policy Arena
            </Link>
          </>
        }
      />

      {metrics.error && !m ? (
        <ErrorState error={metrics.error} onRetry={() => void metrics.refresh()} title="Could not load this region" />
      ) : !m ? (
        <div className="stack" aria-busy="true">
          <TileSkeletons count={8} />
          <LoadingBlock label="Loading region" lines={6} height={18} />
        </div>
      ) : (
        <div className="stack">
          <SafetyNote risk={m.riskLevel} />

          <Card title="Health" subtitle="The exact reasons the telemetry service reported for this region's status.">
            <HealthPanel health={m.health} />
          </Card>

          <RegionMetricTiles m={m} />

          <div className="grid-2">
            <Card title="Hit vs miss" subtitle="Cumulative since the SDK instance started">
              <HitMissDonut hits={m.hits} misses={m.misses} hitRate={m.hitRate} />
            </Card>
            <Card title="Memory utilization" subtitle="Estimated cache memory against its configured limit. Colors change at 80%, 90% and 98%.">
              <div className="stack">
                <Meter
                  percent={m.memoryUtilizationPercent}
                  label="Estimated memory utilization"
                  valueText={`${formatBytes(m.estimatedMemoryUsageBytes)} / ${formatBytes(m.maximumMemoryBytes)} (${formatPercent(m.memoryUtilizationPercent, 1)})`}
                  showTicks
                  showSeverity
                />
                <ul className="thresholds" aria-label="Utilization thresholds">
                  <li>
                    <span className="swatch swatch--warning" aria-hidden="true" />≥ {MEMORY_THRESHOLDS.warning}% getting full
                  </li>
                  <li>
                    <span className="swatch swatch--serious" aria-hidden="true" />≥ {MEMORY_THRESHOLDS.serious}% high
                  </li>
                  <li>
                    <span className="swatch swatch--critical" aria-hidden="true" />≥ {MEMORY_THRESHOLDS.critical}% critical
                  </li>
                </ul>
                <p className="faint">
                  {formatInt(m.size)} of {formatInt(m.capacity)} entries in use. Memory is an estimate of entry sizes, not exact JVM heap.
                </p>
              </div>
            </Card>
          </div>

          <Card>
            <TimelinePanel
              title="Hits and misses over time"
              timeline={timeline.data}
              loading={timeline.loading}
              error={timeline.error}
              refreshing={timeline.refreshing}
              onRetry={() => void timeline.refresh()}
              range={range}
              onRangeChange={setRange}
              emptyText="No telemetry for this region in the selected window yet."
            />
          </Card>

          <div className="grid-2">
            <Card title="LRU vs LFU comparison" subtitle="Policy Arena: simulated hit rates next to the live rate.">
              <PolicyComparisonBars m={m} />
            </Card>
            <Card title="Why entries left the cache" subtitle="Evictions by limit and policy, plus TTL expirations.">
              <EvictionReasonBars m={m} />
            </Card>
          </div>

          <div className="grid-2">
            <Card title="Expirations" subtitle="TTL-based removals, total and recent window.">
              <ExpirationPanel m={m} />
            </Card>
            <Card title="Recommendation" subtitle="Advisory only — an engineer must approve every policy change.">
              {recs.error && !recs.data ? (
                <ErrorState error={recs.error} onRetry={() => void recs.refresh()} title="Could not load the recommendation" />
              ) : recs.loading || !recs.data ? (
                <LoadingBlock label="Loading recommendation" lines={4} />
              ) : rec ? (
                <RecommendationCard rec={rec} showArenaLink />
              ) : (
                <EmptyState title="No recommendation yet" icon="scale">
                  This region has not reported shadow (LRU vs LFU) data yet.
                </EmptyState>
              )}
            </Card>
          </div>

          <div className="grid-2">
            {m.victimEnabled ? (
              <Card title="Victim cache" subtitle="Second-chance layer that rescues recently evicted entries.">
                <VictimPanel m={m} />
              </Card>
            ) : null}
            <Card title="Stampede shield" subtitle="Coalesces concurrent refreshes of an expired hot entry into one source call.">
              <StampedePanel m={m} />
            </Card>
          </div>

          <Card title="Eviction X-ray" subtitle="Newest first. Each row explains one cache decision.">
            <EvictionXray
              events={events.data}
              loading={events.loading}
              error={events.error}
              refreshing={events.refreshing}
              onRetry={() => void events.refresh()}
              selected={xray.selected}
              onToggleGroup={xray.toggle}
              onClearGroups={xray.clear}
            />
          </Card>

          <Card title="Configuration" subtitle="Reported by the SDK versus the desired values set by an admin.">
            {config.error && !config.data ? (
              <ErrorState error={config.error} onRetry={() => void config.refresh()} title="Could not load the configuration" />
            ) : config.loading || !config.data ? (
              <LoadingBlock label="Loading configuration" lines={4} />
            ) : (
              <RegionConfigPanel config={config.data} applicationId={appId} canEdit={isAdmin} />
            )}
          </Card>
        </div>
      )}
    </div>
  );
}
