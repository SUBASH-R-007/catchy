import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useState,
  type ReactNode,
} from 'react';
import { api } from '../api/endpoints';
import { clearToken, getToken, setToken } from '../api/session';
import { setUnauthorizedHandler } from '../api/client';
import type { CurrentUser, LoginResponse, Role } from '../api/types';

export type AuthStatus = 'loading' | 'anonymous' | 'authenticated';

export interface AuthContextValue {
  status: AuthStatus;
  user: CurrentUser | null;
  login: (username: string, password: string) => Promise<void>;
  demoLogin: (role: Role) => Promise<void>;
  logout: () => void;
}

export const AuthContext = createContext<AuthContextValue | null>(null);

export function useAuth(): AuthContextValue {
  const ctx = useContext(AuthContext);
  if (!ctx) throw new Error('useAuth must be used inside <AuthProvider>');
  return ctx;
}

/** Convenience for components that only need the role (null when signed out). */
export function useRole(): Role | null {
  return useAuth().user?.role ?? null;
}

interface AuthProviderProps {
  children: ReactNode;
}

export function AuthProvider({ children }: AuthProviderProps) {
  const [status, setStatus] = useState<AuthStatus>(() => (getToken() ? 'loading' : 'anonymous'));
  const [user, setUser] = useState<CurrentUser | null>(null);

  const signOut = useCallback(() => {
    clearToken();
    setUser(null);
    setStatus('anonymous');
  }, []);

  // A 401 anywhere (token expired / revoked) clears the session and sends the user to /login.
  useEffect(() => {
    setUnauthorizedHandler(signOut);
    return () => setUnauthorizedHandler(null);
  }, [signOut]);

  // Validate a stored token on load.
  useEffect(() => {
    if (!getToken()) return undefined;
    let cancelled = false;
    api
      .me()
      .then((me) => {
        if (cancelled) return;
        setUser({ username: me.username, role: me.role });
        setStatus('authenticated');
      })
      .catch(() => {
        if (cancelled) return;
        signOut();
      });
    return () => {
      cancelled = true;
    };
  }, [signOut]);

  const accept = useCallback((res: LoginResponse) => {
    setToken(res.token);
    setUser({ username: res.username, role: res.role });
    setStatus('authenticated');
  }, []);

  const login = useCallback(
    async (username: string, password: string) => {
      accept(await api.login({ username, password }));
    },
    [accept],
  );

  const demoLogin = useCallback(
    async (role: Role) => {
      accept(await api.demoLogin(role));
    },
    [accept],
  );

  const value = useMemo<AuthContextValue>(
    () => ({ status, user, login, demoLogin, logout: signOut }),
    [status, user, login, demoLogin, signOut],
  );

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}
