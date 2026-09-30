import { act, fireEvent, render, screen, within } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { ToastProvider } from './ToastProvider';
import type { ToastKind } from './toastContext';
import { useToast } from './useToast';

function Trigger({ message, kind }: { message: string; kind?: ToastKind }) {
  const toast = useToast();
  return (
    <button type="button" onClick={() => toast.show(message, kind)}>
      notify {message}
    </button>
  );
}

function region() {
  return screen.getByRole('status', { name: 'Notifications' });
}

describe('ToastProvider / useToast', () => {
  beforeEach(() => {
    vi.useFakeTimers();
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  it('shows a toast in a polite live region and auto-dismisses it after 4 s', () => {
    render(
      <ToastProvider>
        <Trigger message="Cache created" kind="success" />
      </ToastProvider>,
    );
    expect(region()).toHaveAttribute('aria-live', 'polite');

    fireEvent.click(screen.getByRole('button', { name: 'notify Cache created' }));
    expect(within(region()).getByText('Cache created')).toBeInTheDocument();

    act(() => {
      vi.advanceTimersByTime(3999);
    });
    expect(screen.getByText('Cache created')).toBeInTheDocument();

    act(() => {
      vi.advanceTimersByTime(1);
    });
    expect(screen.queryByText('Cache created')).not.toBeInTheDocument();
  });

  it('renders error toasts with role="alert"', () => {
    render(
      <ToastProvider>
        <Trigger message="Server unreachable" kind="error" />
        <Trigger message="Heads up" />
      </ToastProvider>,
    );
    fireEvent.click(screen.getByRole('button', { name: 'notify Server unreachable' }));
    fireEvent.click(screen.getByRole('button', { name: 'notify Heads up' }));

    expect(screen.getByRole('alert')).toHaveTextContent('Server unreachable');
    expect(screen.getAllByRole('alert')).toHaveLength(1);
    expect(within(region()).getByText('Heads up')).toBeInTheDocument();
  });

  it('can be dismissed with its button', () => {
    render(
      <ToastProvider>
        <Trigger message="Saved" />
      </ToastProvider>,
    );
    fireEvent.click(screen.getByRole('button', { name: 'notify Saved' }));
    fireEvent.click(screen.getByRole('button', { name: 'Dismiss notification' }));
    expect(screen.queryByText('Saved')).not.toBeInTheDocument();
  });

  it('keeps at most 4 toasts, dropping the oldest', () => {
    render(
      <ToastProvider>
        {['one', 'two', 'three', 'four', 'five'].map((message) => (
          <Trigger key={message} message={message} />
        ))}
      </ToastProvider>,
    );
    for (const message of ['one', 'two', 'three', 'four', 'five']) {
      fireEvent.click(screen.getByRole('button', { name: `notify ${message}` }));
    }
    expect(within(region()).getAllByRole('button', { name: 'Dismiss notification' })).toHaveLength(
      4,
    );
    expect(within(region()).queryByText('one')).not.toBeInTheDocument();
    expect(within(region()).getByText('five')).toBeInTheDocument();
  });

  it('useToast throws a clear error outside the provider', () => {
    // Keep the expected render error out of the test output (React logs it, jsdom reports it).
    vi.spyOn(console, 'error').mockImplementation(() => {});
    const swallow = (event: ErrorEvent) => event.preventDefault();
    window.addEventListener('error', swallow);
    try {
      expect(() => render(<Trigger message="nope" />)).toThrow(/inside <ToastProvider>/);
    } finally {
      window.removeEventListener('error', swallow);
    }
  });
});
