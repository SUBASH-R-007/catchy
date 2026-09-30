import { screen, within } from '@testing-library/react';
import { afterAll, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';
import { AppRoutes } from '../App';
import type { Role } from '../api/types';
import { mockSignIn, renderWithProviders } from '../test/utils';

async function renderAs(role: Role, route: string) {
  const { configureMock } = await import('../api/mock');
  configureMock({ latencyMs: 0, preseedTicks: 20 });
  await mockSignIn(role);
  return renderWithProviders(<AppRoutes />, { role, route });
}

beforeAll(() => {
  vi.stubEnv('VITE_MOCK_API', 'true');
});
afterAll(() => {
  vi.unstubAllEnvs();
});
beforeEach(() => {
  window.sessionStorage.clear();
});

const nav = () => within(screen.getByRole('navigation', { name: 'Main' }));

describe('role gating: VIEWER', () => {
  it('does not see Audit log or Configuration in the navigation', async () => {
    await renderAs('VIEWER', '/');
    expect(await screen.findByRole('heading', { name: 'Overview' })).toBeInTheDocument();
    expect(nav().getByRole('link', { name: 'Overview' })).toBeInTheDocument();
    expect(nav().getByRole('link', { name: 'Policy Arena' })).toBeInTheDocument();
    expect(nav().queryByRole('link', { name: 'Audit log' })).toBeNull();
    expect(nav().queryByRole('link', { name: 'Configuration' })).toBeNull();
    expect(screen.getByText('VIEWER')).toBeInTheDocument();
  });

  it('gets a friendly "requires ADMIN" page for the audit log and configuration', async () => {
    const first = await renderAs('VIEWER', '/audit-log');
    expect(await screen.findByText('The audit log requires the ADMIN role')).toBeInTheDocument();
    expect(screen.queryByRole('table')).toBeNull();
    first.unmount();
    await renderAs('VIEWER', '/configuration');
    expect(await screen.findByText('Configuration requires the ADMIN role')).toBeInTheDocument();
  });

  it('cannot run simulations (no Run buttons) and is told why', async () => {
    await renderAs('VIEWER', '/simulations');
    expect(await screen.findByText(/Running simulations requires the ENGINEER role/)).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /^Run / })).toBeNull();
    expect(screen.getByRole('heading', { name: 'Sample workload' })).toBeInTheDocument();
  });

  it('sees policy-change requests but no Approve / Reject / Re-evaluate actions', async () => {
    await renderAs('VIEWER', '/policy-arena?app=2');
    expect(await screen.findByText('Policy Arena recommends LFU (+12.8 pts).')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Approve' })).toBeNull();
    expect(screen.queryByRole('button', { name: 'Reject' })).toBeNull();
    expect(screen.queryByRole('button', { name: /Re-evaluate/ })).toBeNull();
    expect(screen.queryByRole('button', { name: /Request .* (anyway|switch)/i })).toBeNull();
    expect(screen.getAllByText(/requires the ENGINEER role/).length).toBeGreaterThan(0);
  });

  it('does not see the API keys panel contents or admin forms', async () => {
    await renderAs('VIEWER', '/applications/1');
    expect(await screen.findByText('API keys are managed by admins')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Create API key' })).toBeNull();
  });
});

describe('role gating: ENGINEER', () => {
  it('can run simulations but still has no admin navigation', async () => {
    await renderAs('ENGINEER', '/simulations');
    expect(await screen.findByRole('button', { name: 'Run Sample workload simulation' })).toBeEnabled();
    expect(screen.getAllByRole('button', { name: /^Run .* simulation$/ })).toHaveLength(4);
    expect(nav().queryByRole('link', { name: 'Audit log' })).toBeNull();
  });

  it('can approve a request made by someone else', async () => {
    await renderAs('ENGINEER', '/policy-arena?app=2');
    const approve = await screen.findByRole('button', { name: 'Approve' });
    expect(approve).toBeEnabled();
  });
});

describe('role gating: ADMIN', () => {
  it('sees Audit log and Configuration and can open the audit log', async () => {
    await renderAs('ADMIN', '/audit-log');
    expect(await screen.findByRole('heading', { name: 'Audit log' })).toBeInTheDocument();
    expect(nav().getByRole('link', { name: 'Audit log' })).toBeInTheDocument();
    expect(nav().getByRole('link', { name: 'Configuration' })).toBeInTheDocument();
    expect(await screen.findByRole('table', { name: /Audit log, newest first/ })).toBeInTheDocument();
    expect(screen.getAllByText('LOGIN_SUCCESS').length).toBeGreaterThan(0);
  });

  it('sees the API key panel on an application page', async () => {
    await renderAs('ADMIN', '/applications/1');
    expect(await screen.findByRole('button', { name: 'Create API key' })).toBeInTheDocument();
  });
});
