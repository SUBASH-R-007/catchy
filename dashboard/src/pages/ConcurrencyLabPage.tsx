import { ChartLine, CircleCheck, CircleX, Play, Users, Zap } from 'lucide-react';
import { useEffect, useId, useState } from 'react';
import type { StressImpl, StressReport } from '../api/rest';
import {
  ChipCard,
  DipSwitch,
  EmptyState,
  ErrorState,
  LedRow,
  Skeleton,
  useToast,
  type DipSwitchOption,
} from '../components';
import { cx } from '../components/cx';
import { formatDurationMs, formatInteger } from '../lib/format';
import {
  BUSY_MESSAGE,
  DURATION_DEFAULT_SEC,
  DURATION_MAX_SEC,
  DURATION_MIN_SEC,
  IMPL_LABEL,
  invariantNames,
  isBusy,
  MAX_EXCEPTIONS,
  STAMPEDE_LOADER_DELAY_MS,
  STAMPEDE_THREADS,
  stampedeSentence,
  stampedeVerdict,
  stressVerdict,
  THREADS_DEFAULT,
  THREADS_MAX,
  THREADS_MIN,
  type Verdict,
} from './concurrency/stress';
import { useStampedeTest, useStressTest } from './concurrency/useDiagnosticRun';
import { PageHeader } from './PageHeader';
import { errorMessage, labelClass, primaryButtonClass } from './playground/ui';

const IMPL_OPTIONS: ReadonlyArray<DipSwitchOption<StressImpl>> = [
  { value: 'SINGLE_LOCK', label: IMPL_LABEL.SINGLE_LOCK },
  { value: 'SEGMENTED', label: IMPL_LABEL.SEGMENTED },
];

/**
 * Concurrency Lab (SPEC 10.5 item 3, Step 3 scope): hammer a cache from many threads and check the
 * five invariants, then prove single-flight loading with the stampede test.
 */
export function ConcurrencyLabPage() {
  return (
    <>
      <PageHeader
        title="Concurrency Lab"
        description="Prove thread safety live: many threads hammer one cache, then five invariant checks light up."
      />
      <div className="flex flex-col gap-6">
        <StressPanel />
        <div className="grid gap-6 xl:grid-cols-2">
          <StampedePanel />
          <ChipCard label="U3 · THROUGHPUT" title="Throughput vs threads (Step 4)">
            <EmptyState
              icon={<ChartLine aria-hidden="true" className="size-6" />}
              title="The JMH throughput chart arrives in Step 4"
              message="Operations per second against thread count, one line per implementation, for each benchmark workload."
            />
          </ChipCard>
        </div>
      </div>
    </>
  );
}

function StressPanel() {
  const id = useId();
  const toast = useToast();
  const stress = useStressTest();
  const [impl, setImpl] = useState<StressImpl>('SINGLE_LOCK');
  const [threads, setThreads] = useState(THREADS_DEFAULT);
  const [seconds, setSeconds] = useState(DURATION_DEFAULT_SEC);
  const running = stress.status === 'running';
  const report = stress.result;

  const runTest = async () => {
    try {
      await stress.run({ impl, threads, durationMs: seconds * 1000 });
    } catch (e) {
      toast.show(isBusy(e) ? BUSY_MESSAGE : errorMessage(e), 'error');
    }
  };

  let result;
  if (running) {
    result = (
      <RunProgress
        startedAt={stress.startedAt}
        expectedMs={stress.config?.durationMs ?? seconds * 1000}
      />
    );
  } else if (stress.status === 'error' && stress.error && !isBusy(stress.error)) {
    result = (
      <ErrorState
        title="The stress test did not complete"
        message={stress.error.message}
        onRetry={() => void runTest()}
      />
    );
  } else if (report) {
    result = <StressResult report={report} />;
  } else {
    result = (
      <EmptyState
        icon={<Users aria-hidden="true" className="size-6" />}
        title="Not run yet"
        message="Choose an implementation, threads and duration, then run the stress test."
      />
    );
  }

  return (
    <ChipCard
      label="U1 · STRESS TEST"
      title="Stress test"
      info="Many threads read and write random keys on one cache at the same time; afterwards five checks confirm the cache is still consistent and nothing deadlocked."
    >
      <div className="flex flex-col gap-6">
        <div className="flex flex-wrap items-end gap-6">
          <DipSwitch
            label="Implementation"
            options={IMPL_OPTIONS}
            value={impl}
            onChange={setImpl}
            disabled={running}
          />
          <Slider
            id={`${id}-threads`}
            label="Threads"
            value={threads}
            min={THREADS_MIN}
            max={THREADS_MAX}
            unit="threads"
            onChange={setThreads}
            disabled={running}
          />
          <Slider
            id={`${id}-duration`}
            label="Duration"
            value={seconds}
            min={DURATION_MIN_SEC}
            max={DURATION_MAX_SEC}
            unit="s"
            onChange={setSeconds}
            disabled={running}
          />
          <button
            type="button"
            className={primaryButtonClass}
            disabled={running}
            onClick={() => void runTest()}
          >
            <Play aria-hidden="true" size={16} />
            {running ? 'Running…' : 'Run stress test'}
          </button>
        </div>

        <LedRow
          names={invariantNames(report)}
          results={running ? null : (report?.invariants ?? null)}
          running={running}
          label="Stress test invariants"
        />

        <div aria-live="polite">{result}</div>
      </div>
    </ChipCard>
  );
}

