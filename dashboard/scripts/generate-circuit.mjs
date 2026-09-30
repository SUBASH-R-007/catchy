/**
 * Generates the dashboard's circuit-board background (SPEC 10.2) into
 * src/theme/circuit.generated.ts.
 *
 *   node scripts/generate-circuit.mjs          write the file
 *   node scripts/generate-circuit.mjs --check  exit 1 if the committed file is out of date
 *
 * The board is 1440x900 on a 24 px routing grid. Traces are routed on the grid with orthogonal
 * runs and 45° chamfered bends, like real PCB copper. A half-resolution occupancy grid (grid
 * nodes plus segment midpoints) guarantees that no two traces touch or cross and that nothing is
 * routed through an IC. Every random choice comes from a mulberry32 PRNG with a fixed seed, so the
 * output is byte-identical on every run.
 */
import { readFileSync, writeFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const SEED = 20260930;
const WIDTH = 1440;
const HEIGHT = 900;
const GRID = 24;
const COLS = WIDTH / GRID; // edge nodes at c = 0 and c = COLS
const ROWS = Math.floor(HEIGHT / GRID); // edge nodes at r = 0 and r = ROWS (y = 888)
const OVERSHOOT = 12; // edge traces run this far past the board edge
const TARGET_TRACES = 40;
const FILL_RESERVE = 6; // traces left for the "edge to pad" fill phase
const LANE_COUNT = 16;
const MAX_LANES_PER_BUS = 2;
const MIN_EDGE_TO_EDGE = 25; // "mostly edge to edge": the rest start at IC pins or end at pads
const MAX_CANDIDATES = 60;
const MAX_NODES = 160;
const MIN_NODES = 6;
const MIN_PAD_TRACE_NODES = 9;
const PAD_R = 5;
const VIA_R = 3.5;
const PIN_LEN = 8;
const PIN_WIDTH = 6;
const PIN_PITCH = 12;

const OUT_FILE = join(
  dirname(fileURLToPath(import.meta.url)),
  '..',
  'src',
  'theme',
  'circuit.generated.ts',
);

// Headings: 0 = E, then clockwise in 45° steps (y grows downwards).
const DX = [1, 1, 0, -1, -1, -1, 0, 1];
const DY = [0, 1, 1, 1, 0, -1, -1, -1];
const E = 0;
const S = 2;
const W = 4;
const N = 6;
const INWARD = { L: E, T: S, R: W, B: N };
const OUTWARD = { L: W, T: N, R: E, B: S };
const OPPOSITE = { L: 'R', R: 'L', T: 'B', B: 'T' };
const ADJACENT = { L: ['T', 'B'], R: ['T', 'B'], T: ['L', 'R'], B: ['L', 'R'] };

const OK = 0;
const EXIT = 1;
const FAIL = 2;

// ---------------------------------------------------------------------------------------------
// Seeded randomness
// ---------------------------------------------------------------------------------------------

function mulberry32(seed) {
  let a = seed >>> 0;
  return () => {
    a = (a + 0x6d2b79f5) >>> 0;
    let t = a;
    t = Math.imul(t ^ (t >>> 15), t | 1);
    t ^= t + Math.imul(t ^ (t >>> 7), t | 61);
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}

function createRng(seed) {
  const next = mulberry32(seed);
  return {
    next,
    int: (lo, hi) => lo + Math.floor(next() * (hi - lo + 1)),
    chance: (p) => next() < p,
    pick: (items) => items[Math.floor(next() * items.length)],
    shuffle(items) {
      const out = [...items];
      for (let i = out.length - 1; i > 0; i--) {
        const j = Math.floor(next() * (i + 1));
        [out[i], out[j]] = [out[j], out[i]];
      }
      return out;
    },
  };
}

// ---------------------------------------------------------------------------------------------
// Grid geometry
// ---------------------------------------------------------------------------------------------

const rot = (heading, steps) => (((heading + steps) % 8) + 8) % 8;

function isInterior(c, r) {
  return c >= 1 && c <= COLS - 1 && r >= 1 && r <= ROWS - 1;
}

function direction(a, b) {
  const dc = Math.sign(b.c - a.c);
  const dr = Math.sign(b.r - a.r);
  for (let d = 0; d < 8; d++) {
    if (DX[d] === dc && DY[d] === dr) return d;
  }
  return -1;
}

function edgeNode(edge, pos) {
  if (edge === 'L') return { c: 0, r: pos };
  if (edge === 'R') return { c: COLS, r: pos };
  if (edge === 'T') return { c: pos, r: 0 };
  return { c: pos, r: ROWS };
}

function edgeAnchor(edge) {
  if (edge === 'L') return { axis: 'c', value: 0 };
  if (edge === 'R') return { axis: 'c', value: COLS };
  if (edge === 'T') return { axis: 'r', value: 0 };
  return { axis: 'r', value: ROWS };
}

function onEdge(node, edge) {
  const { axis, value } = edgeAnchor(edge);
  if (node[axis] !== value) return false;
  const along = axis === 'c' ? node.r : node.c;
  const max = axis === 'c' ? ROWS : COLS;
  return along >= 2 && along <= max - 2;
}

function pinAnchor(ic, side) {
  if (side === 'L') return { axis: 'c', value: ic.c0 - 1 };
  if (side === 'R') return { axis: 'c', value: ic.c1 + 1 };
  if (side === 'T') return { axis: 'r', value: ic.r0 - 1 };
  return { axis: 'r', value: ic.r1 + 1 };
}

function isPinTip(ic, side, node) {
  const { axis, value } = pinAnchor(ic, side);
  if (node[axis] !== value) return false;
  return axis === 'c' ? node.r > ic.r0 && node.r < ic.r1 : node.c > ic.c0 && node.c < ic.c1;
}

function anchorOf(end) {
  if (end.kind === 'edge') return edgeAnchor(end.edge);
  if (end.kind === 'pin') return pinAnchor(end.ic, end.side);
  return null;
}

/** Grid nodes and segment midpoints of a path, in half-grid coordinates. */
function halfPoints(nodes) {
  const points = [];
  for (let i = 0; i < nodes.length; i++) {
    const n = nodes[i];
    if (i > 0) {
      const p = nodes[i - 1];
      points.push({ hx: p.c + n.c, hy: p.r + n.r, node: i });
    }
    points.push({ hx: 2 * n.c, hy: 2 * n.r, node: i });
  }
  return points;
}

function translate(nodes, dc, dr) {
  return nodes.map((n) => ({ c: n.c + dc, r: n.r + dr }));
}

/**
 * Slides the start of a path along its first (orthogonal) run until it sits on `anchor`,
 * trimming or extending that run. Returns null when that is impossible.
 */
function snapStart(nodes, anchor) {
  if (nodes.length < 2) return null;
  const d = direction(nodes[0], nodes[1]);
  const step = anchor.axis === 'c' ? DX[d] : DY[d];
  if (d % 2 !== 0 || step === 0) return null;
  const delta = (anchor.value - nodes[0][anchor.axis]) / step;
  if (delta === 0) return nodes;
  if (delta > 0) {
    let run = 0;
    while (run + 1 < nodes.length && direction(nodes[run], nodes[run + 1]) === d) run++;
    return delta <= run - 2 ? nodes.slice(delta) : null;
  }
  const prefix = [];
  for (let i = -delta; i >= 1; i--) {
    prefix.push({ c: nodes[0].c - DX[d] * i, r: nodes[0].r - DY[d] * i });
  }
  return [...prefix, ...nodes];
}

function snapEnd(nodes, anchor) {
  const snapped = snapStart([...nodes].reverse(), anchor);
  return snapped && snapped.reverse();
}

// ---------------------------------------------------------------------------------------------
// Routing
// ---------------------------------------------------------------------------------------------

/**
 * A steered random walk: from `start` with an orthogonal `heading`, towards `goal` (an edge that
 * the walk must leave through, heading straight out) or, with `goal === null`, for
 * `freeSegments` segments. Returns the list of grid nodes, or null if the walk failed.
 */
function walk(rng, start, heading, goal, freeSegments) {
  const nodes = [{ ...start }];
  let c = start.c;
  let r = start.r;
  let h = heading;
  const exitHeading = goal === null ? -1 : OUTWARD[goal];
  const exitAnchor = goal === null ? null : edgeAnchor(goal);

  const advance = (d, count) => {
    for (let i = 0; i < count; i++) {
      const nc = c + DX[d];
      const nr = r + DY[d];
      if (!isInterior(nc, nr)) {
        if (d === exitHeading && (exitAnchor.axis === 'c' ? nc : nr) === exitAnchor.value) {
          nodes.push({ c: nc, r: nr });
          return EXIT;
        }
        return FAIL;
      }
      c = nc;
      r = nr;
      nodes.push({ c, r });
    }
    return OK;
  };

  const room = (d) => {
    let n = 0;
    while (isInterior(c + DX[d] * (n + 1), r + DY[d] * (n + 1))) n++;
    return n;
  };

  // A side (+1 clockwise, -1 anticlockwise) with room for `k` diagonal steps, or 0.
  const side = (k) => {
    const s = rng.chance(0.5) ? 1 : -1;
    if (room(rot(h, s)) >= k + 1) return s;
    if (room(rot(h, -s)) >= k + 1) return -s;
    return 0;
  };

  const jog = () => {
    const k = rng.chance(0.6) ? 1 : rng.int(2, 3);
    const s = side(k);
    if (s === 0) return advance(h, rng.int(2, 6));
    const status = advance(rot(h, s), k);
    return status === OK ? advance(h, rng.int(2, 8)) : status;
  };

  let status = advance(h, rng.int(2, 7));
  let segments = 0;
  while (status === OK && nodes.length < MAX_NODES) {
    if (goal === null && segments >= freeSegments) break;
    segments++;

    if (goal !== null && h !== exitHeading) {
      // Heading across the board towards an adjacent edge: maybe jog, then one chamfered 90° turn.
      if (rng.chance(0.3)) {
        status = jog();
        continue;
      }
      const sign = rot(h, 2) === exitHeading ? 1 : -1;
      const k = rng.chance(0.7) ? 1 : 2;
      const maxRun = Math.min(36, room(h) - k - 1);
      if (maxRun < 0) return null;
      status = advance(h, rng.int(0, maxRun));
      if (status === OK) status = advance(rot(h, sign), k);
      h = rot(h, 2 * sign);
      if (status === OK) status = advance(h, rng.int(2, 5));
      continue;
    }

    const roll = rng.next();
    if (roll < 0.4) {
      status = advance(h, rng.int(3, 12));
    } else if (roll < 0.72) {
      status = jog();
    } else if (goal === null && roll < 0.9) {
      // Free walk: a permanent chamfered 90° turn.
      const k = rng.chance(0.7) ? 1 : 2;
      const s = side(k + 2);
      if (s === 0) continue;
      status = advance(rot(h, s), k);
      h = rot(h, 2 * s);
      if (status === OK) status = advance(h, rng.int(2, 8));
    } else {
      // S-bend: turn 90° away, run sideways, turn back.
      const s = side(4);
      if (s === 0) continue;
      status = advance(rot(h, s), 1);
      const sideways = rot(h, 2 * s);
      const run = Math.min(rng.int(1, 5), room(sideways) - 2);
      if (status === OK && run > 0) status = advance(sideways, run);
      if (status === OK) status = advance(rot(h, s), 1);
      if (status === OK) status = advance(h, rng.int(2, 8));
    }
  }
  if (goal === null) return status === OK ? nodes : null;
  return status === EXIT ? nodes : null;
}

// ---------------------------------------------------------------------------------------------
// Board
// ---------------------------------------------------------------------------------------------

/** Lays out one candidate board: ICs, IC buses, edge-to-edge buses, then pad-ended fill. */
function layout(rng) {
  const halfW = 2 * COLS + 1;
  const halfH = 2 * ROWS + 1;
  const KEEP_OUT = -1;
  const occupancy = new Int32Array(halfW * halfH); // 0 free, -1 IC keep-out, n > 0 trace n
  const traces = [];
  const ics = [];
  let nextGroup = 1;

  /** Index of the first node that collides with the board, or -1 if the path is clear. */
  const firstConflict = (nodes, exemptFirst) => {
    const seen = new Set();
    for (const { hx, hy, node } of halfPoints(nodes)) {
      if (hx < 0 || hy < 0 || hx >= halfW || hy >= halfH) return node;
      const key = hy * halfW + hx;
      if (seen.has(key)) return node;
      seen.add(key);
      const owner = occupancy[key];
      if (owner === 0 || (owner === KEEP_OUT && exemptFirst && node === 0)) continue;
      return node;
    }
    return -1;
  };

  const fits = (nodes, start, end) => {
    if (!nodes || nodes.length < MIN_NODES) return false;
    const first = nodes[0];
    const last = nodes[nodes.length - 1];
    if (
      start.kind === 'edge' ? !onEdge(first, start.edge) : !isPinTip(start.ic, start.side, first)
    ) {
      return false;
    }
    if (end.kind === 'edge' ? !onEdge(last, end.edge) : !isInterior(last.c, last.r)) return false;
    for (let i = 1; i < nodes.length - 1; i++) {
      if (!isInterior(nodes[i].c, nodes[i].r)) return false;
    }
    return firstConflict(nodes, start.kind === 'pin') === -1;
  };

  const add = (nodes, start, end, group) => {
    const id = traces.length + 1;
    for (const { hx, hy } of halfPoints(nodes)) occupancy[hy * halfW + hx] = id;
    traces.push({ nodes, start, end, group });
  };

  const snap = (nodes, start, end) => {
    let out = snapStart(nodes, anchorOf(start));
    const endAnchor = anchorOf(end);
    if (out && endAnchor) out = snapEnd(out, endAnchor);
    return out;
  };

  /** Adds up to `copies` parallel copies of a trace (a bus), stopping at the first misfit. */
  const addBus = (leader, start, end, group, copies, cap) => {
    const classes = new Set();
    for (let i = 0; i + 1 < leader.length; i++)
      classes.add(direction(leader[i], leader[i + 1]) % 4);
    const free = [0, 1, 2, 3].filter((k) => !classes.has(k));
    if (free.length === 0) return;
    const k = rng.pick(free);
    let placed = 0;
    for (const sign of rng.shuffle([1, -1])) {
      for (let i = 1; placed < copies && traces.length < cap; i++) {
        const moved = snap(translate(leader, DX[k] * sign * i, DY[k] * sign * i), start, end);
        if (!fits(moved, start, end)) break;
        add(moved, start, end, group);
        placed++;
      }
    }
  };

  // --- ICs: one per chosen quadrant, pins on two opposite sides --------------------------------
  const icCount = rng.chance(0.5) ? 4 : 3;
  const labels = rng.shuffle(Array.from({ length: 18 }, (_, i) => i + 2));
  for (const quadrant of rng.shuffle([0, 1, 2, 3]).slice(0, icCount)) {
    const sidePins = rng.chance(0.6); // pins on the left and right sides
    const w = sidePins ? rng.int(4, 6) : rng.int(6, 9);
    const h = sidePins ? rng.int(5, 8) : rng.int(3, 4);
    const left = quadrant % 2 === 0;
    const top = quadrant < 2;
    const c0 = left ? rng.int(6, 21 - w) : rng.int(39, COLS - 7 - w);
    const r0 = top ? rng.int(5, 15 - h) : rng.int(21, ROWS - 5 - h);
    const ic = {
      c0,
      r0,
      c1: c0 + w,
      r1: r0 + h,
      sides: sidePins ? ['L', 'R'] : ['T', 'B'],
      label: `U${labels[ics.length]}`,
    };
    // Keep-out: the body plus a one-cell ring (the pin tips sit on it); the label needs one more
    // cell to the right of top/bottom-pinned parts.
    const extraRight = sidePins ? 0 : 1;
    for (let hy = 2 * (ic.r0 - 1); hy <= 2 * (ic.r1 + 1); hy++) {
      for (let hx = 2 * (ic.c0 - 1); hx <= 2 * (ic.c1 + 1 + extraRight); hx++) {
        occupancy[hy * halfW + hx] = KEEP_OUT;
      }
    }
    ics.push(ic);
  }

  // --- IC buses: a few adjacent pins routed out to a board edge -------------------------------
  for (const ic of ics) {
    for (let attempt = 0; attempt < 400; attempt++) {
      const side = rng.pick(ic.sides);
      const anchor = pinAnchor(ic, side);
      const span = anchor.axis === 'c' ? [ic.r0 + 1, ic.r1 - 1] : [ic.c0 + 1, ic.c1 - 1];
      const along = rng.int(span[0], span[1]);
      const tip =
        anchor.axis === 'c' ? { c: anchor.value, r: along } : { c: along, r: anchor.value };
      const facing = side; // the board edge the pin side faces
      const topHalf = (ic.r0 + ic.r1) / 2 < ROWS / 2;
      const leftHalf = (ic.c0 + ic.c1) / 2 < COLS / 2;
      const nearer = anchor.axis === 'c' ? (topHalf ? 'T' : 'B') : leftHalf ? 'L' : 'R';
      const goal = rng.chance(0.6) ? facing : nearer;
      const nodes = walk(rng, tip, OUTWARD[side], goal, 0);
      const start = { kind: 'pin', ic, side };
      const end = { kind: 'edge', edge: goal };
      if (!fits(nodes, start, end)) continue;
      const group = nextGroup++;
      add(nodes, start, end, group);
      addBus(nodes, start, end, group, rng.int(1, 2), TARGET_TRACES);
      break;
    }
  }

  // --- Edge-to-edge buses -----------------------------------------------------------------------
  const randomEdgeStart = () => {
    const edge = rng.pick(['L', 'T', 'R', 'B']);
    const max = edge === 'L' || edge === 'R' ? ROWS : COLS;
    const goal = rng.chance(0.45) ? OPPOSITE[edge] : rng.pick(ADJACENT[edge]);
    return { edge, goal, node: edgeNode(edge, rng.int(2, max - 2)) };
  };

  for (let attempt = 0; attempt < 12000; attempt++) {
    if (traces.length >= TARGET_TRACES - FILL_RESERVE) break;
    const { edge, goal, node } = randomEdgeStart();
    const nodes = walk(rng, node, INWARD[edge], goal, 0);
    const start = { kind: 'edge', edge };
    const end = { kind: 'edge', edge: goal };
    if (!fits(nodes, start, end)) continue;
    const group = nextGroup++;
    add(nodes, start, end, group);
    addBus(nodes, start, end, group, rng.int(0, 3), TARGET_TRACES - FILL_RESERVE);
  }

  // --- Fill: edge walks that stop at a pad just short of whatever they would run into ---------
  for (let attempt = 0; attempt < 20000 && traces.length < TARGET_TRACES; attempt++) {
    const { edge, goal, node } = randomEdgeStart();
    const nodes = walk(rng, node, INWARD[edge], goal, 0);
    if (!nodes) continue;
    const start = { kind: 'edge', edge };
    const conflict = firstConflict(nodes, false);
    if (conflict === -1) {
      const end = { kind: 'edge', edge: goal };
      if (fits(nodes, start, end)) add(nodes, start, end, nextGroup++);
      continue;
    }
    const cut = nodes.slice(0, Math.max(0, conflict - 1));
    while (cut.length >= 2 && direction(cut[cut.length - 2], cut[cut.length - 1]) % 2 === 1) {
      cut.pop();
    }
    const end = { kind: 'pad' };
    if (cut.length >= MIN_PAD_TRACE_NODES && fits(cut, start, end))
      add(cut, start, end, nextGroup++);
  }

  return { traces, ics };
}

/**
 * Lays out candidate boards from the seeded stream until one is "mostly edge to edge" with every
 * IC wired, keeping the best so far (deterministic: the candidates come from one PRNG stream).
 */
function generate(rng) {
  let best = null;
  for (let candidate = 0; candidate < MAX_CANDIDATES; candidate++) {
    const board = layout(rng);
    const edgeToEdge = board.traces.filter((t) => t.start.kind === 'edge' && t.end.kind === 'edge');
    const wiredIcs = board.ics.filter(
      (ic) => board.traces.filter((t) => t.start.kind === 'pin' && t.start.ic === ic).length >= 2,
    );
    const allWired = wiredIcs.length === board.ics.length;
    const score =
      (board.traces.length === TARGET_TRACES ? 1000 : 0) + (allWired ? 100 : 0) + edgeToEdge.length;
    if (!best || score > best.score) best = { board, score };
    if (
      board.traces.length === TARGET_TRACES &&
      allWired &&
      edgeToEdge.length >= MIN_EDGE_TO_EDGE
    ) {
      break;
    }
  }
  return best.board;
}

// ---------------------------------------------------------------------------------------------
// Output
// ---------------------------------------------------------------------------------------------

function num(n) {
  const v = Math.round(n * 10) / 10;
  return Object.is(v, -0) ? '0' : String(v);
}

function endPoint(node, end) {
  const x = node.c * GRID;
  const y = node.r * GRID;
  if (end.kind === 'edge') {
    if (end.edge === 'L') return { x: -OVERSHOOT, y };
    if (end.edge === 'R') return { x: WIDTH + OVERSHOOT, y };
    if (end.edge === 'T') return { x, y: -OVERSHOOT };
    return { x, y: HEIGHT + OVERSHOOT };
  }
  if (end.kind === 'pin') {
    const { ic, side } = end;
    if (side === 'L') return { x: ic.c0 * GRID - PIN_LEN, y };
    if (side === 'R') return { x: ic.c1 * GRID + PIN_LEN, y };
    if (side === 'T') return { x, y: ic.r0 * GRID - PIN_LEN };
    return { x, y: ic.r1 * GRID + PIN_LEN };
  }
  return { x, y };
}

/** Corner points of a trace in board pixels, with the ends moved onto the edge or pin tip. */
function corners(trace) {
  const { nodes, start, end } = trace;
  const points = [endPoint(nodes[0], start)];
  for (let i = 1; i < nodes.length - 1; i++) {
    if (direction(nodes[i - 1], nodes[i]) !== direction(nodes[i], nodes[i + 1])) {
      points.push({ x: nodes[i].c * GRID, y: nodes[i].r * GRID });
    }
  }
  points.push(endPoint(nodes[nodes.length - 1], end));
  return points;
}

function pathData(points) {
  let d = `M${num(points[0].x)} ${num(points[0].y)}`;
  for (let i = 1; i < points.length; i++) {
    const p = points[i];
    const q = points[i - 1];
    if (p.y === q.y) d += `H${num(p.x)}`;
    else if (p.x === q.x) d += `V${num(p.y)}`;
    else d += `L${num(p.x)} ${num(p.y)}`;
  }
  return d;
}

function pathLength(points) {
  let length = 0;
  for (let i = 1; i < points.length; i++) {
    length += Math.hypot(points[i].x - points[i - 1].x, points[i].y - points[i - 1].y);
  }
  return length;
}

function icShape(ic) {
  const x = ic.c0 * GRID;
  const y = ic.r0 * GRID;
  const w = (ic.c1 - ic.c0) * GRID;
  const h = (ic.r1 - ic.r0) * GRID;
  const pins = [];
  if (ic.sides[0] === 'L') {
    for (let py = y + PIN_PITCH; py < y + h; py += PIN_PITCH) {
      const top = py - PIN_WIDTH / 2;
      pins.push({ x: x - PIN_LEN, y: top, w: PIN_LEN, h: PIN_WIDTH });
      pins.push({ x: x + w, y: top, w: PIN_LEN, h: PIN_WIDTH });
    }
    return {
      x,
      y,
      w,
      h,
      label: { text: ic.label, x, y: y - 8 },
      dot: { x: x + 9, y: y + 9, r: 3 },
      pins,
    };
  }
  for (let px = x + PIN_PITCH; px < x + w; px += PIN_PITCH) {
    const leftEdge = px - PIN_WIDTH / 2;
    pins.push({ x: leftEdge, y: y - PIN_LEN, w: PIN_WIDTH, h: PIN_LEN });
    pins.push({ x: leftEdge, y: y + h, w: PIN_WIDTH, h: PIN_LEN });
  }
  return {
    x,
    y,
    w,
    h,
    label: { text: ic.label, x: x + w + 8, y: y + h / 2 + 4 },
    dot: { x: x + 9, y: y + h - 9, r: 3 },
    pins,
  };
}

function build() {
  const rng = createRng(SEED);
  const { traces, ics } = generate(rng);
  const shaped = traces.map((trace) => {
    const points = corners(trace);
    return { ...trace, points, d: pathData(points), length: pathLength(points) };
  });

  // Electron lanes: the longest traces, at most two per bus so they spread over the board.
  const byLength = shaped
    .map((_, i) => i)
    .sort((a, b) => shaped[b].length - shaped[a].length || a - b);
  const lanes = [];
  const perGroup = new Map();
  for (const i of byLength) {
    if (lanes.length === LANE_COUNT) break;
    const used = perGroup.get(shaped[i].group) ?? 0;
    if (used >= MAX_LANES_PER_BUS) continue;
    perGroup.set(shaped[i].group, used + 1);
    lanes.push(i);
  }
  for (const i of byLength) {
    if (lanes.length === LANE_COUNT) break;
    if (!lanes.includes(i)) lanes.push(i);
  }
  lanes.sort((a, b) => a - b);
  const laneLengths = lanes.map((i) => shaped[i].length);
  const minLength = Math.min(...laneLengths);
  const spread = Math.max(1, Math.max(...laneLengths) - minLength);
  lanes.forEach((i, order) => {
    const trace = shaped[i];
    // Longer lanes take longer so electrons travel at similar speeds; jitter breaks the lockstep.
    const base = 7 + (5 * (trace.length - minLength)) / spread + (rng.next() - 0.5) * 1.4;
    const duration = Math.min(12, Math.max(7, Math.round(base * 10) / 10));
    // Golden-ratio stagger, so no two electrons start at the same point of their lap.
    const phase = (order * 0.618034 + rng.next() * 0.1) % 1;
    trace.lane = { durationS: duration, delayS: Math.round(duration * phase * 10) / 10 };
  });

  // Pads at internal trace ends; vias at some of the bends.
  const pads = shaped
    .filter((t) => t.end.kind === 'pad')
    .map((t) => ({ ...t.points[t.points.length - 1], r: PAD_R }));
  const vias = [];
  const clear = (p) =>
    [...pads, ...vias].every((q) => Math.hypot(p.x - q.x, p.y - q.y) >= 2 * GRID);
  for (const trace of shaped) {
    let placed = 0;
    for (let i = 1; i < trace.points.length - 1 && placed < 2; i++) {
      const p = trace.points[i];
      if (rng.chance(0.35) && clear(p)) {
        vias.push({ x: p.x, y: p.y, r: VIA_R });
        placed++;
      }
    }
  }

  return { traces: shaped, ics: ics.map(icShape), pads, vias };
}

function render({ traces, ics, pads, vias }) {
  const laneCount = traces.filter((t) => t.lane).length;
  const circle = (c) => `{ x: ${num(c.x)}, y: ${num(c.y)}, r: ${num(c.r)} }`;
  const rect = (r) => `{ x: ${num(r.x)}, y: ${num(r.y)}, w: ${num(r.w)}, h: ${num(r.h)} }`;
  const lines = [
    '// Generated by scripts/generate-circuit.mjs — do not edit.',
    '// Regenerate with `npm run generate:circuit`; `node scripts/generate-circuit.mjs --check` verifies it.',
    `// Seed ${SEED}: ${WIDTH}x${HEIGHT} board, ${GRID} px grid, ${traces.length} traces (${laneCount} electron lanes), ${pads.length} pads, ${vias.length} vias, ${ics.length} ICs.`,
    '',
    'export interface CircuitCircle {',
    '  readonly x: number;',
    '  readonly y: number;',
    '  readonly r: number;',
    '}',
    '',
    'export interface CircuitRect {',
    '  readonly x: number;',
    '  readonly y: number;',
    '  readonly w: number;',
    '  readonly h: number;',
    '}',
    '',
    '/** A copper trace; `d` is an SVG path in board pixels. */',
    'export interface CircuitStaticTrace {',
    '  readonly d: string;',
    '  readonly lane: false;',
    '}',
    '',
    '/** A trace that carries an electron: one lap takes `durationS`, starting `delayS` into it. */',
    'export interface CircuitLaneTrace {',
    '  readonly d: string;',
    '  readonly lane: true;',
    '  readonly durationS: number;',
    '  readonly delayS: number;',
    '}',
    '',
    'export type CircuitTrace = CircuitStaticTrace | CircuitLaneTrace;',
    '',
    '/** An IC body with its pin rows, pin-1 dot and silkscreen label. */',
    'export interface CircuitIc extends CircuitRect {',
    '  readonly label: { readonly text: string; readonly x: number; readonly y: number };',
    '  readonly dot: CircuitCircle;',
    '  readonly pins: readonly CircuitRect[];',
    '}',
    '',
    'export interface Circuit {',
    '  readonly width: number;',
    '  readonly height: number;',
    '  readonly grid: number;',
    '  readonly traces: readonly CircuitTrace[];',
    '  readonly pads: readonly CircuitCircle[];',
    '  readonly vias: readonly CircuitCircle[];',
    '  readonly ics: readonly CircuitIc[];',
    '}',
    '',
    'export const CIRCUIT: Circuit = {',
    `  width: ${WIDTH},`,
    `  height: ${HEIGHT},`,
    `  grid: ${GRID},`,
    '  traces: [',
    ...traces.map((t) =>
      t.lane
        ? `    { d: '${t.d}', lane: true, durationS: ${num(t.lane.durationS)}, delayS: ${num(t.lane.delayS)} },`
        : `    { d: '${t.d}', lane: false },`,
    ),
    '  ],',
    '  pads: [',
    ...pads.map((p) => `    ${circle(p)},`),
    '  ],',
    '  vias: [',
    ...vias.map((v) => `    ${circle(v)},`),
    '  ],',
    '  ics: [',
  ];
  for (const ic of ics) {
    lines.push('    {');
    lines.push(`      x: ${num(ic.x)},`);
    lines.push(`      y: ${num(ic.y)},`);
    lines.push(`      w: ${num(ic.w)},`);
    lines.push(`      h: ${num(ic.h)},`);
    lines.push(
      `      label: { text: '${ic.label.text}', x: ${num(ic.label.x)}, y: ${num(ic.label.y)} },`,
    );
    lines.push(`      dot: ${circle(ic.dot)},`);
    lines.push('      pins: [');
    for (let i = 0; i < ic.pins.length; i += 4) {
      lines.push(
        `        ${ic.pins
          .slice(i, i + 4)
          .map(rect)
          .join(', ')},`,
      );
    }
    lines.push('      ],');
    lines.push('    },');
  }
  lines.push('  ],', '};', '');
  return lines.join('\n');
}

// ---------------------------------------------------------------------------------------------
// CLI
// ---------------------------------------------------------------------------------------------

const board = build();
const output = render(board);
const relative = 'src/theme/circuit.generated.ts';

if (process.argv.includes('--check')) {
  let current = '';
  try {
    current = readFileSync(OUT_FILE, 'utf8');
  } catch {
    current = '';
  }
  if (current.replace(/\r\n/g, '\n') !== output) {
    console.error(`${relative} is out of date: run \`npm run generate:circuit\`.`);
    process.exit(1);
  }
  console.log(`${relative} is up to date.`);
} else {
  writeFileSync(OUT_FILE, output);
  const lanes = board.traces.filter((t) => t.lane).length;
  const padEnded = board.traces.filter((t) => t.end.kind === 'pad').length;
  const fromPins = board.traces.filter((t) => t.start.kind === 'pin').length;
  console.log(
    `Wrote ${relative}: ${board.traces.length} traces (${lanes} lanes, ${fromPins} from IC pins, ` +
      `${padEnded} ending at pads), ${board.pads.length} pads, ${board.vias.length} vias, ` +
      `${board.ics.length} ICs, ${Buffer.byteLength(output)} bytes.`,
  );
}
