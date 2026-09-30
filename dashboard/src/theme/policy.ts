import type { PolicyType } from '../api/types';

/** A chart series: one of the eviction policies, or the Bélády optimal line. */
export type SeriesKind = PolicyType | 'OPTIMAL';

export interface SeriesStyle {
  /** Human label, e.g. "LFU decay". */
  label: string;
  /** CSS colour (a var() reference to the shared Okabe-Ito tokens in index.css). */
  color: string;
  /** Raw hex, for places that cannot resolve CSS variables. */
  hex: string;
  /** SVG stroke-dasharray; undefined means a solid line. */
  dash: string | undefined;
  /** Plain-language line style, for legends and screen readers. */
  lineStyle: 'solid' | 'dashed' | 'dotted' | 'long dash';
}

/** Policy colours and line styles (SPEC 10.1): colour is never the only cue. */
export const SERIES_STYLE: Record<SeriesKind, SeriesStyle> = {
  LRU: {
    label: 'LRU',
    color: 'var(--policy-lru)',
    hex: '#56B4E9',
    dash: undefined,
    lineStyle: 'solid',
  },
  LFU: {
    label: 'LFU',
    color: 'var(--policy-lfu)',
    hex: '#E69F00',
    dash: '6 4',
    lineStyle: 'dashed',
  },
  LFU_DECAY: {
    label: 'LFU decay',
    color: 'var(--policy-lfu-decay)',
    hex: '#009E73',
    dash: '1.5 3',
    lineStyle: 'dotted',
  },
  OPTIMAL: {
    label: 'Optimal',
    color: 'var(--policy-optimal)',
    hex: '#9CA3AF',
    dash: '12 6',
    lineStyle: 'long dash',
  },
};
