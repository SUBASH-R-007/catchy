import { X } from 'lucide-react';
import { useCallback, useEffect, useMemo, useRef, useState, type ReactNode } from 'react';
import './components.css';
import { cx } from './cx';
import {
  MAX_TOASTS,
  TOAST_DURATION_MS,
  ToastContext,
  type Toast,
  type ToastApi,
  type ToastKind,
} from './toastContext';

const LED_CLASS: Record<ToastKind, string> = {
  info: 'led--info',
  success: 'led--good',
  error: 'led--critical',
};

/** Provides useToast() and renders a fixed bottom-right stack of auto-dismissing toasts. */
export function ToastProvider({ children }: { children: ReactNode }) {
  const [toasts, setToasts] = useState<Toast[]>([]);
  const nextId = useRef(1);
  const timers = useRef(new Map<number, number>());

  const dismiss = useCallback((id: number) => {
    const timer = timers.current.get(id);
    if (timer !== undefined) window.clearTimeout(timer);
    timers.current.delete(id);
    setToasts((current) => current.filter((toast) => toast.id !== id));
  }, []);

  const show = useCallback(
    (message: string, kind: ToastKind = 'info') => {
      const id = nextId.current++;
      timers.current.set(
        id,
        window.setTimeout(() => dismiss(id), TOAST_DURATION_MS),
      );
      // Oldest toasts beyond the cap are dropped; their timers later fire as harmless no-ops.
      setToasts((current) => [...current, { id, message, kind }].slice(-MAX_TOASTS));
    },
    [dismiss],
  );

  useEffect(() => {
    const pending = timers.current;
    return () => {
      pending.forEach((timer) => window.clearTimeout(timer));
      pending.clear();
    };
  }, []);

  const api = useMemo<ToastApi>(() => ({ show }), [show]);

  return (
    <ToastContext.Provider value={api}>
      {children}
      <div
        role="status"
        aria-live="polite"
        aria-label="Notifications"
        className="pointer-events-none fixed bottom-4 right-4 z-[60] flex w-[360px] max-w-[calc(100vw-32px)] flex-col gap-2"
      >
        {toasts.map((toast) => (
          <div
            key={toast.id}
            role={toast.kind === 'error' ? 'alert' : undefined}
            data-kind={toast.kind}
            className={cx(
              'toast-enter pointer-events-auto flex items-start gap-2 rounded border bg-surface-2 py-2 pl-4 pr-2 shadow-lg shadow-black/40',
              toast.kind === 'error' ? 'border-critical/60' : 'border-trace',
            )}
          >
            <span aria-hidden="true" className={cx('led mt-2', LED_CLASS[toast.kind])} />
            <p className="min-w-0 flex-1 py-1 text-sm text-text">{toast.message}</p>
            <button
              type="button"
              aria-label="Dismiss notification"
              onClick={() => dismiss(toast.id)}
              className="inline-flex size-8 shrink-0 items-center justify-center rounded text-muted transition-colors hover:bg-surface hover:text-text"
            >
              <X aria-hidden="true" size={16} />
            </button>
          </div>
        ))}
      </div>
    </ToastContext.Provider>
  );
}
