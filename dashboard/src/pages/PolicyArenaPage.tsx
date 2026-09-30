import { useEffect, useState, type FormEvent, type ReactNode } from 'react';
import { Link, useSearchParams } from 'react-router-dom';
import { api } from '../api/endpoints';
import { describeError } from '../api/client';
import type { Application, Recommendation, RegionMetrics } from '../api/types';
import { HBars } from '../charts/HBars';
import { ApplicationSelect } from '../components/ApplicationSelect';
import { Badge, PolicyBadge, RiskBadge } from '../components/Badge';
import { Button } from '../components/Button';
import { Card } from '../components/Card';
import { Field } from '../components/Field';
import { Icon } from '../components/Icon';
import { LiveIndicator } from '../components/LiveIndicator';
import { Modal } from '../components/Modal';
import { PageHeader } from '../components/PageHeader';
import { PolicyRequestsPanel } from '../components/PolicyRequestsPanel';
import { ConfidenceMeter } from '../components/RecommendationCard';
import { EmptyState, ErrorState, LoadingBlock } from '../components/States';
import { useAuth } from '../context/AuthContext';
import { useToast } from '../context/ToastContext';
import { useFetch, usePolling } from '../hooks/usePolling';
import { formatClock, formatInt, formatPercent, formatPoints } from '../lib/format';
import { otherPolicy } from '../lib/health';
import { hasRole } from '../lib/roles';
import { paths } from '../lib/routes';

export const AI_LABEL = 'AI-generated explanation — advisory only; the deterministic rule engine decides';
export const APPROVAL_STATEMENT = 'Automation never changes a policy: an engineer must approve.';
const MIN_SAMPLE = 100;
const MIN_IMPROVEMENT = 5;

interface CheckProps {
  ok: boolean | 'wait';
  children: ReactNode;
}

function Check({ ok, children }: CheckProps) {
  return (
    <li className={`check check--${ok === true ? 'ok' : ok === 'wait' ? 'wait' : 'no'}`}>
      <Icon name={ok === true ? 'check' : ok === 'wait' ? 'clock' : 'x-circle'} size={14} />
      <span>{children}</span>
    </li>
  );
}

interface RequestModalProps {
  rec: Recommendation | null;
  onClose: () => void;
  onCreated: () => void;
}

function RequestSwitchModal({ rec, onClose, onCreated }: RequestModalProps) {
  const toast = useToast();
  const [reason, setReason] = useState('');
  const [busy, setBusy] = useState(false);
  const target = rec ? otherPolicy(rec.currentPolicy) : 'LFU';

  useEffect(() => {
    if (rec) {
      setReason(
        rec.action === 'SWITCH'
          ? `Policy Arena recommends ${rec.recommendedPolicy} (${formatPoints(rec.improvementPercent)}).`
          : `Manual request to try ${target} (Policy Arena currently recommends keeping ${rec.currentPolicy}).`,
      );
    }
  }, [rec, target]);

  const submit = async (e: FormEvent) => {
    e.preventDefault();
    if (!rec) return;
    setBusy(true);
    try {
      await api.createPolicyRequest(rec.applicationId, {
        cacheRegion: rec.cacheRegion,
        requestedPolicy: target,
        reason: reason.trim() || undefined,
        recommendationId: rec.id,
      });
      toast.success(`Requested ${target} for ${rec.cacheRegion}. Another engineer (or an admin) must approve it.`);
      onCreated();
    } catch (err) {
      toast.error(describeError(err));
    } finally {
      setBusy(false);
    }
  };

  return (
    <Modal
      open={rec !== null}
      title={rec ? `Request switch of ${rec.cacheRegion} to ${target}` : 'Request switch'}
      onClose={onClose}
      footer={
        <>
          <Button onClick={onClose} disabled={busy}>
            Cancel
          </Button>
          <Button type="submit" form="request-switch-form" variant="primary" loading={busy}>
            Submit request
          </Button>
        </>
      }
    >
      {rec ? (
        <form id="request-switch-form" className="stack" onSubmit={submit}>
          <p>
            This only creates a <strong>request</strong>. Nothing changes until an engineer other than you (or an admin) approves it, and the SDK applies it on its next control poll.
          </p>
          <p className="row">
            <PolicyBadge policy={rec.currentPolicy} /> <Icon name="arrow-right" size={14} /> <PolicyBadge policy={target} />
          </p>
          <Field label="Reason" hint="Recorded in the audit log. Do not include patient or member data.">
            {(p) => <textarea {...p} data-autofocus className="textarea" rows={3} maxLength={400} value={reason} onChange={(e) => setReason(e.target.value)} />}
          </Field>
        </form>
      ) : null}
    </Modal>
  );
}

interface ArenaCardProps {
  rec: Recommendation;
  region: RegionMetrics | undefined;
  canRequest: boolean;
  onRequest: (rec: Recommendation) => void;
}

