import { Clock, LogOut, Replace, Trash2, type LucideIcon } from 'lucide-react';
import type { PolicyType, RemovalCause } from '../../api/types';
import { PolicyBadge } from '../../components';
import { formatClock } from '../../lib/format';
import type { LoggedRemoval } from './race';

interface CauseStyle {
  label: string;
  icon: LucideIcon;
  /** Tailwind text colour class; the icon and word carry the meaning too. */
  tone: string;
}

const CAUSE_STYLE: Record<RemovalCause, CauseStyle> = {
  EVICTED: { label: 'Evicted', icon: LogOut, tone: 'text-warn' },
  EXPIRED: { label: 'Expired', icon: Clock, tone: 'text-trace-glow' },
  EXPLICIT: { label: 'Deleted', icon: Trash2, tone: 'text-critical' },
  REPLACED: { label: 'Replaced', icon: Replace, tone: 'text-pad' },
};

/** "14:03:21.047": removals often share a second, so the log shows milliseconds too. */
function formatEventTime(ts: number): string {
  const ms = ((ts % 1000) + 1000) % 1000;
  return `${formatClock(ts)}.${String(ms).padStart(3, '0')}`;
}

export interface EventLogProps {
  events: readonly LoggedRemoval[];
  /** Policy per cache name, for the badge next to each cache. */
  policies: ReadonlyMap<string, PolicyType>;
}

/** The last removals of the group's caches, newest first (SPEC 10.5, Policy Race). */
export function EventLog({ events, policies }: EventLogProps) {
  return (
    <div className="max-h-[480px] overflow-auto">
      <table className="w-full table-fixed text-left text-sm">
        <caption className="sr-only">
          The {events.length} most recent removals, newest first: time, key, cause and cache
        </caption>
        <thead className="sticky top-0 bg-surface text-muted">
          <tr>
            <th scope="col" className="w-32 py-1 font-normal">
              Time
            </th>
            <th scope="col" className="py-1 font-normal">
              Key
            </th>
            <th scope="col" className="w-32 py-1 font-normal">
              Cause
            </th>
            <th scope="col" className="w-48 py-1 font-normal">
              Cache
            </th>
          </tr>
        </thead>
        <tbody>
          {events.map((e) => {
            const cause = CAUSE_STYLE[e.cause];
            const Icon = cause.icon;
            const policy = policies.get(e.cache);
            return (
              <tr key={e.id} className="border-t border-trace/60">
                <td className="py-1 font-mono text-muted tabular-nums">{formatEventTime(e.ts)}</td>
                <td className="truncate py-1 font-mono text-text" title={e.key}>
                  {e.key}
                </td>
                <td className={`py-1 ${cause.tone}`}>
                  <span className="inline-flex items-center gap-1">
                    <Icon aria-hidden="true" size={14} className="shrink-0" />
                    {cause.label}
                  </span>
                </td>
                <td className="py-1">
                  <span className="inline-flex min-w-0 items-center gap-2">
                    {policy ? <PolicyBadge policy={policy} className="py-0.5" /> : null}
                    <span className="truncate font-mono text-text">{e.cache}</span>
                  </span>
                </td>
              </tr>
            );
          })}
        </tbody>
      </table>
    </div>
  );
}
