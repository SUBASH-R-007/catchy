import { memo, useSyncExternalStore } from 'react';

import { CIRCUIT } from './circuit.generated';
import type { CircuitLaneTrace } from './circuit.generated';
import './circuit.css';

const VIGNETTE_ID = 'circuit-bg-vignette';
const LANES = CIRCUIT.traces.filter((trace): trace is CircuitLaneTrace => trace.lane);

function subscribeToVisibility(onChange: () => void): () => void {
  document.addEventListener('visibilitychange', onChange);
  return () => document.removeEventListener('visibilitychange', onChange);
}

function isDocumentHidden(): boolean {
  return document.hidden;
}

function isHiddenOnServer(): boolean {
  return false;
}

/** The static board: copper traces, vias, pads and ICs. Its data never changes. */
const Board = memo(function Board() {
  return (
    <>
      <g className="circuit-traces">
        {CIRCUIT.traces.map((trace) => (
          <path key={trace.d} d={trace.d} />
        ))}
      </g>
      <g className="circuit-vias">
        {CIRCUIT.vias.map((via) => (
          <circle key={`${via.x},${via.y}`} cx={via.x} cy={via.y} r={via.r} />
        ))}
      </g>
      <g className="circuit-pads">
        {CIRCUIT.pads.map((pad) => (
          <circle key={`${pad.x},${pad.y}`} cx={pad.x} cy={pad.y} r={pad.r} />
        ))}
      </g>
      {CIRCUIT.ics.map((ic) => (
        <g key={ic.label.text} className="circuit-ic">
          <rect className="circuit-ic-body" x={ic.x} y={ic.y} width={ic.w} height={ic.h} rx={3} />
          <g className="circuit-ic-pins">
            {ic.pins.map((pin) => (
              <rect
                key={`${pin.x},${pin.y}`}
                x={pin.x}
                y={pin.y}
                width={pin.w}
                height={pin.h}
                rx={1}
              />
            ))}
          </g>
          <circle className="circuit-ic-dot" cx={ic.dot.x} cy={ic.dot.y} r={ic.dot.r} />
          <text className="circuit-ic-label" x={ic.label.x} y={ic.label.y}>
            {ic.label.text}
          </text>
        </g>
      ))}
    </>
  );
});

/**
 * One electron per lane: a 1.5 % dash that CSS slides along the path by animating
 * stroke-dashoffset. The single glow filter sits on the group (see circuit.css).
 */
const Electrons = memo(function Electrons() {
  return (
    <g className="electrons">
      {LANES.map((lane) => (
        <path
          key={lane.d}
          className="electron"
          d={lane.d}
          pathLength={100}
          style={{ animationDuration: `${lane.durationS}s`, animationDelay: `-${lane.delayS}s` }}
        />
      ))}
    </g>
  );
});

/**
 * The fixed, decorative circuit-board background (SPEC 10.2). `animated` is the app shell's
 * persisted "Circuit animation" toggle; reduced motion and hidden tabs are handled here and in
 * circuit.css.
 */
export function CircuitBackground({ animated }: { animated: boolean }) {
  const hidden = useSyncExternalStore(subscribeToVisibility, isDocumentHidden, isHiddenOnServer);

  return (
    <svg
      className="circuit-bg"
      aria-hidden="true"
      focusable="false"
      viewBox={`0 0 ${CIRCUIT.width} ${CIRCUIT.height}`}
      preserveAspectRatio="xMidYMid slice"
      width="100%"
      height="100%"
      data-paused={hidden ? 'true' : undefined}
    >
      <defs>
        <radialGradient id={VIGNETTE_ID} cx="50%" cy="50%" r="75%">
          <stop offset="0" className="circuit-vignette-stop-0" />
          <stop offset="0.5" className="circuit-vignette-stop-1" />
          <stop offset="1" className="circuit-vignette-stop-2" />
        </radialGradient>
      </defs>
      <Board />
      {animated ? <Electrons /> : null}
      <rect
        className="circuit-vignette"
        width={CIRCUIT.width}
        height={CIRCUIT.height}
        fill={`url(#${VIGNETTE_ID})`}
      />
    </svg>
  );
}
