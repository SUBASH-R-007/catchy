import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { Route, Routes } from 'react-router-dom';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { api } from '../api/endpoints';
import type { Application, PolicyChangeRequest } from '../api/types';
import { makeRecommendation, makeRegion, makeRequest } from '../test/fixtures';
import { renderWithProviders } from '../test/utils';
import { AI_LABEL, APPROVAL_STATEMENT, PolicyArenaPage } from './PolicyArenaPage';

const app: Application = {
  id: 1,
  projectId: 1,
  projectName: 'Claims Platform',
  name: 'claims-service',
  displayName: 'Claims Service',
  environment: 'staging',
  description: '',
  createdAt: '2026-09-01T00:00:00Z',
  lastTelemetryAt: '2026-09-30T09:15:00Z',
  regionCount: 1,
};

function stubArena(requests: PolicyChangeRequest[], recOverrides = {}) {
  vi.spyOn(api, 'listApplications').mockResolvedValue([app]);
  vi.spyOn(api, 'listRecommendations').mockResolvedValue([makeRecommendation(recOverrides)]);
  vi.spyOn(api, 'applicationRegions').mockResolvedValue([makeRegion({ riskLevel: 'HIGH', activePolicy: 'LRU', hitRate: 58.7 })]);
  vi.spyOn(api, 'listPolicyRequests').mockResolvedValue(requests);
}

function renderArena(role: 'VIEWER' | 'ENGINEER' | 'ADMIN', username?: string) {
  return renderWithProviders(
    <Routes>
      <Route path="/policy-arena" element={<PolicyArenaPage />} />
    </Routes>,
    { route: '/policy-arena?app=1', role, username },
  );
}

beforeEach(() => {
  vi.restoreAllMocks();
});

