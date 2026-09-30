import { useContext } from 'react';
import { ToastContext, type ToastApi } from './toastContext';

/** Access the toast stack. Must be called inside <ToastProvider>. */
export function useToast(): ToastApi {
  const api = useContext(ToastContext);
  if (!api) {
    throw new Error(
      'useToast() must be used inside <ToastProvider>. Wrap the app in <ToastProvider>.',
    );
  }
  return api;
}
