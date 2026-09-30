import { http } from './client';
import type {
  ApiKey,
  ApiKeyCreated,
  Application,
  ApplicationMetrics,
  AuditLogEntry,
  AuditLogQuery,
  CreateApplicationRequest,
  CreateApiKeyRequest,
  CreatePolicyChangeRequest,
  CreateProjectRequest,
  CurrentUser,
  DecisionRequest,
  EventQuery,
  CacheEvent,
  Health,
  HealthCheckResponse,
  LoginRequest,
  LoginResponse,
  Overview,
  PolicyChangeRequest,
  Project,
  ProjectMetrics,
  Recommendation,
  RegionConfig,
  RegionMetrics,
  Role,
  SimulationKind,
  SimulationResult,
  Timeline,
  TimelineQuery,
  UpdateRegionConfigRequest,
} from './types';

const enc = encodeURIComponent;

function eventQuery(q: EventQuery = {}) {
  return {
    limit: q.limit,
    actions: q.actions && q.actions.length > 0 ? q.actions.join(',') : undefined,
    since: q.since,
  };
}

/**
 * The single typed entry point for the telemetry service. Pages call `api.*`;
 * in tests the methods are easy to spy on, and in mock mode the transport below is swapped.
 */
export const api = {
  // --- auth -------------------------------------------------------------
  login: (req: LoginRequest) =>
    http.post<LoginResponse>('/auth/login', req, { skipAuthRedirect: true }),
  demoLogin: (role: Role) =>
    http.post<LoginResponse>('/auth/demo-login', { role }, { skipAuthRedirect: true }),
  me: () => http.get<CurrentUser>('/auth/me'),
  serviceHealth: () => http.get<HealthCheckResponse>('/health'),

  // --- projects / applications ------------------------------------------
  listProjects: () => http.get<Project[]>('/projects'),
  getProject: (id: number) => http.get<Project>(`/projects/${id}`),
  createProject: (req: CreateProjectRequest) => http.post<Project>('/projects', req),
  projectMetrics: (id: number) => http.get<ProjectMetrics>(`/projects/${id}/metrics`),
  listApplications: () => http.get<Application[]>('/applications'),
  listProjectApplications: (projectId: number) =>
    http.get<Application[]>(`/projects/${projectId}/applications`),
  getApplication: (id: number) => http.get<Application>(`/applications/${id}`),
  createApplication: (projectId: number, req: CreateApplicationRequest) =>
    http.post<Application>(`/projects/${projectId}/applications`, req),

  // --- API keys ---------------------------------------------------------
  listApiKeys: (applicationId: number) => http.get<ApiKey[]>(`/applications/${applicationId}/api-keys`),
  createApiKey: (applicationId: number, req: CreateApiKeyRequest) =>
    http.post<ApiKeyCreated>(`/applications/${applicationId}/api-keys`, req),
  revokeApiKey: (apiKeyId: number) => http.delete<void>(`/api-keys/${apiKeyId}`),

  // --- metrics ----------------------------------------------------------
  overview: () => http.get<Overview>('/overview'),
  applicationMetrics: (id: number) => http.get<ApplicationMetrics>(`/applications/${id}/metrics`),
  applicationRegions: (id: number) => http.get<RegionMetrics[]>(`/applications/${id}/regions`),
  regionMetrics: (id: number, region: string) =>
    http.get<RegionMetrics>(`/applications/${id}/regions/${enc(region)}/metrics`),
  regionHealth: (id: number, region: string) =>
    http.get<Health>(`/applications/${id}/regions/${enc(region)}/health`),
  regionEvents: (id: number, region: string, q?: EventQuery) =>
    http.get<CacheEvent[]>(`/applications/${id}/regions/${enc(region)}/events`, eventQuery(q)),
  applicationEvents: (id: number, q?: EventQuery) =>
    http.get<CacheEvent[]>(`/applications/${id}/events`, eventQuery(q)),
  regionTimeline: (id: number, region: string, q?: TimelineQuery) =>
    http.get<Timeline>(`/applications/${id}/regions/${enc(region)}/timeline`, { ...q }),
  applicationTimeline: (id: number, q?: TimelineQuery) =>
    http.get<Timeline>(`/applications/${id}/timeline`, { ...q }),
  globalTimeline: (q?: TimelineQuery) => http.get<Timeline>('/timeline', { ...q }),

  // --- region configuration --------------------------------------------
  getRegionConfig: (id: number, region: string) =>
    http.get<RegionConfig>(`/applications/${id}/regions/${enc(region)}/config`),
  updateRegionConfig: (id: number, region: string, req: UpdateRegionConfigRequest) =>
    http.put<RegionConfig>(`/applications/${id}/regions/${enc(region)}/config`, req),

  // --- recommendations / policy change requests ---------------------------
  listRecommendations: (applicationId: number) =>
    http.get<Recommendation[]>(`/applications/${applicationId}/recommendations`),
  evaluateRecommendations: (applicationId: number) =>
    http.post<Recommendation[]>(`/applications/${applicationId}/recommendations/evaluate`),
  listPolicyRequests: (applicationId: number) =>
    http.get<PolicyChangeRequest[]>(`/applications/${applicationId}/policy-change-requests`),
  createPolicyRequest: (applicationId: number, req: CreatePolicyChangeRequest) =>
    http.post<PolicyChangeRequest>(`/applications/${applicationId}/policy-change-requests`, req),
  approvePolicyRequest: (requestId: number, req: DecisionRequest = {}) =>
    http.post<PolicyChangeRequest>(`/policy-change-requests/${requestId}/approve`, req),
  rejectPolicyRequest: (requestId: number, req: DecisionRequest = {}) =>
    http.post<PolicyChangeRequest>(`/policy-change-requests/${requestId}/reject`, req),

  // --- audit log --------------------------------------------------------
  auditLogs: (q: AuditLogQuery = {}) =>
    http.get<AuditLogEntry[]>('/audit-logs', {
      limit: q.limit,
      action: q.action || undefined,
      applicationId: q.applicationId ?? undefined,
    }),

  // --- simulations ------------------------------------------------------
  simulate: (applicationId: number, kind: SimulationKind, requests?: number) =>
    http.post<SimulationResult>(
      `/applications/${applicationId}/simulate/${kind}`,
      requests !== undefined ? { requests } : undefined,
    ),
};

export type Api = typeof api;
