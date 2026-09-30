import { useState } from 'react';
import { Link, useSearchParams } from 'react-router-dom';
import { api } from '../api/endpoints';
import { describeError } from '../api/client';
import type { Application, SimulationComparisonRow, SimulationKind, SimulationResult } from '../api/types';
import { SIMULATION_REQUEST_BOUNDS } from '../api/types';
import { ChartFrame, type LegendItem } from '../charts/ChartFrame';
import { HBars, type BarGroup } from '../charts/HBars';
import { ApplicationSelect } from '../components/ApplicationSelect';
import { Badge } from '../components/Badge';
import { Button } from '../components/Button';
import { Card } from '../components/Card';
import { Field } from '../components/Field';
import { Icon } from '../components/Icon';
import { PageHeader } from '../components/PageHeader';
import { EmptyState, ErrorState, LoadingBlock } from '../components/States';
import { Table, type Column } from '../components/Table';
import { Tile } from '../components/Tile';
import { useAuth } from '../context/AuthContext';
import { useToast } from '../context/ToastContext';
import { useFetch } from '../hooks/usePolling';
import { formatInt, formatPercent } from '../lib/format';
import { hasRole } from '../lib/roles';
import { paths } from '../lib/routes';

interface SimCard {
  kind: SimulationKind;
  title: string;
  description: string;
  produces: string;
  patterns: string;
  defaultRequests: number;
}

export const SIMULATION_CARDS: readonly SimCard[] = [
  {
    kind: 'sample-workload',
    title: 'Sample workload',
    description: 'A realistic mixed read workload: a hot set of popular keys plus cold one-off keys. The quickest way to see hit rates, puts and steady-state evictions.',
    produces: 'sim-sample-workload',
    patterns: 'Mixed',
    defaultRequests: 1000,
  },
  {
    kind: 'high-load',
    title: 'High load',
    description: 'Floods a small cache with unique keys, then with large entries, so both the entry limit (pattern D) and the estimated memory limit (pattern E) force evictions.',
    produces: 'sim-high-load',
    patterns: 'D + E',
    defaultRequests: 5000,
  },
  {
    kind: 'ttl-expiration',
    title: 'TTL expiration',
    description: 'Entries with a short TTL are revisited after they expire, producing MISS and EXPIRED events in the Eviction X-ray (pattern C).',
    produces: 'sim-ttl-expiration',
    patterns: 'C',
    defaultRequests: 400,
  },
  {
    kind: 'policy-comparison',
    title: 'Policy comparison',
    description: 'Runs the same key streams through real LRU and LFU caches: a moving working set (pattern A) and stable popularity with scans (pattern B). Shows who wins and why.',
    produces: 'sim-policy-lru, sim-policy-lfu',
    patterns: 'A + B',
    defaultRequests: 2000,
  },
];

const PATTERNS: ReadonlyArray<{ id: string; name: string; sequence?: string; text: string }> = [
  { id: 'A', name: 'LRU-friendly changing access', sequence: 'A,B,C,A,D,E,D,E,F,G', text: 'The working set keeps moving, so recently used keys matter most. LRU keeps the moving set; LFU clings to keys that were popular earlier.' },
  { id: 'B', name: 'LFU-friendly stable popularity', sequence: 'A,A,A,A,B,B,B,C,D,E,A,B', text: 'A few keys stay popular while one-off keys pass through. LFU protects the popular keys from scans; LRU lets the scan push them out.' },
  { id: 'C', name: 'TTL expiry', text: 'Entries are revisited after their time-to-live has passed: each shows up as an EXPIRED event followed by a MISS and a reload.' },
  { id: 'D', name: 'Capacity eviction', text: 'More distinct keys than the entry limit allows, so the eviction policy must pick victims: ENTRY_LIMIT_EVICTED events.' },
  { id: 'E', name: 'Memory eviction', text: 'Large entries exhaust the estimated memory limit before the entry limit: MEMORY_EVICTED events.' },
];

/** Returns an error message, or null when the optional request count is valid (empty = use the default). */
export function validateRequestCount(text: string): string | null {
  const t = text.trim();
  if (t === '') return null;
  const n = Number(t);
  const { min, max } = SIMULATION_REQUEST_BOUNDS;
  if (!Number.isInteger(n) || n < min || n > max) return `Enter a whole number from ${formatInt(min)} to ${formatInt(max)}, or leave empty for the default.`;
  return null;
}

