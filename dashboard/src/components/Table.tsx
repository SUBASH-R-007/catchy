import type { ReactNode } from 'react';
import { Icon } from './Icon';

export interface Column<T> {
  key: string;
  header: ReactNode;
  cell: (row: T) => ReactNode;
  align?: 'left' | 'right' | 'center';
  /** When set the header becomes a keyboard-operable sort button that calls `onSort(sortKey)`. */
  sortKey?: string;
  className?: string;
}

export interface SortState {
  key: string;
  direction: 'asc' | 'desc';
}

interface TableProps<T> {
  columns: Column<T>[];
  rows: readonly T[];
  rowKey: (row: T) => string | number;
  /** Accessible name of the table (also labels the scroll container). */
  caption: string;
  sort?: SortState | null;
  onSort?: (sortKey: string) => void;
  empty?: ReactNode;
  rowClassName?: (row: T) => string | undefined;
  className?: string;
}

/**
 * Shared data table. The wrapper scrolls horizontally on narrow screens (the page never does)
 * and is keyboard-focusable so the overflow is reachable without a mouse.
 */
export function Table<T>({ columns, rows, rowKey, caption, sort, onSort, empty, rowClassName, className }: TableProps<T>) {
  return (
    <div className={`table-wrap${className ? ` ${className}` : ''}`} role="region" aria-label={caption} tabIndex={0}>
      <table className="table">
        <caption className="sr-only">{caption}</caption>
        <thead>
          <tr>
            {columns.map((col) => {
              const active = col.sortKey !== undefined && sort?.key === col.sortKey;
              return (
                <th
                  key={col.key}
                  scope="col"
                  className={`${col.align ? `align-${col.align}` : ''} ${col.className ?? ''}`.trim() || undefined}
                  aria-sort={active ? (sort?.direction === 'asc' ? 'ascending' : 'descending') : col.sortKey ? 'none' : undefined}
                >
                  {col.sortKey && onSort ? (
                    <button type="button" className={`th-sort${active ? ' is-active' : ''}`} onClick={() => onSort(col.sortKey as string)}>
                      <span>{col.header}</span>
                      <Icon
                        name={active && sort?.direction === 'asc' ? 'arrow-up' : 'arrow-down'}
                        size={12}
                        className="th-sort__icon"
                      />
                    </button>
                  ) : (
                    col.header
                  )}
                </th>
              );
            })}
          </tr>
        </thead>
        <tbody>
          {rows.length === 0 ? (
            <tr>
              <td colSpan={columns.length} className="table__empty">
                {empty ?? 'Nothing to show.'}
              </td>
            </tr>
          ) : (
            rows.map((row) => (
              <tr key={rowKey(row)} className={rowClassName?.(row)}>
                {columns.map((col) => (
                  <td key={col.key} className={`${col.align ? `align-${col.align}` : ''} ${col.className ?? ''}`.trim() || undefined}>
                    {col.cell(row)}
                  </td>
                ))}
              </tr>
            ))
          )}
        </tbody>
      </table>
    </div>
  );
}
