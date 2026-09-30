import { RotateCcw, Trash2 } from 'lucide-react';
import { useEffect, useId, useRef, useState, type KeyboardEvent } from 'react';
import { api } from '../../api/client';
import type { CacheConfig, CacheInfo } from '../../api/rest';
import type { PolicyType } from '../../api/types';
import { DipSwitch, MetricTile, useToast } from '../../components';
import { formatCompact, formatDurationMs, formatInteger, formatPercent } from '../../lib/format';
import { SERIES_STYLE } from '../../theme/policy';
import {
  buttonClass,
  dangerButtonClass,
  errorMessage,
  inputClass,
  labelClass,
  POLICY_OPTIONS,
} from './ui';

export interface CachePanelProps {
  caches: readonly CacheInfo[];
  selected: CacheInfo;
  onSelect: (name: string) => void;
  /** Called after a successful policy switch with the server's new configuration. */
  onPolicySwitched: (config: CacheConfig) => void;
  /** Called after reset-stats or delete, so the page can refresh its data. */
  onChanged: () => void;
}

/**
 * Cache selector, live stats strip, live policy switch, reset stats and delete (with an inline
 * confirmation step instead of window.confirm).
 */
export function CachePanel({
  caches,
  selected,
  onSelect,
  onPolicySwitched,
  onChanged,
}: CachePanelProps) {
  const toast = useToast();
  const id = useId();
  const [pending, setPending] = useState<{ name: string; policy: PolicyType } | null>(null);
  const [confirming, setConfirming] = useState(false);
  const [busy, setBusy] = useState(false);
  const cancelRef = useRef<HTMLButtonElement>(null);
  const deleteRef = useRef<HTMLButtonElement>(null);
  const wasConfirming = useRef(false);

  useEffect(() => {
    if (confirming) cancelRef.current?.focus();
    else if (wasConfirming.current) deleteRef.current?.focus();
    wasConfirming.current = confirming;
  }, [confirming]);

  const switchPolicy = async (policy: PolicyType) => {
    if (pending) return; // one switch at a time
    setPending({ name: selected.name, policy });
    try {
      const config = await api.switchPolicy(selected.name, policy);
      toast.show(`"${config.name}" now uses ${SERIES_STYLE[config.policy].label}.`, 'success');
      onPolicySwitched(config);
    } catch (e) {
      toast.show(errorMessage(e), 'error');
    } finally {
      // Clearing the pending value snaps the switch back to the server's policy on error.
      setPending(null);
    }
  };

  const resetStats = async () => {
    setBusy(true);
    try {
      await api.resetStats(selected.name);
      toast.show(`Stats reset for "${selected.name}".`, 'success');
      onChanged();
    } catch (e) {
      toast.show(errorMessage(e), 'error');
    } finally {
      setBusy(false);
    }
  };

  const deleteCache = async () => {
    setBusy(true);
    try {
      await api.deleteCache(selected.name);
      toast.show(`Deleted cache "${selected.name}".`, 'success');
      wasConfirming.current = false;
      setConfirming(false);
      onChanged();
    } catch (e) {
      toast.show(errorMessage(e), 'error');
    } finally {
      setBusy(false);
    }
  };

  const onConfirmKeyDown = (e: KeyboardEvent<HTMLButtonElement>) => {
    if (e.key === 'Escape') {
      e.stopPropagation();
      setConfirming(false);
    }
  };

  const ttlText =
    selected.defaultTtlMs === null ? 'never' : formatDurationMs(selected.defaultTtlMs);

  return (
    <div className="flex flex-col gap-4">
      <div className="flex flex-wrap items-end gap-4">
        <div className="min-w-0 flex-1 basis-64">
          <label htmlFor={`${id}-cache`} className={labelClass}>
            Cache
          </label>
          <select
            id={`${id}-cache`}
            className={inputClass}
            value={selected.name}
            onChange={(e) => {
              setConfirming(false);
              onSelect(e.target.value);
            }}
          >
            {caches.map((c) => (
              <option key={c.name} value={c.name}>
                {c.name} — {c.group} · {SERIES_STYLE[c.policy].label} · {formatInteger(c.size)}/
                {formatInteger(c.capacity)}
              </option>
            ))}
          </select>
        </div>
        <DipSwitch
          label="Policy (live switch)"
          options={POLICY_OPTIONS}
          value={pending?.name === selected.name ? pending.policy : selected.policy}
          onChange={(p) => void switchPolicy(p)}
        />
      </div>

      <p className="text-sm text-muted">
        Group <span className="font-mono text-text">{selected.group}</span> · size{' '}
        <span className="font-mono text-text tabular-nums">
          {formatInteger(selected.size)} of {formatInteger(selected.capacity)}
        </span>{' '}
        · default TTL <span className="font-mono text-text">{ttlText}</span>. Switching the policy
        keeps every entry and TTL.
      </p>

      <div className="grid grid-cols-2 gap-4 md:grid-cols-3 2xl:grid-cols-5">
        <MetricTile
          label="Hits"
          value={formatCompact(selected.hits)}
          accent="var(--good)"
          info="Lookups that found a live value in the cache, since creation or the last stats reset."
        />
        <MetricTile
          label="Misses"
          value={formatCompact(selected.misses)}
          accent="var(--warn)"
          info="Lookups that found nothing (the key was never stored, was evicted, or expired)."
        />
        <MetricTile
          label="Hit rate"
          value={formatPercent(selected.hitRate)}
          info="Hits divided by all lookups since creation or the last stats reset."
        />
        <MetricTile
          label="Evictions"
          value={formatCompact(selected.evictions)}
          info="Entries the policy removed to make room because the cache was full."
        />
        <MetricTile
          label="Expirations"
          value={formatCompact(selected.expirations)}
          info="Entries removed because their time-to-live ran out."
        />
      </div>

      <div className="flex flex-wrap items-center gap-4">
        <button
          type="button"
          className={buttonClass}
          onClick={() => void resetStats()}
          disabled={busy}
        >
          <RotateCcw aria-hidden="true" size={16} />
          Reset stats
        </button>
        {confirming ? (
          <div
            role="group"
            aria-label={`Confirm deleting ${selected.name}`}
            className="flex flex-wrap items-center gap-2 rounded border border-critical/60 px-3 py-2"
          >
            <p className="text-sm text-text">
              Delete <span className="font-mono">{selected.name}</span> and discard its entries?
            </p>
            <button
              type="button"
              className={dangerButtonClass}
              onClick={() => void deleteCache()}
              onKeyDown={onConfirmKeyDown}
              disabled={busy}
            >
              Yes, delete
            </button>
            <button
              ref={cancelRef}
              type="button"
              className={buttonClass}
              onClick={() => setConfirming(false)}
              onKeyDown={onConfirmKeyDown}
            >
              Cancel
            </button>
          </div>
        ) : (
          <button
            ref={deleteRef}
            type="button"
            className={dangerButtonClass}
            onClick={() => setConfirming(true)}
            disabled={busy}
          >
            <Trash2 aria-hidden="true" size={16} />
            Delete cache
          </button>
        )}
      </div>
    </div>
  );
}
