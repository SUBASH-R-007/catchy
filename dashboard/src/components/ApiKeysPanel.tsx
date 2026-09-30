import { useRef, useState, type FormEvent } from 'react';
import { api } from '../api/endpoints';
import { describeError } from '../api/client';
import type { ApiKey, ApiKeyCreated } from '../api/types';
import { useToast } from '../context/ToastContext';
import { useFetch } from '../hooks/usePolling';
import { formatDateTime, formatRelativeTime } from '../lib/format';
import { Badge } from './Badge';
import { Button } from './Button';
import { Field } from './Field';
import { Icon } from './Icon';
import { Modal } from './Modal';
import { EmptyState, ErrorState, LoadingBlock } from './States';
import { Table, type Column } from './Table';

interface RevealProps {
  created: ApiKeyCreated | null;
  onClose: () => void;
}

async function copyText(text: string, fallbackInput: HTMLInputElement | null): Promise<boolean> {
  try {
    if (navigator.clipboard?.writeText) {
      await navigator.clipboard.writeText(text);
      return true;
    }
  } catch {
    /* fall through to the selection fallback */
  }
  try {
    fallbackInput?.select();
    return document.execCommand('copy');
  } catch {
    return false;
  }
}

/**
 * One-time reveal of a freshly created API key. It cannot be dismissed with Escape or a backdrop click:
 * the engineer must acknowledge it, and the plaintext is dropped from state as soon as the dialog closes.
 */
export function ApiKeyRevealDialog({ created, onClose }: RevealProps) {
  const inputRef = useRef<HTMLInputElement>(null);
  const [copied, setCopied] = useState<'idle' | 'copied' | 'failed'>('idle');

  const copy = async () => {
    if (!created) return;
    const ok = await copyText(created.apiKey, inputRef.current);
    setCopied(ok ? 'copied' : 'failed');
  };

  return (
    <Modal
      open={created !== null}
      title="Copy your new API key now"
      dismissible={false}
      onClose={onClose}
      footer={
        <Button variant="primary" onClick={onClose}>
          I have stored this key
        </Button>
      }
    >
      {created ? (
        <div className="stack">
          <p className="notice notice--warning" role="note">
            <Icon name="alert" size={16} />
            <span>
              <strong>This is the only time the full key is shown.</strong> CATCHY stores just a hash, so it cannot be displayed again. If you lose it, revoke it and create a new one.
            </span>
          </p>
          <dl className="facts facts--plain">
            <div className="facts__item">
              <dt>Label</dt>
              <dd>{created.label}</dd>
            </div>
            <div className="facts__item">
              <dt>Masked form</dt>
              <dd className="mono">{created.maskedKey}</dd>
            </div>
          </dl>
          <Field label="API key" hint="Send it as the X-AcentraCache-Key header from the SDK. Never commit it to source control.">
            {(p) => (
              <div className="copy-row">
                <input {...p} ref={inputRef} data-autofocus className="input mono" readOnly value={created.apiKey} onFocus={(e) => e.currentTarget.select()} />
                <Button icon="copy" onClick={copy}>
                  {copied === 'copied' ? 'Copied' : 'Copy'}
                </Button>
              </div>
            )}
          </Field>
          <p className="sr-only" role="status" aria-live="polite">
            {copied === 'copied' ? 'API key copied to the clipboard' : copied === 'failed' ? 'Copy failed; select the key and copy it manually' : ''}
          </p>
          {copied === 'failed' ? <p className="field__error">Copy failed. Select the key above and copy it manually.</p> : null}
        </div>
      ) : null}
    </Modal>
  );
}

interface ApiKeysPanelProps {
  applicationId: number;
}

