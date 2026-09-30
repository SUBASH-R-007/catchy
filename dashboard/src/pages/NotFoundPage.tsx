import { Link } from 'react-router-dom';
import { EmptyState } from '../components/States';
import { paths } from '../lib/routes';

export function NotFoundPage() {
  return (
    <div className="page">
      <EmptyState
        title="Page not found"
        icon="search"
        action={
          <Link to={paths.overview()} className="btn btn--primary">
            Back to overview
          </Link>
        }
      >
        We could not find that page. Use the navigation above to get back on track.
      </EmptyState>
    </div>
  );
}
