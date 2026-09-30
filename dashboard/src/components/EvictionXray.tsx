import type { ReactNode } from 'react';
import { Link } from 'react-router-dom';
import type { CacheEvent } from '../api/types';
import { EVENT_GROUPS, memoryReleasedBytes, type EventGroupKey } from '../lib/events';
import { formatBytes, formatClock, formatDuration, formatInt } from '../lib/format';
import { paths } from '../lib/routes';
import { ActionBadge, PolicyBadge, SeverityBadge } from './Badge';
import { EmptyState, ErrorState, LoadingBlock } from './States';

interface EvictionXrayProps {
  events: CacheEvent[] | undefined;
  loading?: boolean;
  error?: Error | null;
  refreshing?: boolean;
  onRetry?: () => void;
  selected: EventGroupKey[];
  onToggleGroup: (key: EventGroupKey) => void;
  onClearGroups: () => void;
  /** Application feed: show application + region on every row and link the region. */
  applicationId?: number;
  showRegion?: boolean;
}

function Meta({ label, children }: { label: string; children: ReactNode }) {
  return (
    <span className="xray-meta">
      <span className="xray-meta__label">{label}</span> <span className="xray-meta__value num">{children}</span>
    </span>
  );
}

function ttlText(ms: number | null | undefined, action: CacheEvent['action']): string | null {
  if (ms === null || ms === undefined) return null;
  if (ms <= 0) return action === 'EXPIRED' ? 'expired' : 'none';
  return formatDuration(ms);
}

export function EventRow({ event, applicationId, showRegion }: { event: CacheEvent; applicationId?: number; showRegion?: boolean }) {
  const released = memoryReleasedBytes(event);
  const ttl = ttlText(event.remainingTtlMs, event.action);
  const hasSizes = typeof event.cacheSizeBefore === 'number' && typeof event.cacheSizeAfter === 'number';
  const hasMemory = typeof event.memoryBeforeBytes === 'number' && typeof event.memoryAfterBytes === 'number';
  return (
    <li className={`xray-row xray-row--${event.severity.toLowerCase()}`}>
      <div className="xray-row__head">
        <time className="xray-time num" dateTime={event.timestamp}>
          {formatClock(event.timestamp)}
        </time>
        {showRegion ? (
          <span className="xray-where">
            <span>{event.applicationName}</span>
            <span className="faint"> · </span>
            {applicationId !== undefined ? (
              <Link to={paths.region(applicationId, event.cacheRegion)}>{event.cacheRegion}</Link>
            ) : (
              <span>{event.cacheRegion}</span>
            )}
          </span>
        ) : null}
        <ActionBadge action={event.action} />
        <PolicyBadge policy={event.policy} />
        <SeverityBadge severity={event.severity} />
      </div>
      {event.reason ? <p className="xray-reason">{event.reason}</p> : null}
      <div className="xray-row__meta">
        {typeof event.frequency === 'number' && event.frequency > 0 ? <Meta label="Frequency">{formatInt(event.frequency)}</Meta> : null}
        {ttl ? <Meta label="Remaining TTL">{ttl}</Meta> : null}
        {typeof event.estimatedEntrySizeBytes === 'number' && event.estimatedEntrySizeBytes > 0 ? (
          <Meta label="Entry size">{formatBytes(event.estimatedEntrySizeBytes)}</Meta>
        ) : null}
        {hasSizes ? (
          <Meta label="Cache size">
            {formatInt(event.cacheSizeBefore)} → {formatInt(event.cacheSizeAfter)}
          </Meta>
        ) : null}
        {hasMemory ? (
          <Meta label="Memory (est.)">
            {formatBytes(event.memoryBeforeBytes)} → {formatBytes(event.memoryAfterBytes)}
          </Meta>
        ) : null}
        {released !== null ? <Meta label="Memory released">{formatBytes(released)}</Meta> : null}
        {event.keyFingerprint ? (
          <Meta label="Key">
            <code className="fingerprint">{event.keyFingerprint}</code>
          </Meta>
        ) : (
          <Meta label="Key">
            <span className="faint">category only</span>
          </Meta>
        )}
      </div>
    </li>
  );
}

/**
 * Eviction X-ray: newest-first event timeline explaining every cache decision, with action filter chips.
 * Only safe metadata is ever shown — key fingerprints, sizes and counts; never raw keys or values.
 */
export function EvictionXray({
  events,
  loading,
  error,
  refreshing,
  onRetry,
  selected,
  onToggleGroup,
  onClearGroups,
  applicationId,
  showRegion = false,
}: EvictionXrayProps) {
  let body;
  if (error && !events) body = <ErrorState error={error} onRetry={onRetry} title="Could not load events" />;
  else if (loading || !events) body = <LoadingBlock label="Loading events" lines={5} height={26} />;
  else if (events.length === 0) {
    body = (
      <EmptyState title={selected.length > 0 ? 'No events match these filters' : 'No events yet'} icon="pulse">
        {selected.length > 0
          ? 'Clear the filters or widen the selection to see more cache decisions.'
          : 'Events appear as the SDK reports cache decisions. Start a demo service or run a simulation.'}
      </EmptyState>
    );
  } else {
    body = (
      <ol className={`xray-list${refreshing ? ' is-dimmed' : ''}`} aria-label="Cache events, newest first">
        {events.map((e) => (
          <EventRow key={e.id} event={e} applicationId={applicationId} showRegion={showRegion} />
        ))}
      </ol>
    );
  }

  return (
    <div className="xray">
      <div className="chips" role="group" aria-label="Filter events by action">
        <button type="button" className="chip" aria-pressed={selected.length === 0} onClick={onClearGroups}>
          All
        </button>
        {EVENT_GROUPS.map((g) => (
          <button key={g.key} type="button" className="chip" aria-pressed={selected.includes(g.key)} onClick={() => onToggleGroup(g.key)} title={g.actions.join(', ')}>
            {g.label}
          </button>
        ))}
        <span className="chips__count faint" aria-live="polite">
          {events ? `${events.length} event${events.length === 1 ? '' : 's'}` : ''}
        </span>
      </div>
      <p className="xray-note">Key fingerprints only — raw keys and cached values are never collected or displayed.</p>
      {body}
    </div>
  );
}
