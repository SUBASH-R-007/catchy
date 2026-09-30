import { FileUp } from 'lucide-react';
import { useId, useRef, useState, type DragEvent } from 'react';
import { cx } from '../../components/cx';
import { buttonClass } from '../playground/ui';

export interface TraceDropZoneProps {
  /** Called with the dropped or chosen file. */
  onFile: (file: File) => void;
  disabled?: boolean;
}

function hasFiles(event: DragEvent): boolean {
  return Array.from(event.dataTransfer?.types ?? []).includes('Files');
}

/**
 * A CSV drop target that also works without a mouse: the "Choose CSV file" button opens a normal
 * file input, which is what keyboard and screen-reader users (and tests) use.
 */
export function TraceDropZone({ onFile, disabled = false }: TraceDropZoneProps) {
  const inputRef = useRef<HTMLInputElement>(null);
  const inputId = useId();
  const hintId = useId();
  const [dragging, setDragging] = useState(false);

  const onDragOver = (event: DragEvent<HTMLDivElement>) => {
    if (disabled || !hasFiles(event)) return;
    event.preventDefault();
    event.dataTransfer.dropEffect = 'copy';
    setDragging(true);
  };

  const onDrop = (event: DragEvent<HTMLDivElement>) => {
    event.preventDefault();
    setDragging(false);
    if (disabled) return;
    const file = event.dataTransfer.files[0];
    if (file) onFile(file);
  };

  return (
    <div
      data-testid="trace-drop-zone"
      onDragEnter={onDragOver}
      onDragOver={onDragOver}
      onDragLeave={() => setDragging(false)}
      onDrop={onDrop}
      className={cx(
        'flex flex-col items-center justify-center gap-2 rounded border-2 border-dashed px-6 py-8 text-center transition-colors',
        dragging ? 'border-trace-glow bg-trace/20' : 'border-trace',
        disabled && 'opacity-60',
      )}
    >
      <FileUp aria-hidden="true" size={24} className="text-muted" />
      <p className="font-heading text-base text-text">
        {dragging ? 'Drop the CSV to upload it' : 'Drag a CSV trace here'}
      </p>
      <p id={hintId} className="max-w-[56ch] text-sm text-muted">
        One key per line, or <code className="font-mono">timestamp,key</code> with an optional
        header. Up to 1,000,000 rows and 20 MB.
      </p>
      <label htmlFor={inputId} className="sr-only">
        CSV trace file
      </label>
      <input
        ref={inputRef}
        id={inputId}
        type="file"
        accept=".csv,.txt,text/csv,text/plain"
        className="sr-only"
        tabIndex={-1}
        aria-describedby={hintId}
        disabled={disabled}
        onChange={(event) => {
          const file = event.target.files?.[0];
          // Reset so choosing the same file again still fires a change.
          event.target.value = '';
          if (file) onFile(file);
        }}
      />
      <button
        type="button"
        className={cx(buttonClass, 'mt-2')}
        disabled={disabled}
        aria-describedby={hintId}
        onClick={() => inputRef.current?.click()}
      >
        Choose CSV file…
      </button>
    </div>
  );
}
