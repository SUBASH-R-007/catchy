import './components.css';
import { cx } from './cx';

export interface SkeletonProps {
  /** Sizes the placeholder; with a fixed height the blocks stretch to fill it. */
  className?: string;
  /** Number of placeholder blocks. Defaults to 3. */
  lines?: number;
  /** Accessible label. Defaults to "Loading". */
  label?: string;
}

/** Shimmering placeholder blocks shown while a data view loads (static under reduced motion). */
export function Skeleton({ className, lines = 3, label = 'Loading' }: SkeletonProps) {
  const count = Math.max(1, Math.floor(lines));

  return (
    <div
      role="status"
      aria-busy="true"
      aria-label={label}
      className={cx('flex flex-col gap-2', className)}
    >
      {Array.from({ length: count }, (_, index) => (
        <span
          key={index}
          aria-hidden="true"
          className={cx(
            'skeleton-block block min-h-4 flex-1 rounded-sm',
            count > 1 && index === count - 1 ? 'w-3/5' : 'w-full',
          )}
        />
      ))}
      <span className="sr-only">{label}</span>
    </div>
  );
}
