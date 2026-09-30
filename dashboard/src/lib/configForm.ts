import type { RegionConfig, UpdateRegionConfigRequest } from '../api/types';
import { CONFIG_BOUNDS } from '../api/types';
import { bytesToMegabytes, formatInt, megabytesToBytes } from './format';

/** Form state for the Configuration page: memory in MB and TTL in seconds (human units). */
export interface ConfigFormValues {
  maximumEntries: string;
  maximumMemoryMb: string;
  defaultTtlSeconds: string;
  reason: string;
}

export interface ConfigFormErrors {
  maximumEntries?: string;
  maximumMemoryMb?: string;
  defaultTtlSeconds?: string;
  reason?: string;
  form?: string;
}

function trimNumber(n: number, digits = 3): string {
  return String(Number(n.toFixed(digits)));
}

/** Seed the form from what the SDK reported (or the admin's pending desired value when there is one). */
export function initialFormValues(config: RegionConfig): ConfigFormValues {
  const d = config.desired;
  const r = config.reported;
  const entries = d?.maximumEntries ?? r.maximumEntries;
  const memory = d?.maximumMemoryBytes ?? r.maximumMemoryBytes;
  const ttl = d?.defaultTtlMs ?? r.defaultTtlMs;
  return {
    maximumEntries: entries != null ? String(entries) : '',
    maximumMemoryMb: memory != null ? trimNumber(bytesToMegabytes(memory)) : '',
    defaultTtlSeconds: ttl != null ? trimNumber(ttl / 1000) : '',
    reason: '',
  };
}

export const MAX_REASON_LENGTH = 200;

export interface ConfigValidation {
  errors: ConfigFormErrors;
  /** Request body when valid (only fields that differ from `baseline` are sent). */
  request: UpdateRegionConfigRequest | null;
}

/**
 * Validate against the contract bounds: maximumEntries 1–10,000,000, maximumMemoryBytes ≥ 1024,
 * defaultTtlMs ≥ 1000. Only changed fields are sent; at least one must change.
 */
export function validateConfigForm(values: ConfigFormValues, baseline: ConfigFormValues): ConfigValidation {
  const errors: ConfigFormErrors = {};
  const request: UpdateRegionConfigRequest = {};

  const entriesText = values.maximumEntries.trim();
  if (entriesText !== baseline.maximumEntries.trim()) {
    const n = Number(entriesText);
    const { min, max } = CONFIG_BOUNDS.maximumEntries;
    if (entriesText === '' || !Number.isInteger(n)) errors.maximumEntries = 'Enter a whole number of entries.';
    else if (n < min || n > max) errors.maximumEntries = `Must be between ${formatInt(min)} and ${formatInt(max)}.`;
    else request.maximumEntries = n;
  }

  const memoryText = values.maximumMemoryMb.trim();
  if (memoryText !== baseline.maximumMemoryMb.trim()) {
    const mb = Number(memoryText);
    if (memoryText === '' || !Number.isFinite(mb)) errors.maximumMemoryMb = 'Enter the limit in megabytes (decimals allowed).';
    else {
      const bytes = megabytesToBytes(mb);
      if (bytes < CONFIG_BOUNDS.maximumMemoryBytes.min) {
        errors.maximumMemoryMb = `Must be at least ${CONFIG_BOUNDS.maximumMemoryBytes.min} bytes (about 0.001 MB).`;
      } else request.maximumMemoryBytes = bytes;
    }
  }

  const ttlText = values.defaultTtlSeconds.trim();
  if (ttlText !== baseline.defaultTtlSeconds.trim()) {
    const seconds = Number(ttlText);
    if (ttlText === '' || !Number.isFinite(seconds)) errors.defaultTtlSeconds = 'Enter the TTL in seconds.';
    else {
      const ms = Math.round(seconds * 1000);
      if (ms < CONFIG_BOUNDS.defaultTtlMs.min) errors.defaultTtlSeconds = 'Must be at least 1 second.';
      else request.defaultTtlMs = ms;
    }
  }

  const reason = values.reason.trim();
  if (reason.length > MAX_REASON_LENGTH) errors.reason = `Keep the reason under ${MAX_REASON_LENGTH} characters.`;
  else if (reason) request.reason = reason;

  const changed = request.maximumEntries !== undefined || request.maximumMemoryBytes !== undefined || request.defaultTtlMs !== undefined;
  if (Object.keys(errors).length === 0 && !changed) errors.form = 'Change at least one value before saving.';
  return { errors, request: Object.keys(errors).length === 0 ? request : null };
}
