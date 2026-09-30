import { useMemo, useState } from 'react';
import { Link } from 'react-router-dom';
import type { CacheRiskLevel, RegionMetrics } from '../api/types';
import { RISK_LEVELS } from '../api/types';
import { Meter } from '../charts/Meter';
import { useNow } from '../hooks/useNow';
import {
  formatBytes,
  formatInt,
  formatPercent,
  formatRelativeTime,
} from '../lib/format';
import { paths } from '../lib/routes';
import { filterRegions, REGION_SORT_OPTIONS, sortRegions, type RegionSortKey } from '../lib/sort';
import { HealthBadge, PolicyBadge, RiskBadge } from './Badge';
import { Button } from './Button';
import { Field } from './Field';
import { Table, type Column, type SortState } from './Table';
import { EmptyState, ErrorState, LoadingBlock } from './States';

interface RegionTableProps {
  regions: RegionMetrics[] | undefined;
  loading?: boolean;
  error?: Error | null;
  onRetry?: () => void;
  /** Accessible table name. */
  caption?: string;
  /** Hide the application column + filter (application detail page). */
  singleApplication?: boolean;
  defaultSort?: RegionSortKey;
  emptyHint?: string;
}

/** Maps a column's sort key to the two sort modes it can toggle between. */
const COLUMN_SORTS: Record<string, { desc: RegionSortKey; asc?: RegionSortKey }> = {
  risk: { desc: 'risk-desc' },
  hit: { desc: 'hit-desc', asc: 'hit-asc' },
  memory: { desc: 'memory-desc' },
  evictions: { desc: 'evictions-desc' },
  expirations: { desc: 'expirations-desc' },
  avoided: { desc: 'avoided-desc' },
  recent: { desc: 'recent-desc' },
};

function sortStateOf(key: RegionSortKey): SortState {
  for (const [col, modes] of Object.entries(COLUMN_SORTS)) {
    if (modes.desc === key) return { key: col, direction: 'desc' };
    if (modes.asc === key) return { key: col, direction: 'asc' };
  }
  return { key: '', direction: 'desc' };
}

/**
 * The region table used on Overview, Application detail and the Regions page:
 * sortable (labelled select + keyboard-operable headers), filterable by application and risk, searchable by name.
 */
