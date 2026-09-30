import { useRef, type CSSProperties, type KeyboardEvent } from 'react';
import './components.css';
import { cx } from './cx';

export interface DipSwitchOption<T extends string> {
  value: T;
  label: string;
  /** CSS colour for the knob when this switch is on. Defaults to var(--trace-glow). */
  color?: string;
}

export interface DipSwitchProps<T extends string> {
  /** Accessible name of the radiogroup, also shown as a silkscreen caption. */
  label: string;
  options: ReadonlyArray<DipSwitchOption<T>>;
  value: T;
  onChange: (value: T) => void;
  disabled?: boolean;
  /** Hide the visible silkscreen caption (the group keeps its aria-label). */
  hideLabel?: boolean;
  className?: string;
}

const NEXT_KEYS = new Set(['ArrowRight', 'ArrowDown']);
const PREVIOUS_KEYS = new Set(['ArrowLeft', 'ArrowUp']);

/**
 * A single-choice selector drawn as a bank of DIP switches (WAI-ARIA radiogroup).
 * Arrow keys move focus and select (wrapping); Space/Enter and click select; only the
 * checked switch is in the tab order.
 */
export function DipSwitch<T extends string>({
  label,
  options,
  value,
  onChange,
  disabled = false,
  hideLabel = false,
  className,
}: DipSwitchProps<T>) {
  const groupRef = useRef<HTMLDivElement>(null);
  const checkedIndex = options.findIndex((option) => option.value === value);
  const tabbableIndex = checkedIndex >= 0 ? checkedIndex : 0;

  const select = (index: number) => {
    const option = options[index];
    if (!option || disabled) return;
    if (option.value !== value) onChange(option.value);
  };

  const onKeyDown = (event: KeyboardEvent<HTMLButtonElement>, index: number) => {
    const step = NEXT_KEYS.has(event.key) ? 1 : PREVIOUS_KEYS.has(event.key) ? -1 : 0;
    if (step === 0 || disabled || options.length === 0) return;
    event.preventDefault();
    const nextIndex = (index + step + options.length) % options.length;
    const radios = groupRef.current?.querySelectorAll<HTMLButtonElement>('[role="radio"]');
    radios?.[nextIndex]?.focus();
    select(nextIndex);
  };

  return (
    <div className={cx('inline-flex flex-col gap-2', className)}>
      {hideLabel ? null : (
        <span aria-hidden="true" className="font-mono text-sm uppercase tracking-widest text-muted">
          {label}
        </span>
      )}
      <div
        ref={groupRef}
        role="radiogroup"
        aria-label={label}
        aria-disabled={disabled || undefined}
        className={cx(
          'inline-flex items-start gap-2 rounded border border-trace bg-surface-2 px-2 pb-2 pt-1',
          disabled && 'opacity-50',
        )}
      >
        <span aria-hidden="true" className="self-start font-mono text-sm leading-none text-muted">
          ON
        </span>
        {options.map((option, index) => {
          const checked = index === checkedIndex;
          const knobStyle = option.color
            ? ({ '--dip-color': option.color } as CSSProperties)
            : undefined;
          return (
            <button
              key={option.value}
              type="button"
              role="radio"
              aria-checked={checked}
              tabIndex={index === tabbableIndex ? 0 : -1}
              disabled={disabled}
              onClick={() => select(index)}
              onKeyDown={(event) => onKeyDown(event, index)}
              style={knobStyle}
              className={cx(
                'dip-option flex min-w-12 flex-col items-center gap-2 rounded-sm px-1 pt-2',
                disabled ? 'cursor-not-allowed' : 'cursor-pointer',
              )}
            >
              <span aria-hidden="true" className="dip-track">
                <span className="dip-knob" />
              </span>
              <span
                className={cx(
                  'whitespace-nowrap font-mono text-sm',
                  checked ? 'text-text' : 'text-muted',
                )}
              >
                {option.label}
              </span>
            </button>
          );
        })}
      </div>
    </div>
  );
}
