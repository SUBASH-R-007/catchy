import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import type { ConnectionState } from '../api/types';
import { ConnectionLed } from './ConnectionLed';

const CASES: Array<[ConnectionState, string]> = [
  ['live', 'Live'],
  ['connecting', 'Connecting…'],
  ['reconnecting', 'Reconnecting…'],
  ['offline', 'Offline'],
];

function liveRegion(container: HTMLElement): HTMLElement {
  const region = container.querySelector<HTMLElement>('[aria-live="polite"]');
  if (!region) throw new Error('no live region');
  return region;
}

describe('ConnectionLed', () => {
  it.each(CASES)('shows the label for %s', (state, label) => {
    const { container } = render(<ConnectionLed state={state} />);
    expect(screen.getByText(label)).toBeInTheDocument();
    expect(liveRegion(container)).toHaveTextContent(`Metrics stream: ${label}`);
  });

  it('updates the polite live region when the state changes', () => {
    const { container, rerender } = render(<ConnectionLed state="connecting" />);
    const region = liveRegion(container);
    expect(region).toHaveAttribute('aria-live', 'polite');
    expect(region).toHaveTextContent('Metrics stream: Connecting…');

    rerender(<ConnectionLed state="live" />);
    expect(liveRegion(container)).toBe(region); // same node, so screen readers announce the change
    expect(region).toHaveTextContent('Metrics stream: Live');

    rerender(<ConnectionLed state="reconnecting" />);
    expect(region).toHaveTextContent('Metrics stream: Reconnecting…');

    rerender(<ConnectionLed state="offline" />);
    expect(region).toHaveTextContent('Metrics stream: Offline');
  });

  it('blinks only while connecting or reconnecting', () => {
    const { container, rerender } = render(<ConnectionLed state="reconnecting" />);
    const led = () => container.querySelector('.led');
    expect(led()).toHaveClass('led--warn', 'led--blink');

    rerender(<ConnectionLed state="live" />);
    expect(led()).toHaveClass('led--good');
    expect(led()).not.toHaveClass('led--blink');

    rerender(<ConnectionLed state="offline" />);
    expect(led()).toHaveClass('led--critical');
    expect(led()).not.toHaveClass('led--blink');
  });
});
