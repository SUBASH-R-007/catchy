import { createContext, useContext } from 'react';
import type { MetricsStreamValue } from './useMetricsStream';

export const MetricsContext = createContext<MetricsStreamValue | null>(null);

/** Reads the shared metrics stream provided by MetricsProvider. */
export function useMetrics(): MetricsStreamValue {
  const value = useContext(MetricsContext);
  if (value === null) throw new Error('useMetrics() must be used inside <MetricsProvider>');
  return value;
}
