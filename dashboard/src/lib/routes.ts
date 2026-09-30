/** Central place for in-app links so pages never hand-build URLs. */
export const paths = {
  overview: () => '/',
  login: () => '/login',
  projects: () => '/projects',
  project: (id: number | string) => `/projects/${id}`,
  applications: () => '/applications',
  application: (id: number | string) => `/applications/${id}`,
  region: (appId: number | string, region: string) =>
    `/applications/${appId}/regions/${encodeURIComponent(region)}`,
  regions: () => '/regions',
  policyArena: (appId?: number | string | null) =>
    appId != null ? `/policy-arena?app=${appId}` : '/policy-arena',
  recommendations: () => '/recommendations',
  simulations: (appId?: number | string | null) =>
    appId != null ? `/simulations?app=${appId}` : '/simulations',
  audit: () => '/audit-log',
  configuration: (appId?: number | string | null, region?: string | null) => {
    const q = new URLSearchParams();
    if (appId != null) q.set('app', String(appId));
    if (region) q.set('region', region);
    const s = q.toString();
    return s ? `/configuration?${s}` : '/configuration';
  },
} as const;
