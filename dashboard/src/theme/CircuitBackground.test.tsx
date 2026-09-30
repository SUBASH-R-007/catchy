import { act, render } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';

import { CircuitBackground } from './CircuitBackground';
import { CIRCUIT } from './circuit.generated';
import type { CircuitLaneTrace } from './circuit.generated';

const MARGIN = 24;

function setDocumentHidden(hidden: boolean): void {
  Object.defineProperty(document, 'hidden', { configurable: true, get: () => hidden });
}

function fireVisibilityChange(hidden: boolean): void {
  act(() => {
    setDocumentHidden(hidden);
    document.dispatchEvent(new Event('visibilitychange'));
  });
}

/** Absolute corner points of an SVG path made of M, L, H and V commands. */
function pathPoints(d: string): Array<[number, number]> {
  const points: Array<[number, number]> = [];
  let x = 0;
  let y = 0;
  for (const match of d.matchAll(/([MLHV])([^MLHV]*)/g)) {
    const command = match[1];
    const args = (match[2] ?? '')
      .trim()
      .split(/[\s,]+/)
      .map(Number);
    if (command === 'H') x = args[0] ?? Number.NaN;
    else if (command === 'V') y = args[0] ?? Number.NaN;
    else {
      x = args[0] ?? Number.NaN;
      y = args[1] ?? Number.NaN;
    }
    points.push([x, y]);
  }
  return points;
}

function expectOnBoard(x: number, y: number): void {
  expect(x).toBeGreaterThanOrEqual(-MARGIN);
  expect(x).toBeLessThanOrEqual(CIRCUIT.width + MARGIN);
  expect(y).toBeGreaterThanOrEqual(-MARGIN);
  expect(y).toBeLessThanOrEqual(CIRCUIT.height + MARGIN);
}

afterEach(() => {
  Reflect.deleteProperty(document, 'hidden');
});

describe('CircuitBackground', () => {
  it('renders a decorative full-viewport svg of the whole board', () => {
    const { container } = render(<CircuitBackground animated />);
    const svg = container.querySelector('svg');

    expect(svg).not.toBeNull();
    expect(svg).toHaveClass('circuit-bg');
    expect(svg).toHaveAttribute('aria-hidden', 'true');
    expect(svg).toHaveAttribute('focusable', 'false');
    expect(svg).toHaveAttribute('viewBox', '0 0 1440 900');
    expect(svg).toHaveAttribute('preserveAspectRatio', 'xMidYMid slice');
    expect(container.querySelectorAll('.circuit-traces path')).toHaveLength(CIRCUIT.traces.length);
    expect(container.querySelectorAll('.circuit-ic')).toHaveLength(CIRCUIT.ics.length);
  });

  it('renders one electron per lane when animated', () => {
    const { container } = render(<CircuitBackground animated />);
    const electrons = container.querySelectorAll<SVGPathElement>('.electrons > path.electron');

    expect(electrons).toHaveLength(16);
    for (const electron of electrons) {
      expect(electron).toHaveAttribute('pathLength', '100');
      const duration = Number.parseFloat(electron.style.animationDuration);
      expect(duration).toBeGreaterThanOrEqual(7);
      expect(duration).toBeLessThanOrEqual(12);
      expect(electron.style.animationDelay).toMatch(/^-\d+(\.\d+)?s$/);
    }
  });

  it('renders no electrons when the animation is off', () => {
    const { container } = render(<CircuitBackground animated={false} />);

    expect(container.querySelector('.electrons')).toBeNull();
    expect(container.querySelectorAll('.electron')).toHaveLength(0);
    expect(container.querySelectorAll('.circuit-traces path')).toHaveLength(CIRCUIT.traces.length);
  });

  it('marks the board as paused while the document is hidden', () => {
    setDocumentHidden(false);
    const { container } = render(<CircuitBackground animated />);
    const svg = container.querySelector('svg');
    expect(svg).not.toHaveAttribute('data-paused');

    fireVisibilityChange(true);
    expect(svg).toHaveAttribute('data-paused', 'true');

    fireVisibilityChange(false);
    expect(svg).not.toHaveAttribute('data-paused');
  });

  it('removes its visibilitychange listener on unmount', () => {
    const add = vi.spyOn(document, 'addEventListener');
    const remove = vi.spyOn(document, 'removeEventListener');
    const { unmount } = render(<CircuitBackground animated />);
    const listener = add.mock.calls.find(([type]) => type === 'visibilitychange')?.[1];
    expect(listener).toBeDefined();

    unmount();

    expect(remove).toHaveBeenCalledWith('visibilitychange', listener);
  });
});

describe('generated circuit data', () => {
  it('has about 40 traces, 16 electron lanes and 3-4 ICs', () => {
    const lanes = CIRCUIT.traces.filter((trace): trace is CircuitLaneTrace => trace.lane);

    expect(CIRCUIT.width).toBe(1440);
    expect(CIRCUIT.height).toBe(900);
    expect(CIRCUIT.traces.length).toBeGreaterThanOrEqual(35);
    expect(CIRCUIT.traces.length).toBeLessThanOrEqual(45);
    expect(lanes).toHaveLength(16);
    expect(CIRCUIT.ics.length).toBeGreaterThanOrEqual(3);
    expect(CIRCUIT.ics.length).toBeLessThanOrEqual(4);
    for (const lane of lanes) {
      expect(lane.durationS).toBeGreaterThanOrEqual(7);
      expect(lane.durationS).toBeLessThanOrEqual(12);
      expect(lane.delayS).toBeGreaterThanOrEqual(0);
      expect(lane.delayS).toBeLessThan(lane.durationS);
    }
  });

  it('routes traces orthogonally with 45° bends', () => {
    for (const trace of CIRCUIT.traces) {
      const points = pathPoints(trace.d);
      expect(points.length).toBeGreaterThanOrEqual(2);
      for (let i = 1; i < points.length; i++) {
        const [x0, y0] = points[i - 1] ?? [0, 0];
        const [x1, y1] = points[i] ?? [0, 0];
        const dx = Math.abs(x1 - x0);
        const dy = Math.abs(y1 - y0);
        expect(dx === 0 || dy === 0 || dx === dy).toBe(true);
      }
    }
  });

  it('keeps every coordinate within a small margin of the board', () => {
    for (const trace of CIRCUIT.traces) {
      for (const [x, y] of pathPoints(trace.d)) expectOnBoard(x, y);
    }
    for (const { x, y } of [...CIRCUIT.pads, ...CIRCUIT.vias]) expectOnBoard(x, y);
    for (const ic of CIRCUIT.ics) {
      expectOnBoard(ic.x, ic.y);
      expectOnBoard(ic.x + ic.w, ic.y + ic.h);
      expect(ic.pins.length).toBeGreaterThan(0);
      for (const pin of ic.pins) expectOnBoard(pin.x, pin.y);
    }
  });
});