function Slider({
  id,
  label,
  value,
  min,
  max,
  unit,
  onChange,
  disabled,
}: {
  id: string;
  label: string;
  value: number;
  min: number;
  max: number;
  unit: string;
  onChange: (value: number) => void;
  disabled: boolean;
}) {
  return (
    <div className="w-48">
      <label htmlFor={id} className={labelClass}>
        {label}:{' '}
        <output htmlFor={id} className="font-mono text-text tabular-nums">
          {value} {unit}
        </output>
      </label>
      <input
        id={id}
        type="range"
        min={min}
        max={max}
        step={1}
        value={value}
        disabled={disabled}
        onChange={(e) => onChange(Number(e.target.value))}
        aria-valuetext={`${value} ${unit === 's' ? 'seconds' : unit}`}
        className="w-full accent-[var(--trace-glow)] disabled:opacity-50"
      />
      <div aria-hidden="true" className="flex justify-between font-mono text-sm text-muted">
        <span>{min}</span>
        <span>{max}</span>
      </div>
    </div>
  );
}

/** How often the progress bar advances while a blocking run is in flight. */
const PROGRESS_TICK_MS = 200;

function RunProgress({ startedAt, expectedMs }: { startedAt: number; expectedMs: number }) {
  const [now, setNow] = useState(() => Date.now());

  useEffect(() => {
    const timer = window.setInterval(() => setNow(Date.now()), PROGRESS_TICK_MS);
    return () => window.clearInterval(timer);
  }, []);

  const elapsed = Math.max(0, now - startedAt);
  const percent = Math.min(100, Math.round((elapsed / Math.max(1, expectedMs)) * 100));
  const overtime = elapsed >= expectedMs;

  return (
    <div className="flex flex-col gap-2">
      <div
        role="progressbar"
        aria-label="Stress test progress"
        aria-valuemin={0}
        aria-valuemax={100}
        aria-valuenow={percent}
        aria-valuetext={overtime ? 'Checking invariants' : `${percent}% of the run`}
        className="h-2 overflow-hidden rounded-full border border-trace bg-surface-2"
      >
        <div className="h-full bg-trace-glow" style={{ width: `${percent}%` }} />
      </div>
      <p className="font-mono text-sm text-muted tabular-nums">
        {overtime
          ? 'Checking invariants…'
          : `${formatDurationMs(elapsed)} of ${formatDurationMs(expectedMs)}`}
      </p>
    </div>
  );
}

function VerdictLine({ verdict }: { verdict: Verdict }) {
  const Icon = verdict.passed ? CircleCheck : CircleX;
  return (
    <p
      className={cx(
        'flex items-start gap-2 text-base',
        verdict.passed ? 'text-good' : 'text-critical',
      )}
    >
      <Icon aria-hidden="true" size={20} className="mt-0.5 shrink-0" />
      <span>
        <span className="font-semibold">{verdict.passed ? 'Pass. ' : 'Fail. '}</span>
        {verdict.summary}
      </span>
    </p>
  );
}

