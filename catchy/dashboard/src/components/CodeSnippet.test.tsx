import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';
import { CodeSnippet } from './CodeSnippet';
import { ToastProvider } from './ToastProvider';

const CODE = 'implementation("io.cachelab:cache-core:1.0.0")';

function renderSnippet() {
  return render(
    <ToastProvider>
      <CodeSnippet code={CODE} label="Gradle dependency" language="kotlin" />
    </ToastProvider>,
  );
}

describe('CodeSnippet', () => {
  it('renders the code with its label and language', () => {
    renderSnippet();
    expect(screen.getByRole('figure', { name: 'Gradle dependency' })).toBeInTheDocument();
    expect(screen.getByText(CODE).tagName).toBe('CODE');
    expect(screen.getByText('kotlin')).toBeInTheDocument();
  });

  it('copies the code to the clipboard and shows a "Copied" toast', async () => {
    const user = userEvent.setup(); // installs a clipboard stub on navigator
    const writeText = vi.spyOn(navigator.clipboard, 'writeText');
    renderSnippet();

    await user.click(screen.getByRole('button', { name: 'Copy Gradle dependency' }));

    expect(writeText).toHaveBeenCalledWith(CODE);
    expect(await navigator.clipboard.readText()).toBe(CODE);
    const toasts = screen.getByRole('status', { name: 'Notifications' });
    expect(await within(toasts).findByText('Copied')).toBeInTheDocument();
  });

  it('shows an error toast when the clipboard write fails', async () => {
    const user = userEvent.setup();
    vi.spyOn(navigator.clipboard, 'writeText').mockRejectedValue(new Error('denied'));
    renderSnippet();

    await user.click(screen.getByRole('button', { name: 'Copy Gradle dependency' }));

    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent('Could not copy Gradle dependency');
    expect(screen.queryByText('Copied')).not.toBeInTheDocument();
  });
});
