import { Palette, Play, Zap, ZapOff } from 'lucide-react';
import { useMetrics } from '../api/metricsContext';
import { ConnectionLed, useToast } from '../components';
import type { Preferences } from './preferences';

const buttonClass =
  'inline-flex items-center gap-2 rounded-md border border-trace bg-surface-2 px-3 py-2 text-sm text-text hover:border-trace-glow disabled:opacity-50';

/** Wordmark, stream LED, guided demo, theme switch and animation toggle (SPEC 10.5). */
export function TopBar({ preferences }: { preferences: Preferences }) {
  const { state } = useMetrics();
  const toast = useToast();
  const { theme, setTheme, animated, setAnimated } = preferences;
  const nextTheme = theme === 'pcb-blue' ? 'pcb-amber' : 'pcb-blue';

  return (
    <header className="sticky top-0 z-20 flex flex-wrap items-center gap-4 border-b border-trace bg-surface/95 px-6 py-3 backdrop-blur">
      <p className="font-mono text-lg font-semibold tracking-[0.3em] text-text uppercase">
        <span aria-hidden="true" className="mr-2 text-trace-glow">
          ▣
        </span>
        CacheLab
      </p>
      <ConnectionLed state={state} />
      <div className="ml-auto flex flex-wrap items-center gap-2">
        <button
          type="button"
          className={buttonClass}
          onClick={() => toast.show('The guided demo arrives in Step 4.', 'info')}
        >
          <Play aria-hidden="true" className="size-4" />
          Start guided demo
        </button>
        <button
          type="button"
          className={buttonClass}
          aria-label={`Theme: ${theme === 'pcb-blue' ? 'blue' : 'amber'}. Switch to ${nextTheme === 'pcb-blue' ? 'blue' : 'amber'}`}
          onClick={() => setTheme(nextTheme)}
        >
          <Palette aria-hidden="true" className="size-4" />
          {theme === 'pcb-blue' ? 'Blue' : 'Amber'}
        </button>
        <button
          type="button"
          role="switch"
          aria-checked={animated}
          className={buttonClass}
          onClick={() => setAnimated(!animated)}
        >
          {animated ? (
            <Zap aria-hidden="true" className="size-4 text-electron" />
          ) : (
            <ZapOff aria-hidden="true" className="size-4" />
          )}
          Circuit animation
        </button>
      </div>
    </header>
  );
}