function StressResult({ report }: { report: StressReport }) {
  const exceptions = report.exceptions.slice(0, MAX_EXCEPTIONS);
  const DeadlockIcon = report.deadlockFree ? CircleCheck : CircleX;
  return (
    <div className="flex flex-col gap-4">
      <VerdictLine verdict={stressVerdict(report)} />
      <dl className="grid grid-cols-2 gap-4 md:grid-cols-4">
        <Stat label="Implementation" value={IMPL_LABEL[report.impl]} />
        <Stat label="Total operations" value={formatInteger(report.totalOps)} />
        <Stat label="Throughput" value={`${formatInteger(report.opsPerSec)} ops/s`} />
        <Stat
          label="Measured duration"
          value={`${formatDurationMs(report.durationMs)} · ${report.threads} threads`}
        />
      </dl>
      <p
        className={cx(
          'flex items-center gap-2 text-sm',
          report.deadlockFree ? 'text-good' : 'text-critical',
        )}
      >
        <DeadlockIcon aria-hidden="true" size={16} className="shrink-0" />
        {report.deadlockFree
          ? 'No deadlocks detected.'
          : 'Deadlock detected: some threads were still blocked when the run ended.'}
      </p>
      {exceptions.length > 0 ? (
        <div>
          <p className="mb-1 text-sm text-critical">
            {exceptions.length === 1
              ? '1 exception was thrown:'
              : `${exceptions.length} exceptions were thrown (up to ${MAX_EXCEPTIONS} shown):`}
          </p>
          <ul className="list-disc space-y-1 pl-6 font-mono text-sm break-words text-text">
            {exceptions.map((text, i) => (
              <li key={`${i}-${text}`}>{text}</li>
            ))}
          </ul>
        </div>
      ) : null}
    </div>
  );
}

function Stat({ label, value }: { label: string; value: string }) {
  return (
    <div className="rounded border border-trace bg-surface-2 p-2">
      <dt className="text-sm text-muted">{label}</dt>
      <dd className="font-mono text-base text-text tabular-nums">{value}</dd>
    </div>
  );
}

function StampedePanel() {
  const toast = useToast();
  const stampede = useStampedeTest();
  const running = stampede.status === 'running';
  const result = stampede.result;

  const runTest = async () => {
    try {
      await stampede.run({ threads: STAMPEDE_THREADS, loaderDelayMs: STAMPEDE_LOADER_DELAY_MS });
    } catch (e) {
      toast.show(isBusy(e) ? BUSY_MESSAGE : errorMessage(e), 'error');
    }
  };

  let body;
  if (running) {
    body = <Skeleton lines={2} label="Running the stampede test" />;
  } else if (stampede.status === 'error' && stampede.error && !isBusy(stampede.error)) {
    body = (
      <ErrorState
        title="The stampede test did not complete"
        message={stampede.error.message}
        onRetry={() => void runTest()}
      />
    );
  } else if (result) {
    const sentence = stampedeSentence(result);
    body = (
      <div className="flex flex-col gap-2">
        <p className="text-lg text-text">
          {sentence.lead}
          <strong className="font-mono text-2xl text-trace-glow">{sentence.count}</strong>
          {sentence.tail}
        </p>
        <p className="text-sm text-muted">
          {result.allSameValue
            ? 'All threads got the same value.'
            : 'Threads got different values.'}{' '}
          Took {formatDurationMs(result.durationMs)}.
        </p>
        <VerdictLine verdict={stampedeVerdict(result)} />
      </div>
    );
  } else {
    body = (
      <EmptyState
        icon={<Zap aria-hidden="true" className="size-6" />}
        title="Not run yet"
        message={`${STAMPEDE_THREADS} threads will ask for the same missing key at once; the loader takes ${STAMPEDE_LOADER_DELAY_MS} ms.`}
      />
    );
  }

  return (
    <ChipCard
      label="U2 · STAMPEDE"
      title="Cache stampede"
      info="When many requests miss the same key at once, a naive cache calls the database once per request. CacheLab loads the key once and shares the result with every waiting thread."
    >
      <div className="flex flex-col gap-4">
        <div>
          <button
            type="button"
            className={primaryButtonClass}
            disabled={running}
            onClick={() => void runTest()}
          >
            <Zap aria-hidden="true" size={16} />
            {running ? 'Running…' : 'Run stampede'}
          </button>
        </div>
        <div aria-live="polite">{body}</div>
      </div>
    </ChipCard>
  );
}
