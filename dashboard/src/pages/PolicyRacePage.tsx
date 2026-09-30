import { Database, Layers, Lightbulb, ScanEye } from 'lucide-react';
import { useCallback, useState } from 'react';
import { useSearchParams } from 'react-router';
import { api } from '../api/client';
import { useMetrics } from '../api/metricsContext';
import { hitRateRows, phaseMarkers } from '../api/streamReducer';
import type { Pattern, PolicyType } from '../api/types';
import { ChipCard, EmptyState, ErrorState, Skeleton, useToast } from '../components';
import { HitRateChart } from './overview/HitRateChart';
import { PageHeader } from './PageHeader';
import { errorMessage } from './playground/ui';
import { usePolling } from './playground/usePolling';
import { EventLog } from './race/EventLog';
import { PATTERN_INFO } from './race/patterns';
import {
  groupNames,
  groupSeries,
  OPS_DEFAULT,
  pickGroup,
  RACE_DURATION_SEC,
  RACE_READ_RATIO,
  RACE_SEED,
  recentRemovals,
  simulationView,
} from './race/race';
import { RaceControls } from './race/RaceControls';

/** The group list rarely changes; refresh it every 5 s while the tab is visible. */
const GROUPS_POLL_MS = 5000;

/**
 * Policy Race (SPEC 10.5 item 2, Step 3 scope): drive one workload through every cache of a group
 * and watch their hit rates side by side, with a log of what each cache removed. `?group=` selects
 * the group (used by the guided demo).
 */
