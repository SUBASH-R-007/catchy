import { RotateCw, TriangleAlert } from 'lucide-react';
import { cx } from './cx';

export interface ErrorStateProps {
  /** Defaults to "Something went wrong". */
  title?: string;
  message: string;
  /** Shows a Retry button when given. */
  onRetry?: () => void;
  className?: string;
}

/** Error panel for a data view that failed to load (announced via role="alert"). */
export function ErrorState({
  title = 'Something went wrong',
  message,
  onRetry,
  className,
}: ErrorStateProps) {
  return (
    <div
      role="alert"
      className={cx(
        'flex flex-col items-center justify-center gap-2 rounded border border-critical/50 bg-bg/60 px-6 py-8 text-center',
        className,
      )}
    >
      <TriangleAlert aria-hidden="true" size={24} className="text-critical" />
      <p className="font-heading text-base text-text">{title}</p>
      <p className="max-w-[56ch] text-sm text-muted">{message}</p>
      {onRetry ? (
        <button
          type="button"
          onClick={onRetry}
          className="mt-2 inline-flex items-center gap-2 rounded border border-trace bg-surface-2 px-4 py-2 text-sm text-text transition-colors hover:border-trace-glow hover:text-trace-glow"
        >
          <RotateCw aria-hidden="true" size={16} />
          Retry
        </button>
      ) : null}
    </div>
  );
}
