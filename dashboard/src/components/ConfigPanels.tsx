import { useEffect, useMemo, useState, type FormEvent } from 'react';
import { Link } from 'react-router-dom';
import { api } from '../api/endpoints';
import { describeError } from '../api/client';
import type { RegionConfig } from '../api/types';
import { CONFIG_BOUNDS } from '../api/types';
import { formatBytes, formatDateTime, formatDuration, formatInt, megabytesToBytes } from '../lib/format';
import {
  initialFormValues,
  MAX_REASON_LENGTH,
  validateConfigForm,
  type ConfigFormValues,
} from '../lib/configForm';
import { paths } from '../lib/routes';
import { useToast } from '../context/ToastContext';
import { Badge, PolicyBadge, RiskBadge } from './Badge';
import { Button } from './Button';
import { Field } from './Field';
import { Icon } from './Icon';

function ConfigRows({ config }: { config: RegionConfig }) {
  const d = config.desired;
  const r = config.reported;
  const rows: Array<{ label: string; reported: string; desired: string | null; changed: boolean }> = [
    {
      label: 'Maximum entries',
      reported: formatInt(r.maximumEntries),
      desired: d?.maximumEntries != null ? formatInt(d.maximumEntries) : null,
      changed: d?.maximumEntries != null && d.maximumEntries !== r.maximumEntries,
    },
    {
      label: 'Maximum memory (estimated)',
      reported: formatBytes(r.maximumMemoryBytes),
      desired: d?.maximumMemoryBytes != null ? formatBytes(d.maximumMemoryBytes) : null,
      changed: d?.maximumMemoryBytes != null && d.maximumMemoryBytes !== r.maximumMemoryBytes,
    },
    {
      label: 'Default TTL',
      reported: formatDuration(r.defaultTtlMs),
      desired: d?.defaultTtlMs != null ? formatDuration(d.defaultTtlMs) : null,
      changed: d?.defaultTtlMs != null && d.defaultTtlMs !== r.defaultTtlMs,
    },
  ];
  return (
    <div className="table-wrap" role="region" aria-label={`Configuration of ${config.cacheRegion}`} tabIndex={0}>
    <table className="table table--compact config-table">
      <caption className="sr-only">Reported versus desired configuration for {config.cacheRegion}</caption>
      <thead>
        <tr>
          <th scope="col">Setting</th>
          <th scope="col" className="align-right">
            Reported by SDK
          </th>
          <th scope="col" className="align-right">
            Desired
          </th>
        </tr>
      </thead>
      <tbody>
        {rows.map((row) => (
          <tr key={row.label}>
            <th scope="row">{row.label}</th>
            <td className="align-right num">{row.reported}</td>
            <td className="align-right num">
              {row.desired === null ? (
                <span className="faint">not overridden</span>
              ) : (
                <>
                  {row.desired}
                  {row.changed ? (
                    <Badge tone="warning" icon="clock" className="badge--inline">
                      pending
                    </Badge>
                  ) : null}
                </>
              )}
            </td>
          </tr>
        ))}
      </tbody>
    </table>
    </div>
  );
}

export function PendingNotice({ config }: { config: RegionConfig }) {
  if (!config.pending) return null;
  return (
    <p className="notice notice--warning" role="status">
      <Icon name="clock" size={16} />
      <span>
        Pending until the SDK applies it. The desired values are delivered on the SDK's next control poll, and the reported values above will update once it confirms.
      </span>
    </p>
  );
}

interface RegionConfigPanelProps {
  config: RegionConfig;
  applicationId: number;
  canEdit: boolean;
}

/** Read-only reported-vs-desired configuration (everyone); admins get a link to edit it. */
export function RegionConfigPanel({ config, applicationId, canEdit }: RegionConfigPanelProps) {
  return (
    <div className="stack-sm">
      <div className="row">
        <RiskBadge level={config.riskLevel} />
        {config.reported.activePolicy ? <PolicyBadge policy={config.reported.activePolicy} /> : null}
        {config.tuningVersion ? <span className="faint">Tuning version {config.tuningVersion}</span> : null}
      </div>
      <ConfigRows config={config} />
      <PendingNotice config={config} />
      {config.updatedBy ? (
        <p className="faint">
          Last changed by {config.updatedBy}
          {config.updatedAt ? ` · ${formatDateTime(config.updatedAt)}` : ''}
        </p>
      ) : null}
      {canEdit ? (
        <Link to={paths.configuration(applicationId, config.cacheRegion)} className="inline-link">
          Edit configuration <Icon name="arrow-right" size={13} />
        </Link>
      ) : (
        <p className="faint">Configuration changes require the ADMIN role.</p>
      )}
    </div>
  );
}