function ArenaCard({ rec, region, canRequest, onRequest }: ArenaCardProps) {
  const alt = otherPolicy(rec.currentPolicy);
  const isSwitch = rec.action === 'SWITCH';
  const pending = rec.pendingRequestId != null;
  const sampleOk = rec.minimumSampleMet;
  const gainOk = rec.improvementPercent >= MIN_IMPROVEMENT;
  const live = region?.hitRate;

  return (
    <article className={`arena-card arena-card--${isSwitch ? 'switch' : 'keep'}`}>
      <header className="arena-card__head">
        <div>
          <h3 className="arena-card__title">
            <Link to={paths.region(rec.applicationId, rec.cacheRegion)}>{rec.cacheRegion}</Link>
          </h3>
          <div className="row">
            {region ? <RiskBadge level={region.riskLevel} /> : null}
            <span className="faint">Current policy</span>
            <PolicyBadge policy={rec.currentPolicy} />
          </div>
        </div>
        <Badge tone={isSwitch ? 'warning' : 'good'} icon={isSwitch ? 'alert' : 'check'}>
          {rec.summary}
        </Badge>
      </header>

      <HBars
        percent
        ariaLabel={`Simulated hit rates: LRU ${formatPercent(rec.lruShadowHitRate, 1)}, LFU ${formatPercent(rec.lfuShadowHitRate, 1)}${live !== undefined ? `; live ${formatPercent(live, 1)}` : ''}`}
        groups={[
          {
            rows: [
              {
                key: 'lru',
                label: (
                  <>
                    LRU <span className="faint">{rec.currentPolicy === 'LRU' ? '(current)' : '(simulated)'}</span>
                  </>
                ),
                value: rec.lruShadowHitRate,
                color: 'var(--series-lru)',
                valueLabel: formatPercent(rec.lruShadowHitRate, 1),
                marker: live !== undefined ? { value: live, label: `Live hit rate ${formatPercent(live, 1)}` } : undefined,
              },
              {
                key: 'lfu',
                label: (
                  <>
                    LFU <span className="faint">{rec.currentPolicy === 'LFU' ? '(current)' : '(simulated)'}</span>
                  </>
                ),
                value: rec.lfuShadowHitRate,
                color: 'var(--series-lfu)',
                valueLabel: formatPercent(rec.lfuShadowHitRate, 1),
                marker: live !== undefined ? { value: live, label: `Live hit rate ${formatPercent(live, 1)}` } : undefined,
              },
            ],
          },
        ]}
      />
      {live !== undefined ? <p className="faint arena-card__legend">Black tick: live measured hit rate ({formatPercent(live, 1)}). Bars are simulated over the latest window.</p> : null}

      <div className="arena-card__stats">
        <div>
          <span className="arena-card__stat-label">{alt} vs current</span>
          <strong className={`arena-card__stat-value num${rec.improvementPercent > 0 ? ' is-positive' : ''}`}>{formatPoints(rec.improvementPercent)}</strong>
        </div>
        <div className="arena-card__confidence">
          <span className="arena-card__stat-label">Confidence</span>
          <ConfidenceMeter value={rec.confidence} />
        </div>
      </div>

      <ul className="checks" aria-label="Conditions for a SWITCH recommendation">
        <Check ok={sampleOk}>
          Sample size {formatInt(rec.sampleSize)} of {MIN_SAMPLE} required — {sampleOk ? 'minimum met' : 'minimum not met'}
        </Check>
        <Check ok={gainOk}>
          Improvement {formatPoints(rec.improvementPercent)} — needs at least +{MIN_IMPROVEMENT} pts
        </Check>
        <Check ok={rec.cooldownActive ? 'wait' : true}>
          {rec.cooldownActive
            ? `Cooldown active${rec.cooldownEndsAt ? ` until ${formatClock(rec.cooldownEndsAt)}` : ''}`
            : 'No cooldown active'}
        </Check>
      </ul>

      <p className="arena-card__reason">{rec.reason}</p>
      {rec.aiExplanation ? (
        <aside className="ai-note" aria-label="AI-generated explanation">
          <p className="ai-note__label">
            <Icon name="info" size={14} /> {AI_LABEL}
          </p>
          <p>{rec.aiExplanation}</p>
        </aside>
      ) : null}

      <footer className="arena-card__actions">
        {canRequest ? (
          pending ? (
            <p className="faint">
              <Icon name="clock" size={14} /> Request #{rec.pendingRequestId} is already pending for this region — see the requests below.
            </p>
          ) : isSwitch ? (
            <Button variant="primary" icon="scale" onClick={() => onRequest(rec)}>
              Request switch to {rec.recommendedPolicy}
            </Button>
          ) : (
            <Button variant="secondary" icon="scale" onClick={() => onRequest(rec)}>
              Request {alt} anyway
            </Button>
          )
        ) : (
          <p className="faint">Requesting a policy change requires the ENGINEER role.</p>
        )}
      </footer>
    </article>
  );
}

function initialApp(apps: Application[], requested: number | null): number | null {
  if (requested !== null && apps.some((a) => a.id === requested)) return requested;
  return apps.find((a) => a.regionCount > 0)?.id ?? apps[0]?.id ?? null;
}

