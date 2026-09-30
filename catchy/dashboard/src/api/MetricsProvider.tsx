import type { ReactNode } from 'react';
import { MetricsContext } from './metricsContext';
import { useMetricsStream } from './useMetricsStream';

/** Opens the single metrics stream connection for the whole app. */
export function MetricsProvider({ children }: { children: ReactNode }) {
  const value = useMetricsStream();
  return <MetricsContext.Provider value={value}>{children}</MetricsContext.Provider>;
}
