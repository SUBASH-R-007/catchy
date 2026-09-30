import { Outlet } from 'react-router';
import { CircuitBackground } from '../theme/CircuitBackground';
import { usePreferences } from './preferences';
import { Sidebar } from './Sidebar';
import { TopBar } from './TopBar';

/** Circuit background, sidebar, top bar and the routed page. */
export function AppShell() {
  const preferences = usePreferences();
  return (
    <>
      <CircuitBackground animated={preferences.animated} />
      <a
        href="#main"
        className="sr-only z-50 rounded-md bg-surface-2 px-4 py-2 focus:not-sr-only focus:fixed focus:top-2 focus:left-2"
      >
        Skip to content
      </a>
      <div className="flex min-h-screen">
        <Sidebar />
        <div className="flex min-w-0 flex-1 flex-col">
          <TopBar preferences={preferences} />
          <main id="main" tabIndex={-1} className="mx-auto w-full max-w-[1400px] flex-1 p-6">
            <Outlet />
          </main>
        </div>
      </div>
    </>
  );
}
