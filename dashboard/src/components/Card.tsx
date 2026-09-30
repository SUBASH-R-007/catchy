import { useId, type ReactNode } from 'react';

interface CardProps {
  title?: ReactNode;
  subtitle?: ReactNode;
  actions?: ReactNode;
  children: ReactNode;
  className?: string;
  /** Heading level for the title (default h2). */
  level?: 2 | 3;
}

export function Card({ title, subtitle, actions, children, className, level = 2 }: CardProps) {
  const uid = useId();
  const titleId = `c${uid.replace(/:/g, '')}`;
  const Heading = level === 2 ? 'h2' : 'h3';
  return (
    <section className={`card${className ? ` ${className}` : ''}`} aria-labelledby={title ? titleId : undefined}>
      {title || actions ? (
        <header className="card__header">
          <div className="card__heading">
            {title ? <Heading id={titleId} className="card__title">{title}</Heading> : null}
            {subtitle ? <p className="card__subtitle">{subtitle}</p> : null}
          </div>
          {actions ? <div className="card__actions">{actions}</div> : null}
        </header>
      ) : null}
      <div className="card__body">{children}</div>
    </section>
  );
}
