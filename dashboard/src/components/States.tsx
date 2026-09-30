import type { ReactNode } from 'react';
import { describeError } from '../api/client';
import { Button } from './Button';
import { Icon, type IconName } from './Icon';

interface EmptyStateProps {
  title: string;
  children?: ReactNode;
  action?: ReactNode;
  icon?: IconName;
  className?: string;
}

/** Friendly empty state: what is missing and what to do next. */
export function EmptyState({ title, children, action, icon = 'layers', className }: EmptyStateProps) {
  return (
    <div className={`empty${className ? ` ${className}` : ''}`}>
      <span className="empty__icon" aria-hidden="true">
        <Icon name={icon} size={22} />
      </span>
      <h3 className="empty__title">{title}</h3>
      {children ? <p className="empty__text">{children}</p> : null}
      {action ? <div className="empty__action">{action}</div> : null}
    </div>
  );
}

interface ErrorStateProps {
  error: unknown;
  onRetry?: () => void;
  title?: string;
  className?: string;
}

export function ErrorState({ error, onRetry, title = 'Could not load this data', className }: ErrorStateProps) {
  return (
    <div className={`error-state${className ? ` ${className}` : ''}`} role="alert">
      <span className="error-state__icon" aria-hidden="true">
        <Icon name="alert" size={20} />
      </span>
      <div className="error-state__body">
        <h3 className="error-state__title">{title}</h3>
        <p className="error-state__text">{describeError(error)}</p>
      </div>
      {onRetry ? (
        <Button size="sm" icon="refresh" onClick={onRetry}>
          Retry
        </Button>
      ) : null}
    </div>
  );
}

interface SkeletonProps {
  height?: number | string;
  width?: number | string;
  className?: string;
}

export function Skeleton({ height = 14, width = '100%', className }: SkeletonProps) {
  return <span className={`skeleton${className ? ` ${className}` : ''}`} style={{ height, width }} aria-hidden="true" />;
}

/** A block of skeleton lines with an accessible "Loading" announcement. */
export function LoadingBlock({ label = 'Loading', lines = 3, height = 14 }: { label?: string; lines?: number; height?: number }) {
  return (
    <div className="loading-block" role="status" aria-live="polite">
      <span className="sr-only">{label}…</span>
      {Array.from({ length: lines }, (_, i) => (
        <Skeleton key={i} height={height} width={i === lines - 1 ? '62%' : '100%'} />
      ))}
    </div>
  );
}

/** Grid of skeleton tiles shown while KPI data loads. */
export function TileSkeletons({ count = 8 }: { count?: number }) {
  return (
    <div className="grid-kpi" role="status" aria-live="polite">
      <span className="sr-only">Loading metrics…</span>
      {Array.from({ length: count }, (_, i) => (
        <div key={i} className="tile" aria-hidden="true">
          <Skeleton height={12} width="55%" />
          <Skeleton height={28} width="70%" className="skeleton--mt" />
          <Skeleton height={10} width="40%" className="skeleton--mt" />
        </div>
      ))}
    </div>
  );
}

/** Full-page centered spinner used while the stored session is validated. */
export function PageLoading({ label = 'Loading' }: { label?: string }) {
  return (
    <div className="page-loading" role="status" aria-live="polite">
      <span className="spinner spinner--lg" aria-hidden="true" />
      <span>{label}…</span>
    </div>
  );
}
