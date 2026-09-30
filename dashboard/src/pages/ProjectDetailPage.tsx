import { useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import { api } from '../api/endpoints';
import { ApplicationCard } from '../components/ApplicationCard';
import { HealthBadge } from '../components/Badge';
import { Button } from '../components/Button';
import { Card } from '../components/Card';
import { NewApplicationModal } from '../components/CreateForms';
import { HealthPanel } from '../components/HealthPanel';
import { LiveIndicator } from '../components/LiveIndicator';
import { isNotFound, NotFoundNotice } from '../components/NotFoundNotice';
import { PageHeader } from '../components/PageHeader';
import { EmptyState, ErrorState, LoadingBlock, TileSkeletons } from '../components/States';
import { Tile } from '../components/Tile';
import { useAuth } from '../context/AuthContext';
import { usePolling, useFetch } from '../hooks/usePolling';
import { mergeSummaries } from '../lib/applications';
import { formatBytes, formatCompact, formatInt, formatPercent } from '../lib/format';
import { hasRole } from '../lib/roles';
import { paths } from '../lib/routes';

export function ProjectDetailPage() {
  const { projectId } = useParams();
  const id = Number(projectId);
  if (!Number.isInteger(id) || id <= 0) return <NotFoundNotice what="Project" backTo={paths.projects()} backLabel="All projects" />;
  return <ProjectDetail key={id} id={id} />;
}

function ProjectDetail({ id }: { id: number }) {
  const { user } = useAuth();
  const navigate = useNavigate();
  const [adding, setAdding] = useState(false);
  const project = useFetch(() => api.getProject(id), [id]);
  const metrics = usePolling(() => api.projectMetrics(id), [id]);
  const apps = usePolling(() => api.listProjectApplications(id), [id]);
  const isAdmin = hasRole(user?.role, 'ADMIN');

  if (isNotFound(project.error) || isNotFound(metrics.error)) {
    return <NotFoundNotice what="Project" backTo={paths.projects()} backLabel="All projects" />;
  }

  const m = metrics.data;
  const summaries = m && apps.data ? mergeSummaries(apps.data, m.applications) : (m?.applications ?? []);

  return (
    <div className="page">
      <PageHeader
        crumbs={[{ label: 'Projects', to: paths.projects() }, { label: project.data?.name ?? 'Project' }]}
        title={project.data?.name ?? 'Project'}
        subtitle={project.data?.description || undefined}
        badges={m ? <HealthBadge status={m.health.status} /> : undefined}
        actions={
          <>
            <LiveIndicator updatedAt={metrics.updatedAt} error={metrics.error} paused={metrics.paused} loading={metrics.loading} />
            {isAdmin ? (
              <Button variant="primary" icon="plus" onClick={() => setAdding(true)}>
                Add application
              </Button>
            ) : null}
          </>
        }
      />

      {metrics.error && !m ? (
        <ErrorState error={metrics.error} onRetry={() => void metrics.refresh()} title="Could not load project metrics" />
      ) : !m ? (
        <TileSkeletons count={6} />
      ) : (
        <div className="stack">
          <div className="grid-kpi">
            <Tile label="Applications" value={formatInt(m.applicationCount)} />
            <Tile label="Cache regions" value={formatInt(m.regionCount)} />
            <Tile label="Hit rate" value={formatPercent(m.totals.hitRate)} sub={`${formatInt(m.totals.hits)} hits · ${formatInt(m.totals.misses)} misses`} />
            <Tile
              label="Memory"
              qualifier="estimated"
              value={formatBytes(m.totals.estimatedMemoryUsageBytes)}
              sub={`of ${formatBytes(m.totals.maximumMemoryBytes)} · ${formatPercent(m.totals.memoryUtilizationPercent, 1)}`}
            />
            <Tile label="Evictions" value={formatInt(m.totals.evictions)} sub={`${formatInt(m.totals.expirations)} expirations`} />
            <Tile label="Source calls avoided" value={formatCompact(m.totals.sourceCallsAvoided)} />
          </div>

          <div className="grid-2-1">
            <section aria-labelledby="proj-apps" className="stack-sm">
              <h2 id="proj-apps">Applications</h2>
              {apps.loading && summaries.length === 0 ? (
                <LoadingBlock label="Loading applications" lines={4} />
              ) : summaries.length === 0 ? (
                <Card>
                  <EmptyState
                    title="No applications in this project"
                    icon="layers"
                    action={isAdmin ? <Button variant="primary" icon="plus" onClick={() => setAdding(true)}>Add application</Button> : undefined}
                  >
                    {isAdmin ? 'Add an application, then create an API key so its SDK can send telemetry.' : 'An ADMIN needs to add the first application.'}
                  </EmptyState>
                </Card>
              ) : (
                <div className="grid-cards">
                  {summaries.map((s) => (
                    <ApplicationCard key={s.applicationId} summary={s} />
                  ))}
                </div>
              )}
            </section>
            <Card title="Project health" subtitle="Worst application health in this project.">
              <HealthPanel health={m.health} />
            </Card>
          </div>
        </div>
      )}

      <NewApplicationModal
        open={adding}
        projectId={id}
        onClose={() => setAdding(false)}
        onCreated={(app) => {
          setAdding(false);
          void apps.refresh();
          void metrics.refresh();
          navigate(paths.application(app.id));
        }}
      />
    </div>
  );
}
