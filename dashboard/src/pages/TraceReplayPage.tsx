import { BarChart3, Download, FileCheck2, FileText } from 'lucide-react';
import { useId, useState, type FormEvent } from 'react';
import { LATEST_REPORT_CSV_URL, traceApi } from '../api/client';
import type { ReplayResult, TraceUploaded } from '../api/rest';
import { POLICY_TYPES, type PolicyType } from '../api/types';
import { ChipCard, EmptyState, ErrorState, PolicyBadge, Skeleton, useToast } from '../components';
import { formatDurationMs, formatInteger } from '../lib/format';
import { PageHeader } from './PageHeader';
import {
  buttonClass,
  errorMessage,
  fieldErrorClass,
  inputClass,
  labelClass,
  primaryButtonClass,
} from './playground/ui';
import {
  capacityError,
  DEFAULT_CAPACITY,
  downloadText,
  parseCapacity,
  replayCsv,
  replayJson,
  reportFileName,
  traceFileError,
} from './replay/replay';
import { ReplayChart } from './replay/ReplayChart';
import { ResultsTable } from './replay/ResultsTable';
import { TraceDropZone } from './replay/TraceDropZone';

interface LoadedTrace extends TraceUploaded {
  /** Where it came from, e.g. the file name or "sample formulary trace". */
  source: string;
}

type Replay =
  | { status: 'idle' }
  | { status: 'running' }
  | { status: 'error'; message: string }
  | { status: 'done'; result: ReplayResult };

/**
 * Trace Replay (SPEC 10.5 item 5, 9.5): upload an access log (or use the sample), replay it offline
 * through each chosen policy at a chosen capacity, and compare with the optimal hit rate.
 */
