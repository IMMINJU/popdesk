// screens.md 5절 13 DataTable, 열 고정. 고정 칸의 left는 앞 고정 칸들의 너비를 더한 값이라, 그 칸들이 그 너비보다 좁아지면 옆 칸을 덮는다.
// jsdom은 배치를 하지 않으므로 인라인 스타일만 본다.
import { render, screen, within } from '@testing-library/react';
import { describe, expect, test } from 'vitest';
import { found, must } from '../__tests__/support';
import { DataTable, type DataTableColumn } from './DataTable';

interface Row {
  key: string;
  title: string;
  company: string;
}

const rows: Row[] = [{ key: 'ACME-7', title: '로그인이 안 됩니다', company: '에이크미' }];

const cells = {
  key: (r: Row) => r.key,
  title: (r: Row) => r.title,
  company: (r: Row) => r.company,
};

/** 머리 칸과 첫 본문 줄의 칸을 열 순서대로. */
async function columnCells(index: number): Promise<{ head: HTMLElement; body: HTMLElement }> {
  const t = await found(() => screen.queryByRole('table', { name: /문의 목록/ }), '표 "문의 목록"');
  const head = must(within(t).getAllByRole('columnheader')[index], `머리 칸 ${String(index)}`);
  const bodyRow = must(within(t).getAllByRole('row')[1], '첫 본문 줄');
  const body = must(within(bodyRow).getAllByRole('cell')[index], `본문 칸 ${String(index)}`);
  return { head, body };
}

describe('DataTable 열 고정', () => {
  test.each([
    { what: 'width를 준 고정 칸', width: '6.5rem', min: '6.5rem' },
    { what: 'width를 주지 않은 고정 칸', width: undefined, min: '8rem' },
  ])('DataTable 열 고정($what): 첫 고정 칸의 머리 칸과 본문 칸의 min-width가 $min, 둘째 고정 칸의 left가 그 너비다', async ({ width, min }) => {
    const columns: DataTableColumn<Row>[] = [
      { key: 'key', header: '키', cell: cells.key, sticky: true, ...(width === undefined ? {} : { width }) },
      { key: 'title', header: '제목', cell: cells.title, sticky: true },
      { key: 'company', header: '고객사', cell: cells.company },
    ];
    render(<DataTable caption="문의 목록, 1건" columns={columns} rows={rows} rowKey={(r) => r.key} />);

    const first = await columnCells(0);
    expect(first.head.style.minWidth, '첫 고정 칸의 머리 칸').toBe(min);
    expect(first.body.style.minWidth, '첫 고정 칸의 본문 칸').toBe(min);
    expect(first.head.style.left, '첫 고정 칸의 left').toBe('0px');

    const second = await columnCells(1);
    expect(second.head.style.left, '둘째 고정 칸의 머리 칸').toBe(`calc(${min})`);
    expect(second.body.style.left, '둘째 고정 칸의 본문 칸').toBe(`calc(${min})`);
  });

  test('DataTable 열 고정: 고정이 아닌 칸에는 min-width와 left가 없다. width를 주어도 그렇다', async () => {
    const columns: DataTableColumn<Row>[] = [
      { key: 'key', header: '키', cell: cells.key, sticky: true, width: '6.5rem' },
      { key: 'title', header: '제목', cell: cells.title, width: '12rem' },
      { key: 'company', header: '고객사', cell: cells.company },
    ];
    render(<DataTable caption="문의 목록, 1건" columns={columns} rows={rows} rowKey={(r) => r.key} />);

    for (const index of [1, 2]) {
      const { head, body } = await columnCells(index);
      expect(head.style.minWidth, `머리 칸 ${String(index)}`).toBe('');
      expect(body.style.minWidth, `본문 칸 ${String(index)}`).toBe('');
      expect(head.style.left, `머리 칸 ${String(index)}의 left`).toBe('');
    }
    expect((await columnCells(1)).head.style.width).toBe('12rem');
  });

  test('DataTable 열 고정(불러오는 중): 뼈대 줄의 고정 칸도 min-width가 그 너비다', async () => {
    const columns: DataTableColumn<Row>[] = [
      { key: 'key', header: '키', cell: cells.key, sticky: true, width: '6.5rem' },
      { key: 'title', header: '제목', cell: cells.title, sticky: true },
      { key: 'company', header: '고객사', cell: cells.company },
    ];
    render(<DataTable caption="문의 목록" columns={columns} rows={[]} rowKey={(r) => r.key} status="loading" skeletonRows={2} />);

    const t = await found(() => screen.queryByRole('table', { name: /문의 목록/ }), '표 "문의 목록"');
    const skeleton = Array.from(must(t.querySelector('tbody'), '<tbody>').querySelectorAll<HTMLElement>('tr:first-child > td'));
    expect(skeleton).toHaveLength(3);
    expect(skeleton[0]?.style.minWidth, '뼈대 줄의 첫 고정 칸').toBe('6.5rem');
    expect(skeleton[1]?.style.left, '뼈대 줄의 둘째 고정 칸').toBe('calc(6.5rem)');
    expect(skeleton[2]?.style.minWidth, '뼈대 줄의 고정이 아닌 칸').toBe('');
  });
});