/** ADMIN: create (plaintext shown once), list (masked) and revoke (with confirmation) API keys. */
export function ApiKeysPanel({ applicationId }: ApiKeysPanelProps) {
  const toast = useToast();
  const keys = useFetch(() => api.listApiKeys(applicationId), [applicationId]);
  const [label, setLabel] = useState('');
  const [labelError, setLabelError] = useState<string | null>(null);
  const [creating, setCreating] = useState(false);
  const [created, setCreated] = useState<ApiKeyCreated | null>(null);
  const [revoking, setRevoking] = useState<ApiKey | null>(null);
  const [revokeBusy, setRevokeBusy] = useState(false);

  const create = async (e: FormEvent) => {
    e.preventDefault();
    const trimmed = label.trim();
    if (!trimmed) {
      setLabelError('Give the key a label, e.g. "staging pod 1".');
      return;
    }
    if (trimmed.length > 80) {
      setLabelError('Labels are limited to 80 characters.');
      return;
    }
    setLabelError(null);
    setCreating(true);
    try {
      const res = await api.createApiKey(applicationId, { label: trimmed });
      setCreated(res);
      setLabel('');
      void keys.refresh();
    } catch (err) {
      toast.error(describeError(err));
    } finally {
      setCreating(false);
    }
  };

  const revoke = async () => {
    if (!revoking) return;
    setRevokeBusy(true);
    try {
      await api.revokeApiKey(revoking.id);
      toast.success(`Revoked key "${revoking.label}". Services using it will now receive 401.`);
      setRevoking(null);
      void keys.refresh();
    } catch (err) {
      toast.error(describeError(err));
    } finally {
      setRevokeBusy(false);
    }
  };

  const columns: Column<ApiKey>[] = [
    { key: 'label', header: 'Label', cell: (k) => k.label },
    { key: 'key', header: 'Key', cell: (k) => <code>{k.maskedKey}</code> },
    { key: 'created', header: 'Created', className: 'nowrap', cell: (k) => formatDateTime(k.createdAt) },
    {
      key: 'used',
      header: 'Last used',
      className: 'nowrap',
      cell: (k) => (k.lastUsedAt ? formatRelativeTime(k.lastUsedAt) : <span className="faint">never</span>),
    },
    {
      key: 'status',
      header: 'Status',
      cell: (k) =>
        k.active ? (
          <Badge tone="good" icon="check">
            Active
          </Badge>
        ) : (
          <Badge tone="neutral" icon="x-circle" title={k.revokedAt ? `Revoked ${formatDateTime(k.revokedAt)}` : 'Revoked'}>
            Revoked
          </Badge>
        ),
    },
    {
      key: 'actions',
      header: <span className="sr-only">Actions</span>,
      align: 'right',
      cell: (k) =>
        k.active ? (
          <Button size="sm" variant="danger" icon="trash" onClick={() => setRevoking(k)} aria-label={`Revoke key ${k.label}`}>
            Revoke
          </Button>
        ) : null,
    },
  ];

  let body;
  if (keys.error && !keys.data) body = <ErrorState error={keys.error} onRetry={() => void keys.refresh()} title="Could not load API keys" />;
  else if (keys.loading || !keys.data) body = <LoadingBlock label="Loading API keys" lines={3} />;
  else if (keys.data.length === 0) {
    body = (
      <EmptyState title="No API keys yet" icon="key">
        Create a key so a service can send telemetry for this application.
      </EmptyState>
    );
  } else body = <Table columns={columns} rows={keys.data} rowKey={(k) => k.id} caption="API keys" />;

  return (
    <div className="stack">
      <form className="inline-form" onSubmit={create} noValidate>
        <Field label="New key label" error={labelError} className="inline-form__field">
          {(p) => <input {...p} className="input" value={label} maxLength={90} onChange={(e) => setLabel(e.target.value)} placeholder="e.g. staging pod 1" />}
        </Field>
        <Button type="submit" variant="primary" icon="key" loading={creating}>
          Create API key
        </Button>
      </form>
      {body}
      <ApiKeyRevealDialog created={created} onClose={() => setCreated(null)} />
      <Modal
        open={revoking !== null}
        title="Revoke this API key?"
        onClose={() => setRevoking(null)}
        footer={
          <>
            <Button onClick={() => setRevoking(null)} disabled={revokeBusy}>
              Cancel
            </Button>
            <Button variant="danger" icon="trash" loading={revokeBusy} onClick={revoke}>
              Revoke key
            </Button>
          </>
        }
      >
        <p>
          Any service still using <strong>{revoking?.label}</strong> (<code>{revoking?.maskedKey}</code>) will start receiving <code>401</code> and stop sending telemetry. This cannot be undone.
        </p>
      </Modal>
    </div>
  );
}
