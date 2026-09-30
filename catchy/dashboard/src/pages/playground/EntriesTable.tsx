import { useEffect, useState } from 'react';
import type { EntryView } from '../../api/rest';
import type { PolicyType } from '../../api/types';
import { cx } from '../../components/cx';
import { formatInteger } from '../../lib/format';
import { formatTtl, ttlRemainingAt } from './commands';

/** How often the TTL countdown re-renders between polls. */
const COUNTDOWN_TICK_MS = 100;

export interface EntriesTableProps {
  entries: readonly EntryView[];
  /** Date.now() when the entries were fetched; countdowns interpolate from here. */
  fetchedAt: number;
  policy: PolicyType;
}

/**
 * Live entries in policy order with a smooth TTL countdown between polls. A row whose countdown
 * reaches 0 shows "expired" until the next poll drops it. Frequency is not tracked under LRU.
 */
export function EntriesTable({ entries, fetchedAt, policy }: EntriesTableProps) {
  const [now, setNow] = useState(() => Date.now());
  const counting = entries.some((e) => e.ttlRemainingMs !== null);

  useEffect(() => {
    if (!counting) return;
    const timer = window.setInterval(() => setNow(Date.now()), COUNTDOWN_TICK_MS);
    return () => window.clearInterval(timer);
  }, [counting]);

  const lru = policy === 'LRU';
  // Before the first tick after a poll, never show a countdown longer than the server's value.
  const at = Math.max(now, fetchedAt);

  return (
    <div className="max-h-[420px] overflow-auto">
      <table className="w-full table-fixed text-left text-sm">
        <caption className="sr-only">
          Entries in the policy&apos;s order, with frequency and time left to live
        </caption>
        <thead className="sticky top-0 bg-surface text-muted">
          <tr>
            <th scope="col" className="py-1 font-normal">
              Key
            </th>
            <th scope="col" className="w-28 py-1 text-right font-normal">
              Frequency
            </th>
            <th scope="col" className="w-32 py-1 text-right font-normal">
              TTL left
            </th>
          </tr>
        </thead>
        <tbody className="font-mono tabular-nums">
          {entries.map((e) => {
            const remaining = ttlRemainingAt(e.ttlRemainingMs, fetchedAt, at);
            const expired = remaining === 0;
            return (
              <tr key={e.key} className={cx('border-t border-trace/60', expired && 'opacity-60')}>
                <td className="truncate py-1 text-text" title={e.key}>
                  {e.key}
                </td>
                <td className="py-1 text-right text-text">
                  {lru ? (
                    <>
                      <span aria-hidden="true" className="text-muted">
                        —
                      </span>
                      <span className="sr-only">not tracked under LRU</span>
                    </>
                  ) : (
                    formatInteger(e.frequency)
                  )}
                </td>
                <td
                  className={cx(
                    'py-1 text-right',
                    remaining === null ? 'text-muted' : expired ? 'text-warn' : 'text-text',
                  )}
                >
                  {formatTtl(remaining)}
                </td>
              </tr>
            );
          })}
        </tbody>
      </table>
    </div>
  );
}
