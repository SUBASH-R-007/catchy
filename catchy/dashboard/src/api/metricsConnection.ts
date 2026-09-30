import { parseMetricsSnapshot } from './guard';
import type { ConnectionState, MetricsSnapshot } from './types';

/** The subset of the browser EventSource that the connection uses (lets tests supply a fake). */
export interface EventSourceLike {
  onopen: ((ev: Event) => unknown) | null;
  onerror: ((ev: Event) => unknown) | null;
  addEventListener(type: string, listener: (ev: MessageEvent) => void): void;
  close(): void;
}

export type EventSourceFactory = (url: string) => EventSourceLike;

/** Reconnect delays: 1, 2, 4, then capped at 8 s (SPEC 10.4). */
export const BACKOFF_MS: readonly number[] = [1000, 2000, 4000, 8000];

/** After this many consecutive failures the LED turns red; retries continue every 8 s. */
export const OFFLINE_AFTER_FAILURES = 4;

/**
 * The server sends an event every 500 ms. An open stream that stays silent this long is treated as
 * dead: a proxy can keep the browser's connection open after the server behind it has gone.
 */
export const STALE_AFTER_MS = 3000;

export interface ConnectionCallbacks {
  onState: (state: ConnectionState) => void;
  onSnapshot: (snapshot: MetricsSnapshot) => void;
  /** Called for each dropped event (invalid JSON or wrong shape). */
  onInvalid?: () => void;
}

/**
 * Owns one EventSource on the metrics stream and replaces it with exponential backoff when it
 * fails or goes silent. The browser's built-in retry is bypassed (we close on error) so the backoff
 * and the connection state are ours to control.
 */
export class MetricsConnection {
  private source: EventSourceLike | null = null;
  private retryTimer: ReturnType<typeof setTimeout> | null = null;
  private staleTimer: ReturnType<typeof setTimeout> | null = null;
  private failures = 0;
  private stopped = true;

  constructor(
    private readonly url: string,
    private readonly factory: EventSourceFactory,
    private readonly callbacks: ConnectionCallbacks,
  ) {}

  start(): void {
    if (!this.stopped) return;
    this.stopped = false;
    this.failures = 0;
    this.callbacks.onState('connecting');
    this.open();
  }

  stop(): void {
    this.stopped = true;
    this.clearTimers();
    this.source?.close();
    this.source = null;
  }

  private open(): void {
    const es = this.factory(this.url);
    this.source = es;
    es.onopen = () => {
      if (this.source !== es) return;
      this.failures = 0;
      this.callbacks.onState('live');
      this.armWatchdog(es);
    };
    es.addEventListener('metrics', (ev) => {
      if (this.source !== es) return;
      this.armWatchdog(es);
      const snapshot = typeof ev.data === 'string' ? parseMetricsSnapshot(ev.data) : null;
      if (snapshot) this.callbacks.onSnapshot(snapshot);
      else this.callbacks.onInvalid?.();
    });
    es.onerror = () => this.fail(es);
  }

  /** Restarts the silence timer; firing it counts as a connection failure. */
  private armWatchdog(es: EventSourceLike): void {
    if (this.staleTimer !== null) clearTimeout(this.staleTimer);
    this.staleTimer = setTimeout(() => this.fail(es), STALE_AFTER_MS);
  }

  private fail(es: EventSourceLike): void {
    es.close();
    if (this.source !== es || this.stopped) return;
    this.source = null;
    this.clearTimers();
    this.failures += 1;
    this.callbacks.onState(this.failures >= OFFLINE_AFTER_FAILURES ? 'offline' : 'reconnecting');
    const delay = BACKOFF_MS[Math.min(this.failures, BACKOFF_MS.length) - 1] ?? 8000;
    this.retryTimer = setTimeout(() => {
      this.retryTimer = null;
      if (!this.stopped) this.open();
    }, delay);
  }

  private clearTimers(): void {
    if (this.retryTimer !== null) clearTimeout(this.retryTimer);
    if (this.staleTimer !== null) clearTimeout(this.staleTimer);
    this.retryTimer = null;
    this.staleTimer = null;
  }
}
