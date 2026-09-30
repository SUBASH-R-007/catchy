import { act, fireEvent, render, screen, within } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { LedRow } from './LedRow';

const NAMES = [
  'Size within capacity',
  'Index matches policy',
  'No lost updates',
  'Stats add up',
  'Listeners fire once',
] as const;

const ALL_PASS = NAMES.map((name) => ({ name, passed: true, detail: 'ok' }));

function statuses(): Array<string | null> {
  return screen.getAllByRole('listitem').map((item) => item.getAttribute('data-status'));
}

describe('LedRow', () => {
  beforeEach(() => {
    vi.useFakeTimers();
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  it('renders one idle LED per invariant before any run', () => {
    render(<LedRow names={NAMES} results={null} />);
    const list = screen.getByRole('list', { name: 'Invariant checks' });
    expect(within(list).getAllByRole('listitem')).toHaveLength(5);
    expect(statuses()).toEqual(Array(5).fill('idle'));
    expect(list).not.toHaveAttribute('aria-busy');
    expect(screen.getAllByText('not run')).toHaveLength(5);
  });

  it('sets aria-busy and pulses every LED while running', () => {
    render(<LedRow names={NAMES} results={null} running />);
    expect(screen.getByRole('list')).toHaveAttribute('aria-busy', 'true');
    expect(statuses()).toEqual(Array(5).fill('running'));
    for (const item of screen.getAllByRole('listitem')) {
      expect(item.querySelector('.led')).toHaveClass('led--warn', 'led--pulse');
    }
  });

  it('lights passing LEDs one by one at 150 ms steps', () => {
    const { rerender } = render(<LedRow names={NAMES} results={null} running />);
    rerender(<LedRow names={NAMES} results={ALL_PASS} running={false} />);

    expect(statuses()).toEqual(Array(5).fill('pending'));

    act(() => {
      vi.advanceTimersByTime(0);
    });
    expect(statuses()).toEqual(['passed', 'pending', 'pending', 'pending', 'pending']);

    act(() => {
      vi.advanceTimersByTime(149);
    });
    expect(statuses().filter((status) => status === 'passed')).toHaveLength(1);

    act(() => {
      vi.advanceTimersByTime(1);
    });
    expect(statuses()).toEqual(['passed', 'passed', 'pending', 'pending', 'pending']);

    act(() => {
      vi.advanceTimersByTime(150);
    });
    expect(statuses().filter((status) => status === 'passed')).toHaveLength(3);

    act(() => {
      vi.advanceTimersByTime(300);
    });
    expect(statuses()).toEqual(Array(5).fill('passed'));
    expect(screen.getAllByText('passed')).toHaveLength(5);
    for (const item of screen.getAllByRole('listitem')) {
      expect(item.querySelector('.led')).toHaveClass('led--good');
    }
  });

  it('does not restart the sequence when the parent passes an equal new results array', () => {
    const { rerender } = render(<LedRow names={NAMES} results={ALL_PASS} />);
    act(() => {
      vi.advanceTimersByTime(600);
    });
    expect(statuses()).toEqual(Array(5).fill('passed'));

    rerender(<LedRow names={[...NAMES]} results={ALL_PASS.map((result) => ({ ...result }))} />);
    expect(statuses()).toEqual(Array(5).fill('passed'));
  });

  it('replays the sequence for a new run with identical results', () => {
    const { rerender } = render(<LedRow names={NAMES} results={ALL_PASS} />);
    act(() => {
      vi.advanceTimersByTime(600);
    });
    rerender(<LedRow names={NAMES} results={ALL_PASS} running />);
    // A long run: nothing may light up behind the pulsing LEDs.
    act(() => {
      vi.advanceTimersByTime(2000);
    });
    expect(statuses()).toEqual(Array(5).fill('running'));
    rerender(<LedRow names={NAMES} results={ALL_PASS} running={false} />);
    expect(statuses()).toEqual(Array(5).fill('pending'));
    act(() => {
      vi.advanceTimersByTime(0);
    });
    expect(statuses()).toEqual(['passed', 'pending', 'pending', 'pending', 'pending']);
    act(() => {
      vi.advanceTimersByTime(600);
    });
    expect(statuses()).toEqual(Array(5).fill('passed'));
  });

  it('shows a failed LED in red with its detail on focus and hover via aria-describedby', () => {
    const detail = 'size 1025 exceeded capacity 1024 at op 88,112';
    const results = NAMES.map((name, index) => ({
      name,
      passed: index !== 2,
      detail: index === 2 ? detail : 'ok',
    }));
    render(<LedRow names={NAMES} results={results} />);
    act(() => {
      vi.advanceTimersByTime(600);
    });

    const failed = screen.getByText('No lost updates').closest('li');
    if (!failed) throw new Error('missing list item');
    expect(failed).toHaveAttribute('data-status', 'failed');
    expect(failed.querySelector('.led')).toHaveClass('led--critical');
    expect(failed).toHaveTextContent(`failed: ${detail}`);
    expect(failed).toHaveAttribute('tabindex', '0');

    const tooltip = screen.getByRole('tooltip', { hidden: true });
    expect(failed).toHaveAttribute('aria-describedby', tooltip.id);
    expect(tooltip).not.toBeVisible();

    act(() => {
      failed.focus();
    });
    expect(failed).toHaveFocus();
    expect(screen.getByRole('tooltip')).toHaveTextContent(detail);
    expect(failed).toHaveAccessibleDescription(detail);

    fireEvent.keyDown(document, { key: 'Escape' });
    expect(screen.queryByRole('tooltip')).not.toBeInTheDocument();

    act(() => {
      failed.blur();
    });
    fireEvent.mouseEnter(failed);
    expect(screen.getByRole('tooltip')).toBeVisible();
    fireEvent.mouseLeave(failed);
    expect(screen.queryByRole('tooltip')).not.toBeInTheDocument();

    // Passing LEDs are not focus stops.
    const passed = screen.getByText('Stats add up').closest('li');
    expect(passed).not.toHaveAttribute('tabindex');
    expect(passed).not.toHaveAttribute('aria-describedby');
  });

  it('lights everything at once under prefers-reduced-motion', () => {
    const matchMedia = vi.fn((query: string) => ({
      matches: query.includes('prefers-reduced-motion'),
      media: query,
      onchange: null,
      addEventListener: vi.fn(),
      removeEventListener: vi.fn(),
      addListener: vi.fn(),
      removeListener: vi.fn(),
      dispatchEvent: vi.fn(),
    }));
    Object.defineProperty(window, 'matchMedia', {
      configurable: true,
      writable: true,
      value: matchMedia,
    });
    try {
      render(<LedRow names={NAMES} results={ALL_PASS} />);
      expect(statuses()).toEqual(Array(5).fill('passed'));
      expect(matchMedia).toHaveBeenCalledWith('(prefers-reduced-motion: reduce)');
    } finally {
      Reflect.deleteProperty(window, 'matchMedia');
    }
  });
});
