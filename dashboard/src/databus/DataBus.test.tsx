import { act, render, screen } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { DataBus } from './DataBus';

const CACHE = { name: 'lru-A', opsPerSec: 5012, hitRateWindow10s: 0.838 };

function visibleElectrons(container: HTMLElement): Element[] {
  return Array.from(container.querySelectorAll('circle[r="5"]')).filter(
    (c) => c.getAttribute('visibility') !== 'hidden',
  );
}

describe('DataBus', () => {
  beforeEach(() => {
    vi.useFakeTimers({ toFake: ['requestAnimationFrame', 'cancelAnimationFrame', 'performance'] });
  });

  afterEach(() => {
    vi.useRealTimers();
    delete document.documentElement.dataset.animation;
  });

  it('spawns electrons with the frame loop and shows the legend and caption', () => {
    const { container } = render(<DataBus cache={CACHE} />);
    expect(visibleElectrons(container)).toHaveLength(0);
    act(() => {
      vi.advanceTimersByTime(1000);
    });
    // 5,012 ops/s at 1 electron per 1,000 requests: about 5 electrons in the first second.
    const live = visibleElectrons(container);
    expect(live.length).toBeGreaterThanOrEqual(4);
    expect(live.length).toBeLessThanOrEqual(6);
    expect(live[0]?.getAttribute('transform')).toMatch(/^translate\(/);
    expect(screen.getByText('1 electron ≈ 1,000 requests')).toBeInTheDocument();
    expect(screen.getByText('84% of requests never reached the database.')).toBeInTheDocument();
  });

  it('shows static labelled arrows when the circuit animation is off', () => {
    document.documentElement.dataset.animation = 'off';
    const { container } = render(<DataBus cache={CACHE} />);
    expect(screen.getByTestId('data-bus')).toHaveAttribute('data-motion', 'off');
    expect(container.querySelectorAll('circle[r="5"]')).toHaveLength(0);
    expect(screen.getByText('hit 84%')).toBeInTheDocument();
    expect(screen.getByText('miss 16%')).toBeInTheDocument();
    expect(screen.queryByText(/1 electron ≈/)).not.toBeInTheDocument();
  });

  it('shows static arrows under prefers-reduced-motion', () => {
    const matchMedia = vi.fn().mockReturnValue({
      matches: true,
      addEventListener: vi.fn(),
      removeEventListener: vi.fn(),
    });
    vi.stubGlobal('matchMedia', matchMedia);
    try {
      render(<DataBus cache={CACHE} />);
      expect(screen.getByTestId('data-bus')).toHaveAttribute('data-motion', 'off');
    } finally {
      vi.unstubAllGlobals();
    }
  });

  it('pauses the loop while the tab is hidden', () => {
    const { container } = render(<DataBus cache={CACHE} />);
    const hidden = vi.spyOn(document, 'hidden', 'get').mockReturnValue(true);
    act(() => {
      document.dispatchEvent(new Event('visibilitychange'));
      vi.advanceTimersByTime(2000);
    });
    expect(visibleElectrons(container)).toHaveLength(0);
    hidden.mockReturnValue(false);
    act(() => {
      document.dispatchEvent(new Event('visibilitychange'));
      vi.advanceTimersByTime(1000);
    });
    expect(visibleElectrons(container).length).toBeGreaterThan(0);
  });
});
