import { useState, type ReactNode } from 'react';
import { Icon } from '../components/Icon';

export interface LegendItem {
  label: string;
  /** CSS color (usually `var(--series-hit)`). */
  color: string;
  kind?: 'line' | 'swatch';
  /** Optional current value shown after the label (text stays in ink colors, never the series color). */
  value?: string;
}

/** Legend: a colored key beside ink-colored text, so identity never depends on text color. */
export function Legend({ items }: { items: LegendItem[] }) {
  return (
    <ul className="legend">
      {items.map((item) => (
        <li key={item.label} className="legend__item">
          <span
            className={`legend__key legend__key--${item.kind ?? 'swatch'}`}
            style={{ background: item.color }}
            aria-hidden="true"
          />
          <span className="legend__label">{item.label}</span>
          {item.value ? <span className="legend__value num">{item.value}</span> : null}
        </li>
      ))}
    </ul>
  );
}

export interface ChartTableData {
  headers: string[];
  rows: Array<Array<string | number>>;
}

interface ChartFrameProps {
  title: string;
  subtitle?: ReactNode;
  /** Plain-language description of what the chart shows (read by screen readers). */
  summary?: string;
  legend?: LegendItem[];
  /** Data behind the chart; enables the "Table view" toggle (the WCAG-clean twin of the chart). */
  table?: ChartTableData;
  /** Extra controls (range / series selectors) rendered in the header. */
  controls?: ReactNode;
  /** Dim the chart while a refetch is in progress instead of flashing a skeleton. */
  dimmed?: boolean;
  className?: string;
  children: ReactNode;
}

export function ChartFrame({ title, subtitle, summary, legend, table, controls, dimmed, className, children }: ChartFrameProps) {
  const [showTable, setShowTable] = useState(false);
  return (
    <figure className={`chart-frame${className ? ` ${className}` : ''}${dimmed ? ' is-dimmed' : ''}`}>
      <figcaption className="chart-frame__head">
        <div className="chart-frame__titles">
          <h3 className="chart-frame__title">{title}</h3>
          {subtitle ? <p className="chart-frame__sub">{subtitle}</p> : null}
        </div>
        <div className="chart-frame__tools">
          {controls}
          {table ? (
            <button
              type="button"
              className="btn btn--ghost btn--sm"
              aria-pressed={showTable}
              onClick={() => setShowTable((v) => !v)}
            >
              <Icon name="layers" size={14} />
              <span>{showTable ? 'Chart view' : 'Table view'}</span>
            </button>
          ) : null}
        </div>
      </figcaption>
      {summary ? <p className="sr-only">{summary}</p> : null}
      {showTable && table ? (
        <div className="table-wrap chart-frame__table" role="region" aria-label={`${title} data`} tabIndex={0}>
          <table className="table table--compact">
            <caption className="sr-only">{title} data</caption>
            <thead>
              <tr>
                {table.headers.map((h, i) => (
                  <th key={h} scope="col" className={i === 0 ? undefined : 'align-right'}>
                    {h}
                  </th>
                ))}
              </tr>
            </thead>
            <tbody>
              {table.rows.map((row, r) => (
                <tr key={r}>
                  {row.map((cell, c) => (
                    <td key={c} className={c === 0 ? undefined : 'align-right num'}>
                      {cell}
                    </td>
                  ))}
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      ) : (
        children
      )}
      {legend && legend.length > 0 && !showTable ? <Legend items={legend} /> : null}
    </figure>
  );
}
