import { api } from '../api/endpoints';
import { Card } from '../components/Card';
import { LiveIndicator } from '../components/LiveIndicator';
import { PageHeader } from '../components/PageHeader';
import { RegionTable } from '../components/RegionTable';
import { usePolling } from '../hooks/usePolling';

/** Global region table: every cache region of every application. */
export function RegionsPage() {
  const overview = usePolling(() => api.overview(), []);
  return (
    <div className="page">
      <PageHeader
        title="Cache regions"
        subtitle="Every cache region across all applications. Memory figures are estimates of cache size, not exact JVM heap."
        actions={<LiveIndicator updatedAt={overview.updatedAt} error={overview.error} paused={overview.paused} loading={overview.loading} />}
      />
      <Card>
        <RegionTable
          regions={overview.data?.regions}
          loading={overview.loading}
          error={overview.error}
          onRetry={() => void overview.refresh()}
          caption="All cache regions"
        />
      </Card>
    </div>
  );
}
