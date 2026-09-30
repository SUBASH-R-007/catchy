import type { CacheAction, CacheEvent } from '../api/types';
import type { Tone } from './health';

/** Filter chips of the Eviction X-ray; an empty selection means "all actions". */
export type EventGroupKey =
  | 'hits'
  | 'misses'
  | 'puts'
  | 'evictions'
  | 'expirations'
  | 'policy'
  | 'stampede'
  | 'other';

export interface EventGroup {
  key: EventGroupKey;
  label: string;
  actions: CacheAction[];
}

export const EVENT_GROUPS: readonly EventGroup[] = [
  { key: 'hits', label: 'Hits', actions: ['HIT', 'VICTIM_HIT', 'STALE_SERVED'] },
  { key: 'misses', label: 'Misses', actions: ['MISS'] },
  { key: 'puts', label: 'Puts', actions: ['PUT'] },
  { key: 'evictions', label: 'Evictions', actions: ['EVICTED', 'MEMORY_EVICTED', 'ENTRY_LIMIT_EVICTED'] },
  { key: 'expirations', label: 'Expirations', actions: ['EXPIRED'] },
  {
    key: 'policy',
    label: 'Policy/Config',
    actions: ['POLICY_CHANGED', 'CONFIG_CHANGED', 'POLICY_RECOMMENDATION'],
  },
  {
    key: 'stampede',
    label: 'Stampede',
    actions: ['REFRESH_STARTED', 'REFRESH_COALESCED', 'REFRESH_FAILED', 'SOURCE_VALIDATED'],
  },
  { key: 'other', label: 'Other', actions: ['REMOVE', 'CLEAR', 'CLEANUP'] },
];

/** Flatten selected chips into the comma-separated `actions` query value (deduplicated, stable order). */
export function actionsForGroups(selected: readonly EventGroupKey[]): CacheAction[] {
  const out: CacheAction[] = [];
  for (const g of EVENT_GROUPS) {
    if (selected.includes(g.key)) {
      for (const a of g.actions) if (!out.includes(a)) out.push(a);
    }
  }
  return out;
}

const EVICTION_ACTIONS: readonly CacheAction[] = ['EVICTED', 'MEMORY_EVICTED', 'ENTRY_LIMIT_EVICTED'];

export function isEvictionAction(action: CacheAction): boolean {
  return EVICTION_ACTIONS.includes(action);
}

export function actionTone(action: CacheAction): Tone {
  switch (action) {
    case 'HIT':
    case 'VICTIM_HIT':
    case 'SOURCE_VALIDATED':
      return 'good';
    case 'MISS':
    case 'EXPIRED':
    case 'STALE_SERVED':
    case 'REFRESH_COALESCED':
      return 'warning';
    case 'PUT':
    case 'POLICY_CHANGED':
    case 'CONFIG_CHANGED':
    case 'POLICY_RECOMMENDATION':
    case 'REFRESH_STARTED':
      return 'info';
    case 'EVICTED':
    case 'MEMORY_EVICTED':
    case 'ENTRY_LIMIT_EVICTED':
    case 'REFRESH_FAILED':
      return 'serious';
    default:
      return 'neutral';
  }
}

/** Memory released by an event (bytes) — only meaningful when memory actually dropped. */
export function memoryReleasedBytes(
  event: Pick<CacheEvent, 'memoryBeforeBytes' | 'memoryAfterBytes' | 'action'>,
): number | null {
  if (!isEvictionAction(event.action) && event.action !== 'EXPIRED') return null;
  const before = event.memoryBeforeBytes;
  const after = event.memoryAfterBytes;
  if (typeof before !== 'number' || typeof after !== 'number') return null;
  const released = before - after;
  return released > 0 ? released : null;
}