export function TraceReplayPage() {
  const toast = useToast();
  const capacityId = useId();
  const capacityErrorId = useId();
  const [trace, setTrace] = useState<LoadedTrace | null>(null);
  const [loadingTrace, setLoadingTrace] = useState<string | null>(null);
  const [traceError, setTraceError] = useState<string | null>(null);
  const [capacity, setCapacity] = useState(String(DEFAULT_CAPACITY));
  const [policies, setPolicies] = useState<ReadonlySet<PolicyType>>(new Set(POLICY_TYPES));
  const [submitted, setSubmitted] = useState(false);
  const [replay, setReplay] = useState<Replay>({ status: 'idle' });

  const capError = capacityError(capacity);
  const policyError = policies.size === 0 ? 'Choose at least one policy.' : null;
  const busy = loadingTrace !== null || replay.status === 'running';

  const loadTrace = async (source: string, load: () => Promise<TraceUploaded>) => {
    setLoadingTrace(source);
    setTraceError(null);
    try {
      const uploaded = await load();
      setTrace({ ...uploaded, source });
      setReplay({ status: 'idle' });
      toast.show(`Loaded ${formatInteger(uploaded.rows)} rows from ${source}.`, 'success');
    } catch (e) {
      setTraceError(errorMessage(e));
    } finally {
      setLoadingTrace(null);
    }
  };

  const onFile = (file: File) => {
    const problem = traceFileError(file);
    if (problem) {
      setTraceError(problem);
      return;
    }
    void loadTrace(`“${file.name}”`, () => traceApi.upload(file));
  };

  const runReplay = async () => {
    if (!trace) return;
    setReplay({ status: 'running' });
    try {
      const result = await traceApi.replay(trace.traceId, {
        capacity: parseCapacity(capacity),
        policies: POLICY_TYPES.filter((p) => policies.has(p)),
      });
      setReplay({ status: 'done', result });
    } catch (e) {
      setReplay({ status: 'error', message: errorMessage(e) });
    }
  };

  const onSubmit = (event: FormEvent) => {
    event.preventDefault();
    setSubmitted(true);
    if (capError || policyError || !trace) return;
    void runReplay();
  };

  const togglePolicy = (policy: PolicyType, on: boolean) =>
    setPolicies((current) => {
      const next = new Set(current);
      if (on) next.add(policy);
      else next.delete(policy);
      return next;
    });

  let traceStatus;
  if (loadingTrace !== null) {
    traceStatus = <Skeleton lines={2} label={`Loading ${loadingTrace}`} />;
  } else if (traceError !== null) {
    traceStatus = <ErrorState title="Could not load the trace" message={traceError} />;
  } else if (trace) {
    traceStatus = (
      <p role="status" className="flex items-center gap-2 text-sm text-text">
        <FileCheck2 aria-hidden="true" size={16} className="shrink-0 text-good" />
        <span>
          Trace <span className="font-mono">{trace.traceId}</span> ready:{' '}
          <span className="font-mono tabular-nums">{formatInteger(trace.rows)}</span> rows from{' '}
          {trace.source}.
        </span>
      </p>
    );
  } else {
    traceStatus = (
      <p role="status" className="text-sm text-muted">
        No trace loaded yet.
      </p>
    );
  }

  let results;
  if (replay.status === 'running') {
    results = <Skeleton lines={6} label="Replaying the trace" />;
  } else if (replay.status === 'error') {
    results = (
      <ErrorState
        title="Replay failed"
        message={replay.message}
        onRetry={trace ? () => void runReplay() : undefined}
      />
    );
  } else if (replay.status === 'done') {
    results = <ReplayResults result={replay.result} />;
  } else {
    results = (
      <EmptyState
        icon={<BarChart3 aria-hidden="true" className="size-6" />}
        title="No replay yet"
        message="Load a trace, pick a capacity and policies, then press Replay. Results appear here."
      />
    );
  }

  return (
    <>
      <PageHeader
        title="Trace Replay"
        description="Replay a real access log through every policy, offline, and compare with the best possible hit rate."
      >
        <a
          href={LATEST_REPORT_CSV_URL}
          className="inline-flex items-center gap-2 text-sm text-trace-glow underline underline-offset-4 hover:text-text"
          title="Per-cache summary of the last Policy Race simulation (not this replay)"
        >
          <Download aria-hidden="true" size={16} />
          Last simulation report (CSV)
        </a>
      </PageHeader>

      <div className="flex flex-col gap-6">
        <div className="grid gap-6 xl:grid-cols-2">
          <ChipCard
            label="U1 · TRACE"
            title="1. Choose a trace"
            info="A trace is a list of cache lookups in order, one key per line. The server keeps the last 3 uploads in memory."
          >
            <div className="flex flex-col gap-4">
              <TraceDropZone onFile={onFile} disabled={busy} />
              <div className="flex flex-wrap items-center gap-4">
                <button
                  type="button"
                  className={buttonClass}
                  disabled={busy}
                  onClick={() => void loadTrace('the sample formulary trace', traceApi.sample)}
                >
                  <FileText aria-hidden="true" size={16} />
                  Use sample formulary trace
                </button>
                <span className="text-sm text-muted">100,000 pharmacy lookups (seed 7).</span>
              </div>
              {traceStatus}
            </div>
          </ChipCard>

          <ChipCard
            label="U2 · REPLAY"
            title="2. Replay"
            info="Runs the whole trace through a fresh cache per policy as fast as possible (no throttling), plus the optimal Bélády replay."
          >
            <form noValidate onSubmit={onSubmit} className="flex flex-col gap-4">
              <div>
                <label htmlFor={capacityId} className={labelClass}>
                  Capacity (entries)
                </label>
                <input
                  id={capacityId}
                  type="text"
                  inputMode="numeric"
                  className={`${inputClass} max-w-48`}
                  value={capacity}
                  onChange={(e) => setCapacity(e.target.value)}
                  aria-invalid={submitted && capError !== null}
                  aria-describedby={submitted && capError ? capacityErrorId : undefined}
                />
                {submitted && capError ? (
                  <p id={capacityErrorId} className={fieldErrorClass}>
                    {capError}
                  </p>
                ) : null}
              </div>
              <fieldset>
                <legend className={labelClass}>Policies</legend>
                <div className="flex flex-wrap gap-4">
                  {POLICY_TYPES.map((p) => (
                    <label key={p} className="flex cursor-pointer items-center gap-2">
                      <input
                        type="checkbox"
                        className="size-4 accent-[var(--trace-glow)]"
                        checked={policies.has(p)}
                        onChange={(e) => togglePolicy(p, e.target.checked)}
                      />
                      <PolicyBadge policy={p} />
                    </label>
                  ))}
                </div>
                {submitted && policyError ? (
                  <p className={fieldErrorClass}>{policyError}</p>
                ) : null}
              </fieldset>
              <div className="flex flex-wrap items-center gap-4">
                <button type="submit" className={primaryButtonClass} disabled={busy || !trace}>
                  {replay.status === 'running' ? 'Replaying…' : 'Replay'}
                </button>
                {!trace ? (
                  <span className="text-sm text-muted">Load a trace first.</span>
                ) : null}
              </div>
            </form>
          </ChipCard>
        </div>

        <ChipCard
          label="U3 · RESULTS"
          title="Hit rate per policy"
          info="The share of lookups each policy answered from the cache when replaying the trace; the hatched bar is the best any policy could do."
        >
          {results}
        </ChipCard>
      </div>
    </>
  );
}

function ReplayResults({ result }: { result: ReplayResult }) {
  return (
    <div className="flex flex-col gap-6">
      <p className="text-sm text-muted">
        Replayed <span className="font-mono text-text tabular-nums">{formatInteger(result.rows)}</span>{' '}
        rows of trace <span className="font-mono text-text">{result.traceId}</span> at capacity{' '}
        <span className="font-mono text-text tabular-nums">{formatInteger(result.capacity)}</span> in{' '}
        <span className="font-mono text-text tabular-nums">{formatDurationMs(result.durationMs)}</span>.
      </p>
      <ReplayChart result={result} />
      <ResultsTable result={result} />
      <div className="flex flex-wrap items-center gap-4">
        <button
          type="button"
          className={buttonClass}
          onClick={() => downloadText(replayCsv(result), reportFileName(result, 'csv'), 'text/csv')}
        >
          <Download aria-hidden="true" size={16} />
          Download report (CSV)
        </button>
        <button
          type="button"
          className={buttonClass}
          onClick={() =>
            downloadText(replayJson(result), reportFileName(result, 'json'), 'application/json')
          }
        >
          <Download aria-hidden="true" size={16} />
          Download report (JSON)
        </button>
      </div>
    </div>
  );
}