export function RegionTable({
  regions,
  loading,
  error,
  onRetry,
  caption = 'Cache regions',
  singleApplication = false,
  defaultSort = 'risk-desc',
  emptyHint = 'No telemetry yet — start a demo service or run a simulation.',
}: RegionTableProps) {
  const now = useNow(1000);
  const [sortKey, setSortKey] = useState<RegionSortKey>(defaultSort);
  const [applicationId, setApplicationId] = useState<number | null>(null);
  const [risk, setRisk] = useState<CacheRiskLevel | ''>('');
  const [query, setQuery] = useState('');

  const applications = useMemo(() => {
    const map = new Map<number, string>();
    for (const r of regions ?? []) map.set(r.applicationId, `${r.applicationName} (${r.environment})`);
    return [...map.entries()].sort((a, b) => a[1].localeCompare(b[1]));
  }, [regions]);

  const visible = useMemo(
    () => sortRegions(filterRegions(regions ?? [], { applicationId, riskLevel: risk, query }), sortKey),
    [regions, applicationId, risk, query, sortKey],
  );

  const onSort = (col: string) => {
    const modes = COLUMN_SORTS[col];
    if (!modes) return;
    if (modes.asc && sortKey === modes.desc) setSortKey(modes.asc);
    else setSortKey(modes.desc);
  };

  const columns: Column<RegionMetrics>[] = [
    {
      key: 'region',
      header: 'Region name',
      cell: (r) => (
        <span className="region-name">
          <Link to={paths.region(r.applicationId, r.cacheRegion)}>{r.cacheRegion}</Link>
          {r.cacheRegion.startsWith('sim-') ? <span className="tag">simulation</span> : null}
        </span>
      ),
    },
    ...(singleApplication
      ? []
      : [
          {
            key: 'app',
            header: 'Application',
            className: 'nowrap',
            cell: (r: RegionMetrics) => (
              <Link to={paths.application(r.applicationId)}>
                {r.applicationName}
                <span className="faint"> · {r.environment}</span>
              </Link>
            ),
          } satisfies Column<RegionMetrics>,
        ]),
    { key: 'risk', header: 'Risk level', sortKey: 'risk', cell: (r) => <RiskBadge level={r.riskLevel} /> },
    { key: 'policy', header: 'Active policy', cell: (r) => <PolicyBadge policy={r.activePolicy} /> },
    { key: 'hit', header: 'Hit rate', sortKey: 'hit', align: 'right', className: 'num', cell: (r) => formatPercent(r.hitRate) },
    { key: 'miss', header: 'Miss rate', align: 'right', className: 'num', cell: (r) => formatPercent(r.missRate) },
    {
      key: 'entries',
      header: 'Current entries',
      align: 'right',
      className: 'num nowrap',
      cell: (r) => (
        <>
          {formatInt(r.size)}
          <span className="faint"> / {formatInt(r.capacity)}</span>
        </>
      ),
    },
    {
      key: 'memory',
      header: (
        <>
          Estimated memory
        </>
      ),
      sortKey: 'memory',
      align: 'right',
      className: 'num',
      cell: (r) => formatBytes(r.estimatedMemoryUsageBytes),
    },
    {
      key: 'util',
      header: 'Memory utilization',
      className: 'col-meter',
      cell: (r) => <Meter size="sm" percent={r.memoryUtilizationPercent} label={`Memory utilization of ${r.cacheRegion}`} />,
    },
    { key: 'evictions', header: 'Evictions', sortKey: 'evictions', align: 'right', className: 'num', cell: (r) => formatInt(r.evictions) },
    { key: 'expirations', header: 'Expirations', sortKey: 'expirations', align: 'right', className: 'num', cell: (r) => formatInt(r.expirations) },
    { key: 'avoided', header: 'Source calls avoided', sortKey: 'avoided', align: 'right', className: 'num', cell: (r) => formatInt(r.sourceCallsAvoided) },
    { key: 'health', header: 'Health status', cell: (r) => <HealthBadge status={r.health.status} /> },
    {
      key: 'recent',
      header: 'Last updated',
      sortKey: 'recent',
      className: 'nowrap',
      cell: (r) => <time dateTime={r.lastUpdated}>{formatRelativeTime(r.lastUpdated, now)}</time>,
    },
  ];

  const filtersActive = applicationId !== null || risk !== '' || query.trim() !== '';
  const clear = () => {
    setApplicationId(null);
    setRisk('');
    setQuery('');
  };

  let body;
  if (error && !regions) {
    body = <ErrorState error={error} onRetry={onRetry} title="Could not load cache regions" />;
  } else if (loading || !regions) {
    body = <LoadingBlock label="Loading cache regions" lines={5} height={22} />;
  } else if (regions.length === 0) {
    body = <EmptyState title="No cache regions reported yet">{emptyHint}</EmptyState>;
  } else {
    body = (
      <Table
        columns={columns}
        rows={visible}
        rowKey={(r) => `${r.applicationId}/${r.cacheRegion}`}
        caption={caption}
        sort={sortStateOf(sortKey)}
        onSort={onSort}
        empty={
          <EmptyState title="No regions match these filters" icon="search">
            Try a different application, risk level or search term.
            <span className="empty__inline-action">
              <Button size="sm" onClick={clear}>
                Clear filters
              </Button>
            </span>
          </EmptyState>
        }
      />
    );
  }

  return (
    <div className="region-table">
      <div className="toolbar" role="group" aria-label="Region table controls">
        <Field label="Sort by">
          {(p) => (
            <select {...p} className="select" value={sortKey} onChange={(e) => setSortKey(e.target.value as RegionSortKey)}>
              {REGION_SORT_OPTIONS.map((o) => (
                <option key={o.key} value={o.key}>
                  {o.label}
                </option>
              ))}
            </select>
          )}
        </Field>
        {singleApplication ? null : (
          <Field label="Application">
            {(p) => (
              <select {...p} className="select" value={applicationId ?? ''} onChange={(e) => setApplicationId(e.target.value ? Number(e.target.value) : null)}>
                <option value="">All applications</option>
                {applications.map(([id, label]) => (
                  <option key={id} value={id}>
                    {label}
                  </option>
                ))}
              </select>
            )}
          </Field>
        )}
        <Field label="Risk level">
          {(p) => (
            <select {...p} className="select" value={risk} onChange={(e) => setRisk(e.target.value as CacheRiskLevel | '')}>
              <option value="">All risk levels</option>
              {RISK_LEVELS.map((l) => (
                <option key={l} value={l}>
                  {l}
                </option>
              ))}
            </select>
          )}
        </Field>
        <Field label="Search region name">
          {(p) => (
            <input {...p} className="input" type="search" placeholder="e.g. claim-rules" value={query} onChange={(e) => setQuery(e.target.value)} />
          )}
        </Field>
        <p className="toolbar__count muted" aria-live="polite">
          {regions ? `Showing ${visible.length} of ${regions.length} region${regions.length === 1 ? '' : 's'}` : ''}
          {filtersActive ? (
            <>
              {' '}
              <button type="button" className="link-btn" onClick={clear}>
                Clear filters
              </button>
            </>
          ) : null}
        </p>
      </div>
      {body}
    </div>
  );
}
