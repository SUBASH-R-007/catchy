import { Link } from 'react-router-dom';
import type { Recommendation } from '../api/types';
import { confidenceBand } from '../lib/health';
import { formatPercent, formatPoints } from '../lib/format';
import { paths } from '../lib/routes';
import { Badge, PolicyBadge } from './Badge';
import { Icon } from './Icon';

export function ConfidenceMeter({ value }: { value: number }) {
  const band = confidenceBand(value);
  return (
    <div className="confidence">
      <div
        className="confidence__track"
        role="meter"
        aria-label="Recommendation confidence"
        aria-valuemin={0}
        aria-valuemax={100}
        aria-valuenow={value}
        aria-valuetext={`${value} out of 100 (${band})`}
      >
        <div className={`confidence__fill confidence__fill--${band}`} style={{ width: `${Math.min(100, Math.max(0, value))}%` }} />
      </div>
      <span className="confidence__text num">
        {value}
        <span className="faint">/100 · {band}</span>
      </span>
    </div>
  );
}

interface RecommendationCardProps {
  rec: Recommendation;
  /** Show the application name (true on cross-application lists). */
  showApplication?: boolean;
  /** Link to the Policy Arena for this application. */
  showArenaLink?: boolean;
}

/** Compact recommendation: summary, current vs simulated LRU / LFU hit rates, confidence, reason. */
export function RecommendationCard({ rec, showApplication = false, showArenaLink = false }: RecommendationCardProps) {
  const isSwitch = rec.action === 'SWITCH';
  return (
    <article className={`rec-card rec-card--${isSwitch ? 'switch' : 'keep'}`}>
      <header className="rec-card__head">
        <div>
          <h3 className="rec-card__title">{rec.summary}</h3>
          <p className="rec-card__sub">
            {showApplication ? (
              <>
                <Link to={paths.application(rec.applicationId)}>{rec.applicationName}</Link> /{' '}
              </>
            ) : null}
            <Link to={paths.region(rec.applicationId, rec.cacheRegion)}>{rec.cacheRegion}</Link>
          </p>
        </div>
        <Badge tone={isSwitch ? 'warning' : 'good'} icon={isSwitch ? 'alert' : 'check'}>
          {isSwitch ? 'Switch suggested' : 'Keep'}
        </Badge>
      </header>

      <div className="rec-card__rates">
        <div className="rec-rate">
          <span className="rec-rate__label">
            <PolicyBadge policy="LRU" /> simulated
          </span>
          <span className="rec-rate__value num">{formatPercent(rec.lruShadowHitRate, 1)}</span>
        </div>
        <div className="rec-rate">
          <span className="rec-rate__label">
            <PolicyBadge policy="LFU" /> simulated
          </span>
          <span className="rec-rate__value num">{formatPercent(rec.lfuShadowHitRate, 1)}</span>
        </div>
        <div className="rec-rate">
          <span className="rec-rate__label">Current policy</span>
          <span className="rec-rate__value">
            <PolicyBadge policy={rec.currentPolicy} />
          </span>
        </div>
      </div>
      <p className="rec-card__delta">
        {rec.currentPolicy === 'LRU' ? 'LFU' : 'LRU'} vs current (simulated):{' '}
        <strong className="num">{formatPoints(rec.improvementPercent)}</strong>
      </p>
      <ConfidenceMeter value={rec.confidence} />
      <p className="rec-card__reason">{rec.reason}</p>
      {showArenaLink ? (
        <Link className="rec-card__link" to={paths.policyArena(rec.applicationId)}>
          Open in Policy Arena <Icon name="arrow-right" size={13} />
        </Link>
      ) : null}
    </article>
  );
}
