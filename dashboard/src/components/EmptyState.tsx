import { CircuitBoard } from 'lucide-react';
import type { ReactNode } from 'react';
import { cx } from './cx';

export interface EmptyStateProps {
  title: string;
  message?: string;
  /** Decorative icon; defaults to an empty circuit board. */
  icon?: ReactNode;
  /** A call to action, e.g. a "Start simulation" button. */
  action?: ReactNode;
  className?: string;
}

/** Placeholder for a data view with nothing to show yet. */
export function EmptyState({ title, message, icon, action, className }: EmptyStateProps) {
  return (
    <div
      className={cx(
        'flex flex-col items-center justify-center gap-2 rounded border border-dashed border-trace px-6 py-8 text-center',
        className,
      )}
    >
      <span aria-hidden="true" className="text-muted">
        {icon ?? <CircuitBoard size={24} strokeWidth={1.75} />}
      </span>
      <p className="font-heading text-base text-text">{title}</p>
      {message ? <p className="max-w-[48ch] text-sm text-muted">{message}</p> : null}
      {action ? <div className="mt-2">{action}</div> : null}
    </div>
  );
}
