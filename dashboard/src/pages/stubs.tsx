import { StubPage } from './StubPage';

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
