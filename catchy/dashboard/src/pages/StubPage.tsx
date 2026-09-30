import { Construction } from 'lucide-react';
import { ChipCard, EmptyState } from '../components';
import { PageHeader } from './PageHeader';

export interface StubPageProps {
  title: string;
  description: string;
  chipLabel: string;
  step: number;
  features: readonly string[];
}

/** Placeholder for a page delivered by a later build step (SPEC 12, Step 1). */
export function StubPage({ title, description, chipLabel, step, features }: StubPageProps) {
  return (
    <>
      <PageHeader title={title} description={description} />
      <ChipCard label={chipLabel} title={`Arrives in Step ${step}`}>
        <EmptyState
          icon={<Construction aria-hidden="true" className="size-6" />}
          title={`${title} is delivered in Step ${step}`}
          message="This board is wired but not yet populated. It will contain:"
        />
        <ul className="mx-auto mt-4 max-w-xl list-disc space-y-1 pl-6 text-base text-muted">
          {features.map((f) => (
            <li key={f}>{f}</li>
          ))}
        </ul>
      </ChipCard>
    </>
  );
}
