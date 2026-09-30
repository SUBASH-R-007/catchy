import type { AlertSeverity, Health } from '../types';
import { formatInt } from '../../lib/format';

export interface HealthInput {
  requests: number;
  hitRate: number;
  memoryUtilizationPercent: number;
  recentEvictions: number;
  recentMinutes: number;
  telemetryEventsFailed: number;
}

export interface FailingCondition {
  code: 'HIT_RATE' | 'MEMORY' | 'EVICTIONS' | 'TELEMETRY';
  severity: AlertSeverity;
  message: string;
}

export interface HealthEvaluation {
  health: Health;
  failing: FailingCondition[];
}

const MIN_REQUESTS = 50;

/** Mirrors the backend health rules described in docs/api.md (`Health`). */
export function evaluateHealth(input: HealthInput): HealthEvaluation {
  if (input.requests < MIN_REQUESTS) {
    return {
      health: {
        status: 'UNKNOWN',
        score: 0,
        reasons:
          input.requests === 0
            ? ['No telemetry received yet.']
            : [`Only ${formatInt(input.requests)} requests observed; not enough traffic to judge health.`],
      },
      failing: [],
    };
  }

  const failing: FailingCondition[] = [];
  const passing: string[] = [];
  const hit = input.hitRate;
  const mem = Math.round(input.memoryUtilizationPercent);

  if (hit < 40) {
    failing.push({ code: 'HIT_RATE', severity: 'CRITICAL', message: `Hit rate is ${hit.toFixed(1)}%, below 65% target.` });
  } else if (hit < 65) {
    failing.push({ code: 'HIT_RATE', severity: 'WARNING', message: `Hit rate is ${hit.toFixed(1)}%, below 65% target.` });
  } else {
    passing.push(`Hit rate is ${hit.toFixed(1)}%, above ${hit >= 85 ? 85 : 65}% target.`);
  }

  if (input.memoryUtilizationPercent >= 98) {
    failing.push({ code: 'MEMORY', severity: 'CRITICAL', message: `Memory utilization is ${mem}%.` });
  } else if (input.memoryUtilizationPercent >= 90) {
    failing.push({ code: 'MEMORY', severity: 'WARNING', message: `Memory utilization is ${mem}%.` });
  } else {
    passing.push(`Memory utilization is ${mem}%, below the 90% limit.`);
  }

  if (input.recentEvictions >= 300) {
    failing.push({
      code: 'EVICTIONS',
      severity: 'WARNING',
      message: `${formatInt(input.recentEvictions)} evictions occurred in the last ${input.recentMinutes} minutes.`,
    });
  } else {
    passing.push(
      `${formatInt(input.recentEvictions)} evictions in the last ${input.recentMinutes} minutes (under 300).`,
    );
  }

  if (input.telemetryEventsFailed > 0) {
    failing.push({
      code: 'TELEMETRY',
      severity: 'WARNING',
      message: `${formatInt(input.telemetryEventsFailed)} telemetry events failed to send.`,
    });
  }

  let score = 100;
  score -= hit < 85 ? Math.min(45, (85 - hit) * 0.9) : 0;
  score -= input.memoryUtilizationPercent > 70 ? Math.min(30, (input.memoryUtilizationPercent - 70) * 1.2) : 0;
  score -= Math.min(25, input.recentEvictions / 40);
  score -= input.telemetryEventsFailed > 0 ? 10 : 0;
  score = Math.max(0, Math.min(100, Math.round(score)));

  if (failing.some((f) => f.severity === 'CRITICAL')) {
    return { health: { status: 'CRITICAL', score, reasons: failing.map((f) => f.message) }, failing };
  }
  if (failing.length > 0) {
    return { health: { status: 'WARNING', score, reasons: failing.map((f) => f.message) }, failing };
  }
  return {
    health: { status: score >= 90 && hit >= 85 ? 'EXCELLENT' : 'GOOD', score, reasons: passing },
    failing,
  };
}
