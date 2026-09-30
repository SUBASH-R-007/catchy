import { Info } from 'lucide-react';
import {
  useEffect,
  useId,
  useRef,
  useState,
  type FocusEvent as ReactFocusEvent,
  type ReactNode,
} from 'react';
import { cx } from './cx';

export interface InfoPopoverProps {
  /** What the popover explains; the button is labelled "About {label}". */
  label: string;
  children: ReactNode;
  /** Which edge of the button the popover lines up with. Defaults to 'end' (right). */
  align?: 'start' | 'end';
}

const POPOVER_WIDTH = 280;
const VIEWPORT_MARGIN = 16;

/** Keep the preferred alignment unless the popover would spill off that side of the viewport. */
function choosePlacement(button: DOMRect, preferred: 'start' | 'end'): 'start' | 'end' {
  const viewportWidth = document.documentElement.clientWidth || window.innerWidth;
  const fitsEnd = button.right - POPOVER_WIDTH >= VIEWPORT_MARGIN;
  const fitsStart = button.left + POPOVER_WIDTH <= viewportWidth - VIEWPORT_MARGIN;
  if (preferred === 'end') return fitsEnd || !fitsStart ? 'end' : 'start';
  return fitsStart || !fitsEnd ? 'start' : 'end';
}

/**
 * A small info button that opens a non-modal explanation popover (role="dialog").
 * Focus moves into the popover; Esc closes it and returns focus to the button;
 * clicking outside (or tabbing away) closes it.
 */
export function InfoPopover({ label, children, align = 'end' }: InfoPopoverProps) {
  const [open, setOpen] = useState(false);
  const [placement, setPlacement] = useState(align);
  const popoverId = useId();
  const wrapperRef = useRef<HTMLSpanElement>(null);
  const buttonRef = useRef<HTMLButtonElement>(null);
  const popoverRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    if (!open) return;
    popoverRef.current?.focus();

    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key !== 'Escape') return;
      setOpen(false);
      buttonRef.current?.focus();
    };
    const onMouseDown = (event: MouseEvent) => {
      const wrapper = wrapperRef.current;
      if (wrapper && event.target instanceof Node && !wrapper.contains(event.target)) {
        setOpen(false);
      }
    };
    document.addEventListener('keydown', onKeyDown);
    document.addEventListener('mousedown', onMouseDown);
    return () => {
      document.removeEventListener('keydown', onKeyDown);
      document.removeEventListener('mousedown', onMouseDown);
    };
  }, [open]);

  const toggle = () => {
    const rect = buttonRef.current?.getBoundingClientRect();
    if (!open && rect) setPlacement(choosePlacement(rect, align));
    setOpen((value) => !value);
  };

  const onBlur = (event: ReactFocusEvent<HTMLSpanElement>) => {
    const next = event.relatedTarget;
    // Only close when focus clearly moved somewhere else on the page.
    if (next instanceof Node && !event.currentTarget.contains(next)) setOpen(false);
  };

  return (
    <span ref={wrapperRef} className="relative inline-flex" onBlur={onBlur}>
      <button
        ref={buttonRef}
        type="button"
        aria-label={`About ${label}`}
        aria-expanded={open}
        aria-controls={popoverId}
        onClick={toggle}
        className={cx(
          'inline-flex size-6 items-center justify-center rounded-full text-muted',
          'transition-colors hover:bg-surface-2 hover:text-trace-glow',
          open && 'bg-surface-2 text-trace-glow',
        )}
      >
        <Info aria-hidden="true" size={16} strokeWidth={2} />
      </button>
      <div
        ref={popoverRef}
        id={popoverId}
        role="dialog"
        aria-label={label}
        tabIndex={-1}
        hidden={!open}
        className={cx(
          'absolute top-full z-50 mt-2 w-[280px] max-w-[calc(100vw-32px)]',
          'rounded border border-trace bg-surface-2 p-4 text-left shadow-lg shadow-black/40',
          placement === 'end' ? 'right-0' : 'left-0',
        )}
      >
        <p className="mb-2 font-mono text-sm uppercase tracking-widest text-muted">{label}</p>
        <div className="font-sans text-sm text-text">{children}</div>
      </div>
    </span>
  );
}
