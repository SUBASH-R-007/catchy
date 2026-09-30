import { BrowserRouter, Route, Routes } from 'react-router';
import { MetricsProvider } from './api/MetricsProvider';
import { AppShell } from './app/AppShell';
import { ToastProvider } from './components';
import { OverviewPage } from './pages/OverviewPage';
import { PlaygroundPage } from './pages/PlaygroundPage';
import {
  ConcurrencyLabPage,
  IntegrationsPage,
  PolicyRacePage,
  TraceReplayPage,
} from './pages/stubs';

export function App() {
  return (
    <ToastProvider>
      <MetricsProvider>
        <BrowserRouter>
          <Routes>
            <Route element={<AppShell />}>
              <Route index element={<OverviewPage />} />
              <Route path="race" element={<PolicyRacePage />} />
              <Route path="concurrency" element={<ConcurrencyLabPage />} />
              <Route path="playground" element={<PlaygroundPage />} />
              <Route path="replay" element={<TraceReplayPage />} />
              <Route path="integrations" element={<IntegrationsPage />} />
              <Route path="*" element={<OverviewPage />} />
            </Route>
          </Routes>
        </BrowserRouter>
      </MetricsProvider>
    </ToastProvider>
  );
}
