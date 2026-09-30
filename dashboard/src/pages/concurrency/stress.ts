import { ApiError } from '../../api/client';
import type { StampedeResult, StressImpl, StressReport } from '../../api/rest';

/** The five invariants, in report order (openapi StressReport.invariants). */
export const INVARIANT_NAMES: readonly string[] = [
  'Size bound',
  'Accounting',
  'No phantom values',
  'Structure intact',
  'No exceptions',
];

export const IMPL_LABEL: Record<StressImpl, string> = {
  SINGLE_LOCK: 'Single lock',
  SEGMENTED: 'Segmented',
};

export const THREADS_MIN = 1;
export const THREADS_MAX = 64;
export const THREADS_DEFAULT = 32;
export const DURATION_MIN_SEC = 1;
export const DURATION_MAX_SEC = 10;
export const DURATION_DEFAULT_SEC = 5;

/** The stampede panel always runs the SPEC 5 scenario: 200 threads, a 200 ms loader. */
export const STAMPEDE_THREADS = 200;
export const STAMPEDE_LOADER_DELAY_MS = 200;

/** A report shows at most this many exception summaries. */
export const MAX_EXCEPTIONS = 10;

/** Invariant names to show: the report's own names once a run exists, else the documented five. */
export function invariantNames(report: StressReport | null): readonly string[] {
  return report && report.invariants.length > 0
    ? report.invariants.map((i) => i.name)
    : INVARIANT_NAMES;
}

export interface Verdict {
  passed: boolean;
  summary: string;
}

/** Overall result of a stress run: every invariant held and no deadlock. */
export function stressVerdict(report: StressReport): Verdict {
  const failed = report.invariants.filter((i) => !i.passed);
  const total = report.invariants.length;
  const passed = failed.length === 0 && report.deadlockFree;
  if (passed) {
    return {
      passed,
      summary: `All ${total} invariants held with ${report.threads} threads on the ${IMPL_LABEL[report.impl].toLowerCase()} cache.`,
    };
  }
  const parts: string[] = [];
  if (failed.length > 0) {
    parts.push(
      `${failed.length} of ${total} invariants failed (${failed.map((i) => i.name).join(', ')})`,
    );
  }
  if (!report.deadlockFree) parts.push('a deadlock was detected');
  return { passed, summary: `Problem: ${parts.join(' and ')}.` };
}

/** "200 threads asked for the same missing key → loader ran 1 time." as parts, for emphasis. */
export function stampedeSentence(result: StampedeResult): {
  lead: string;
  count: string;
  tail: string;
} {
  return {
    lead: `${result.threads} threads asked for the same missing key → loader ran `,
    count: String(result.loaderCalls),
    tail: result.loaderCalls === 1 ? ' time.' : ' times.',
  };
}

/** Single-flight works when the loader ran exactly once and everyone saw the same value. */
export function stampedeVerdict(result: StampedeResult): Verdict {
  const passed = result.loaderCalls === 1 && result.allSameValue;
  if (passed) return { passed, summary: 'Single-flight held: no stampede.' };
  const parts: string[] = [];
  if (result.loaderCalls !== 1) parts.push(`the loader ran ${result.loaderCalls} times`);
  if (!result.allSameValue) parts.push('threads saw different values');
  return { passed, summary: `Stampede: ${parts.join(' and ')}.` };
}

/** True when the server refused because another stress job is running. */
export function isBusy(error: unknown): boolean {
  return error instanceof ApiError && error.status === 409;
}

export const BUSY_MESSAGE =
  'Another stress job is running on the server. Try again when it finishes.';
