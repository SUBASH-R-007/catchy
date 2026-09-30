import { useId, useRef, useState, type FormEvent } from 'react';
import { api } from '../../api/client';
import type { CacheInfo } from '../../api/rest';
import { cx } from '../../components/cx';
import { useToast } from '../../components';
import { formatClock } from '../../lib/format';
import {
  describeDelete,
  describeGet,
  describePut,
  parseTtlSeconds,
  pushHistory,
  validateKey,
  type CommandOp,
  type CommandResult,
  type HistoryEntry,
  type Outcome,
} from './commands';
import {
  buttonClass,
  errorMessage,
  fieldErrorClass,
  inputClass,
  labelClass,
  primaryButtonClass,
} from './ui';

export interface CommandFormProps {
  cache: CacheInfo;
  /** Called after every successful command, so entries and stats refresh at once. */
  onDone: () => void;
}

const OUTCOME_TEXT: Record<Outcome, string> = {
  hit: 'text-good',
  miss: 'text-warn',
  stored: 'text-trace-glow',
  removed: 'text-trace-glow',
  'not-found': 'text-warn',
};

const OUTCOME_BORDER: Record<Outcome, string> = {
  hit: 'border-good/60',
  miss: 'border-warn/60',
  stored: 'border-trace-glow/60',
  removed: 'border-trace-glow/60',
  'not-found': 'border-warn/60',
};

/** Get / put / delete one key, with a clear result sentence and the last 10 operations. */
export function CommandForm({ cache, onDone }: CommandFormProps) {
  const toast = useToast();
  const id = useId();
  const [key, setKey] = useState('');
  const [value, setValue] = useState('');
  const [ttl, setTtl] = useState('');
  const [busy, setBusy] = useState<CommandOp | null>(null);
  const [result, setResult] = useState<CommandResult | null>(null);
  const [history, setHistory] = useState<HistoryEntry[]>([]);
  const [touched, setTouched] = useState(false);
  const nextId = useRef(1);

  const keyError = validateKey(key);
  const ttlParsed = parseTtlSeconds(ttl);

  const run = async (op: CommandOp) => {
    setTouched(true);
    if (keyError !== null || busy) return;
    if (op === 'PUT' && !ttlParsed.ok) return;
    setBusy(op);
    try {
      let r: CommandResult;
      if (op === 'GET') {
        r = describeGet(key, await api.getEntry(cache.name, key));
      } else if (op === 'PUT') {
        const ttlMs = ttlParsed.ok ? ttlParsed.value : null;
        await api.putEntry(cache.name, key, ttlMs === null ? { value } : { value, ttlMs });
        r = describePut(key, ttlMs, cache.defaultTtlMs);
      } else {
        r = describeDelete(key, (await api.deleteEntry(cache.name, key)).removed);
      }
      setResult(r);
      setHistory((h) => pushHistory(h, { ...r, id: nextId.current++, ts: Date.now() }));
      onDone();
    } catch (e) {
      toast.show(errorMessage(e), 'error');
    } finally {
      setBusy(null);
    }
  };

  const onSubmit = (e: FormEvent) => {
    e.preventDefault();
    void run('GET');
  };

  const showKeyError = touched && keyError !== null;
  const showTtlError = !ttlParsed.ok;

  return (
    <div className="flex flex-col gap-4">
      <form onSubmit={onSubmit} noValidate className="flex flex-col gap-4">
        <div className="grid gap-4 sm:grid-cols-2">
          <div>
            <label htmlFor={`${id}-key`} className={labelClass}>
              Key
            </label>
            <input
              id={`${id}-key`}
              className={inputClass}
              value={key}
              onChange={(e) => setKey(e.target.value)}
              placeholder="e.g. drug:4411"
              autoComplete="off"
              spellCheck={false}
              maxLength={200}
              aria-invalid={showKeyError}
              aria-describedby={showKeyError ? `${id}-key-error` : undefined}
            />
            {showKeyError ? (
              <p id={`${id}-key-error`} className={fieldErrorClass}>
                {keyError}
              </p>
            ) : null}
          </div>
          <div>
            <label htmlFor={`${id}-ttl`} className={labelClass}>
              TTL for put (s, optional)
            </label>
            <input
              id={`${id}-ttl`}
              className={inputClass}
              type="number"
              inputMode="decimal"
              min={0}
              step="any"
              placeholder={cache.defaultTtlMs === null ? 'never' : 'cache default'}
              value={ttl}
              onChange={(e) => setTtl(e.target.value)}
              aria-invalid={showTtlError}
              aria-describedby={showTtlError ? `${id}-ttl-error` : undefined}
            />
            {!ttlParsed.ok ? (
              <p id={`${id}-ttl-error`} className={fieldErrorClass}>
                {ttlParsed.error}
              </p>
            ) : null}
          </div>
        </div>
        <div>
          <label htmlFor={`${id}-value`} className={labelClass}>
            Value (for put)
          </label>
          <input
            id={`${id}-value`}
            className={inputClass}
            value={value}
            onChange={(e) => setValue(e.target.value)}
            placeholder="any text"
            autoComplete="off"
            maxLength={10_000}
          />
        </div>
        <div className="flex flex-wrap gap-2">
          <button type="submit" className={primaryButtonClass} disabled={busy !== null}>
            Get
          </button>
          <button
            type="button"
            className={buttonClass}
            onClick={() => void run('PUT')}
            disabled={busy !== null}
          >
            Put
          </button>
          <button
            type="button"
            className={buttonClass}
            onClick={() => void run('DELETE')}
            disabled={busy !== null}
          >
            Delete
          </button>
        </div>
      </form>

      <div
        role="status"
        aria-live="polite"
        aria-label="Result"
        className={cx(
          'min-h-12 rounded border px-4 py-3 font-mono text-sm',
          result
            ? `${OUTCOME_BORDER[result.outcome]} ${OUTCOME_TEXT[result.outcome]}`
            : 'border-dashed border-trace text-muted',
        )}
      >
        {result ? (
          <>
            <span className="text-muted">
              {result.op} {result.key} →{' '}
            </span>
            {result.message}
          </>
        ) : (
          'Run a command to see whether the key is a hit or a miss.'
        )}
      </div>

      <div>
        <h3 className="mb-2 font-heading text-base text-text">Last 10 operations</h3>
        {history.length === 0 ? (
          <p className="text-sm text-muted">No operations yet.</p>
        ) : (
          <table className="w-full table-fixed text-left text-sm">
            <thead className="text-muted">
              <tr>
                <th scope="col" className="w-24 py-1 font-normal">
                  Time
                </th>
                <th scope="col" className="w-20 py-1 font-normal">
                  Op
                </th>
                <th scope="col" className="py-1 font-normal">
                  Key
                </th>
                <th scope="col" className="w-24 py-1 font-normal">
                  Result
                </th>
              </tr>
            </thead>
            <tbody className="font-mono">
              {history.map((h) => (
                <tr key={h.id} className="border-t border-trace/60">
                  <td className="py-1 text-muted tabular-nums">{formatClock(h.ts)}</td>
                  <td className="py-1 text-text">{h.op}</td>
                  <td className="truncate py-1 text-text" title={h.key}>
                    {h.key}
                  </td>
                  <td className={cx('py-1', OUTCOME_TEXT[h.outcome])}>{h.short}</td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </div>
    </div>
  );
}
