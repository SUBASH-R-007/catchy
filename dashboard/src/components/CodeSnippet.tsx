import { Check, Copy } from 'lucide-react';
import { useEffect, useId, useState } from 'react';
import { cx } from './cx';
import { useToast } from './useToast';

export interface CodeSnippetProps {
  code: string;
  /** Short name of the snippet, e.g. "Gradle dependency". */
  label: string;
  /** Shown in the header, e.g. "kotlin" or "properties". */
  language?: string;
  className?: string;
}

/** How long the copy button shows a check mark after a successful copy. */
const COPIED_ICON_MS = 2000;

/** A monospace code block with a copy button; must be rendered inside <ToastProvider>. */
export function CodeSnippet({ code, label, language, className }: CodeSnippetProps) {
  const toast = useToast();
  const labelId = useId();
  const [copied, setCopied] = useState(false);

  useEffect(() => {
    if (!copied) return;
    const timer = window.setTimeout(() => setCopied(false), COPIED_ICON_MS);
    return () => window.clearTimeout(timer);
  }, [copied]);

  const copy = async () => {
    try {
      if (typeof navigator === 'undefined' || !navigator.clipboard) {
        throw new Error('Clipboard API unavailable');
      }
      await navigator.clipboard.writeText(code);
      setCopied(true);
      toast.show('Copied', 'success');
    } catch {
      toast.show(`Could not copy ${label}. Select the text and copy it manually.`, 'error');
    }
  };

  return (
    <figure
      aria-labelledby={labelId}
      className={cx('min-w-0 rounded border border-trace bg-surface-2', className)}
    >
      <div className="flex items-center justify-between gap-4 border-b border-trace py-1 pl-4 pr-1">
        <div className="flex min-w-0 items-center gap-2 font-mono text-sm">
          <span id={labelId} className="truncate text-text">
            {label}
          </span>
          {language ? (
            <span className="shrink-0 uppercase tracking-widest text-muted">{language}</span>
          ) : null}
        </div>
        <button
          type="button"
          aria-label={`Copy ${label}`}
          onClick={() => void copy()}
          className={cx(
            'inline-flex size-8 shrink-0 items-center justify-center rounded transition-colors hover:bg-surface',
            copied ? 'text-good' : 'text-muted hover:text-trace-glow',
          )}
        >
          {copied ? (
            <Check aria-hidden="true" size={16} strokeWidth={2.5} />
          ) : (
            <Copy aria-hidden="true" size={16} />
          )}
        </button>
      </div>
      {/* Keyboard users need focus to scroll long lines (WCAG 2.1.1), hence tabIndex on <pre>. */}
      {/* eslint-disable-next-line jsx-a11y/no-noninteractive-tabindex */}
      <pre tabIndex={0} className="overflow-x-auto p-4 font-mono text-sm text-text">
        <code>{code}</code>
      </pre>
    </figure>
  );
}
