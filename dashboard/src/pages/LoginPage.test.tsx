import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { afterAll, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';
import { getToken } from '../api/session';
import { AuthProvider, useAuth } from '../context/AuthContext';
import { ToastProvider } from '../context/ToastContext';
import { LoginPage } from './LoginPage';

function WhoAmI() {
  const { user, status } = useAuth();
  return <p>{status === 'authenticated' && user ? `Signed in as ${user.username} (${user.role})` : 'Signed out'}</p>;
}

function renderLogin() {
  return render(
    <ToastProvider>
      <MemoryRouter initialEntries={['/login']}>
        <AuthProvider>
          <Routes>
            <Route path="/login" element={<LoginPage />} />
            <Route path="/" element={<WhoAmI />} />
          </Routes>
        </AuthProvider>
      </MemoryRouter>
    </ToastProvider>,
  );
}

async function resetMock(options: { demoMode?: boolean } = {}) {
  const { configureMock } = await import('../api/mock');
  configureMock({ latencyMs: 0, preseedTicks: 5, ...options });
}

beforeAll(() => {
  vi.stubEnv('VITE_MOCK_API', 'true');
});
afterAll(() => {
  vi.unstubAllEnvs();
});
beforeEach(async () => {
  await resetMock();
});

describe('LoginPage: demo role flow (mock API)', () => {
  it('explains the three roles and the safety promise', () => {
    renderLogin();
    for (const role of ['ADMIN', 'ENGINEER', 'VIEWER']) {
      expect(screen.getByRole('button', { name: `Continue as ${role}` })).toBeInTheDocument();
    }
    expect(screen.getByText(/Create and revoke API keys/)).toBeInTheDocument();
    expect(screen.getByText(/Read dashboards, metrics and timelines/)).toBeInTheDocument();
    expect(screen.getByText(/Healthcare-aware engineering demonstration/)).toBeInTheDocument();
  });

  it('signs in with one click on a role card and stores the token in sessionStorage', async () => {
    const user = userEvent.setup();
    renderLogin();
    await user.click(screen.getByRole('button', { name: 'Continue as ENGINEER' }));
    expect(await screen.findByText('Signed in as engineer (ENGINEER)')).toBeInTheDocument();
    expect(getToken()).toMatch(/^mock\.ENGINEER\.engineer\./);
  });

  it('shows a busy state while the request is in flight and disables the other cards', async () => {
    const { configureMock } = await import('../api/mock');
    configureMock({ latencyMs: 60, preseedTicks: 5 });
    const user = userEvent.setup();
    renderLogin();
    await user.click(screen.getByRole('button', { name: 'Continue as VIEWER' }));
    expect(screen.getByRole('button', { name: 'Continue as VIEWER' })).toHaveAttribute('aria-busy', 'true');
    expect(screen.getByRole('button', { name: 'Continue as ADMIN' })).toBeDisabled();
    expect(await screen.findByText('Signed in as viewer (VIEWER)')).toBeInTheDocument();
  });

  it('tells the user demo mode is off when demo-login answers 404', async () => {
    await resetMock({ demoMode: false });
    const user = userEvent.setup();
    renderLogin();
    await user.click(screen.getByRole('button', { name: 'Continue as ADMIN' }));
    expect(await screen.findByText(/Demo mode is off on this server/)).toBeInTheDocument();
    expect(getToken()).toBeNull();
    // the role cards are usable again
    expect(screen.getByRole('button', { name: 'Continue as ADMIN' })).toBeEnabled();
  });
});

describe('LoginPage: username / password form', () => {
  it('validates required fields', async () => {
    const user = userEvent.setup();
    renderLogin();
    await user.click(screen.getByRole('button', { name: 'Sign in' }));
    expect(screen.getByText('Enter your username.')).toBeInTheDocument();
    expect(screen.getByText('Enter your password.')).toBeInTheDocument();
  });

  it('shows an error for bad credentials and signs in with good ones', async () => {
    const user = userEvent.setup();
    renderLogin();
    await user.type(screen.getByLabelText('Username'), 'admin');
    await user.type(screen.getByLabelText('Password'), 'wrong');
    await user.click(screen.getByRole('button', { name: 'Sign in' }));
    expect(await screen.findByText('Invalid username or password.')).toBeInTheDocument();
    expect(getToken()).toBeNull();

    await user.clear(screen.getByLabelText('Password'));
    await user.type(screen.getByLabelText('Password'), 'admin123');
    await user.click(screen.getByRole('button', { name: 'Sign in' }));
    await waitFor(() => expect(screen.getByText('Signed in as admin (ADMIN)')).toBeInTheDocument());
  });
});
