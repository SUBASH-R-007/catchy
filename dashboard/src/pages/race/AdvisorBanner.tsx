import { Lightbulb, Scale } from 'lucide-react';
import { useState } from 'react';
import { advisorApi, ApiError } from '../../api/client';
import type { AdvisorRecommendation } from '../../api/types';
import { PolicyBadge, useToast } from '../../components';
import { SERIES_STYLE } from '../../theme/policy';
import { errorMessage, primaryButtonClass } from '../playground/ui';
import { gainText, recommendationKey } from './race';

export interface AdvisorBannerProps {
  group: string;
  /** The group's live recommendation from the metrics stream; null when none. */
  advisor: AdvisorRecommendation | null;
}

/**
 * The policy advisor's recommendation (SPEC 6.3): the advisor never switches by itself, so the
 * banner offers an Apply button. After a successful switch the banner hides until the stream
 * reports a different recommendation.
 */
export function AdvisorBanner({ group, advisor }: AdvisorBannerProps) {
  const toast = useToast();
  const [applying, setApplying] = useState(false);
  const [hiddenKey, setHiddenKey] = useState<string | null>(null);
  const [lastAdvisor, setLastAdvisor] = useState(advisor);

  // Once the stream reports no recommendation, a later identical one should show again.
  if (advisor !== lastAdvisor) {
    setLastAdvisor(advisor);
    if (advisor === null && hiddenKey !== null) setHiddenKey(null);
  }

  const key = advisor ? recommendationKey(group, advisor) : null;

  if (!advisor || key === hiddenKey) {
    return (
      <p className="flex items-center gap-2 text-sm text-muted">
        <Scale aria-hidden="true" size={16} className="shrink-0" />
        No switch recommended: no other policy beats the current one by more than 3 points over the
        last 30 s.
      </p>
    );
  }

  const target = SERIES_STYLE[advisor.recommended].label;

  const apply = async () => {
    setApplying(true);
    try {
      const { switchedTo } = await advisorApi.apply(group);
      setHiddenKey(key);
      toast.show(`Switched group “${group}” to ${SERIES_STYLE[switchedTo].label}.`, 'success');
    } catch (e) {
      if (e instanceof ApiError && e.status === 409) {
        toast.show(
          'The advisor no longer recommends a switch — the traffic changed. Nothing was switched.',
          'info',
        );
      } else {
        toast.show(errorMessage(e), 'error');
      }
    } finally {
      setApplying(false);
    }
  };

  return (
    <div
      role="status"
      className="flex flex-wrap items-center justify-between gap-4 rounded border border-good/60 bg-bg/60 px-4 py-3"
    >
      <div className="flex min-w-0 items-start gap-3">
        <Lightbulb aria-hidden="true" size={20} className="mt-0.5 shrink-0 text-good" />
        <div className="min-w-0">
          <p className="font-heading text-base text-text">
            Switch to {target}: {gainText(advisor)}
          </p>
          <p className="mt-1 flex flex-wrap items-center gap-2 text-sm text-muted">
            <span>Shadow caches with the same traffic:</span>
            <PolicyBadge policy={advisor.recommended} className="py-0.5" />
            <span>beat the current</span>
            <PolicyBadge policy={advisor.current} className="py-0.5" />
            <span>for the whole window. Nothing changes until you apply it.</span>
          </p>
        </div>
      </div>
      <button
        type="button"
        className={primaryButtonClass}
        onClick={() => void apply()}
        disabled={applying}
        aria-label={`Apply: switch group ${group} to ${target}`}
      >
        {applying ? 'Applying…' : 'Apply'}
      </button>
    </div>
  );
}
