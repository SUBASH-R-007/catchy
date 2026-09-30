import type { ReactNode } from 'react';
import { Link } from 'react-router-dom';
import { Icon } from './Icon';

interface Crumb {
  label: string;
  to?: string;
}

interface PageHeaderProps {
  title: ReactNode;
  subtitle?: ReactNode;
  /** Breadcrumb trail above the title. */
  crumbs?: Crumb[];
  /** Badges next to the title. */
  badges?: ReactNode;
  /** Right-aligned controls (live indicator, buttons). */
  actions?: ReactNode;
}

export function PageHeader({ title, subtitle, crumbs, badges, actions }: PageHeaderProps) {
  return (
    <header className="page-header">
      {crumbs && crumbs.length > 0 ? (
        <nav aria-label="Breadcrumb" className="crumbs">
          <ol>
            {crumbs.map((c, i) => (
              <li key={`${c.label}-${i}`}>
                {c.to ? <Link to={c.to}>{c.label}</Link> : <span aria-current="page">{c.label}</span>}
                {i < crumbs.length - 1 ? <Icon name="arrow-right" size={12} className="crumbs__sep" /> : null}
              </li>
            ))}
          </ol>
        </nav>
      ) : null}
      <div className="page-header__row">
        <div className="page-header__text">
          <div className="page-header__title-row">
            <h1 className="page-title">{title}</h1>
            {badges ? <div className="page-header__badges">{badges}</div> : null}
          </div>
          {subtitle ? <p className="page-sub">{subtitle}</p> : null}
        </div>
        {actions ? <div className="page-header__actions">{actions}</div> : null}
      </div>
    </header>
  );
}
