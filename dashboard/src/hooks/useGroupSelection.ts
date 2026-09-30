import { useCallback, useState } from 'react';
import type { CacheAction } from '../api/types';
import { actionsForGroups, type EventGroupKey } from '../lib/events';

/** Selected Eviction X-ray filter chips and the flattened `actions` list they translate to. */
export function useGroupSelection(): {
  selected: EventGroupKey[];
  actions: CacheAction[];
  actionsKey: string;
  toggle: (key: EventGroupKey) => void;
  clear: () => void;
} {
  const [selected, setSelected] = useState<EventGroupKey[]>([]);
  const toggle = useCallback(
    (key: EventGroupKey) => setSelected((s) => (s.includes(key) ? s.filter((k) => k !== key) : [...s, key])),
    [],
  );
  const clear = useCallback(() => setSelected([]), []);
  const actions = actionsForGroups(selected);
  return { selected, actions, actionsKey: actions.join(','), toggle, clear };
}
