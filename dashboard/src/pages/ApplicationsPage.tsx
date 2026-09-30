import { useMemo, useState } from 'react';
import { Link } from 'react-router-dom';
import { api } from '../api/endpoints';
import { ApplicationCard } from '../components/ApplicationCard';
import { Button } from '../components/Button';
import { Field } from '../components/Field';
import { LiveIndicator } from '../components/LiveIndicator';
import { PageHeader } from '../components/PageHeader';
import { EmptyState, ErrorState, LoadingBlock } from '../components/States';
import { useAuth } from '../context/AuthContext';
import { usePolling } from '../hooks/usePolling';
import { mergeSummaries } from '../lib/applications';
import { hasRole } from '../lib/roles';
import { paths } from '../lib/routes';

export function ApplicationsPage() {
  const { user } = useAuth();
  const [projectId, setProjectId] = useState<number | null>(null);
  const [environment, setEnvironment] = useState('');

  const data = usePolling(async () => {
    const [apps, overview] = await Promise.all([api.listApplications(), api.overview()]);
    return { apps, summaries: mergeSummaries(apps, overview.applications) };
  }, []);

  const projects = useMemo(() => {
    const map = new Map<number, string>();
    for (const s of data.data?.summaries ?? []) map.set(s.projectId, s.projectName);
    return [...map.entries()].sort((a, b) => a[1].localeCompare(b[1]));
  }, [data.data]);
  const environments = useMemo(() => [...new Set((data.data?.summaries ?? []).map((s) => s.environment))].sort(), [data.data]);

  const visible = (data.data?.summaries ?? []).filter(
    (s) => (projectId === null || s.projectId === projectId) && (environment === '' || s.environment === environment),
  );
  const filtering = projectId !== null || environment !== '';

  let body;
  if (data.error && !data.data) body = <ErrorState error={data.error} onRetry={() => void data.refresh()} title="Could not load applications" />;
  else if (data.loading || !data.data) body = <LoadingBlock label="Loading applications" lines={6} height={22} />;
  else if (data.data.apps.length === 0) {
    body = (
      <EmptyState
        title="No applications registered"
        icon="layers"
        action={
          <Link to={paths.projects()} className="btn btn--primary">
            Go to projects
          </Link>
        }
      >
        {hasRole(user?.role, 'ADMIN')
          ? 'Open a project and add an application, then create an API key for its SDK.'
          : 'An ADMIN needs to add applications under a project.'}
      </EmptyState>
    );
  } else if (visible.length === 0) {
    body = (
      <EmptyState
        title="No applications match these filters"
        icon="search"
        action={
          <Button
            onClick={() => {
              setProjectId(null);
              setEnvironment('');
            }}
          >
            Clear filters
          </Button>
        }
      >
        Try another project or environment.
      </EmptyState>
    );
  } else {
    body = (
      <div className="grid-cards">
        {visible.map((s) => (
          <ApplicationCard key={s.applicationId} summary={s} />
        ))}
      </div>
    );
  }

  return (
    <div className="page">
      <PageHeader
        title="Applications"
        subtitle="Every registered application, with the health of its cache regions."
        actions={<LiveIndicator updatedAt={data.updatedAt} error={data.error} paused={data.paused} loading={data.loading} />}
      />
      <div className="toolbar" role="group" aria-label="Application filters">
        <Field label="Project">
          {(p) => (
            <select {...p} className="select" value={projectId ?? ''} onChange={(e) => setProjectId(e.target.value ? Number(e.target.value) : null)}>
              <option value="">All projects</option>
              {projects.map(([id, name]) => (
                <option key={id} value={id}>
                  {name}
                </option>
              ))}
            </select>
          )}
        </Field>
        <Field label="Environment">
          {(p) => (
            <select {...p} className="select" value={environment} onChange={(e) => setEnvironment(e.target.value)}>
              <option value="">All environments</option>
              {environments.map((env) => (
                <option key={env} value={env}>
                  {env}
                </option>
              ))}
            </select>
          )}
        </Field>
        <p className="toolbar__count muted" aria-live="polite">
          {data.data ? `Showing ${visible.length} of ${data.data.apps.length} application${data.data.apps.length === 1 ? '' : 's'}` : ''}
          {filtering ? ' (filtered)' : ''}
        </p>
      </div>
      {body}
    </div>
  );
}
