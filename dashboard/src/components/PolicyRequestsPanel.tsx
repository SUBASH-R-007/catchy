import { useId, useState } from 'react';
import { Link } from 'react-router-dom';
import { api } from '../api/endpoints';
import { describeError } from '../api/client';
import type { PolicyChangeRequest } from '../api/types';
import { useAuth } from '../context/AuthContext';
import { useToast } from '../context/ToastContext';
import { formatDateTime } from '../lib/format';
import { paths } from '../lib/routes';
import { canDecideRequest, hasRole } from '../lib/roles';
import { PolicyBadge, RequestStatusBadge } from './Badge';
import { Button } from './Button';
import { EmptyState, ErrorState, LoadingBlock } from './States';
import { Icon } from './Icon';

interface RequestItemProps {
  request: PolicyChangeRequest;
  onChanged: () => void;
  showApplication?: boolean;
}

export function PolicyRequestItem({ request, onChanged, showApplication = false }: RequestItemProps) {
  const { user } = useAuth();
  const toast = useToast();
  const uid = useId();
  const noteId = `n${uid.replace(/:/g, '')}`;
  const reasonId = `r${uid.replace(/:/g, '')}`;
  const [note, setNote] = useState('');
  const [busy, setBusy] = useState<'approve' | 'reject' | null>(null);

  const isEngineer = hasRole(user?.role, 'ENGINEER');
  const permission = canDecideRequest(user, request);
  const pending = request.status === 'PENDING';

  const decide = async (kind: 'approve' | 'reject') => {
    setBusy(kind);
    try {
      const body = note.trim() ? { note: note.trim() } : {};
      if (kind === 'approve') await api.approvePolicyRequest(request.id, body);
      else await api.rejectPolicyRequest(request.id, body);
      toast.success(
        kind === 'approve'
          ? `Approved request #${request.id}. The SDK applies ${request.requestedPolicy} on its next control poll.`
          : `Rejected request #${request.id}.`,
      );
      setNote('');
      onChanged();
    } catch (err) {
      toast.error(describeError(err));
    } finally {
      setBusy(null);
    }
  };

  return (
    <li className={`request request--${request.status.toLowerCase()}`}>
      <div className="request__head">
        <div className="request__title">
          <span className="request__id num">#{request.id}</span>
          {showApplication ? (
            <>
              <Link to={paths.application(request.applicationId)}>{request.applicationName}</Link>
              <span className="faint"> / </span>
            </>
          ) : null}
          <Link to={paths.region(request.applicationId, request.cacheRegion)}>{request.cacheRegion}</Link>
          <span className="request__change">
            <PolicyBadge policy={request.currentPolicy} />
            <Icon name="arrow-right" size={13} />
            <PolicyBadge policy={request.requestedPolicy} />
          </span>
        </div>
        <RequestStatusBadge status={request.status} />
      </div>
      <p className="request__reason">{request.reason}</p>
      <p className="request__meta faint">
        Requested by <strong>{request.requestedBy}</strong> · {formatDateTime(request.createdAt)}
        {request.decidedBy ? (
          <>
            {' '}
            · {request.status === 'REJECTED' ? 'Rejected' : 'Approved'} by <strong>{request.decidedBy}</strong>
            {request.decidedAt ? ` · ${formatDateTime(request.decidedAt)}` : ''}
          </>
        ) : null}
        {request.appliedAt ? ` · Applied ${formatDateTime(request.appliedAt)}` : ''}
      </p>
      {request.decisionNote ? <p className="request__note">Note: {request.decisionNote}</p> : null}

      {request.status === 'APPROVED' ? (
        <p className="notice notice--info" role="status">
          <Icon name="clock" size={16} />
          <span>Approved. Waiting for the SDK to report that the region now runs {request.requestedPolicy}.</span>
        </p>
      ) : null}
      {request.status === 'APPLIED' ? (
        <p className="notice notice--good" role="status">
          <Icon name="check" size={16} />
          <span>Applied: the region now runs {request.requestedPolicy}.</span>
        </p>
      ) : null}

      {pending && isEngineer ? (
        <div className="request__actions">
          <div className="field">
            <label className="field__label" htmlFor={noteId}>
              Decision note (optional)
            </label>
            <input
              id={noteId}
              className="input"
              value={note}
              maxLength={300}
              onChange={(e) => setNote(e.target.value)}
              placeholder="e.g. Shadow gain is stable"
              disabled={!permission.allowed}
              aria-describedby={!permission.allowed ? reasonId : undefined}
            />
          </div>
          <div className="row">
            <Button
              variant="primary"
              icon="check"
              disabled={!permission.allowed}
              loading={busy === 'approve'}
              onClick={() => decide('approve')}
              aria-describedby={!permission.allowed ? reasonId : undefined}
            >
              Approve
            </Button>
            <Button
              variant="danger"
              icon="x-circle"
              disabled={!permission.allowed}
              loading={busy === 'reject'}
              onClick={() => decide('reject')}
              aria-describedby={!permission.allowed ? reasonId : undefined}
            >
              Reject
            </Button>
          </div>
          {!permission.allowed ? (
            <p id={reasonId} className="request__why">
              <Icon name="lock" size={14} /> {permission.reason}
            </p>
          ) : null}
        </div>
      ) : null}
      {pending && !isEngineer ? <p className="faint">Approving or rejecting requires the ENGINEER role.</p> : null}
    </li>
  );
}

interface PolicyRequestsPanelProps {
  requests: PolicyChangeRequest[] | undefined;
  loading?: boolean;
  error?: Error | null;
  onRetry?: () => void;
  onChanged: () => void;
  showApplication?: boolean;
}

/** Policy-change requests, newest first, with Approve / Reject for ENGINEER+ on PENDING ones. */
export function PolicyRequestsPanel({ requests, loading, error, onRetry, onChanged, showApplication }: PolicyRequestsPanelProps) {
  if (error && !requests) return <ErrorState error={error} onRetry={onRetry} title="Could not load policy-change requests" />;
  if (loading || !requests) return <LoadingBlock label="Loading policy-change requests" lines={4} />;
  if (requests.length === 0) {
    return (
      <EmptyState title="No policy-change requests" icon="scale">
        Requests appear here when an engineer asks to switch a region's eviction policy. Nothing changes until another engineer approves it.
      </EmptyState>
    );
  }
  return (
    <ul className="requests">
      {requests.map((r) => (
        <PolicyRequestItem key={r.id} request={r} onChanged={onChanged} showApplication={showApplication} />
      ))}
    </ul>
  );
}
