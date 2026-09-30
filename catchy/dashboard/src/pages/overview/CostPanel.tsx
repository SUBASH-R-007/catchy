import { useId, useState } from 'react';
import type { CacheMetrics } from '../../api/types';
import { MetricTile } from '../../components';
import { formatCompact, formatDurationMs, formatInteger, formatMoney } from '../../lib/format';
import {
  estimateCostSaved,
  MAX_PRICE_PER_1000,
  parsePrice,
  readStoredPrice,
  writeStoredPrice,
} from './cost';

export interface CostPanelProps {
  cache: CacheMetrics;
}

/**
 * Savings for one cache (SPEC 9.3, 10.5): database calls avoided, latency saved and the estimated
 * cost saved at an editable, remembered price per 1,000 calls.
 */
export function CostPanel({ cache }: CostPanelProps) {
  const id = useId();
  const [price, setPrice] = useState(readStoredPrice);
  const [text, setText] = useState(() => String(price));
  const parsed = parsePrice(text);

  const onChange = (value: string) => {
    setText(value);
    const next = parsePrice(value);
    if (next.ok) {
      setPrice(next.value);
      writeStoredPrice(next.value);
    }
  };

  const cost = estimateCostSaved(cache.dbCallsAvoided, price);

  return (
    <div className="flex flex-col gap-4">
      <div className="grid gap-4 sm:grid-cols-3">
        <MetricTile
          label="DB calls avoided"
          value={formatCompact(cache.dbCallsAvoided)}
          sublabel={`${formatInteger(cache.dbCallsAvoided)} hits`}
          accent="var(--good)"
          info="Each cache hit is a lookup the database never had to answer."
        />
        <MetricTile
          label="Latency saved"
          value={formatDurationMs(cache.latencySavedMs)}
          sublabel="at 12.5 ms per call"
          info="Database waiting time avoided, assuming the simulated 12.5 ms average per call."
        />
        <MetricTile
          label="Cost saved"
          value={formatMoney(cost)}
          sublabel={`at ${formatMoney(price)} / 1,000 calls`}
          accent="var(--warn)"
          info="Calls avoided divided by 1,000, times the price per 1,000 calls you set below."
        />
      </div>
      <div className="flex flex-wrap items-end gap-4">
        <div>
          <label htmlFor={`${id}-price`} className="mb-1 block text-sm text-muted">
            Price per 1,000 DB calls ($)
          </label>
          <input
            id={`${id}-price`}
            type="number"
            inputMode="decimal"
            min={0}
            max={MAX_PRICE_PER_1000}
            step={0.01}
            value={text}
            onChange={(e) => onChange(e.target.value)}
            aria-invalid={!parsed.ok}
            aria-describedby={!parsed.ok ? `${id}-price-error` : `${id}-price-hint`}
            className="w-40 rounded-md border border-trace bg-surface-2 px-3 py-2 font-mono text-sm text-text hover:border-trace-glow aria-invalid:border-critical"
          />
        </div>
        {parsed.ok ? (
          <p id={`${id}-price-hint`} className="pb-2 text-sm text-muted">
            Remembered in this browser. Cost is recalculated as you type.
          </p>
        ) : (
          <p id={`${id}-price-error`} className="pb-2 text-sm text-critical">
            {parsed.error} Still using {formatMoney(price)}.
          </p>
        )}
      </div>
    </div>
  );
}
