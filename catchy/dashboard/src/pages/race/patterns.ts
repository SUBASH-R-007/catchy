import type { Pattern } from '../../api/types';

export interface PatternInfo {
  label: string;
  /** One plain-language sentence: what the traffic looks like. */
  description: string;
}

/** The eight workload patterns (SPEC 9.1), in picker order, with plain-language descriptions. */
export const PATTERN_INFO: Record<Pattern, PatternInfo> = {
  UNIFORM: {
    label: 'Uniform',
    description: 'Every key is equally likely, so no policy can beat chance.',
  },
  ZIPF: {
    label: 'Zipf',
    description: 'A few popular keys get most of the traffic, like real lookups.',
  },
  SCAN_POLLUTION: {
    label: 'Scan pollution',
    description: 'A one-off scan of cold keys floods the cache.',
  },
  LOOP: {
    label: 'Loop',
    description: 'Keys repeat in a cycle slightly larger than the cache.',
  },
  SHIFTING_HOTSPOT: {
    label: 'Shifting hotspot',
    description: 'The popular keys change every 20 seconds.',
  },
  TTL_BURST: {
    label: 'TTL burst',
    description: 'Popular keys, but 30% of writes expire after 2 seconds.',
  },
  FORMULARY: {
    label: 'Formulary',
    description: 'Pharmacy drug lookups with a morning rush on common drugs.',
  },
  PROVIDER_DIRECTORY: {
    label: 'Provider directory',
    description: 'Doctor-directory lookups whose busy region moves every 30 seconds.',
  },
};
