import { Link } from 'react-router-dom';
import { isApiError } from '../api/client';
import { EmptyState } from './States';

export function isNotFound(error: unknown): boolean {
  return isApiError(error) && error.status === 404;
}

interface NotFoundNoticeProps {
  what: string;
  backTo: string;
  backLabel: string;
}

/** Friendly "this record does not exist" state for detail pages that got a 404. */
export function NotFoundNotice({ what, backTo, backLabel }: NotFoundNoticeProps) {
  return (
    <div className="page">
      <EmptyState
        title={`${what} not found`}
        icon="search"
        action={
          <Link to={backTo} className="btn btn--secondary">
            {backLabel}
          </Link>
        }
      >
        It may have been removed, or the link is wrong.
      </EmptyState>
    </div>
  );
}
