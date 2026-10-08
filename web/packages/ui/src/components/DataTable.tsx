import type { ReactNode } from 'react';

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
  /** Skeleton rows while loading. Default 5. */
  skeletonRows?: number;
  /** 빈 상태 칸, usually an EmptyState. */
  empty?: ReactNode;
  /** Shown in place of rows when `status` is `error`. */
  error?: ReactNode;
  /** 촘촘 (console) or 여유 (portal). Default follows the app density. */
  density?: 'compact' | 'comfortable';
}

export function DataTable<Row>(props: DataTableProps<Row>) {
  const status = props.status ?? 'ready';
  const span = props.columns.length;
  return (
    <div data-pd="DataTable" data-density={props.density}>
      <table aria-busy={status === 'loading'}>
        <caption>{props.caption}</caption>
        <thead>
          <tr>
            {props.columns.map((c) => (
              <th key={c.key} scope="col">
                {c.header}
              </th>
            ))}
          </tr>
        </thead>
        <tbody>
          {status === 'loading' &&
            Array.from({ length: props.skeletonRows ?? 5 }, (_, i) => (
              <tr key={i}>
                <td colSpan={span} />
              </tr>
            ))}
          {status === 'error' && (
            <tr>
              <td colSpan={span}>{props.error}</td>
            </tr>
          )}
          {status === 'ready' && props.rows.length === 0 && (
            <tr>
              <td colSpan={span}>{props.empty}</td>
            </tr>
          )}
          {status === 'ready' &&
            props.rows.map((row) => (
              <tr key={props.rowKey(row)}>
                {props.columns.map((c) => (
                  <td key={c.key}>{c.cell(row)}</td>
                ))}
              </tr>
            ))}
        </tbody>
      </table>
    </div>
  );
}
