import { useEffect, useRef, useState } from 'react';
import { useNow } from '../hooks/useNow';
import { formatSecondsAgo } from '../lib/format';

interface LiveIndicatorProps {
  /** Epoch ms of the last successful poll (null before the first one). */
  updatedAt: number | null;
  error?: Error | null;
  paused?: boolean;
  loading?: boolean;
}

type LiveState = 'connecting' | 'live' | 'error' | 'paused';

/**
 * "Live • updated 2 s ago". The visible text ticks every second; only *state changes*
 * (live / paused / connection problem) are announced through the polite live region.
 */
export function LiveIndicator({ updatedAt, error, paused, loading }: LiveIndicatorProps) {
  const now = useNow(1000);
  const state: LiveState = paused ? 'paused' : error ? 'error' : updatedAt === null || loading ? 'connecting' : 'live';
  const seconds = updatedAt === null ? null : Math.max(0, Math.round((now - updatedAt) / 1000));

  const [announcement, setAnnouncement] = useState('');
  const previous = useRef<LiveState | null>(null);
  useEffect(() => {
    if (previous.current !== state) {
      previous.current = state;
      setAnnouncement(
        state === 'live'
          ? 'Live updates active'
          : state === 'paused'
            ? 'Live updates paused while the tab is hidden'
            : state === 'error'
              ? 'Connection problem: showing the last data received'
              : 'Connecting to live updates',
      );
    }
  }, [state]);

  const label =
    state === 'live'
      ? `Live • updated ${seconds === null ? 'just now' : formatSecondsAgo(seconds)}`
      : state === 'paused'
        ? 'Paused • tab hidden'
        : state === 'error'
          ? `Connection problem${updatedAt !== null ? ` • last update ${formatSecondsAgo(seconds)}` : ''}`
          : 'Connecting…';

  return (
    <span className={`live live--${state}`}>
      <span className="live__dot" aria-hidden="true" />
      <span className="live__text">{label}</span>
      <span className="sr-only" aria-live="polite" role="status">
        {announcement}
      </span>
    </span>
  );
}
