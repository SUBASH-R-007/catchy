import { ArrowRight, PackageOpen } from 'lucide-react';
import { useCallback, useRef } from 'react';
import { api } from '../../api/client';
import type { PolicySnapshot } from '../../api/rest';
import type { SeriesInfo } from '../../api/streamReducer';
import { EmptyState, ErrorState, PolicyBadge, Skeleton } from '../../components';
import { formatInteger } from '../../lib/format';
import { SERIES_STYLE } from '../../theme/policy';
import { usePolling } from '../playground/usePolling';
import {
  frequencyBars,
  INSIDE_LIMIT,
  INSIDE_POLL_MS,
  insideSummary,
  recencyStrip,
} from './inside';
import { useInView } from './useInView';

export interface InsideCacheProps {
  /** The group's caches, in server order. */
  caches: readonly SeriesInfo[];
}

/**
 * "Inside the cache" (SPEC 10.5): each cache's policy order, polled every second from /snapshot,
 * only while this card is on screen and the tab is visible.
 */
export function InsideCache({ caches }: InsideCacheProps) {
  const ref = useRef<HTMLDivElement>(null);
  const inView = useInView(ref);
  return (
    <div ref={ref} className="flex flex-col gap-6">
      {caches.map((c) => (
        <SnapshotPanel key={c.name} cache={c} active={inView} />
      ))}
    </div>
  );
}

function SnapshotPanel({ cache, active }: { cache: SeriesInfo; active: boolean }) {
  const fetchSnapshot = useCallback(() => api.snapshot(cache.name, INSIDE_LIMIT), [cache.name]);
  const poll = usePolling(active ? fetchSnapshot : null, INSIDE_POLL_MS);
  const snapshot = poll.data;
  const policy = snapshot?.type ?? cache.policy;

  let body;
  if (snapshot === undefined) {
    body = poll.error ? (
      <ErrorState
        title={`Cannot look inside ${cache.name}`}
        message={poll.error.message}
        onRetry={poll.refresh}
      />
    ) : (
      <Skeleton lines={3} label={`Loading the inside of ${cache.name}`} />
    );
  } else if (snapshot.entries.length === 0) {
    body = (
      <EmptyState
        icon={<PackageOpen aria-hidden="true" className="size-6" />}
        title={`${cache.name} is empty`}
        message="Start a workload: keys appear here as soon as the cache stores them."
      />
    );
  } else if (snapshot.type === 'LRU') {
    body = <RecencyStrip cache={cache.name} snapshot={snapshot} />;
  } else {
    body = <FrequencyBars cache={cache.name} snapshot={snapshot} />;
  }

  return (
    <section aria-label={`Inside ${cache.name}`}>
      <h3 className="mb-2 flex flex-wrap items-center gap-2 text-sm text-text">
        <PolicyBadge policy={policy} className="py-0.5" />
        <span className="font-mono">{cache.name}</span>
        <span className="text-muted">
          {policy === 'LRU' ? '· 20 most recent keys' : '· top 20 by frequency'}
        </span>
      </h3>
      {body}
      {snapshot && snapshot.entries.length > 0 && poll.error ? (
        <p className="mt-2 text-sm text-warn">Update failed: {poll.error.message}</p>
      ) : null}
    </section>
  );
}

function RecencyStrip({ cache, snapshot }: { cache: string; snapshot: PolicySnapshot }) {
  const keys = recencyStrip(snapshot);
  return (
    <figure className="m-0">
      <div className="mb-1 flex items-center justify-between text-sm text-muted" aria-hidden="true">
        <span className="flex items-center gap-1">
          Most recent <ArrowRight size={14} />
        </span>
        <span>Least recent (evicted first)</span>
      </div>
      <ol
        className="flex flex-wrap gap-1"
        aria-label={`${cache}: most recently used keys, most recent first`}
      >
        {keys.map((k) => (
          <li
            key={k.key}
            title={`#${k.rank}: ${k.key}`}
            className={
              k.rank === 1
                ? 'flex max-w-40 items-center gap-1 rounded-sm border border-lru bg-bg px-2 py-0.5 font-mono text-sm text-text'
                : 'flex max-w-40 items-center gap-1 rounded-sm border border-trace bg-surface-2 px-2 py-0.5 font-mono text-sm text-text'
            }
          >
            <span className="text-muted tabular-nums">{k.rank}</span>
            <span className="truncate">{k.key}</span>
          </li>
        ))}
      </ol>
      <figcaption className="sr-only">{insideSummary(cache, snapshot)}</figcaption>
    </figure>
  );
}

function FrequencyBars({ cache, snapshot }: { cache: string; snapshot: PolicySnapshot }) {
  const bars = frequencyBars(snapshot);
  const color = SERIES_STYLE[snapshot.type].color;
  return (
    <figure className="m-0">
      <ol className="space-y-1" aria-label={`${cache}: keys by access frequency, highest first`}>
        {bars.map((b) => (
          <li
            key={b.key}
            className="grid grid-cols-[minmax(0,8rem)_minmax(0,1fr)_4.5rem] items-center gap-2 text-sm"
          >
            <span className="truncate font-mono text-text" title={b.key}>
              {b.key}
            </span>
            <span aria-hidden="true" className="h-3 rounded-r-sm bg-surface-2">
              <span
                className="block h-full rounded-r-[4px]"
                style={{ width: `${Math.max(1, b.share * 100)}%`, background: color }}
              />
            </span>
            <span className="text-right font-mono text-text tabular-nums">
              {formatInteger(b.frequency)}
              <span className="sr-only"> accesses</span>
            </span>
          </li>
        ))}
      </ol>
      <figcaption className="sr-only">{insideSummary(cache, snapshot)}</figcaption>
    </figure>
  );
}