describe('Policy Arena: approvals', () => {
  it('disables Approve and Reject when an ENGINEER is the requester, and explains why', async () => {
    stubArena([makeRequest({ requestedBy: 'engineer' })]);
    renderArena('ENGINEER', 'engineer');
    const approve = await screen.findByRole('button', { name: 'Approve' });
    expect(approve).toBeDisabled();
    expect(screen.getByRole('button', { name: 'Reject' })).toBeDisabled();
    expect(screen.getByText(/You requested this change/)).toBeInTheDocument();
    expect(approve).toHaveAccessibleDescription(/You requested this change/);
  });

  it('lets a different engineer approve, sending the optional note', async () => {
    const approveSpy = vi.spyOn(api, 'approvePolicyRequest').mockResolvedValue(makeRequest({ status: 'APPROVED', decidedBy: 'reviewer' }));
    stubArena([makeRequest({ requestedBy: 'engineer' })]);
    renderArena('ENGINEER', 'reviewer');
    const user = userEvent.setup();
    const approve = await screen.findByRole('button', { name: 'Approve' });
    expect(approve).toBeEnabled();
    await user.type(screen.getByLabelText('Decision note (optional)'), 'Shadow gain is stable');
    await user.click(approve);
    await waitFor(() => expect(approveSpy).toHaveBeenCalledWith(3, { note: 'Shadow gain is stable' }));
    expect(await screen.findByText(/Approved request #3/)).toBeInTheDocument();
  });

  it('lets an ADMIN decide their own request', async () => {
    stubArena([makeRequest({ requestedBy: 'admin' })]);
    renderArena('ADMIN', 'admin');
    expect(await screen.findByRole('button', { name: 'Approve' })).toBeEnabled();
  });

  it('shows the backend error as a toast when approval is refused', async () => {
    const { ApiError } = await import('../api/client');
    vi.spyOn(api, 'approvePolicyRequest').mockRejectedValue(new ApiError(403, 'You cannot approve or reject your own request'));
    stubArena([makeRequest({ requestedBy: 'engineer' })]);
    renderArena('ADMIN', 'admin');
    const user = userEvent.setup();
    await user.click(await screen.findByRole('button', { name: 'Approve' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('You cannot approve or reject your own request');
  });

  it('shows APPLIED and REJECTED requests as read-only states', async () => {
    stubArena([
      makeRequest({ id: 5, status: 'APPLIED', appliedAt: '2026-09-30T09:20:00Z', decidedBy: 'admin' }),
      makeRequest({ id: 4, status: 'REJECTED', decidedBy: 'admin', decisionNote: 'Not now' }),
    ]);
    renderArena('ADMIN', 'admin');
    expect(await screen.findByText(/Applied: the region now runs LFU/)).toBeInTheDocument();
    expect(screen.getByText('APPLIED')).toBeInTheDocument();
    expect(screen.getByText('REJECTED')).toBeInTheDocument();
    expect(screen.getByText('Note: Not now')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Approve' })).toBeNull();
  });
});

describe('Policy Arena: recommendations', () => {
  it('states that automation never changes a policy and shows the confidence and checks', async () => {
    stubArena([]);
    renderArena('ENGINEER');
    expect(await screen.findByText(APPROVAL_STATEMENT)).toBeInTheDocument();
    expect(await screen.findByRole('meter', { name: 'Recommendation confidence' })).toHaveAttribute('aria-valuenow', '92');
    expect(screen.getByText(/Sample size 1,000 of 100 required — minimum met/)).toBeInTheDocument();
    expect(screen.getByText('No cooldown active')).toBeInTheDocument();
    expect(screen.getByText('+11.5 pts')).toBeInTheDocument();
  });

  it('labels an AI explanation as advisory only', async () => {
    stubArena([], { aiExplanation: 'Eligibility lookups are dominated by a few plans.' });
    renderArena('VIEWER');
    expect(await screen.findByText('Eligibility lookups are dominated by a few plans.')).toBeInTheDocument();
    expect(screen.getByText(AI_LABEL)).toBeInTheDocument();
  });

  it('offers a primary "Request switch" for SWITCH and only a secondary "Request anyway" for KEEP', async () => {
    stubArena([]);
    const first = renderArena('ENGINEER');
    expect(await screen.findByRole('button', { name: 'Request switch to LFU' })).toBeInTheDocument();
    first.unmount();
    stubArena([], { action: 'KEEP', recommendedPolicy: 'LRU', summary: 'Keep LRU', improvementPercent: 2 });
    renderArena('ENGINEER');
    expect(await screen.findByRole('button', { name: 'Request LFU anyway' })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /Request switch/ })).toBeNull();
  });

  it('disables requesting while a request is already pending', async () => {
    stubArena([makeRequest({ requestedBy: 'admin' })], { pendingRequestId: 3 });
    renderArena('ENGINEER', 'reviewer');
    expect(await screen.findByText(/Request #3 is already pending/)).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /Request switch/ })).toBeNull();
  });

  it('submits a policy-change request with the recommendation id', async () => {
    const create = vi.spyOn(api, 'createPolicyRequest').mockResolvedValue(makeRequest());
    stubArena([]);
    renderArena('ENGINEER', 'engineer');
    const user = userEvent.setup();
    await user.click(await screen.findByRole('button', { name: 'Request switch to LFU' }));
    const dialog = await screen.findByRole('dialog');
    expect(within(dialog).getByText(/Nothing changes until an engineer other than you/i)).toBeInTheDocument();
    await user.click(within(dialog).getByRole('button', { name: 'Submit request' }));
    await waitFor(() =>
      expect(create).toHaveBeenCalledWith(1, {
        cacheRegion: 'claim-rules',
        requestedPolicy: 'LFU',
        reason: 'Policy Arena recommends LFU (+11.5 pts).',
        recommendationId: 7,
      }),
    );
    expect(await screen.findByText(/Requested LFU for claim-rules/)).toBeInTheDocument();
  });

  it('re-evaluates on demand (ENGINEER+)', async () => {
    const evaluate = vi.spyOn(api, 'evaluateRecommendations').mockResolvedValue([makeRecommendation()]);
    stubArena([]);
    renderArena('ENGINEER');
    const user = userEvent.setup();
    await user.click(await screen.findByRole('button', { name: 'Re-evaluate' }));
    await waitFor(() => expect(evaluate).toHaveBeenCalledWith(1));
    expect(await screen.findByText(/Re-evaluated 1 region/)).toBeInTheDocument();
  });
});