interface ConfigEditorProps {
  applicationId: number;
  config: RegionConfig;
  /** Called with the fresh config after a successful save. */
  onSaved: (config: RegionConfig) => void;
}

/** ADMIN form: edit maximumEntries, memory (entered in MB) and TTL (entered in seconds) with contract-bound validation. */
export function ConfigEditor({ applicationId, config, onSaved }: ConfigEditorProps) {
  const toast = useToast();
  // Polling hands us a new `config` object every few seconds; only re-seed the form when a value it shows really changes.
  const baselineKey = JSON.stringify([config.cacheRegion, config.tuningVersion, config.reported, config.desired ?? null]);
  // eslint-disable-next-line react-hooks/exhaustive-deps
  const baseline = useMemo(() => initialFormValues(config), [baselineKey]);
  const [values, setValues] = useState<ConfigFormValues>(baseline);
  const [saving, setSaving] = useState(false);
  const [touched, setTouched] = useState(false);
  const [serverError, setServerError] = useState<string | null>(null);

  // Re-seed when the selected region changes or a save completes (new tuning version).
  useEffect(() => {
    setValues(baseline);
    setTouched(false);
    setServerError(null);
  }, [baseline]);

  const { errors, request } = validateConfigForm(values, baseline);
  const shown = touched ? errors : {};
  const set = (field: keyof ConfigFormValues) => (e: { target: { value: string } }) => {
    setValues((v) => ({ ...v, [field]: e.target.value }));
    setServerError(null);
  };

  const memoryBytesPreview = (() => {
    const mb = Number(values.maximumMemoryMb);
    return values.maximumMemoryMb.trim() !== '' && Number.isFinite(mb) && mb > 0 ? `= ${formatInt(megabytesToBytes(mb))} bytes` : null;
  })();

  const submit = async (e: FormEvent) => {
    e.preventDefault();
    setTouched(true);
    if (!request) return;
    setSaving(true);
    setServerError(null);
    try {
      const saved = await api.updateRegionConfig(applicationId, config.cacheRegion, request);
      toast.success(`Configuration requested for ${config.cacheRegion}. It stays pending until the SDK applies it.`);
      onSaved(saved);
    } catch (err) {
      const message = describeError(err);
      setServerError(message);
      toast.error(message);
    } finally {
      setSaving(false);
    }
  };

  return (
    <form className="stack" onSubmit={submit} noValidate aria-label={`Configuration for ${config.cacheRegion}`}>
      <div className="form-grid">
        <Field
          label="Maximum entries"
          hint={`Whole number, ${formatInt(CONFIG_BOUNDS.maximumEntries.min)} to ${formatInt(CONFIG_BOUNDS.maximumEntries.max)}.`}
          error={shown.maximumEntries}
        >
          {(p) => <input {...p} className="input" inputMode="numeric" value={values.maximumEntries} onChange={set('maximumEntries')} />}
        </Field>
        <Field
          label="Maximum memory (MB)"
          hint={<>Estimated cache memory limit. {memoryBytesPreview ?? `Minimum ${CONFIG_BOUNDS.maximumMemoryBytes.min} bytes.`}</>}
          error={shown.maximumMemoryMb}
        >
          {(p) => <input {...p} className="input" inputMode="decimal" value={values.maximumMemoryMb} onChange={set('maximumMemoryMb')} />}
        </Field>
        <Field label="Default TTL (seconds)" hint="Entries expire after this long. Minimum 1 second." error={shown.defaultTtlSeconds}>
          {(p) => <input {...p} className="input" inputMode="decimal" value={values.defaultTtlSeconds} onChange={set('defaultTtlSeconds')} />}
        </Field>
      </div>
      <Field
        label="Reason (optional)"
        hint={`Recorded in the audit log. Up to ${MAX_REASON_LENGTH} characters. Do not include patient or member data.`}
        error={shown.reason}
      >
        {(p) => <input {...p} className="input" maxLength={MAX_REASON_LENGTH + 20} value={values.reason} onChange={set('reason')} placeholder="e.g. Raise capacity for month-end load" />}
      </Field>
      {shown.form ? (
        <p className="field__error" role="alert">
          {shown.form}
        </p>
      ) : null}
      {serverError ? (
        <p className="field__error" role="alert">
          {serverError}
        </p>
      ) : null}
      <div className="row">
        <Button type="submit" variant="primary" loading={saving} icon="check">
          Request change
        </Button>
        <Button
          onClick={() => {
            setValues(baseline);
            setTouched(false);
            setServerError(null);
          }}
          disabled={saving}
        >
          Reset
        </Button>
      </div>
    </form>
  );
}

export { ConfigRows };
