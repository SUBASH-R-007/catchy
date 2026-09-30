import type { Application } from '../api/types';
import { applicationLabel } from '../lib/applications';
import { Field } from './Field';

interface ApplicationSelectProps {
  applications: readonly Application[];
  value: number | null;
  onChange: (id: number | null) => void;
  label?: string;
  /** Adds an "All applications" option that maps to `null`. */
  allowAll?: boolean;
  disabled?: boolean;
}

export function ApplicationSelect({ applications, value, onChange, label = 'Application', allowAll = false, disabled }: ApplicationSelectProps) {
  return (
    <Field label={label}>
      {(p) => (
        <select
          {...p}
          className="select"
          value={value ?? ''}
          disabled={disabled}
          onChange={(e) => onChange(e.target.value === '' ? null : Number(e.target.value))}
        >
          {allowAll ? <option value="">All applications</option> : null}
          {!allowAll && value === null ? <option value="">Select an application…</option> : null}
          {applications.map((a) => (
            <option key={a.id} value={a.id}>
              {applicationLabel(a)}
              {a.regionCount === 0 ? ' — no telemetry yet' : ''}
            </option>
          ))}
        </select>
      )}
    </Field>
  );
}
