import { useMemo, useState } from 'react';
import { api } from '../api/endpoints';
import type { RecommendationAction } from '../api/types';
import { Field } from '../components/Field';
import { LiveIndicator } from '../components/LiveIndicator';
import { PageHeader } from '../components/PageHeader';
import { RecommendationCard } from '../components/RecommendationCard';
import { EmptyState, ErrorState, LoadingBlock } from '../components/States';
import { usePolling } from '../hooks/usePolling';
import { plural } from '../lib/format';

type Filter = 'ALL' | RecommendationAction;

const FILTERS: ReadonlyArray<{ key: Filter; label: string }> = [
  { key: 'ALL', label: 'All' },
  { key: 'SWITCH', label: 'Switch suggested' },
  { key: 'KEEP', label: 'Keep' },
];

/** Every recommendation across all applications (GET /applications, then per-application recommendations). */
export function RecommendationsPage() {
  const [filter, setFilter] = useState<Filter>('ALL');
  const [query, setQuery] = useState('');
  const data = usePolling(async () => {
    const apps = await api.listApplications();
    const lists = await Promise.all(apps.map((a) => api.listRecommendations(a.id)));
    return lists.flat();
  }, []);

  const visible = useMemo(() => {
    const q = query.trim().toLowerCase();
    return (data.data ?? [])
      .filter((r) => (filter === 'ALL' || r.action === filter) && (!q || r.cacheRegion.toLowerCase().includes(q) || r.applicationName.toLowerCase().includes(q)))
      .sort((a, b) => (a.action === b.action ? b.improvementPercent - a.improvementPercent : a.action === 'SWITCH' ? -1 : 1));
  }, [data.data, filter, query]);

  let body;
  if (data.error && !data.data) body = <ErrorState error={data.error} onRetry={() => void data.refresh()} title="Could not load recommendations" />;
  else if (data.loading || !data.data) body = <LoadingBlock label="Loading recommendations" lines={6} height={20} />;
  else if (data.data.length === 0) {
    body = (
      <EmptyState title="No recommendations yet" icon="scale">
        No telemetry yet — start a demo service or run a simulation. Recommendations appear once regions report LRU vs LFU shadow data.
      </EmptyState>
    );
  } else if (visible.length === 0) {
    body = (
      <EmptyState title="No recommendations match" icon="search">
        Try another filter or search term.
      </EmptyState>
    );
  } else {
    body = (
      <div className="grid-cards">
        {visible.map((r) => (
          <RecommendationCard key={`${r.applicationId}/${r.cacheRegion}`} rec={r} showApplication showArenaLink />
        ))}
      </div>
    );
  }

  return (
    <div className="page">
      <PageHeader
        title="Recommendations"
        subtitle="Latest policy recommendation for every cache region. Advisory only — an engineer must approve every change."
        actions={<LiveIndicator updatedAt={data.updatedAt} error={data.error} paused={data.paused} loading={data.loading} />}
      />
      <div className="toolbar" role="group" aria-label="Recommendation filters">
        <div className="field">
          <span className="field__label" id="rec-filter-label">
            Action
          </span>
          <div className="segmented" role="group" aria-labelledby="rec-filter-label">
            {FILTERS.map((f) => (
              <button key={f.key} type="button" className={`segmented__btn${filter === f.key ? ' is-active' : ''}`} aria-pressed={filter === f.key} onClick={() => setFilter(f.key)}>
                {f.label}
              </button>
            ))}
          </div>
        </div>
        <Field label="Search application or region">
          {(p) => <input {...p} className="input" type="search" value={query} onChange={(e) => setQuery(e.target.value)} placeholder="e.g. member-eligibility" />}
        </Field>
        <p className="toolbar__count muted" aria-live="polite">
          {data.data ? `Showing ${plural(visible.length, 'recommendation')}` : ''}
        </p>
      </div>
      {body}
    </div>
  );
}
