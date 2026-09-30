import { Database } from 'lucide-react';
import { useId, useState } from 'react';
import { useMetrics } from '../api/metricsContext';
import { hitRateRows, phaseMarkers, seriesOf } from '../api/streamReducer';
import { ChipCard, EmptyState, ErrorState, Skeleton } from '../components';
import { SERIES_STYLE } from '../theme/policy';
import { HitRateChart } from './overview/HitRateChart';
import { KpiTiles } from './overview/KpiTiles';
import { kpisFor } from './overview/kpis';
import { PageHeader } from './PageHeader';

const selectClass =
  'rounded-md border border-trace bg-surface-2 px-3 py-2 font-mono text-sm text-text hover:border-trace-glow';

/** Overview (SPEC 10.5): KPI tiles for a selected cache and hit rate over time for all caches. */
export function OverviewPage() {
  const { state, history } = useMetrics();
  const [selected, setSelected] = useState<string | null>(null);
  const selectId = useId();
  const caches = history.latest?.caches ?? [];
  const current = caches.find((c) => c.name === selected) ?? caches[0];
  const kpis = current ? kpisFor(history, current.name) : null;
  const waiting = history.latest === null;

  return (
    <>
      <PageHeader
        title="Overview"
        description="Live health of every cache: how often lookups are served from memory instead of the database."
      >
        {caches.length > 0 && (
          <div className="flex items-center gap-2">
            <label htmlFor={selectId} className="text-sm text-muted">
              Cache
            </label>
            <select
              id={selectId}
              className={selectClass}
              value={current?.name ?? ''}
              onChange={(e) => setSelected(e.target.value)}
            >
              {caches.map((c) => (
                <option key={c.name} value={c.name}>
                  {c.name} · {SERIES_STYLE[c.policy].label}
                </option>
              ))}
            </select>
          </div>
        )}
      </PageHeader>

      <div className="flex flex-col gap-6">
        <ChipCard label="U0 · DATA BUS" title="Data bus">
          <p className="text-base text-muted">
            The live CLIENT → CACHE → DB electron view arrives in Step 4.
          </p>
        </ChipCard>

        <ChipCard
          label={`U1 · KPIS${current ? ` · ${current.name.toUpperCase()}` : ''}`}
          title="Key numbers"
          info="Headline numbers for the cache selected above, refreshed twice per second."
        >
          {waiting ? (
            state === 'offline' ? (
              <ErrorState message="Cannot reach the metrics stream. Start the server with ./gradlew :cache-server:bootRun — the panel reconnects on its own." />
            ) : (
              <Skeleton lines={2} label="Waiting for the first metrics" />
            )
          ) : kpis ? (
            <KpiTiles kpis={kpis} />
          ) : (
            <EmptyState
              icon={<Database aria-hidden="true" className="size-6" />}
              title="No caches yet"
              message="Create one in the Playground, or start a workload in the Policy Race."
            />
          )}
        </ChipCard>

        <ChipCard
          label="U2 · HIT RATE · 10 S WINDOW"
          title="Hit rate over time"
          info="Each line is one cache's hit rate over a sliding 10-second window; higher means fewer database calls. Dashed vertical lines mark workload phase changes."
        >
          {waiting ? (
            state === 'offline' ? (
              <ErrorState message="No data: the metrics stream is offline." />
            ) : (
              <Skeleton lines={6} label="Waiting for the first metrics" />
            )
          ) : caches.length === 0 ? (
            <EmptyState
              title="Nothing to chart yet"
              message="Hit rates appear once a cache exists."
            />
          ) : (
            <HitRateChart
              rows={hitRateRows(history)}
              series={seriesOf(history)}
              markers={phaseMarkers(history)}
            />
          )}
        </ChipCard>

        <div className="grid gap-6 lg:grid-cols-2">
          <ChipCard label="U3 · REMOVALS BY CAUSE" title="Removals by cause">
            <p className="text-base text-muted">
              Evictions per second vs expirations per second for each cache arrive in Step 2.
            </p>
          </ChipCard>
          <ChipCard label="U4 · COST" title="Savings (estimates)">
            <p className="text-base text-muted">
              Database calls avoided, latency saved and estimated cost saved arrive in Step 3.
            </p>
          </ChipCard>
        </div>
      </div>
    </>
  );
}