function ComparisonChart({ rows }: { rows: SimulationComparisonRow[] }) {
  const groups: BarGroup[] = rows.map((r) => ({
    label: `${r.pattern} — winner: ${r.winner}`,
    rows: [
      { key: `${r.pattern}-lru`, label: 'LRU', value: r.lruHitRate, color: 'var(--series-lru)', valueLabel: formatPercent(r.lruHitRate, 1) },
      { key: `${r.pattern}-lfu`, label: 'LFU', value: r.lfuHitRate, color: 'var(--series-lfu)', valueLabel: formatPercent(r.lfuHitRate, 1) },
    ],
  }));
  const legend: LegendItem[] = [
    { label: 'LRU', color: 'var(--series-lru)' },
    { label: 'LFU', color: 'var(--series-lfu)' },
  ];
  return (
    <ChartFrame
      title="LRU vs LFU hit rate by pattern"
      legend={legend}
      summary={rows.map((r) => `${r.pattern}: LRU ${formatPercent(r.lruHitRate, 1)}, LFU ${formatPercent(r.lfuHitRate, 1)}, winner ${r.winner}`).join('. ')}
      table={{
        headers: ['Pattern', 'LRU hit rate', 'LFU hit rate', 'Winner'],
        rows: rows.map((r) => [r.pattern, formatPercent(r.lruHitRate, 1), formatPercent(r.lfuHitRate, 1), r.winner]),
      }}
    >
      <HBars groups={groups} percent ariaLabel="Simulated hit rate per pattern for LRU and LFU" />
    </ChartFrame>
  );
}

export function SimulationResultPanel({ result, applicationId }: { result: SimulationResult; applicationId: number }) {
  const cols: Column<SimulationComparisonRow>[] = [
    { key: 'pattern', header: 'Pattern', cell: (r) => r.pattern },
    { key: 'lru', header: 'LRU', align: 'right', className: 'num', cell: (r) => formatPercent(r.lruHitRate, 1) },
    { key: 'lfu', header: 'LFU', align: 'right', className: 'num', cell: (r) => formatPercent(r.lfuHitRate, 1) },
    { key: 'winner', header: 'Winner', cell: (r) => <Badge tone={r.winner === 'TIE' ? 'neutral' : 'good'} icon="check">{r.winner}</Badge> },
    { key: 'why', header: 'Interpretation', cell: (r) => r.interpretation },
  ];
  return (
    <div className="stack" aria-live="polite">
      <div className="notice notice--good" role="status">
        <Icon name="check" size={16} />
        <span>
          <strong>{result.summary}</strong>
        </span>
      </div>
      <div className="grid-kpi">
        <Tile label="Hits" value={formatInt(result.hits)} />
        <Tile label="Misses" value={formatInt(result.misses)} />
        <Tile label="Hit rate" value={formatPercent(result.hitRate)} />
        <Tile label="Evictions" value={formatInt(result.evictions)} />
        <Tile label="Expirations" value={formatInt(result.expirations)} />
        <Tile label="Duration" value={`${formatInt(result.durationMs)} ms`} sub={`run ${result.simulationId}`} />
      </div>
      <div>
        <h3>Steps</h3>
        <ol className="sim-steps">
          {result.steps.map((s) => (
            <li key={s.name}>
              <strong>{s.name}</strong> — {s.description}
              <span className="sim-steps__detail faint">{s.detail}</span>
            </li>
          ))}
        </ol>
      </div>
      {result.comparison && result.comparison.length > 0 ? (
        <div className="stack">
          <ComparisonChart rows={result.comparison} />
          <Table columns={cols} rows={result.comparison} rowKey={(r) => r.pattern} caption="Policy comparison results" />
        </div>
      ) : null}
      <div>
        <h3>Generated regions</h3>
        <ul className="region-links">
          {result.cacheRegions.map((r) => (
            <li key={r}>
              <Link to={paths.region(applicationId, r)} className="btn btn--secondary btn--sm">
                {r} <Icon name="arrow-right" size={13} />
              </Link>
            </li>
          ))}
        </ul>
        <p className="faint">Simulations use synthetic keys only. Open a region to see its Eviction X-ray events and charts.</p>
      </div>
    </div>
  );
}

function pickApp(apps: Application[], requested: number | null): number | null {
  if (requested !== null && apps.some((a) => a.id === requested)) return requested;
  return apps[0]?.id ?? null;
}

