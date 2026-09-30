import { StubPage } from './StubPage';

export function PolicyRacePage() {
  return (
    <StubPage
      title="Policy Race"
      description="Run the same traffic through LRU, LFU and LFU with decay side by side."
      chipLabel="U3 · POLICY RACE"
      step={3}
      features={[
        'Group selector, access pattern picker, ops/sec slider and start/stop (Step 3)',
        'Side-by-side hit-rate lines and the removal event log (Step 3)',
        'Optimal (Bélády) line, advisor banner with Apply, and "Inside the cache" (Step 4)',
      ]}
    />
  );
}

export function ConcurrencyLabPage() {
  return (
    <StubPage
      title="Concurrency Lab"
      description="Prove thread safety live: stress tests with invariant checks and a stampede test."
      chipLabel="U4 · CONCURRENCY LAB"
      step={3}
      features={[
        'Single-lock vs segmented stress test with five invariant LEDs (Step 3)',
        'Stampede panel: 200 threads, one loader call (Step 3)',
        'JMH throughput chart: ops/sec vs threads per implementation (Step 4)',
      ]}
    />
  );
}

export function TraceReplayPage() {
  return (
    <StubPage
      title="Trace Replay"
      description="Replay a real access log through every policy and compare with the optimal hit rate."
      chipLabel="U6 · TRACE REPLAY"
      step={4}
      features={[
        'CSV drag-and-drop or the sample formulary trace',
        'Choose capacity and policies; results as hit-rate bars plus the optimal',
        'Download the report',
      ]}
    />
  );
}

export function IntegrationsPage() {
  return (
    <StubPage
      title="Integrations"
      description="Use CacheLab from your code: builder API, Spring @Cacheable and Prometheus metrics."
      chipLabel="U7 · INTEGRATIONS"
      step={4}
      features={[
        'Gradle and Maven dependency snippets',
        'Builder example and @Cacheable with application.properties',
        'Prometheus endpoint and the measured formulary-service latency',
      ]}
    />
  );
}
