import { render } from '@testing-library/react';
import type { ReactElement } from 'react';
import { MemoryRouter } from 'react-router-dom';
import { api } from '../api/endpoints';
import { setToken } from '../api/session';
import type { Role } from '../api/types';
import { AuthContext, type AuthContextValue } from '../context/AuthContext';
import { ToastProvider } from '../context/ToastContext';

/** Auth context with a fixed user, bypassing the network (role gating and page tests). */
export function authValue(role: Role | null, username = role ? role.toLowerCase() : ''): AuthContextValue {
  return {
    status: role ? 'authenticated' : 'anonymous',
    user: role ? { username, role } : null,
    login: async () => undefined,
    demoLogin: async () => undefined,
    logout: () => undefined,
  };
}

interface RenderOptions {
  route?: string;
  role?: Role | null;
  username?: string;
}

export function renderWithProviders(ui: ReactElement, { route = '/', role = 'VIEWER', username }: RenderOptions = {}) {
  return render(
    <AuthContext.Provider value={authValue(role, username)}>
      <ToastProvider>
        <MemoryRouter initialEntries={[route]}>{ui}</MemoryRouter>
      </ToastProvider>
    </AuthContext.Provider>,
  );
}

/** Sign in against the in-browser mock backend (needs `vi.stubEnv('VITE_MOCK_API', 'true')`). */
export async function mockSignIn(role: Role): Promise<void> {
  const res = await api.demoLogin(role);
  setToken(res.token);
}
