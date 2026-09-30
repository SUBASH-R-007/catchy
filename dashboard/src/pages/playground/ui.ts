import type { DipSwitchOption } from '../../components';
import type { PolicyType } from '../../api/types';
import { POLICY_TYPES } from '../../api/types';
import { SERIES_STYLE } from '../../theme/policy';

/** Shared class names and options for the Playground forms. */

export const inputClass =
  'w-full rounded-md border border-trace bg-surface-2 px-3 py-2 font-mono text-sm text-text placeholder:text-muted hover:border-trace-glow aria-invalid:border-critical';

export const labelClass = 'mb-1 block text-sm text-muted';

export const buttonClass =
  'inline-flex items-center justify-center gap-2 rounded border border-trace bg-surface-2 px-4 py-2 text-sm text-text transition-colors hover:border-trace-glow hover:text-trace-glow disabled:cursor-not-allowed disabled:opacity-50 disabled:hover:border-trace disabled:hover:text-text';

export const primaryButtonClass =
  'inline-flex items-center justify-center gap-2 rounded border border-trace-glow bg-surface-2 px-4 py-2 text-sm text-trace-glow transition-colors hover:bg-trace/40 disabled:cursor-not-allowed disabled:opacity-50';

export const dangerButtonClass =
  'inline-flex items-center justify-center gap-2 rounded border border-critical/60 bg-surface-2 px-4 py-2 text-sm text-critical transition-colors hover:border-critical disabled:cursor-not-allowed disabled:opacity-50';

export const fieldErrorClass = 'mt-1 text-sm text-critical';

/** LRU / LFU / LFU decay DIP switch options in the policy colours. */
export const POLICY_OPTIONS: ReadonlyArray<DipSwitchOption<PolicyType>> = POLICY_TYPES.map((p) => ({
  value: p,
  label: SERIES_STYLE[p].label,
  color: SERIES_STYLE[p].color,
}));

export function errorMessage(e: unknown): string {
  return e instanceof Error ? e.message : String(e);
}
