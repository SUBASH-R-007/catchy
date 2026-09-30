import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';
import { InfoPopover } from './InfoPopover';

const TEXT = 'Share of reads served from the cache over the last 10 seconds.';

function setup() {
  const user = userEvent.setup();
  render(
    <div>
      <InfoPopover label="Hit rate">{TEXT}</InfoPopover>
      <button type="button">elsewhere</button>
    </div>,
  );
  return { user, button: screen.getByRole('button', { name: 'About Hit rate' }) };
}

describe('InfoPopover', () => {
  it('opens on click as a labelled dialog with the text and moves focus into it', async () => {
    const { user, button } = setup();
    expect(button).toHaveAttribute('aria-expanded', 'false');
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();

    await user.click(button);

    const dialog = screen.getByRole('dialog', { name: 'Hit rate' });
    expect(dialog).toHaveTextContent(TEXT);
    expect(button).toHaveAttribute('aria-expanded', 'true');
    expect(button).toHaveAttribute('aria-controls', dialog.id);
    expect(dialog).toHaveFocus();
  });

  it('Esc closes the popover and returns focus to the button', async () => {
    const { user, button } = setup();
    await user.click(button);
    expect(screen.getByRole('dialog')).toBeInTheDocument();

    await user.keyboard('{Escape}');

    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    expect(button).toHaveAttribute('aria-expanded', 'false');
    expect(button).toHaveFocus();
  });

  it('closes on an outside click', async () => {
    const { user, button } = setup();
    await user.click(button);
    expect(screen.getByRole('dialog')).toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: 'elsewhere' }));

    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
  });

  it('toggles when the button is clicked again, and stays open when clicking inside', async () => {
    const { user, button } = setup();
    await user.click(button);
    await user.click(screen.getByText(TEXT));
    expect(screen.getByRole('dialog')).toBeInTheDocument();

    await user.click(button);
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
  });

  it('flips to open rightwards when there is no room on the left', async () => {
    const { user, button } = setup();
    vi.spyOn(button, 'getBoundingClientRect').mockReturnValue(
      DOMRect.fromRect({ x: 20, y: 100, width: 24, height: 24 }),
    );
    vi.spyOn(document.documentElement, 'clientWidth', 'get').mockReturnValue(1280);

    await user.click(button);

    expect(screen.getByRole('dialog')).toHaveClass('left-0');
    expect(screen.getByRole('dialog')).not.toHaveClass('right-0');
  });

  it('closes when keyboard focus leaves it', async () => {
    const { user, button } = setup();
    await user.click(button);
    await user.tab(); // from the dialog to the next focusable element
    expect(screen.getByRole('button', { name: 'elsewhere' })).toHaveFocus();
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
  });
});
