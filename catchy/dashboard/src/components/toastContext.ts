import { createContext } from 'react';

export type ToastKind = 'info' | 'success' | 'error';

export interface Toast {
  id: number;
  message: string;
  kind: ToastKind;
}

export interface ToastApi {
  /** Shows a toast that dismisses itself after 4 s. Defaults to kind 'info'. */
  show(message: string, kind?: ToastKind): void;
}

/** How long a toast stays on screen. */
export const TOAST_DURATION_MS = 4000;

/** At most this many toasts are visible; older ones are dropped first. */
export const MAX_TOASTS = 4;

export const ToastContext = createContext<ToastApi | null>(null);
