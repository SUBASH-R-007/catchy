import type { ReactNode } from 'react';
import { usePageTitle } from './usePageTitle';

export function PageHeader({
  title,
  description,
  children,
}: {
  title: string;
  description: string;
  children?: ReactNode;
}) {
  usePageTitle(title);
  return (
    <div className="mb-6 flex flex-wrap items-end justify-between gap-4">
      <div>
        <h1 className="font-heading text-2xl font-semibold text-text">{title}</h1>
        <p className="mt-1 max-w-3xl text-base text-muted">{description}</p>
      </div>
      {children}
    </div>
  );
}