export function SimulationsPage() {
  const { user } = useAuth();
  const toast = useToast();
  const [params, setParams] = useSearchParams();
  const apps = useFetch(() => api.listApplications(), []);
  const requestedParam = params.get('app');
  const requested = requestedParam !== null && Number.isInteger(Number(requestedParam)) ? Number(requestedParam) : null;
  const appId = apps.data ? pickApp(apps.data, requested) : null;
  const [requestsText, setRequestsText] = useState('');
  const [running, setRunning] = useState<SimulationKind | null>(null);
  const [result, setResult] = useState<{ result: SimulationResult; applicationId: number } | null>(null);
  const canRun = hasRole(user?.role, 'ENGINEER');
  const requestsError = validateRequestCount(requestsText);

  const run = async (kind: SimulationKind) => {
    if (appId === null || requestsError) return;
    setRunning(kind);
    try {
      const n = requestsText.trim() === '' ? undefined : Number(requestsText);
      const res = await api.simulate(appId, kind, n);
      setResult({ result: res, applicationId: appId });
      toast.success(`Simulation finished: ${res.cacheRegions.join(', ')}.`);
    } catch (err) {
      toast.error(describeError(err));
    } finally {
      setRunning(null);
    }
  };

  return (
    <div className="page">
      <PageHeader
        title="Simulations"
        subtitle="Generate synthetic, privacy-safe cache traffic for an application to see hit rates, evictions, expirations and the LRU vs LFU trade-off."
      />

      {!canRun ? (
        <p className="notice notice--info" role="note">
          <Icon name="lock" size={16} />
          <span>Running simulations requires the ENGINEER role. You can read how each simulation works below.</span>
        </p>
      ) : null}

      {apps.error && !apps.data ? (
        <ErrorState error={apps.error} onRetry={() => void apps.refresh()} title="Could not load applications" />
      ) : apps.loading || !apps.data ? (
        <LoadingBlock label="Loading applications" lines={3} />
      ) : apps.data.length === 0 ? (
        <EmptyState title="No applications yet" icon="layers">
          An admin needs to register an application before simulations can run against it.
        </EmptyState>
      ) : (
        <div className="toolbar" role="group" aria-label="Simulation settings">
          <ApplicationSelect applications={apps.data} value={appId} onChange={(id) => setParams(id === null ? {} : { app: String(id) }, { replace: true })} />
          <Field
            label="Requests (optional)"
            hint={`${formatInt(SIMULATION_REQUEST_BOUNDS.min)}–${formatInt(SIMULATION_REQUEST_BOUNDS.max)}; empty uses each simulation's default.`}
            error={requestsError}
          >
            {(p) => <input {...p} className="input" inputMode="numeric" value={requestsText} onChange={(e) => setRequestsText(e.target.value)} placeholder="default" />}
          </Field>
        </div>
      )}

      <div className="grid-cards grid-cards--wide">
        {SIMULATION_CARDS.map((c) => (
          <article key={c.kind} className="sim-card">
            <header className="sim-card__head">
              <h2 className="sim-card__title">{c.title}</h2>
              <Badge tone="info">Pattern {c.patterns}</Badge>
            </header>
            <p>{c.description}</p>
            <p className="faint">
              Creates region <code>{c.produces}</code> · default {formatInt(c.defaultRequests)} requests
            </p>
            {canRun ? (
              <Button
                variant="primary"
                icon="play"
                onClick={() => void run(c.kind)}
                loading={running === c.kind}
                disabled={running !== null || appId === null || requestsError !== null}
                aria-label={`Run ${c.title} simulation`}
              >
                Run
              </Button>
            ) : null}
          </article>
        ))}
      </div>

      {running ? (
        <p className="notice notice--info" role="status">
          <span className="spinner" aria-hidden="true" /> Running {running}…
        </p>
      ) : null}

      {result ? (
        <Card title={`Result: ${result.result.kind}`} subtitle={`Application ${apps.data?.find((a) => a.id === result.applicationId)?.name ?? result.applicationId}`}>
          <SimulationResultPanel result={result.result} applicationId={result.applicationId} />
        </Card>
      ) : null}

      <Card title="What the patterns mean" subtitle="The access patterns behind the simulations.">
        <ul className="patterns">
          {PATTERNS.map((p) => (
            <li key={p.id} className="pattern">
              <span className="pattern__id" aria-hidden="true">
                {p.id}
              </span>
              <div>
                <h3>
                  Pattern {p.id} — {p.name}
                </h3>
                {p.sequence ? (
                  <p>
                    Example sequence: <code>{p.sequence}</code>
                  </p>
                ) : null}
                <p className="muted">{p.text}</p>
              </div>
            </li>
          ))}
        </ul>
      </Card>
    </div>
  );
}
