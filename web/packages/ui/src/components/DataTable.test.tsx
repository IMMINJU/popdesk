// screens.md 5절 13 DataTable. 불러오는 중(뼈대 줄), 빈 상태 칸, 데이터, 오류. C-3 표는 <table>과 <caption>.
import { render, screen, within } from '@testing-library/react';
import { describe, expect, test } from 'vitest';
import { expectNoAxeViolations, found, must, spokenText, text } from '../__tests__/support';
import { DataTable, type DataTableColumn } from './DataTable';

interface Row {
  key: string;
  title: string;
  company: string;
}

const rows: Row[] = [
  { key: 'ACME-7', title: '로그인이 안 됩니다', company: '에이크미' },
  { key: 'ACME-6', title: '프린터가 안 됩니다', company: '에이크미' },
];

const columns: DataTableColumn<Row>[] = [
  { key: 'key', header: '키', cell: (r) => r.key, sticky: true },
  { key: 'title', header: '제목', cell: (r) => <a href={`/issues/${r.key}`}>{r.title}</a>, sticky: true },
  { key: 'company', header: '고객사', cell: (r) => r.company },
];

const table = () => found(() => screen.queryByRole('table', { name: /문의 목록/ }), '표 "문의 목록"');

describe('DataTable', () => {
  test('DataTable 데이터: caption이 표 이름이고 열 머리는 columnheader, 줄마다 칸', async () => {
    render(<DataTable caption="문의 목록, 2건" columns={columns} rows={rows} rowKey={(r) => r.key} />);

    const t = await table();
    expect(within(t).getAllByRole('columnheader').map((th) => text(th))).toEqual(['키', '제목', '고객사']);
    const bodyRows = within(t).getAllByRole('row').slice(1);
    expect(bodyRows).toHaveLength(2);
    expect(text(bodyRows[0])).toContain('로그인이 안 됩니다');
    expect(within(t).getByRole('link', { name: '프린터가 안 됩니다' })).toHaveAttribute('href', '/issues/ACME-6');
  });

  test('DataTable 불러오는 중: aria-busy, 뼈대 줄은 읽지 않고(aria-hidden) 열 머리는 바로 보인다', async () => {
    render(<DataTable caption="문의 목록" columns={columns} rows={[]} rowKey={(r) => r.key} status="loading" skeletonRows={10} />);

    const t = await table();
    expect(t.closest('[aria-busy="true"]') ?? null, 'aria-busy="true"').not.toBeNull();
    expect(within(t).getAllByRole('columnheader')).toHaveLength(3);
    const tbody = must(t.querySelector('tbody'), '<tbody>');
    expect(tbody.querySelectorAll('[aria-hidden="true"]').length, '뼈대(aria-hidden)').toBeGreaterThanOrEqual(1);
    expect(spokenText(tbody)).toBe('');
  });

  test('DataTable 빈 상태: 줄이 없으면 empty를 표 안 한 칸에 보인다', async () => {
    render(
      <DataTable
        caption="문의 목록"
        columns={columns}
        rows={[]}
        rowKey={(r) => r.key}
        empty={<p>아직 문의가 없습니다.</p>}
      />,
    );

    const t = await table();
    expect(text(t)).toContain('아직 문의가 없습니다.');
    expect(t.closest('[aria-busy="true"]')).toBeNull();
  });

  test('DataTable 오류: status가 error면 줄 대신 error를 보인다', async () => {
    render(
      <DataTable
        caption="문의 목록"
        columns={columns}
        rows={rows}
        rowKey={(r) => r.key}
        status="error"
        error={<p>서버에 연결하지 못했습니다. 잠시 뒤 다시 하세요.</p>}
      />,
    );

    const t = await table();
    expect(text(t)).toContain('서버에 연결하지 못했습니다.');
    expect(text(t)).not.toContain('로그인이 안 됩니다');
  });

  test('DataTable axe: 데이터·불러오는 중·빈 상태에 접근성 위반이 없다', async () => {
    render(
      <div>
        <DataTable caption="문의 목록, 2건" columns={columns} rows={rows} rowKey={(r) => r.key} />
        <DataTable caption="고객사 목록" columns={columns} rows={[]} rowKey={(r) => r.key} status="loading" />
        <DataTable caption="구성원 목록" columns={columns} rows={[]} rowKey={(r) => r.key} empty={<p>없습니다.</p>} />
      </div>,
    );

    await expectNoAxeViolations();
  });
});
