import {
  PATTERNS,
  POLICY_TYPES,
  REMOVAL_CAUSES,
  type AdvisorRecommendation,
  type CacheMetrics,
  type GroupMetrics,
  type MetricsSnapshot,
  type RemovalEvent,
  type SimulationStatus,
} from './types';

/**
 * Runtime shape guard for the metrics stream (schema v2). Events that fail it are dropped, so a
 * malformed payload can never crash a chart.
 */

type Obj = Record<string, unknown>;

const isObj = (v: unknown): v is Obj => typeof v === 'object' && v !== null && !Array.isArray(v);
const isNum = (v: unknown): v is number => typeof v === 'number' && Number.isFinite(v);
const isInt = (v: unknown): v is number => isNum(v) && Number.isInteger(v);
const isCount = (v: unknown): v is number => isInt(v) && v >= 0;
const isRate = (v: unknown): v is number => isNum(v) && v >= 0 && v <= 1;
const isStr = (v: unknown): v is string => typeof v === 'string';
const isNonEmptyStr = (v: unknown): v is string => isStr(v) && v.length > 0;
const isOneOf = <T extends string>(values: readonly T[], v: unknown): v is T =>
  isStr(v) && (values as readonly string[]).includes(v);

function isCacheMetrics(v: unknown): v is CacheMetrics {
  return (
    isObj(v) &&
    isNonEmptyStr(v.name) &&
    isNonEmptyStr(v.group) &&
    isOneOf(POLICY_TYPES, v.policy) &&
    isCount(v.size) &&
    isInt(v.capacity) &&
    v.capacity >= 1 &&
    isCount(v.hits) &&
    isCount(v.misses) &&
    isRate(v.hitRate) &&
    isRate(v.hitRateWindow10s) &&
    isCount(v.evictions) &&
    isCount(v.expirations) &&
    isNum(v.opsPerSec) &&
    v.opsPerSec >= 0 &&
    isNum(v.getP50Micros) &&
    v.getP50Micros >= 0 &&
    isNum(v.getP99Micros) &&
    v.getP99Micros >= 0 &&
    isCount(v.dbCallsAvoided) &&
    isNum(v.latencySavedMs) &&
    v.latencySavedMs >= 0 &&
    isNum(v.estCostSaved) &&
    v.estCostSaved >= 0
  );
}

function isAdvisor(v: unknown): v is AdvisorRecommendation {
  return (
    isObj(v) &&
    isOneOf(POLICY_TYPES, v.current) &&
    isOneOf(POLICY_TYPES, v.recommended) &&
    isNum(v.expectedGainPts) &&
    v.expectedGainPts >= 0 &&
    isInt(v.windowSec) &&
    v.windowSec >= 1
  );
}

function isGroup(v: unknown): v is GroupMetrics {
  return (
    isObj(v) &&
    isNonEmptyStr(v.name) &&
    Array.isArray(v.caches) &&
    v.caches.every(isStr) &&
    (v.optimalHitRate === null || isRate(v.optimalHitRate)) &&
    (v.advisor === null || isAdvisor(v.advisor))
  );
}

function isSimulation(v: unknown): v is SimulationStatus {
  return (
    isObj(v) &&
    isNonEmptyStr(v.id) &&
    typeof v.running === 'boolean' &&
    isNonEmptyStr(v.group) &&
    isOneOf(PATTERNS, v.pattern) &&
    (v.act === null || (isInt(v.act) && v.act >= 1 && v.act <= 4)) &&
    isCount(v.phaseIndex) &&
    isInt(v.phaseCount) &&
    v.phaseCount >= 1 &&
    (v.phaseCaption === null || isStr(v.phaseCaption)) &&
    isInt(v.phaseStartedTs)
  );
}

function isEvent(v: unknown): v is RemovalEvent {
  return (
    isObj(v) &&
    isInt(v.ts) &&
    isNonEmptyStr(v.cache) &&
    isStr(v.key) &&
    isOneOf(REMOVAL_CAUSES, v.cause)
  );
}

/** True if {@code v} is a well-formed schema v2 metrics payload. */
export function isMetricsSnapshot(v: unknown): v is MetricsSnapshot {
  return (
    isObj(v) &&
    isInt(v.ts) &&
    Array.isArray(v.caches) &&
    v.caches.every(isCacheMetrics) &&
    Array.isArray(v.groups) &&
    v.groups.every(isGroup) &&
    (v.simulation === null || isSimulation(v.simulation)) &&
    Array.isArray(v.events) &&
    v.events.length <= 50 &&
    v.events.every(isEvent)
  );
}

/** Parses one SSE `data` string; returns null for invalid JSON or a payload of the wrong shape. */
export function parseMetricsSnapshot(data: string): MetricsSnapshot | null {
  try {
    const parsed: unknown = JSON.parse(data);
    return isMetricsSnapshot(parsed) ? parsed : null;
  } catch {
    return null;
  }
}
