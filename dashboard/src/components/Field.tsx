import { useId, type ReactNode } from 'react';

interface ControlProps {
  id: string;
  'aria-describedby'?: string;
  'aria-invalid'?: boolean;
}

interface FieldProps {
  label: ReactNode;
  hint?: ReactNode;
  error?: string | null;
  /** Visually hide the label (it is still announced). */
  hideLabel?: boolean;
  className?: string;
  children: (props: ControlProps) => ReactNode;
}

/** Label + control + hint + error wired together with ids (htmlFor / aria-describedby / aria-invalid). */
export function Field({ label, hint, error, hideLabel = false, className, children }: FieldProps) {
  const uid = useId();
  const id = `f${uid.replace(/:/g, '')}`;
  const hintId = hint ? `${id}-hint` : undefined;
  const errId = error ? `${id}-err` : undefined;
  const describedBy = [hintId, errId].filter(Boolean).join(' ') || undefined;
  return (
    <div className={`field${className ? ` ${className}` : ''}`}>
      <label htmlFor={id} className={hideLabel ? 'sr-only' : 'field__label'}>
        {label}
      </label>
      {children({ id, 'aria-describedby': describedBy, 'aria-invalid': error ? true : undefined })}
      {hint ? (
        <p id={hintId} className="field__hint">
          {hint}
        </p>
      ) : null}
      {error ? (
        <p id={errId} className="field__error" role="alert">
          {error}
        </p>
      ) : null}
    </div>
  );
}
