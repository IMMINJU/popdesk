// 1b design.md 5절 DataTable 줄 펼치기: `renderExpanded`와 `expandLabel`을 주면 줄마다 그 이름의 버튼이 있고,
// 누르면 펼친 내용이 그 줄 아래에 나온다. 펼친 상태는 표가 안에서 쥔다.
import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, test } from 'vitest';
import { expectNoAxeViolations, found, text } from '../__tests__/support';
import { DataTable, type DataTableColumn } from './DataTable';

interface Row {
  id: number;
  action: string;
  hash: string;
}

const rows: Row[] = [
  { id: 12, action: '계정 정지', hash: 'a1'.repeat(32) },
  { id: 11, action: '로그인', hash: 'b2'.repeat(32) },
];

const columns: DataTableColumn<Row>[] = [
  { key: 'action', header: '일', cell: (r) => r.action },
  { key: 'id', header: '번호', cell: (r) => String(r.id) },
];

function Expandable() {
  return (
    <DataTable
      caption="감사 로그, 2건"
      columns={columns}
      rows={rows}
      rowKey={(r) => String(r.id)}
      renderExpanded={(r) => <p>해시 {r.hash}</p>}
      expandLabel={(r) => `항목 ${String(r.id)} 자세히`}
    />
  );
}

const table = () => found(() => screen.queryByRole('table', { name: /감사 로그/ }), '표 "감사 로그"');
const opener = (id: number) => found(() => screen.queryByRole('button', { name: `항목 ${String(id)} 자세히` }), `"항목 ${String(id)} 자세히" 버튼`);

/** The index, among the table's rows, of the row whose text holds `wanted`; -1 when there is none. */
function rowIndex(t: HTMLElement, wanted: string): number {
  return within(t)
    .queryAllByRole('row')
    .findIndex((r) => text(r).includes(wanted));
}

describe('DataTable 줄 펼치기', () => {
  test('DataTable 줄 펼치기: 줄마다 expandLabel 이름의 버튼(aria-expanded), 누르면 그 줄 아래에 펼친 내용, 다시 누르면 접힌다', async () => {
    render(<Expandable />);
    const user = userEvent.setup();
    const t = await table();

    const first = await opener(12);
    const second = await opener(11);
    expect(first).toHaveAttribute('aria-expanded', 'false');
    expect(second).toHaveAttribute('aria-expanded', 'false');
    expect(first.closest('tr'), '버튼은 그 줄 안에').toBe(within(t).getAllByRole('row')[rowIndex(t, '계정 정지')]);
    expect(text(t), '평소에는 접혀 있다').not.toContain('해시');

    await user.click(first);
    expect(first).toHaveAttribute('aria-expanded', 'true');
    expect(second).toHaveAttribute('aria-expanded', 'false');
    expect(text(t)).toContain(`해시 ${'a1'.repeat(32)}`);
    expect(text(t), '누르지 않은 줄').not.toContain('b2'.repeat(32));
    expect(rowIndex(t, 'a1'.repeat(32)), '펼친 내용은 누른 줄 바로 아래').toBe(rowIndex(t, '계정 정지') + 1);
    expect(rowIndex(t, '로그인'), '다음 줄은 그 아래').toBe(rowIndex(t, '계정 정지') + 2);

    await user.click(first);
    expect(first).toHaveAttribute('aria-expanded', 'false');
    expect(text(t)).not.toContain('해시');
  });

  test('DataTable 줄 펼치기 없음: renderExpanded를 주지 않으면 버튼이 없다', async () => {
    render(<DataTable caption="감사 로그, 2건" columns={columns} rows={rows} rowKey={(r) => String(r.id)} />);

    const t = await table();
    expect(within(t).queryAllByRole('button')).toHaveLength(0);
    expect(within(t).getAllByRole('row')).toHaveLength(3);
  });

  test('DataTable 줄 펼치기 axe: 한 줄을 펼친 표에 접근성 위반이 없다', async () => {
    render(<Expandable />);
    const user = userEvent.setup();
    await user.click(await opener(12));
    expect(await opener(12)).toHaveAttribute('aria-expanded', 'true');

    await expectNoAxeViolations();
  });
});
