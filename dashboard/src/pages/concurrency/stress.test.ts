import { describe, expect, it } from 'vitest';
import { ApiError } from '../../api/client';
import type { StressReport } from '../../api/rest';
import {
  INVARIANT_NAMES,
  invariantNames,
  isBusy,
  stampedeSentence,
  stampedeVerdict,
  stressVerdict,
} from './stress';

function report(overrides: Partial<StressReport> = {}): StressReport {
  return {
    impl: 'SEGMENTED',
    threads: 32,
    durationMs: 5004,
    totalOps: 12_000_000,
    opsPerSec: 2_398_081,
    invariants: INVARIANT_NAMES.map((name) => ({ name, passed: true, detail: 'ok' })),
    exceptions: [],
    deadlockFree: true,
    ...overrides,
  };
}

describe('stress helpers', () => {
  it('uses the documented names until a report arrives', () => {
    expect(invariantNames(null)).toEqual(INVARIANT_NAMES);
    const custom = report({ invariants: [{ name: 'Custom', passed: true, detail: '' }] });
    expect(invariantNames(custom)).toEqual(['Custom']);
  });

  it('passes when every invariant holds and nothing deadlocked', () => {
    expect(stressVerdict(report())).toEqual({
      passed: true,
      summary: 'All 5 invariants held with 32 threads on the segmented cache.',
    });
  });

  it('names failed invariants and deadlocks', () => {
    const bad = report({
      invariants: INVARIANT_NAMES.map((name, i) => ({ name, passed: i !== 1, detail: '' })),
      deadlockFree: false,
    });
    const verdict = stressVerdict(bad);
    expect(verdict.passed).toBe(false);
    expect(verdict.summary).toBe(
      'Problem: 1 of 5 invariants failed (Accounting) and a deadlock was detected.',
    );
  });

  it('builds the stampede sentence', () => {
    const r = { threads: 200, loaderCalls: 1, allSameValue: true, durationMs: 210 };
    const s = stampedeSentence(r);
    expect(`${s.lead}${s.count}${s.tail}`).toBe(
      '200 threads asked for the same missing key → loader ran 1 time.',
    );
    expect(stampedeVerdict(r).passed).toBe(true);
    const bad = { ...r, loaderCalls: 7, allSameValue: false };
    expect(stampedeSentence(bad).tail).toBe(' times.');
    expect(stampedeVerdict(bad)).toEqual({
      passed: false,
      summary: 'Stampede: the loader ran 7 times and threads saw different values.',
    });
  });

  it('recognises a busy server', () => {
    expect(isBusy(new ApiError('busy', 409, null))).toBe(true);
    expect(isBusy(new ApiError('bad', 400, null))).toBe(false);
    expect(isBusy(new Error('x'))).toBe(false);
  });
});
