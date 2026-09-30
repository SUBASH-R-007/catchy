import { NavLink, Outlet, useNavigate } from 'react-router-dom';
import { useAuth } from '../context/AuthContext';
import { useTheme } from '../hooks/useTheme';
import { hasRole } from '../lib/roles';
import { paths } from '../lib/routes';
import type { Role } from '../api/types';
import { RoleBadge } from './Badge';
import { Icon } from './Icon';

export const SAFETY_FOOTER_NOTE =
  'Healthcare-aware engineering demonstration — shows safe cache metadata only; not a compliance certification.';

export function Wordmark({ large = false }: { large?: boolean }) {
  return (
    <span className={`brand${large ? ' brand--large' : ''}`}>
      <svg className="brand__mark" viewBox="0 0 32 32" width={large ? 40 : 28} height={large ? 40 : 28} aria-hidden="true" focusable="false">
        <rect width="32" height="32" rx="8" fill="var(--accent)" />
        <path d="M21.5 11.2A7.4 7.4 0 1 0 21.5 20.8" fill="none" stroke="#fff" strokeWidth="3.2" strokeLinecap="round" />
        <circle cx="21.6" cy="16" r="2.3" fill="#fff" />
      </svg>
      <span className="brand__text">
        <span className="brand__name">CATCHY</span>
        <span className="brand__sub">AcentraCache Insight</span>
      </span>
    </span>
  );
}

export function SafetyFooter() {
  return (
    <footer className="site-footer">
      <p>{SAFETY_FOOTER_NOTE}</p>
    </footer>
  );
}

interface NavItem {
  to: string;
  label: string;
  end?: boolean;
  min: Role;
}

export const NAV_ITEMS: readonly NavItem[] = [
  { to: paths.overview(), label: 'Overview', end: true, min: 'VIEWER' },
  { to: paths.projects(), label: 'Projects', min: 'VIEWER' },
  { to: paths.applications(), label: 'Applications', min: 'VIEWER' },
  { to: paths.regions(), label: 'Regions', min: 'VIEWER' },
  { to: '/policy-arena', label: 'Policy Arena', min: 'VIEWER' },
  { to: '/simulations', label: 'Simulations', min: 'VIEWER' },
  { to: paths.audit(), label: 'Audit log', min: 'ADMIN' },
  { to: '/configuration', label: 'Configuration', min: 'ADMIN' },
];

/** Top bar (wordmark, role-filtered nav, user + role, theme toggle, logout), routed content and the permanent safety footer. */
export function AppShell() {
  const { user, logout } = useAuth();
  const { theme, toggle } = useTheme();
  const navigate = useNavigate();
  const items = NAV_ITEMS.filter((i) => hasRole(user?.role, i.min));

  return (
    <div className="shell">
      <a href="#main" className="skip-link">
        Skip to content
      </a>
      <header className="topbar">
        <div className="topbar__inner">
          <NavLink to={paths.overview()} className="topbar__brand" aria-label="CATCHY — AcentraCache Insight, home">
            <Wordmark />
          </NavLink>
          <div className="topbar__right">
            {user ? (
              <div className="topbar__user" aria-label="Signed-in user">
                <span className="topbar__username">{user.username}</span>
                <RoleBadge role={user.role} />
              </div>
            ) : null}
            <button
              type="button"
              className="icon-btn"
              onClick={toggle}
              aria-label={theme === 'dark' ? 'Switch to light theme' : 'Switch to dark theme'}
              title={theme === 'dark' ? 'Switch to light theme' : 'Switch to dark theme'}
            >
              <Icon name={theme === 'dark' ? 'sun' : 'moon'} />
            </button>
            <button
              type="button"
              className="btn btn--ghost btn--sm"
              onClick={() => {
                logout();
                navigate(paths.login(), { replace: true });
              }}
            >
              <Icon name="logout" size={14} />
              <span>Log out</span>
            </button>
          </div>
        </div>
        <nav className="nav" aria-label="Main">
          <ul className="nav__list">
            {items.map((item) => (
              <li key={item.to}>
                <NavLink to={item.to} end={item.end} className={({ isActive }) => `nav__link${isActive ? ' is-active' : ''}`}>
                  {item.label}
                </NavLink>
              </li>
            ))}
          </ul>
        </nav>
      </header>
      <main id="main" className="main" tabIndex={-1}>
        <Outlet />
      </main>
      <SafetyFooter />
    </div>
  );
}
