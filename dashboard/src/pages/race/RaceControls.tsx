import { Play, Square } from 'lucide-react';
import { useId } from 'react';
import { PATTERNS, type Pattern } from '../../api/types';
import { cx } from '../../components/cx';
import { formatInteger } from '../../lib/format';
import { buttonClass, labelClass, primaryButtonClass } from '../playground/ui';
import { PATTERN_INFO } from './patterns';
import { formatElapsed, OPS_MAX, OPS_MIN, OPS_STEP, type SimulationView } from './race';

const selectClass =
  'rounded-md border border-trace bg-surface-2 px-3 py-2 font-mono text-sm text-text hover:border-trace-glow';

export interface RaceControlsProps {
  groups: readonly string[];
  group: string;
  onGroupChange: (group: string) => void;
  pattern: Pattern;
  onPatternChange: (pattern: Pattern) => void;
  opsPerSec: number;
  onOpsChange: (ops: number) => void;
  simulation: SimulationView;
  /** A start or stop request is in flight. */
  busy: boolean;
  canStop: boolean;
  onStart: () => void;
  onStop: () => void;
}

/** Group, pattern and rate pickers plus start/stop and the running simulation's status. */
export function RaceControls({
  groups,
  group,
  onGroupChange,
  pattern,
  onPatternChange,
  opsPerSec,
  onOpsChange,
  simulation,
  busy,
  canStop,
  onStart,
  onStop,
}: RaceControlsProps) {
  const id = useId();
  const running = simulation.running;
  const options = groups.includes(group) ? groups : [group, ...groups];

  return (
    <div className="flex flex-col gap-6">
      <div className="flex flex-wrap items-end gap-6">
        <div>
          <label htmlFor={`${id}-group`} className={labelClass}>
            Cache group
          </label>
          <select
            id={`${id}-group`}
            className={selectClass}
            value={group}
            onChange={(e) => onGroupChange(e.target.value)}
          >
            {options.map((g) => (
              <option key={g} value={g}>
                {g}
              </option>
            ))}
          </select>
        </div>
        <div className="min-w-[240px] flex-1">
          <label htmlFor={`${id}-ops`} className={labelClass}>
            Operations per second:{' '}
            <output htmlFor={`${id}-ops`} className="font-mono text-text tabular-nums">
              {formatInteger(opsPerSec)}
            </output>
          </label>
          <input
            id={`${id}-ops`}
            type="range"
            min={OPS_MIN}
            max={OPS_MAX}
            step={OPS_STEP}
            value={opsPerSec}
            onChange={(e) => onOpsChange(Number(e.target.value))}
            aria-valuetext={`${formatInteger(opsPerSec)} operations per second`}
            className="w-full accent-[var(--trace-glow)]"
          />
          <div aria-hidden="true" className="flex justify-between font-mono text-sm text-muted">
            <span>{formatInteger(OPS_MIN)}</span>
            <span>{formatInteger(OPS_MAX)}</span>
          </div>
        </div>
      </div>

      <fieldset>
        <legend className={labelClass}>Traffic pattern</legend>
        <div className="grid gap-2 md:grid-cols-2 xl:grid-cols-4">
          {PATTERNS.map((p) => {
            const checked = p === pattern;
            return (
              <label
                key={p}
                htmlFor={`${id}-pattern-${p}`}
                className={cx(
                  'grid cursor-pointer grid-cols-[auto_minmax(0,1fr)] items-center gap-x-2 rounded border bg-surface-2 p-2 transition-colors hover:border-trace-glow',
                  checked ? 'border-trace-glow' : 'border-trace',
                )}
              >
                <input
                  id={`${id}-pattern-${p}`}
                  type="radio"
                  name={`${id}-pattern`}
                  value={p}
                  checked={checked}
                  onChange={() => onPatternChange(p)}
                  className="accent-[var(--trace-glow)]"
                />
                <span className="min-w-0 font-mono text-sm text-text">{PATTERN_INFO[p].label}</span>
                <span className="col-start-2 text-sm text-muted">
                  {PATTERN_INFO[p].description}
                </span>
              </label>
            );
          })}
        </div>
      </fieldset>

      <div className="flex flex-wrap items-center gap-4">
        <button type="button" className={primaryButtonClass} disabled={busy} onClick={onStart}>
          <Play aria-hidden="true" size={16} />
          {running ? `Switch to ${PATTERN_INFO[pattern].label}` : 'Start'}
        </button>
        <button type="button" className={buttonClass} disabled={busy || !canStop} onClick={onStop}>
          <Square aria-hidden="true" size={16} />
          Stop
        </button>
        <SimulationStatus simulation={simulation} group={group} />
      </div>
    </div>
  );
}

function SimulationStatus({ simulation, group }: { simulation: SimulationView; group: string }) {
  const { running, pattern } = simulation;
  const label = pattern ? PATTERN_INFO[pattern].label : null;
  const elsewhere = running && simulation.group !== null && simulation.group !== group;
  return (
    <div className="flex min-w-0 flex-1 flex-col gap-1">
      <p className="flex items-center gap-2 text-sm text-text">
        <span aria-hidden="true" className={cx('led', running && 'led--good')} />
        <span role="status">
          {running
            ? `Running ${label ?? 'a workload'} on group “${simulation.group ?? group}”`
            : 'No workload running'}
        </span>
        {running ? (
          <span className="font-mono text-muted tabular-nums">
            {formatElapsed(simulation.elapsedMs)} elapsed
          </span>
        ) : null}
      </p>
      {running && simulation.caption ? (
        <p className="text-sm text-muted">{simulation.caption}</p>
      ) : null}
      {elsewhere ? (
        <p className="text-sm text-warn">
          That workload drives another group; starting here replaces it.
        </p>
      ) : null}
    </div>
  );
}
