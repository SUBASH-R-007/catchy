import { useId, type ReactNode } from 'react';
import './components.css';
import { cx } from './cx';
import { InfoPopover } from './InfoPopover';

export interface ChipCardProps {
  /** Silkscreen designator, e.g. "U1 · Hit rate" (rendered uppercase). */
  label: string;
  /** Optional heading, rendered as an h2. */
  title?: string;
  /** One plain sentence explaining the card, shown in an InfoPopover. */
  info?: string;
  children?: ReactNode;
  className?: string;
  /** Extra controls shown top-right, before the info button. */
  actions?: ReactNode;
}

/** "U1 · Hit rate" -> "Hit rate": the part after the designator reads better in "About …". */
function withoutDesignator(label: string): string {
  const parts = label.split('·');
  const last = parts[parts.length - 1]?.trim();
  return last ? last : label;
}

/** A card drawn as an IC package: opaque surface, trace border, pin rows and a silkscreen label. */
export function ChipCard({ label, title, info, children, className, actions }: ChipCardProps) {
  const id = useId();
  const labelId = `${id}-label`;
  const titleId = `${id}-title`;
  const hasTools = Boolean(actions) || Boolean(info);

  return (
    <section
      aria-labelledby={title ? titleId : labelId}
      className={cx('chip-card rounded border border-trace bg-surface p-6', className)}
    >
      <div className="flex items-start justify-between gap-4">
        <div className="min-w-0">
          <p id={labelId} className="font-mono text-sm uppercase tracking-widest text-muted">
            {label}
          </p>
          {title ? (
            <h2 id={titleId} className="mt-1 font-heading text-lg text-text">
              {title}
            </h2>
          ) : null}
        </div>
        {hasTools ? (
          <div className="flex shrink-0 items-center gap-2">
            {actions}
            {info ? (
              <InfoPopover label={title ?? withoutDesignator(label)}>{info}</InfoPopover>
            ) : null}
          </div>
        ) : null}
      </div>
      {children !== undefined && children !== null ? <div className="mt-4">{children}</div> : null}
    </section>
  );
}