export function PolicyRacePage() {
  const { state, history } = useMetrics();
  const toast = useToast();
  const [searchParams, setSearchParams] = useSearchParams();
  const [pattern, setPattern] = useState<Pattern>('ZIPF');
  const [opsPerSec, setOpsPerSec] = useState(OPS_DEFAULT);
  const [busy, setBusy] = useState(false);
  const [startedId, setStartedId] = useState<string | null>(null);

  const cachesPoll = usePolling(api.listCaches, GROUPS_POLL_MS);
  const groups = groupNames(cachesPoll.data, history.latest?.caches ?? []);
  const group = pickGroup(searchParams.get('group'), groups);

  const setGroup = useCallback(
    (next: string) => setSearchParams({ group: next }, { replace: true }),
    [setSearchParams],
  );

  const series = groupSeries(history, group);
  const cacheNames = series.map((s) => s.name);
  const policies = new Map<string, PolicyType>(series.map((s) => [s.name, s.policy]));
  const events = recentRemovals(history, cacheNames);
  const simulation = simulationView(history.latest);
  const waiting = history.latest === null;
  // Without a live stream the running state is unknown, so allow stopping what we started.
  const canStop = simulation.running || (state !== 'live' && startedId !== null);

  const start = async () => {
    setBusy(true);
    try {
      const { id } = await api.startSimulation({
        group,
        pattern,
        opsPerSec,
        readRatio: RACE_READ_RATIO,
        durationSec: RACE_DURATION_SEC,
        seed: RACE_SEED,
      });
      setStartedId(id);
      toast.show(`Started ${PATTERN_INFO[pattern].label} on group “${group}”.`, 'success');
    } catch (e) {
      toast.show(errorMessage(e), 'error');
    } finally {
      setBusy(false);
    }
  };

  const stop = async () => {
    const id = simulation.id ?? startedId;
    if (id === null) return;
    setBusy(true);
    try {
      await api.stopSimulation(id);
      setStartedId(null);
      toast.show('Workload stopped.', 'info');
    } catch (e) {
      toast.show(errorMessage(e), 'error');
    } finally {
      setBusy(false);
    }
  };

  const streamPlaceholder = (lines: number) =>
    state === 'offline' ? (
      <ErrorState message="No data: the metrics stream is offline. Start the server with ./gradlew :cache-server:bootRun — the page reconnects on its own." />
    ) : (
      <Skeleton lines={lines} label="Waiting for the first metrics" />
    );

  const noCaches = (
    <EmptyState
      icon={<Database aria-hidden="true" className="size-6" />}
      title={`Group “${group}” has no caches`}
      message="Pick another group above. The server boots with group “demo” (LRU and LFU)."
    />
  );

  let controls;
  if (groups.length === 0 && cachesPoll.data === undefined && waiting) {
    controls = cachesPoll.error ? (
      <ErrorState
        title="Cannot load cache groups"
        message={cachesPoll.error.message}
        onRetry={cachesPoll.refresh}
      />
    ) : (
      <Skeleton lines={4} label="Loading cache groups" />
    );
  } else if (groups.length === 0) {
    controls = (
      <EmptyState
        icon={<Layers aria-hidden="true" className="size-6" />}
        title="No cache groups"
        message="A race needs a group of caches. Restart the server to get the default “demo” group."
      />
    );
  } else {
    controls = (
      <RaceControls
        groups={groups}
        group={group}
        onGroupChange={setGroup}
        pattern={pattern}
        onPatternChange={setPattern}
        opsPerSec={opsPerSec}
        onOpsChange={setOpsPerSec}
        simulation={simulation}
        busy={busy}
        canStop={canStop}
        onStart={() => void start()}
        onStop={() => void stop()}
      />
    );
  }

  return (
    <>
      <PageHeader
        title="Policy Race"
        description="Run the same traffic through every cache of a group and see which eviction policy keeps the most hits."
      />

      <div className="flex flex-col gap-6">
        <ChipCard
          label="U1 · WORKLOAD"
          title="Workload"
          info="Starts a simulated workload: the same keys, in the same order, go to every cache in the group, so any difference in hit rate comes from the eviction policy. Runs for up to 5 minutes (90% reads, seed 42)."
        >
          {controls}
        </ChipCard>

        {/* Step 4: the optimal (Bélády) line joins the chart and the advisor banner goes here. */}
        <ChipCard label="U2 · ADVISOR" title="Advisor — arrives in Step 4">
          <EmptyState
            icon={<Lightbulb aria-hidden="true" className="size-6" />}
            title="Policy advice is coming"
            message="Step 4 adds the optimal (Bélády) line to the chart and a banner recommending the best policy, with an Apply button."
          />
        </ChipCard>

        <ChipCard
          label={`U3 · HIT RATE · ${group.toUpperCase()}`}
          title="Hit rate, side by side"
          info="Each line is one cache's hit rate over a sliding 10-second window, for the caches in the selected group only; higher means fewer database calls."
        >
          {waiting ? (
            streamPlaceholder(6)
          ) : series.length === 0 ? (
            noCaches
          ) : (
            <HitRateChart
              rows={hitRateRows(history)}
              series={series}
              markers={phaseMarkers(history)}
            />
          )}
        </ChipCard>

        <div className="grid gap-6 xl:grid-cols-[minmax(0,3fr)_minmax(0,2fr)]">
          <ChipCard
            label="U4 · EVENT LOG"
            title="Removals"
            info="The last 50 entries the group's caches removed, newest first: evicted to make room, expired, deleted by a client, or replaced by a new value."
          >
            {waiting ? (
              streamPlaceholder(6)
            ) : series.length === 0 ? (
              noCaches
            ) : events.length === 0 ? (
              <EmptyState
                title="No removals yet"
                message="Start a workload: evictions appear here once the caches are full."
              />
            ) : (
              <EventLog events={events} policies={policies} />
            )}
          </ChipCard>

          <ChipCard label="U5 · INSIDE THE CACHE" title="Inside the cache (Step 4)">
            <EmptyState
              icon={<ScanEye aria-hidden="true" className="size-6" />}
              title="A look inside arrives in Step 4"
              message="LRU's 20 most recent keys as a strip, and LFU's top 20 frequencies as bars, refreshed every second."
            />
          </ChipCard>
        </div>
      </div>
    </>
  );
}
