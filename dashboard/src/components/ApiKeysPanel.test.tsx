import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { api } from '../api/endpoints';
import type { ApiKey, ApiKeyCreated } from '../api/types';
import { ToastProvider } from '../context/ToastContext';
import { ApiKeyRevealDialog, ApiKeysPanel } from './ApiKeysPanel';

const PLAINTEXT = 'acc_ab12cd34_0123456789abcdef0123456789abcdef';

const created: ApiKeyCreated = {
  id: 4,
  applicationId: 1,
  label: 'staging pod 1',
  keyPrefix: 'acc_ab12cd34',
  maskedKey: 'acc_ab12cd34_••••••••••••••••',
  apiKey: PLAINTEXT,
  createdAt: '2026-09-30T09:15:30Z',
};

const listed: ApiKey = {
  id: 4,
  applicationId: 1,
  label: 'staging pod 1',
  keyPrefix: 'acc_ab12cd34',
  maskedKey: 'acc_ab12cd34_••••••••••••••••',
  createdAt: '2026-09-30T09:15:30Z',
  lastUsedAt: null,
  revokedAt: null,
  active: true,
};

function renderPanel() {
  return render(
    <ToastProvider>
      <ApiKeysPanel applicationId={1} />
    </ToastProvider>,
  );
}

beforeEach(() => {
  vi.restoreAllMocks();
});

describe('API key one-time reveal', () => {
  it('shows the plaintext key once, with a warning, and drops it after acknowledgement', async () => {
    const list = vi.spyOn(api, 'listApiKeys');
    list.mockResolvedValueOnce([]);
    list.mockResolvedValue([listed]);
    const create = vi.spyOn(api, 'createApiKey').mockResolvedValue(created);
    const user = userEvent.setup();
    renderPanel();

    expect(await screen.findByText('No API keys yet')).toBeInTheDocument();
    await user.type(screen.getByLabelText('New key label'), '  staging pod 1 ');
    await user.click(screen.getByRole('button', { name: 'Create API key' }));
    await waitFor(() => expect(create).toHaveBeenCalledWith(1, { label: 'staging pod 1' }));

    const dialog = await screen.findByRole('dialog', { name: 'Copy your new API key now' });
    expect(within(dialog).getByText(/only time the full key is shown/i)).toBeInTheDocument();
    expect(within(dialog).getByLabelText('API key')).toHaveValue(PLAINTEXT);
    expect(within(dialog).getByRole('button', { name: 'Copy' })).toBeInTheDocument();
    // list was refreshed, and it only contains the masked form
    await waitFor(() => expect(list).toHaveBeenCalledTimes(2));
    expect(screen.getAllByText('acc_ab12cd34_••••••••••••••••').length).toBeGreaterThan(0);

    // cannot be dismissed accidentally: Escape and the backdrop do nothing, there is no close icon
    await user.keyboard('{Escape}');
    expect(screen.getByRole('dialog')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Close dialog' })).toBeNull();

    await user.click(within(dialog).getByRole('button', { name: 'I have stored this key' }));
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());
    // the plaintext no longer exists anywhere in the DOM
    expect(document.body.textContent).not.toContain(PLAINTEXT);
    expect(screen.queryByDisplayValue(PLAINTEXT)).toBeNull();
  });

  it('copies the key to the clipboard and confirms', async () => {
    const user = userEvent.setup(); // installs a clipboard stub on navigator
    render(<ApiKeyRevealDialog created={created} onClose={() => undefined} />);
    await user.click(screen.getByRole('button', { name: 'Copy' }));
    expect(await navigator.clipboard.readText()).toBe(PLAINTEXT);
    expect(await screen.findByRole('button', { name: 'Copied' })).toBeInTheDocument();
  });

  it('renders nothing while there is no freshly created key', () => {
    render(<ApiKeyRevealDialog created={null} onClose={() => undefined} />);
    expect(screen.queryByRole('dialog')).toBeNull();
  });

  it('requires a label before creating a key', async () => {
    vi.spyOn(api, 'listApiKeys').mockResolvedValue([]);
    const create = vi.spyOn(api, 'createApiKey');
    const user = userEvent.setup();
    renderPanel();
    await screen.findByText('No API keys yet');
    await user.click(screen.getByRole('button', { name: 'Create API key' }));
    expect(screen.getByText(/Give the key a label/)).toBeInTheDocument();
    expect(create).not.toHaveBeenCalled();
  });
});

describe('API key revoke', () => {
  it('asks for confirmation before revoking and then refreshes the list', async () => {
    const list = vi.spyOn(api, 'listApiKeys');
    list.mockResolvedValueOnce([listed]);
    list.mockResolvedValue([{ ...listed, active: false, revokedAt: '2026-09-30T10:00:00Z' }]);
    const revoke = vi.spyOn(api, 'revokeApiKey').mockResolvedValue(undefined);
    const user = userEvent.setup();
    renderPanel();

    await user.click(await screen.findByRole('button', { name: 'Revoke key staging pod 1' }));
    const dialog = await screen.findByRole('dialog', { name: 'Revoke this API key?' });
    expect(revoke).not.toHaveBeenCalled();
    await user.click(within(dialog).getByRole('button', { name: 'Cancel' }));
    expect(revoke).not.toHaveBeenCalled();

    await user.click(screen.getByRole('button', { name: 'Revoke key staging pod 1' }));
    await user.click(within(await screen.findByRole('dialog')).getByRole('button', { name: 'Revoke key' }));
    await waitFor(() => expect(revoke).toHaveBeenCalledWith(4));
    expect(await screen.findByText('Revoked')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /Revoke key staging pod 1/ })).toBeNull();
  });
});
