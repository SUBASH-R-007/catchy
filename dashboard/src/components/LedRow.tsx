import { useEffect, useId, useState } from 'react';
import './components.css';
import { cx } from './cx';
import { usePrefersReducedMotion } from './usePrefersReducedMotion';

export interface InvariantResult {
  name: string;
  passed: boolean;
  detail: string;
}

export interface LedRowProps {
  /** The invariant names, in display order (five for the stress test). */
  names: readonly string[];
  /** Results of the last run, or null before any run. */
  results: ReadonlyArray<InvariantResult> | null;
  /** A run is in progress: all LEDs pulse amber. */
  running?: boolean;
  /** Accessible name of the list. */
  label?: string;
  className?: string;
}

/** Delay between LEDs lighting up after a run completes. */
export const LED_STAGGER_MS = 150;

type LedStatus = 'idle' | 'running' | 'pending' | 'passed' | 'failed';

/**
 * One LED per invariant. Results light up in sequence (150 ms apart; all at once under
 * reduced motion); a failed LED is red and shows its detail on hover and keyboard focus.
 */
export function LedRow({
  names,
  results,
  running = false,
  label = 'Invariant checks',
  className,
}: LedRowProps) {
  const reducedMotion = usePrefersReducedMotion();
  // Each new run replays the light-up sequence, even when its results match the previous run.
  const [run, setRun] = useState({ running, id: 0 });
  if (run.running !== running) setRun({ running, id: running ? run.id + 1 : run.id });
  // Key the reveal by content, so a parent re-creating the same results array does not restart it.
  const resultsKey = results
    ? `${run.id}|${JSON.stringify(results.map((result) => [result.name, result.passed, result.detail]))}`
    : null;
  const [reveal, setReveal] = useState<{ key: string | null; count: number }>({
    key: null,
    count: 0,
  });

  const ledCount = names.length;

  useEffect(() => {
    // Wait for the run to finish, so the sequence plays when the results are actually shown.
    if (resultsKey === null || reducedMotion || running) return;
    const timers = Array.from({ length: ledCount }, (_, index) =>
      window.setTimeout(() => {
        const count = index + 1;
        // Only ever move forward, so a restarted effect never dims an LED that is already lit.
        setReveal((prev) =>
          prev.key === resultsKey && prev.count >= count ? prev : { key: resultsKey, count },
        );
      }, index * LED_STAGGER_MS),
    );
    return () => timers.forEach((timer) => window.clearTimeout(timer));
  }, [resultsKey, reducedMotion, running, ledCount]);

  const revealed =
    resultsKey === null
      ? 0
      : reducedMotion
        ? ledCount
        : reveal.key === resultsKey
          ? reveal.count
          : 0;

  return (
    // Container query: a vertical status list in narrow cards, a row of five LEDs when wide.
    <div className={cx('@container', className)}>
      <ul
        aria-label={label}
        aria-busy={running || undefined}
        className="grid gap-2 @xl:grid-cols-5"
      >
        {names.map((name, index) => {
          const result = results?.find((candidate) => candidate.name === name) ?? results?.[index];
          let status: LedStatus = 'idle';
          if (running) status = 'running';
          else if (result)
            status = index < revealed ? (result.passed ? 'passed' : 'failed') : 'pending';
          return <LedItem key={name} name={name} status={status} detail={result?.detail ?? ''} />;
        })}
      </ul>
    </div>
  );
}

const LED_CLASS: Record<LedStatus, string> = {
  idle: '',
  pending: '',
  running: 'led--warn led--pulse',
  passed: 'led--good',
  failed: 'led--critical',
};

const STATUS_TEXT: Record<Exclude<LedStatus, 'failed'>, string> = {
  idle: 'not run',
  pending: 'checking',
  running: 'running',
  passed: 'passed',
};

function LedItem({ name, status, detail }: { name: string; status: LedStatus; detail: string }) {
  const detailId = useId();
  const [tipOpen, setTipOpen] = useState(false);
  const failed = status === 'failed';
  const showTip = failed && tipOpen;

  useEffect(() => {
    if (!showTip) return;
    // WCAG 1.4.13: the tooltip can be dismissed without moving focus or the pointer.
    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key === 'Escape') setTipOpen(false);
    };
    document.addEventListener('keydown', onKeyDown);
    return () => document.removeEventListener('keydown', onKeyDown);
  }, [showTip]);

  const focusableTabIndex = failed ? 0 : undefined;

  return (
    <li
      className={cx(
        'relative flex items-center gap-2 rounded p-2 @xl:flex-col @xl:text-center',
        failed && 'cursor-help',
      )}
      data-status={status}
      tabIndex={focusableTabIndex}
      aria-describedby={failed ? detailId : undefined}
      onFocus={() => setTipOpen(true)}
      onBlur={() => setTipOpen(false)}
      onMouseEnter={() => setTipOpen(true)}
      onMouseLeave={() => setTipOpen(false)}
    >
      <span aria-hidden="true" className={cx('led led--lg', LED_CLASS[status])} />
      <span className="min-w-0 flex-1 text-sm text-text @xl:flex-none">{name}</span>
      {/* A visible word as well as the colour (WCAG 1.4.1); screen readers get the sr-only text. */}
      {status === 'passed' || failed ? (
        <span
          aria-hidden="true"
          className={cx(
            'font-mono text-sm uppercase tracking-widest',
            failed ? 'text-critical' : 'text-good',
          )}
        >
          {failed ? 'Fail' : 'Pass'}
        </span>
      ) : null}
      <span className="sr-only">{failed ? `failed: ${detail}` : STATUS_TEXT[status]}</span>
      {failed ? (
        <span
          id={detailId}
          role="tooltip"
          hidden={!showTip}
          className="absolute left-0 top-full z-40 mt-1 w-[240px] max-w-[calc(100vw-32px)] rounded @xl:left-1/2 @xl:-translate-x-1/2 border border-critical/60 bg-surface-2 p-2 text-left font-mono text-sm text-text shadow-lg shadow-black/40"
        >
          {detail}
        </span>
      ) : null}
    </li>
  );
}
