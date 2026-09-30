import { Plus } from 'lucide-react';
import { useId, useState, type FormEvent, type RefObject } from 'react';
import { api } from '../../api/client';
import type { CacheConfig, CreateCacheRequest } from '../../api/rest';
import type { PolicyType } from '../../api/types';
import { DipSwitch, useToast } from '../../components';
import { nextCacheName, parseCapacity, parseTtlSeconds, validateCacheName } from './commands';
import {
  errorMessage,
  fieldErrorClass,
  inputClass,
  labelClass,
  POLICY_OPTIONS,
  primaryButtonClass,
} from './ui';

export interface CreateCacheFormProps {
  onCreated: (config: CacheConfig) => void;
  nameInputRef?: RefObject<HTMLInputElement>;
}

/** "Create a cache": name, policy, capacity (default 5, so evictions show quickly), default TTL. */
export function CreateCacheForm({ onCreated, nameInputRef }: CreateCacheFormProps) {
  const toast = useToast();
  const id = useId();
  const [name, setName] = useState('play-1');
  const [policy, setPolicy] = useState<PolicyType>('LRU');
  const [capacity, setCapacity] = useState('5');
  const [ttl, setTtl] = useState('');
  const [touched, setTouched] = useState(false);
  const [busy, setBusy] = useState(false);

  const nameError = validateCacheName(name);
  const capacityParsed = parseCapacity(capacity);
  const ttlParsed = parseTtlSeconds(ttl);
  const valid = nameError === null && capacityParsed.ok && ttlParsed.ok;
  const showErrors = touched;

  const submit = async (e: FormEvent) => {
    e.preventDefault();
    setTouched(true);
    if (!valid || busy) return;
    const body: CreateCacheRequest = {
      name,
      policy,
      capacity: capacityParsed.value,
      group: 'playground',
    };
    if (ttlParsed.value !== null) body.defaultTtlMs = ttlParsed.value;
    setBusy(true);
    try {
      const created = await api.createCache(body);
      toast.show(`Created cache "${created.name}".`, 'success');
      setName(nextCacheName(created.name));
      setTouched(false);
      onCreated(created);
    } catch (err) {
      toast.show(errorMessage(err), 'error');
    } finally {
      setBusy(false);
    }
  };

  return (
    <form onSubmit={(e) => void submit(e)} noValidate className="flex flex-col gap-4">
      <div>
        <label htmlFor={`${id}-name`} className={labelClass}>
          Name
        </label>
        <input
          ref={nameInputRef}
          id={`${id}-name`}
          className={inputClass}
          value={name}
          onChange={(e) => setName(e.target.value)}
          autoComplete="off"
          spellCheck={false}
          maxLength={40}
          aria-invalid={showErrors && nameError !== null}
          aria-describedby={showErrors && nameError ? `${id}-name-error` : undefined}
        />
        {showErrors && nameError ? (
          <p id={`${id}-name-error`} className={fieldErrorClass}>
            {nameError}
          </p>
        ) : null}
      </div>

      <DipSwitch
        label="Eviction policy"
        options={POLICY_OPTIONS}
        value={policy}
        onChange={setPolicy}
      />

      <div className="grid grid-cols-2 gap-4">
        <div>
          <label htmlFor={`${id}-capacity`} className={labelClass}>
            Capacity (entries)
          </label>
          <input
            id={`${id}-capacity`}
            className={inputClass}
            type="number"
            inputMode="numeric"
            min={1}
            max={1_000_000}
            step={1}
            value={capacity}
            onChange={(e) => setCapacity(e.target.value)}
            aria-invalid={showErrors && !capacityParsed.ok}
            aria-describedby={showErrors && !capacityParsed.ok ? `${id}-cap-error` : undefined}
          />
          {showErrors && !capacityParsed.ok ? (
            <p id={`${id}-cap-error`} className={fieldErrorClass}>
              {capacityParsed.error}
            </p>
          ) : null}
        </div>
        <div>
          <label htmlFor={`${id}-ttl`} className={labelClass}>
            Default TTL (s, optional)
          </label>
          <input
            id={`${id}-ttl`}
            className={inputClass}
            type="number"
            inputMode="decimal"
            min={0}
            step="any"
            placeholder="never"
            value={ttl}
            onChange={(e) => setTtl(e.target.value)}
            aria-invalid={showErrors && !ttlParsed.ok}
            aria-describedby={showErrors && !ttlParsed.ok ? `${id}-ttl-error` : undefined}
          />
          {showErrors && !ttlParsed.ok ? (
            <p id={`${id}-ttl-error`} className={fieldErrorClass}>
              {ttlParsed.error}
            </p>
          ) : null}
        </div>
      </div>

      <p className="text-sm text-muted">
        A small capacity (like 5) makes evictions easy to see. New caches join the “playground”
        group.
      </p>

      <div>
        <button type="submit" className={primaryButtonClass} disabled={busy}>
          <Plus aria-hidden="true" size={16} />
          {busy ? 'Creating…' : 'Create cache'}
        </button>
      </div>
    </form>
  );
}
