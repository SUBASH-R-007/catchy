import { useEffect, useRef, useState } from 'react';
import { MetricsConnection } from './metricsConnection';
import { appendSnapshots, EMPTY_HISTORY, type StreamHistory } from './streamReducer';
import type { ConnectionState, MetricsSnapshot } from './types';

export const STREAM_URL = '/api/metrics/stream';

/** Buffered events are flushed into React state at most twice per second (SPEC 10.4). */
export const FLUSH_INTERVAL_MS = 500;

export interface MetricsStreamValue {
  state: ConnectionState;
  history: StreamHistory;
}

/**
 * Subscribes to the metrics SSE stream: tracks the connection state, keeps a ring of the last 120
 * valid snapshots and re-renders at most twice per second. Use it once, in MetricsProvider; read it
 * elsewhere with useMetrics().
 */
export function useMetricsStream(url: string = STREAM_URL): MetricsStreamValue {
  const [state, setState] = useState<ConnectionState>(() =>
    typeof EventSource === 'undefined' ? 'offline' : 'connecting',
  );
  const [history, setHistory] = useState<StreamHistory>(EMPTY_HISTORY);
  const buffer = useRef<MetricsSnapshot[]>([]);

  useEffect(() => {
    if (typeof EventSource === 'undefined') return undefined;
    const connection = new MetricsConnection(url, (u) => new EventSource(u), {
      onState: setState,
      onSnapshot: (snapshot) => buffer.current.push(snapshot),
    });
    connection.start();
    const flush = setInterval(() => {
      if (buffer.current.length === 0) return;
      const batch = buffer.current;
      buffer.current = [];
      setHistory((h) => appendSnapshots(h, batch));
    }, FLUSH_INTERVAL_MS);
    return () => {
      connection.stop();
      clearInterval(flush);
    };
  }, [url]);

  return { state, history };
}
