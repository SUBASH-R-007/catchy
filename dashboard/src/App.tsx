import { Route, Routes } from 'react-router-dom';
import { AppShell } from './components/AppShell';
import { RequireAuth, RequireRole } from './components/RequireRole';
import { ApplicationDetailPage } from './pages/ApplicationDetailPage';
import { ApplicationsPage } from './pages/ApplicationsPage';
import { AuditLogPage } from './pages/AuditLogPage';
import { ConfigurationPage } from './pages/ConfigurationPage';
import { LoginPage } from './pages/LoginPage';
import { NotFoundPage } from './pages/NotFoundPage';
import { OverviewPage } from './pages/OverviewPage';
import { PolicyArenaPage } from './pages/PolicyArenaPage';
import { ProjectDetailPage } from './pages/ProjectDetailPage';
import { ProjectsPage } from './pages/ProjectsPage';
import { RecommendationsPage } from './pages/RecommendationsPage';
import { RegionDetailPage } from './pages/RegionDetailPage';
import { RegionsPage } from './pages/RegionsPage';
import { SimulationsPage } from './pages/SimulationsPage';

/** Route table. Admin-only pages are guarded client-side; the backend remains the real enforcement. */
export function AppRoutes() {
  return (
    <Routes>
      <Route path="/login" element={<LoginPage />} />
      <Route
        element={
          <RequireAuth>
            <AppShell />
          </RequireAuth>
        }
      >
        <Route index element={<OverviewPage />} />
        <Route path="projects" element={<ProjectsPage />} />
        <Route path="projects/:projectId" element={<ProjectDetailPage />} />
        <Route path="applications" element={<ApplicationsPage />} />
        <Route path="applications/:appId" element={<ApplicationDetailPage />} />
        <Route path="applications/:appId/regions/:region" element={<RegionDetailPage />} />
        <Route path="regions" element={<RegionsPage />} />
        <Route path="policy-arena" element={<PolicyArenaPage />} />
        <Route path="recommendations" element={<RecommendationsPage />} />
        <Route path="simulations" element={<SimulationsPage />} />
        <Route
          path="audit-log"
          element={
            <RequireRole min="ADMIN" feature="The audit log">
              <AuditLogPage />
            </RequireRole>
          }
        />
        <Route
          path="configuration"
          element={
            <RequireRole min="ADMIN" feature="Configuration">
              <ConfigurationPage />
            </RequireRole>
          }
        />
        <Route path="*" element={<NotFoundPage />} />
      </Route>
    </Routes>
  );
}
