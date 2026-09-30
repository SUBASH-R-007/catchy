import type { ConnectionState } from '../api/types';
import './components.css';
import { cx } from './cx';

const STATES: Record<ConnectionState, { text: string; led: string }> = {
  live: { text: 'Live', led: 'led--good' },
  connecting: { text: 'Connecting…', led: 'led--warn led--blink' },
  reconnecting: { text: 'Reconnecting…', led: 'led--warn led--blink' },
  offline: { text: 'Offline', led: 'led--critical' },
};

/** Status LED for the metrics stream; changes are announced politely to screen readers. */
export function ConnectionLed({ state }: { state: ConnectionState }) {
  const { text, led } = STATES[state];

  return (
    <span className="inline-flex items-center gap-2 font-mono text-sm" data-state={state}>
      <span aria-hidden="true" className={cx('led', led)} />
      {/* The live region below carries the accessible text, so the visible label is hidden from AT. */}
      <span aria-hidden="true" className="text-text">
        {text}
      </span>
      <span className="sr-only" aria-live="polite" aria-atomic="true">
        Metrics stream: {text}
      </span>
    </span>
  );
}
