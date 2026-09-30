import { Database, Plus } from 'lucide-react';
import { useCallback, useMemo, useRef, useState } from 'react';
import { api } from '../api/client';
import type { CacheConfig, CacheInfo, EntryView } from '../api/rest';
import { ChipCard, EmptyState, ErrorState, Skeleton } from '../components';
import { formatInteger } from '../lib/format';
import { PageHeader } from './PageHeader';
import { CachePanel } from './playground/CachePanel';
import { CommandForm } from './playground/CommandForm';
import { CreateCacheForm } from './playground/CreateCacheForm';
import { EntriesTable } from './playground/EntriesTable';
import { primaryButtonClass } from './playground/ui';
import { usePolling } from './playground/usePolling';

/** Polling intervals (SPEC 10.5): entries every 1 s, the cache list (stats strip) every 2 s. */
const ENTRIES_POLL_MS = 1000;
const CACHES_POLL_MS = 2000;
const ENTRIES_LIMIT = 50;

function emptyInfo(config: CacheConfig): CacheInfo {
  return {
    ...config,
    size: 0,
    hits: 0,
    misses: 0,
    hitRate: 0,
    missRate: 0,
    evictions: 0,
    expirations: 0,
    puts: 0,
  };
}

/**
 * Playground (SPEC 10.5 item 4): create a cache, pick one, run get/put/delete by hand, watch its
 * entries' TTLs count down and switch its policy live.
 */
export function PlaygroundPage() {
  const [selectedName, setSelectedName] = useState<string | null>(null);
  const nameInputRef = useRef<HTMLInputElement>(null);

  const cachesPoll = usePolling(api.listCaches, CACHES_POLL_MS);
  const caches = cachesPoll.data;
  const selected =
    caches?.find((c) => c.name === selectedName) ??
    caches?.find((c) => c.group === 'playground') ??
    caches?.[0];

  const name = selected?.name ?? null;
  const fetchEntries = useMemo(
    () => (name === null ? null : () => api.listEntries(name, ENTRIES_LIMIT)),
    [name],
  );
  const entriesPoll = usePolling(fetchEntries, ENTRIES_POLL_MS);

  const { refresh: refreshCaches, mutate: mutateCaches } = cachesPoll;
  const { refresh: refreshEntries } = entriesPoll;
  const refreshAll = useCallback(() => {
    refreshCaches();
    refreshEntries();
  }, [refreshCaches, refreshEntries]);

  const onCreated = useCallback(
    (config: CacheConfig) => {
      mutateCaches((list) => [...list.filter((c) => c.name !== config.name), emptyInfo(config)]);
      setSelectedName(config.name);
      refreshCaches();
    },
    [mutateCaches, refreshCaches],
  );

  const onPolicySwitched = useCallback(
    (config: CacheConfig) => {
      mutateCaches((list) => list.map((c) => (c.name === config.name ? { ...c, ...config } : c)));
      refreshAll();
    },
    [mutateCaches, refreshAll],
  );

  const createCta = (
    <button
      type="button"
      className={primaryButtonClass}
      onClick={() => nameInputRef.current?.focus()}
    >
      <Plus aria-hidden="true" size={16} />
      Create a cache
    </button>
  );

  /** Shared loading / error / empty handling for the three views that need a selected cache. */
  const placeholder = (loadingLines: number, emptyMessage: string) => {
    if (caches === undefined) {
      return cachesPoll.error ? (
        <ErrorState
          title="Cannot load caches"
          message={cachesPoll.error.message}
          onRetry={refreshAll}
        />
      ) : (
        <Skeleton lines={loadingLines} label="Loading caches" />
      );
    }
    return (
      <EmptyState
        icon={<Database aria-hidden="true" className="size-6" />}
        title="No caches yet"
        message={emptyMessage}
        action={createCta}
      />
    );
  };

  return (
    <>
      <PageHeader
        title="Playground"
        description="Create a cache, then get, put and delete keys by hand and watch TTLs count down."
      />

      <div className="grid gap-6 xl:grid-cols-[minmax(0,2fr)_minmax(0,3fr)]">
        <ChipCard
          label="U1 · CREATE"
          title="Create a cache"
          info="Makes a new in-memory cache on the server with the eviction policy, size limit and default time-to-live you choose."
        >
          <CreateCacheForm onCreated={onCreated} nameInputRef={nameInputRef} />
        </ChipCard>

        <ChipCard
          label={`U2 · CACHE${selected ? ` · ${selected.name.toUpperCase()}` : ''}`}
          title="Selected cache"
          info="Live statistics for the chosen cache, refreshed every 2 seconds; the DIP switch changes its eviction policy without losing entries."
        >
          {selected && caches ? (
            <>
              {cachesPoll.error ? (
                <p role="status" className="mb-4 text-sm text-warn">
                  Lost contact with the server — showing the last known values while retrying.
                </p>
              ) : null}
              <CachePanel
                caches={caches}
                selected={selected}
                onSelect={setSelectedName}
                onPolicySwitched={onPolicySwitched}
                onChanged={refreshAll}
              />
            </>
          ) : (
            placeholder(4, 'Create your first cache on this page to start experimenting.')
          )}
        </ChipCard>

        <ChipCard
          label="U3 · COMMANDS"
          title="Get, put, delete"
          info="Runs one cache operation on the selected cache; a get counts as a real lookup, so it changes the hit and miss numbers."
        >
          {selected ? (
            <CommandForm cache={selected} onDone={refreshAll} />
          ) : (
            placeholder(5, 'Commands run against a cache — create one first.')
          )}
        </ChipCard>

        <ChipCard
          label="U4 · ENTRIES"
          title="Entries"
          info="What the selected cache holds right now, in its policy's order, refreshed every second while this tab is visible. Reading this list does not count as an access."
        >
          {selected ? (
            <EntriesView
              selected={selected}
              entries={entriesPoll.data}
              error={entriesPoll.error}
              fetchedAt={entriesPoll.fetchedAt}
              onRetry={refreshEntries}
            />
          ) : (
            placeholder(6, 'Entries appear here once a cache exists and you put some keys.')
          )}
        </ChipCard>
      </div>
    </>
  );
}

function EntriesView({
  selected,
  entries,
  error,
  fetchedAt,
  onRetry,
}: {
  selected: CacheInfo;
  entries: readonly EntryView[] | undefined;
  error: Error | null;
  fetchedAt: number;
  onRetry: () => void;
}) {
  if (entries === undefined) {
    return error ? (
      <ErrorState title="Cannot load entries" message={error.message} onRetry={onRetry} />
    ) : (
      <Skeleton lines={6} label="Loading entries" />
    );
  }
  if (entries.length === 0) {
    return (
      <EmptyState
        title="This cache is empty"
        message="Put a key with the form to see it here, with its TTL counting down."
      />
    );
  }
  return (
    <>
      <p className="mb-2 text-sm text-muted">
        {entries.length >= ENTRIES_LIMIT
          ? `Showing the first ${ENTRIES_LIMIT} of ${formatInteger(selected.size)} entries.`
          : `${formatInteger(entries.length)} ${entries.length === 1 ? 'entry' : 'entries'} of ${formatInteger(selected.capacity)} slots.`}
      </p>
      <EntriesTable entries={entries} fetchedAt={fetchedAt} policy={selected.policy} />
    </>
  );
}
