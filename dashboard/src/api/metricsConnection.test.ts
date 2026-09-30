import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { snapshot } from './fixtures';
import { MetricsConnection, STALE_AFTER_MS, type EventSourceLike } from './metricsConnection';
import type { ConnectionState, MetricsSnapshot } from './types';

class FakeEventSource implements EventSourceLike {
  onopen: ((ev: Event) => unknown) | null = null;
  onerror: ((ev: Event) => unknown) | null = null;
  closed = false;
  private listeners: ((ev: MessageEvent) => void)[] = [];

  addEventListener(type: string, listener: (ev: MessageEvent) => void): void {
    if (type === 'metrics') this.listeners.push(listener);
  }
  close(): void {
    this.closed = true;
  }
  open(): void {
    this.onopen?.(new Event('open'));
  }
  fail(): void {
    this.onerror?.(new Event('error'));
  }
  emit(data: string): void {
    this.listeners.forEach((l) => l(new MessageEvent('metrics', { data })));
  }
}

describe('MetricsConnection', () => {
  let sources: FakeEventSource[];
  let states: ConnectionState[];
  let received: MetricsSnapshot[];
  let invalid: number;
  let connection: MetricsConnection;

  const current = () => sources[sources.length - 1] as FakeEventSource;

  beforeEach(() => {
    vi.useFakeTimers();
    sources = [];
    states = [];
    received = [];
    invalid = 0;
    connection = new MetricsConnection(
      '/api/metrics/stream',
      () => {
        const es = new FakeEventSource();
        sources.push(es);
        return es;
      },
      {
        onState: (s) => states.push(s),
        onSnapshot: (s) => received.push(s),
        onInvalid: () => (invalid += 1),
      },
    );
  });

  afterEach(() => {
    connection.stop();
    vi.useRealTimers();
  });

  it('goes connecting → live and delivers valid snapshots', () => {
    connection.start();
    current().open();
    current().emit(JSON.stringify(snapshot()));
    expect(states).toEqual(['connecting', 'live']);
    expect(received).toHaveLength(1);
  });

  it('drops malformed events without disconnecting', () => {
    connection.start();
    current().open();
    current().emit('{broken');
    current().emit(JSON.stringify({ ts: 1 }));
    expect(received).toHaveLength(0);
    expect(invalid).toBe(2);
    expect(current().closed).toBe(false);
  });

  it('reconnects with 1, 2, 4, 8, 8 s backoff and turns offline after 4 failures', () => {
    connection.start();
    const delays = [1000, 2000, 4000, 8000, 8000];
    for (const delay of delays) {
      const before = sources.length;
      current().fail();
      vi.advanceTimersByTime(delay - 1);
      expect(sources).toHaveLength(before);
      vi.advanceTimersByTime(1);
      expect(sources).toHaveLength(before + 1);
    }
    expect(states).toEqual([
      'connecting',
      'reconnecting',
      'reconnecting',
      'reconnecting',
      'offline',
      'offline',
    ]);
    expect(sources.slice(0, -1).every((s) => s.closed)).toBe(true);
  });

  it('turns live again and resets the backoff once the server is back', () => {
    connection.start();
    current().fail();
    vi.advanceTimersByTime(1000);
    current().open();
    expect(states.at(-1)).toBe('live');
    current().fail();
    expect(states.at(-1)).toBe('reconnecting');
    const before = sources.length;
    vi.advanceTimersByTime(1000);
    expect(sources).toHaveLength(before + 1);
  });

  it('treats a silent stream as failed (a proxy may keep a dead connection open)', () => {
    connection.start();
    current().open();
    expect(states.at(-1)).toBe('live');
    vi.advanceTimersByTime(STALE_AFTER_MS - 1);
    expect(states.at(-1)).toBe('live');
    vi.advanceTimersByTime(1);
    expect(states.at(-1)).toBe('reconnecting');
    expect(current().closed).toBe(true);
    const before = sources.length;
    vi.advanceTimersByTime(1000);
    expect(sources).toHaveLength(before + 1);
  });

  it('stays live while events keep arriving', () => {
    connection.start();
    current().open();
    for (let i = 0; i < 20; i++) {
      vi.advanceTimersByTime(500);
      current().emit(JSON.stringify(snapshot({ ts: 1790000000500 + i })));
    }
    expect(states).toEqual(['connecting', 'live']);
    expect(sources).toHaveLength(1);
  });

  it('does not run the watchdog after stop()', () => {
    connection.start();
    current().open();
    connection.stop();
    vi.advanceTimersByTime(60_000);
    expect(states).toEqual(['connecting', 'live']);
  });

  it('stops retrying after stop()', () => {
    connection.start();
    current().fail();
    connection.stop();
    vi.advanceTimersByTime(60_000);
    expect(sources).toHaveLength(1);
  });

  it('ignores events from a replaced source', () => {
    connection.start();
    const stale = current();
    stale.fail();
    vi.advanceTimersByTime(1000);
    stale.emit(JSON.stringify(snapshot()));
    stale.open();
    expect(received).toHaveLength(0);
    expect(states.at(-1)).toBe('reconnecting');
  });
});
