import { useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { api } from '../api/endpoints';
import type { Project } from '../api/types';
import { Button } from '../components/Button';
import { Card } from '../components/Card';
import { NewProjectModal } from '../components/CreateForms';
import { PageHeader } from '../components/PageHeader';
import { EmptyState, ErrorState, LoadingBlock } from '../components/States';
import { Table, type Column } from '../components/Table';
import { useAuth } from '../context/AuthContext';
import { useFetch } from '../hooks/usePolling';
import { formatDateTime, plural } from '../lib/format';
import { hasRole } from '../lib/roles';
import { paths } from '../lib/routes';

export function ProjectsPage() {
  const { user } = useAuth();
  const navigate = useNavigate();
  const projects = useFetch(() => api.listProjects(), []);
  const [creating, setCreating] = useState(false);
  const isAdmin = hasRole(user?.role, 'ADMIN');

  const columns: Column<Project>[] = [
    { key: 'name', header: 'Project', cell: (p) => <Link to={paths.project(p.id)}>{p.name}</Link> },
    { key: 'desc', header: 'Description', cell: (p) => p.description || <span className="faint">—</span> },
    { key: 'apps', header: 'Applications', align: 'right', className: 'num', cell: (p) => plural(p.applicationCount, 'application') },
    { key: 'created', header: 'Created', className: 'nowrap', cell: (p) => formatDateTime(p.createdAt) },
  ];

  let body;
  if (projects.error && !projects.data) body = <ErrorState error={projects.error} onRetry={() => void projects.refresh()} title="Could not load projects" />;
  else if (projects.loading || !projects.data) body = <LoadingBlock label="Loading projects" lines={4} height={20} />;
  else if (projects.data.length === 0) {
    body = (
      <EmptyState
        title="No projects yet"
        icon="layers"
        action={isAdmin ? <Button variant="primary" icon="plus" onClick={() => setCreating(true)}>New project</Button> : undefined}
      >
        {isAdmin ? 'Create a project to group the applications of one product area.' : 'An ADMIN needs to create the first project.'}
      </EmptyState>
    );
  } else body = <Table columns={columns} rows={projects.data} rowKey={(p) => p.id} caption="Projects" />;

  return (
    <div className="page">
      <PageHeader
        title="Projects"
        subtitle="Projects group the applications of one product area."
        actions={
          isAdmin ? (
            <Button variant="primary" icon="plus" onClick={() => setCreating(true)}>
              New project
            </Button>
          ) : undefined
        }
      />
      <Card>{body}</Card>
      {!isAdmin ? <p className="faint">Creating projects requires the ADMIN role.</p> : null}
      <NewProjectModal
        open={creating}
        onClose={() => setCreating(false)}
        onCreated={(p) => {
          setCreating(false);
          void projects.refresh();
          navigate(paths.project(p.id));
        }}
      />
    </div>
  );
}
