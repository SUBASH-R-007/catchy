import type { ReplayResult } from '../../api/rest';
import { PolicyBadge } from '../../components';
import { formatInteger, formatPercent } from '../../lib/format';
import { replayBars } from './replay';

/** Hits, misses, evictions and hit rate per replayed policy, plus the optimal hit rate. */
export function ResultsTable({ result }: { result: ReplayResult }) {
  const order = replayBars(result).map((b) => b.kind);
  const rows = [...result.results].sort(
    (a, b) => order.indexOf(a.policy) - order.indexOf(b.policy),
  );
  const num = 'py-1 text-right font-mono tabular-nums';
  return (
    <div className="overflow-x-auto">
      <table className="w-full text-left text-sm">
        <caption className="sr-only">
          Replay results per policy: hit rate, hits, misses and evictions
        </caption>
        <thead className="text-muted">
          <tr>
            <th scope="col" className="py-1 font-normal">
              Policy
            </th>
            <th scope="col" className="py-1 text-right font-normal">
              Hit rate
            </th>
            <th scope="col" className="py-1 text-right font-normal">
              Hits
            </th>
            <th scope="col" className="py-1 text-right font-normal">
              Misses
            </th>
            <th scope="col" className="py-1 text-right font-normal">
              Evictions
            </th>
          </tr>
        </thead>
        <tbody className="text-text">
          {rows.map((r) => (
            <tr key={r.policy} className="border-t border-trace/60">
              <th scope="row" className="py-1 font-normal">
                <PolicyBadge policy={r.policy} className="py-0.5" />
              </th>
              <td className={num}>{formatPercent(r.hitRate)}</td>
              <td className={num}>{formatInteger(r.hits)}</td>
              <td className={num}>{formatInteger(r.misses)}</td>
              <td className={num}>{formatInteger(r.evictions)}</td>
            </tr>
          ))}
          <tr className="border-t border-trace/60">
            <th scope="row" className="py-1 font-normal">
              <PolicyBadge policy="OPTIMAL" className="py-0.5" />
              <span className="ml-2 text-muted">(Bélády)</span>
            </th>
            <td className={num}>{formatPercent(result.optimalHitRate)}</td>
            <td className={`${num} text-muted`} colSpan={3}>
              upper bound, not a deployable policy
            </td>
          </tr>
        </tbody>
      </table>
    </div>
  );
}
