import { FlaskConical } from 'lucide-react';
import { cx } from './cx';

export interface SampleBadgeProps {
  /** e.g. "Sample data — run benchmarks" or "Estimate". */
  text?: string;
  className?: string;
}

/** Marks a view that shows sample or estimated data rather than live measurements. */
export function SampleBadge({ text = 'Sample data', className }: SampleBadgeProps) {
  return (
    <span
      className={cx(
        'inline-flex items-center gap-2 rounded-full border border-warn/50 bg-bg px-2 py-1 text-sm leading-none text-text',
        className,
      )}
    >
      <FlaskConical aria-hidden="true" size={14} className="shrink-0 text-warn" />
      <span>{text}</span>
    </span>
  );
}
