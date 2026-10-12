import { useState, type ReactNode } from 'react';
import { ChevronDownIcon } from '../icons';

export interface DataTableColumn<Row> {
  key: string;
  header: ReactNode;
  cell: (row: Row) => ReactNode;
  /** 열 고정 (왼쪽) while the table scrolls sideways. */
  sticky?: boolean;
  /** Default `start`. Numbers and times may use `end`. */
  align?: 'start' | 'end';
  /** CSS width, e.g. `8rem`. */
  width?: string;
}

export interface DataTableProps<Row> {
  /** `<caption>`, e.g. "문의 목록, 24건". */
  caption: string;
  /** Default true: the caption is for screen readers only. */
  captionHidden?: boolean;
  columns: readonly DataTableColumn<Row>[];
  rows: readonly Row[];
  rowKey: (row: Row) => string;
  /** 불러오는 중 (skeleton rows), 데이터, 오류. Default `ready`. With no rows, `ready` shows `empty`. */
  status?: 'loading' | 'ready' | 'error';
  /**
   * Skeleton rows while loading. Default 5. A screen passes 0 for the first
   * 300ms so a fast answer shows no skeleton (screens.md 2절).
   */
  skeletonRows?: number;
  /** 빈 상태 칸, usually an EmptyState. */
  empty?: ReactNode;
  /** Shown in place of rows when `status` is `error`. */
  error?: ReactNode;
  /** 촘촘 (console) or 여유 (portal). Default follows the app density. */
  density?: 'compact' | 'comfortable';
  /**
   * 줄 펼치기: what a row shows under itself when opened, e.g. the hashes of an
   * audit entry. Each row then starts with a button, and the opened content is
   * the next `tr`. The table keeps which rows are open; a screen folds them all
   * by giving the table a new `key`. Without it the table draws as before.
   */
  renderExpanded?: (row: Row) => ReactNode;
  /** Name of a row's 펼치기 button, e.g. "항목 12 자세히". Used with `renderExpanded`. */
  expandLabel?: (row: Row) => string;
}

/**
 * `<table>` with a caption. Column heads show at once, also while loading.
 * Rows have no lines between them; one line sits under the head (tokens.md 9절).
 */
export function DataTable<Row>(props: DataTableProps<Row>) {
  const status = props.status ?? 'ready';
  const { renderExpanded } = props;
  const [opened, setOpened] = useState<ReadonlySet<string>>(new Set());
  const span = props.columns.length + (renderExpanded ? 1 : 0);
  const toggle = (key: string) => {
    setOpened((current) => {
      const next = new Set(current);
      if (!next.delete(key)) next.add(key);
      return next;
    });
  };
  // Sticky columns sit at the sum of the widths before them.
  const offsets: (string | undefined)[] = [];
  let left: string[] = [];
  for (const c of props.columns) {
    offsets.push(c.sticky ? (left.length === 0 ? '0px' : `calc(${left.join(' + ')})`) : undefined);
    if (c.sticky) left = [...left, c.width ?? '8rem'];
  }
  const cellProps = (c: DataTableColumn<Row>, i: number) => ({
    'data-align': c.align ?? 'start',
    'data-sticky': c.sticky ? 'true' : undefined,
    // A sticky column keeps the width the offsets count on, also when the table is squeezed.
    style: { width: c.width, minWidth: c.sticky ? (c.width ?? '8rem') : undefined, left: offsets[i] },
  });
  return (
    <div className="pd-table-wrap" data-pd="DataTable" data-density={props.density}>
      <table className="pd-table" aria-busy={status === 'loading' ? true : undefined}>
        <caption className={props.captionHidden === false ? 'pd-table-caption' : 'pd-sr-only'}>{props.caption}</caption>
        <thead>
          <tr>
            {renderExpanded && <td className="pd-table-expand-cell" />}
            {props.columns.map((c, i) => (
              <th key={c.key} scope="col" {...cellProps(c, i)}>
                {c.header}
              </th>
            ))}
          </tr>
        </thead>
        <tbody>
          {status === 'loading' &&
            Array.from({ length: props.skeletonRows ?? 5 }, (_, r) => (
              <tr key={r} className="pd-table-skeleton" aria-hidden="true">
                {renderExpanded && <td className="pd-table-expand-cell" />}
                {props.columns.map((c, i) => (
                  <td key={c.key} {...cellProps(c, i)}>
                    <span className="pd-skeleton-item pd-skeleton-pulse" style={{ width: `${String(40 + ((r * 7 + i * 13) % 50))}%` }} />
                  </td>
                ))}
              </tr>
            ))}
          {status === 'error' && (
            <tr>
              <td colSpan={span} className="pd-table-message">
                {props.error}
              </td>
            </tr>
          )}
          {status === 'ready' && props.rows.length === 0 && (
            <tr>
              <td colSpan={span} className="pd-table-message">
                {props.empty}
              </td>
            </tr>
          )}
          {status === 'ready' &&
            props.rows.map((row) => {
              const key = props.rowKey(row);
              const cells = props.columns.map((c, i) => (
                <td key={c.key} {...cellProps(c, i)}>
                  {c.cell(row)}
                </td>
              ));
              if (!renderExpanded) return <tr key={key}>{cells}</tr>;
              const open = opened.has(key);
              // A fragment needs the key; the two rows stay next to each other.
              return [
                <tr key={key}>
                  <td className="pd-table-expand-cell">
                    <button
                      type="button"
                      className="pd-table-expand"
                      aria-expanded={open}
                      aria-label={props.expandLabel?.(row)}
                      onClick={() => {
                        toggle(key);
                      }}
                    >
                      <span aria-hidden="true">
                        <ChevronDownIcon />
                      </span>
                    </button>
                  </td>
                  {cells}
                </tr>,
                open && (
                  <tr key={`${key}-expanded`} className="pd-table-expanded">
                    <td colSpan={span}>{renderExpanded(row)}</td>
                  </tr>
                ),
              ];
            })}
        </tbody>
      </table>
    </div>
  );
}
