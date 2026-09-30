import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { useState } from 'react';
import { describe, expect, it, vi } from 'vitest';
import { DipSwitch } from './DipSwitch';

type Policy = 'LRU' | 'LFU' | 'LFU_DECAY';

const OPTIONS = [
  { value: 'LRU', label: 'LRU', color: 'var(--policy-lru)' },
  { value: 'LFU', label: 'LFU', color: 'var(--policy-lfu)' },
  { value: 'LFU_DECAY', label: 'LFU decay', color: 'var(--policy-lfu-decay)' },
] as const;

function Controlled({ onChange }: { onChange: (value: Policy) => void }) {
  const [value, setValue] = useState<Policy>('LRU');
  return (
    <DipSwitch<Policy>
      label="Eviction policy"
      options={OPTIONS}
      value={value}
      onChange={(next) => {
        setValue(next);
        onChange(next);
      }}
    />
  );
}

describe('DipSwitch', () => {
  it('renders a labelled radiogroup with aria-checked on the selected option', () => {
    render(<DipSwitch label="Eviction policy" options={OPTIONS} value="LFU" onChange={vi.fn()} />);

    expect(screen.getByRole('radiogroup', { name: 'Eviction policy' })).toBeInTheDocument();
    const radios = screen.getAllByRole('radio');
    expect(radios).toHaveLength(3);
    expect(screen.getByRole('radio', { name: 'LFU' })).toHaveAttribute('aria-checked', 'true');
    expect(screen.getByRole('radio', { name: 'LRU' })).toHaveAttribute('aria-checked', 'false');
    expect(screen.getByRole('radio', { name: 'LFU decay' })).toHaveAttribute(
      'aria-checked',
      'false',
    );
  });

  it('keeps only the checked radio in the tab order', async () => {
    const user = userEvent.setup();
    render(
      <>
        <button type="button">before</button>
        <DipSwitch label="Eviction policy" options={OPTIONS} value="LFU" onChange={vi.fn()} />
      </>,
    );

    const tabbable = screen.getAllByRole('radio').filter((radio) => radio.tabIndex === 0);
    expect(tabbable).toHaveLength(1);
    expect(tabbable[0]).toHaveAccessibleName('LFU');

    await user.click(screen.getByRole('button', { name: 'before' }));
    await user.tab();
    expect(screen.getByRole('radio', { name: 'LFU' })).toHaveFocus();
  });

  it('ArrowRight / ArrowLeft move focus, select and wrap around', async () => {
    const user = userEvent.setup();
    const onChange = vi.fn();
    render(<Controlled onChange={onChange} />);

    await user.tab();
    expect(screen.getByRole('radio', { name: 'LRU' })).toHaveFocus();

    await user.keyboard('{ArrowRight}');
    expect(onChange).toHaveBeenLastCalledWith('LFU');
    expect(screen.getByRole('radio', { name: 'LFU' })).toHaveFocus();
    expect(screen.getByRole('radio', { name: 'LFU' })).toHaveAttribute('aria-checked', 'true');

    await user.keyboard('{ArrowRight}{ArrowRight}');
    expect(onChange).toHaveBeenLastCalledWith('LRU'); // wrapped past the last option
    expect(screen.getByRole('radio', { name: 'LRU' })).toHaveFocus();

    await user.keyboard('{ArrowLeft}');
    expect(onChange).toHaveBeenLastCalledWith('LFU_DECAY'); // wrapped past the first option
    expect(screen.getByRole('radio', { name: 'LFU decay' })).toHaveFocus();

    await user.keyboard('{ArrowUp}');
    expect(onChange).toHaveBeenLastCalledWith('LFU');
    await user.keyboard('{ArrowDown}');
    expect(onChange).toHaveBeenLastCalledWith('LFU_DECAY');
    expect(onChange).toHaveBeenCalledTimes(6);
  });

  it('moves focus even when the parent has not re-rendered yet', async () => {
    const user = userEvent.setup();
    const onChange = vi.fn();
    render(<DipSwitch label="Eviction policy" options={OPTIONS} value="LRU" onChange={onChange} />);

    screen.getByRole('radio', { name: 'LRU' }).focus();
    await user.keyboard('{ArrowLeft}');
    expect(onChange).toHaveBeenCalledWith('LFU_DECAY');
    expect(screen.getByRole('radio', { name: 'LFU decay' })).toHaveFocus();
  });

  it('Space and Enter select the focused option; click selects', async () => {
    const user = userEvent.setup();
    const onChange = vi.fn();
    render(<DipSwitch label="Eviction policy" options={OPTIONS} value="LRU" onChange={onChange} />);

    screen.getByRole('radio', { name: 'LFU' }).focus();
    await user.keyboard(' ');
    expect(onChange).toHaveBeenCalledTimes(1);
    expect(onChange).toHaveBeenLastCalledWith('LFU');

    screen.getByRole('radio', { name: 'LFU decay' }).focus();
    await user.keyboard('{Enter}');
    expect(onChange).toHaveBeenCalledTimes(2);
    expect(onChange).toHaveBeenLastCalledWith('LFU_DECAY');

    await user.click(screen.getByRole('radio', { name: 'LFU' }));
    expect(onChange).toHaveBeenCalledTimes(3);
    expect(onChange).toHaveBeenLastCalledWith('LFU');

    // Re-selecting the checked option is a no-op.
    await user.click(screen.getByRole('radio', { name: 'LRU' }));
    expect(onChange).toHaveBeenCalledTimes(3);
  });

  it('blocks input when disabled', async () => {
    const user = userEvent.setup();
    const onChange = vi.fn();
    render(
      <DipSwitch
        label="Eviction policy"
        options={OPTIONS}
        value="LRU"
        onChange={onChange}
        disabled
      />,
    );

    expect(screen.getByRole('radiogroup')).toHaveAttribute('aria-disabled', 'true');
    for (const radio of screen.getAllByRole('radio')) expect(radio).toBeDisabled();

    await user.click(screen.getByRole('radio', { name: 'LFU' }));
    await user.tab();
    await user.keyboard('{ArrowRight} ');
    expect(onChange).not.toHaveBeenCalled();
  });
});
