import { useState, type FormEvent } from 'react';
import { Navigate, useLocation } from 'react-router-dom';
import { ApiError, describeError } from '../api/client';
import type { Role } from '../api/types';
import { Wordmark, SafetyFooter } from '../components/AppShell';
import { Button } from '../components/Button';
import { Field } from '../components/Field';
import { Icon } from '../components/Icon';
import { useAuth } from '../context/AuthContext';
import { paths } from '../lib/routes';

interface RoleCard {
  role: Role;
  title: string;
  summary: string;
  can: string[];
}

export const ROLE_CARDS: readonly RoleCard[] = [
  {
    role: 'ADMIN',
    title: 'Admin',
    summary: 'Platform owner',
    can: [
      'Everything an Engineer can do',
      'Create projects and applications',
      'Create and revoke API keys',
      'Change cache configuration',
      'Approve your own requests and read the audit log',
    ],
  },
  {
    role: 'ENGINEER',
    title: 'Engineer',
    summary: 'Backend / platform engineer',
    can: [
      'Everything a Viewer can do',
      'Run simulations and re-evaluate recommendations',
      'Request policy changes',
      'Approve or reject other engineers’ requests',
    ],
  },
  {
    role: 'VIEWER',
    title: 'Viewer',
    summary: 'Support / read-only',
    can: ['Read dashboards, metrics and timelines', 'Inspect Eviction X-ray events', 'See recommendations (no actions)'],
  },
];

interface LocationState {
  from?: string;
}

export function LoginPage() {
  const { status, login, demoLogin } = useAuth();
  const location = useLocation();
  const from = (location.state as LocationState | null)?.from;
  const [pendingRole, setPendingRole] = useState<Role | null>(null);
  const [demoError, setDemoError] = useState<string | null>(null);
  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');
  const [submitting, setSubmitting] = useState(false);
  const [formError, setFormError] = useState<string | null>(null);
  const [touched, setTouched] = useState(false);

  if (status === 'authenticated') return <Navigate to={from && from !== paths.login() ? from : paths.overview()} replace />;

  const mockMode = import.meta.env.VITE_MOCK_API === 'true';

  const chooseRole = async (role: Role) => {
    setDemoError(null);
    setPendingRole(role);
    try {
      await demoLogin(role);
    } catch (e) {
      if (e instanceof ApiError && e.status === 404) {
        setDemoError('Demo mode is off on this server (CATCHY_DEMO_MODE is not true). Sign in with a username and password instead.');
      } else {
        setDemoError(describeError(e));
      }
      setPendingRole(null);
    }
  };

  const submit = async (e: FormEvent) => {
    e.preventDefault();
    setTouched(true);
    setFormError(null);
    if (!username.trim() || !password) return;
    setSubmitting(true);
    try {
      await login(username.trim(), password);
    } catch (err) {
      setFormError(err instanceof ApiError && err.status === 401 ? 'Invalid username or password.' : describeError(err));
      setSubmitting(false);
    }
  };

  return (
    <div className="login">
      <main className="login__main" id="main">
        <div className="login__brand">
          <Wordmark large />
          <h1 className="login__title">See what your caches are deciding — and why.</h1>
          <p className="login__lead">
            CATCHY gives Acentra backend, platform and support engineers live cache health, LRU vs LFU recommendations and an Eviction X-ray of every cache decision across services.
          </p>
          <ul className="login__points">
            <li>
              <Icon name="shield" size={16} /> Safe metadata only: key fingerprints, never raw keys, cached values or patient data.
            </li>
            <li>
              <Icon name="scale" size={16} /> Automation never changes a policy — an engineer must approve.
            </li>
          </ul>
        </div>

        <div className="login__panel">
          <section aria-labelledby="demo-heading" className="stack-sm">
            <h2 id="demo-heading">Choose a demo role</h2>
            <p className="muted">One click signs you in with that role&apos;s permissions.</p>
            <div className="role-cards">
              {ROLE_CARDS.map((card) => (
                <button
                  key={card.role}
                  type="button"
                  className="role-card"
                  onClick={() => void chooseRole(card.role)}
                  disabled={pendingRole !== null}
                  aria-busy={pendingRole === card.role || undefined}
                  aria-label={`Continue as ${card.role}`}
                >
                  <span className="role-card__head">
                    <span className="role-card__title">{card.title}</span>
                    <span className="role-card__role mono">{card.role}</span>
                  </span>
                  <span className="role-card__summary">{card.summary}</span>
                  <ul className="role-card__list">
                    {card.can.map((c) => (
                      <li key={c}>
                        <Icon name="check" size={13} />
                        <span>{c}</span>
                      </li>
                    ))}
                  </ul>
                  <span className="role-card__cta">
                    {pendingRole === card.role ? (
                      <>
                        <span className="spinner" aria-hidden="true" /> Signing in…
                      </>
                    ) : (
                      <>
                        Continue as {card.title} <Icon name="arrow-right" size={14} />
                      </>
                    )}
                  </span>
                </button>
              ))}
            </div>
            {demoError ? (
              <p className="notice notice--warning" role="alert">
                <Icon name="alert" size={16} />
                <span>{demoError}</span>
              </p>
            ) : null}
          </section>

          <div className="login__divider" role="separator">
            <span>or sign in with a username</span>
          </div>

          <form className="stack-sm" onSubmit={submit} noValidate aria-label="Sign in with username and password">
            <Field label="Username" error={touched && !username.trim() ? 'Enter your username.' : null}>
              {(p) => <input {...p} className="input" autoComplete="username" value={username} onChange={(e) => setUsername(e.target.value)} />}
            </Field>
            <Field label="Password" error={touched && !password ? 'Enter your password.' : null}>
              {(p) => (
                <input {...p} className="input" type="password" autoComplete="current-password" value={password} onChange={(e) => setPassword(e.target.value)} />
              )}
            </Field>
            {formError ? (
              <p className="field__error" role="alert">
                {formError}
              </p>
            ) : null}
            <Button type="submit" variant="primary" loading={submitting}>
              Sign in
            </Button>
            {mockMode ? (
              <p className="faint" role="note">
                Mock API active (no backend needed). Demo accounts: admin / admin123, engineer / engineer123, viewer / viewer123.
              </p>
            ) : null}
          </form>
        </div>
      </main>
      <SafetyFooter />
    </div>
  );
}