export function PolicyArenaPage() {
  const { user } = useAuth();
  const toast = useToast();
  const [params, setParams] = useSearchParams();
  const apps = useFetch(() => api.listApplications(), []);
  const requestedParam = params.get('app');
  const requested = requestedParam !== null && Number.isInteger(Number(requestedParam)) ? Number(requestedParam) : null;
  const appId = apps.data ? initialApp(apps.data, requested) : null;
  const enabled = appId !== null;

  const recs = usePolling(() => api.listRecommendations(appId as number), [appId], { enabled });
  const regions = usePolling(() => api.applicationRegions(appId as number), [appId], { enabled });
  const requests = usePolling(() => api.listPolicyRequests(appId as number), [appId], { enabled });

  const [evaluating, setEvaluating] = useState(false);
  const [requestFor, setRequestFor] = useState<Recommendation | null>(null);
  const isEngineer = hasRole(user?.role, 'ENGINEER');

  const refreshAll = () => {
    void recs.refresh();
    void requests.refresh();
    void regions.refresh();
  };

  const evaluate = async () => {
    if (appId === null) return;
    setEvaluating(true);
    try {
      const result = await api.evaluateRecommendations(appId);
      toast.success(`Re-evaluated ${result.length} region${result.length === 1 ? '' : 's'}. The rule engine is deterministic; results reflect the latest shadow data.`);
      refreshAll();
    } catch (err) {
      toast.error(describeError(err));
    } finally {
      setEvaluating(false);
    }
  };

  const regionByName = new Map((regions.data ?? []).map((r) => [r.cacheRegion, r]));

  let body;
  if (apps.error && !apps.data) body = <ErrorState error={apps.error} onRetry={() => void apps.refresh()} title="Could not load applications" />;
  else if (apps.loading || !apps.data) body = <LoadingBlock label="Loading applications" lines={4} />;
  else if (apps.data.length === 0) {
    body = (
      <EmptyState title="No applications yet" icon="layers">
        Register an application and let its SDK report shadow data; the Policy Arena compares LRU and LFU per region.
      </EmptyState>
    );
  } else if (recs.error && !recs.data) body = <ErrorState error={recs.error} onRetry={() => void recs.refresh()} title="Could not load recommendations" />;
  else if (recs.loading || !recs.data) body = <LoadingBlock label="Loading recommendations" lines={6} height={20} />;
  else if (recs.data.length === 0) {
    body = (
      <EmptyState title="No recommendations for this application yet" icon="scale">
        No telemetry yet — start a demo service or run a simulation. Regions need shadow (LRU vs LFU) data before they can be compared.
      </EmptyState>
    );
  } else {
    body = (
      <div className="grid-cards grid-cards--wide">
        {recs.data.map((rec) => (
          <ArenaCard key={rec.id} rec={rec} region={regionByName.get(rec.cacheRegion)} canRequest={isEngineer} onRequest={setRequestFor} />
        ))}
      </div>
    );
  }

  return (
    <div className="page">
      <PageHeader
        title="Policy Arena"
        subtitle="Live LRU vs LFU comparison per cache region, using shadow simulation over recent reads."
        actions={
          <>
            <LiveIndicator updatedAt={recs.updatedAt} error={recs.error} paused={recs.paused} loading={recs.loading && enabled} />
            <Link to={paths.recommendations()} className="btn btn--secondary btn--sm">
              All recommendations
            </Link>
          </>
        }
      />
      <p className="notice notice--info" role="note">
        <Icon name="shield" size={16} />
        <span>
          <strong>{APPROVAL_STATEMENT}</strong> Recommendations come from a deterministic rule engine; requests, approvals and rollouts are all audited.
        </span>
      </p>

      <div className="toolbar" role="group" aria-label="Policy Arena controls">
        <ApplicationSelect
          applications={apps.data ?? []}
          value={appId}
          onChange={(id) => setParams(id === null ? {} : { app: String(id) }, { replace: true })}
          disabled={!apps.data}
        />
        {isEngineer ? (
          <Button icon="refresh" onClick={() => void evaluate()} loading={evaluating} disabled={appId === null}>
            Re-evaluate
          </Button>
        ) : (
          <p className="faint toolbar__note">Re-evaluating requires the ENGINEER role.</p>
        )}
      </div>

      <div className="stack">{body}</div>

      {appId !== null ? (
        <Card title="Policy-change requests" subtitle="Newest first. A request needs approval from another engineer (or an admin) before the SDK applies it.">
          <PolicyRequestsPanel
            requests={requests.data}
            loading={requests.loading}
            error={requests.error}
            onRetry={() => void requests.refresh()}
            onChanged={refreshAll}
          />
        </Card>
      ) : null}

      <RequestSwitchModal
        rec={requestFor}
        onClose={() => setRequestFor(null)}
        onCreated={() => {
          setRequestFor(null);
          refreshAll();
        }}
      />
    </div>
  );
}
