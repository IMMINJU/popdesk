// screens.md 5절 20 Skeleton, 14 DataTable, 16 IssueList. C-10 변이 테스트가 찾은 빈칸:
// 뼈대 개수, 빈 상태가 데이터와 겹치지 않음, 오류 칸 너비.
import { render, screen } from '@testing-library/react';
import { describe, expect, test } from 'vitest';
import { text } from '../__tests__/support';
import { DataTable } from './DataTable';
import { IssueList } from './IssueList';
import { Skeleton } from './Skeleton';

describe('Skeleton 동작', () => {
  test('Skeleton 개수: count만큼, 기본은 하나', () => {
    const { container, rerender } = render(<Skeleton shape="line" />);
    expect(container.firstElementChild?.children).toHaveLength(1);

    rerender(<Skeleton shape="line" count={5} width="60%" />);
    const items = Array.from(container.firstElementChild?.children ?? []) as HTMLElement[];
    expect(items).toHaveLength(5);
    expect(items.every((el) => el.style.width === '60%')).toBe(true);
  });

  test('IssueList 불러오는 중: 기본 뼈대 카드는 다섯 장이고 읽지 않는다', () => {
    const { container } = render(<IssueList items={[]} status="loading" />);

    const cards = container.querySelectorAll('li');
    expect(cards).toHaveLength(5);
    expect(Array.from(cards).every((li) => li.getAttribute('aria-hidden') === 'true')).toBe(true);
  });

  test('DataTable 불러오는 중: 기본 뼈대 줄은 다섯, skeletonRows 0이면 없다', () => {
    const columns = [{ key: 'k', header: '번호', cell: (r: { k: string }) => r.k }];
    const { container, rerender } = render(<DataTable caption="문의 목록" columns={columns} rows={[]} rowKey={(r) => r.k} status="loading" />);
    expect(container.querySelectorAll('tbody tr')).toHaveLength(5);

    rerender(<DataTable caption="문의 목록" columns={columns} rows={[]} rowKey={(r) => r.k} status="loading" skeletonRows={0} />);
    expect(container.querySelectorAll('tbody tr')).toHaveLength(0);
  });

  test('DataTable 데이터: 줄이 있으면 빈 상태 칸도 뼈대도 없다', () => {
    const columns = [{ key: 'k', header: '번호', cell: (r: { k: string }) => r.k }];
    const { container } = render(
      <DataTable caption="문의 목록" columns={columns} rows={[{ k: 'ACME-1' }]} rowKey={(r) => r.k} empty="문의가 없습니다" />,
    );

    expect(Array.from(container.querySelectorAll('tbody tr')).map((tr) => text(tr))).toEqual(['ACME-1']);
  });

  test('DataTable 열 고정: 고정 열은 앞 고정 열 너비의 합에 붙고(너비가 없으면 8rem), 고정이 아닌 열은 자리를 받지 않는다', () => {
    const columns = [
      { key: 'key', header: '번호', cell: (r: { k: string }) => r.k, sticky: true, width: '6rem' },
      { key: 'title', header: '제목', cell: (r: { k: string }) => r.k, sticky: true },
      { key: 'who', header: '올린 사람', cell: (r: { k: string }) => r.k, sticky: true },
      { key: 'at', header: '시각', cell: (r: { k: string }) => r.k, width: '5rem' },
    ];
    render(<DataTable caption="문의 목록" columns={columns} rows={[{ k: 'ACME-1' }]} rowKey={(r) => r.k} />);

    const lefts = screen.getAllByRole('columnheader').map((th) => th.style.left);
    expect(lefts).toEqual(['0px', 'calc(6rem)', 'calc(14rem)', '']);
    expect(screen.getAllByRole('cell').map((td) => td.style.left)).toEqual(lefts);
    expect(screen.getByRole('columnheader', { name: '시각' }).style.width).toBe('5rem');
  });

  test('DataTable 오류: 오류 칸은 모든 열을 덮는다', () => {
    const columns = ['a', 'b', 'c'].map((k) => ({ key: k, header: k, cell: () => k }));
    render(<DataTable caption="문의 목록" columns={columns} rows={[]} rowKey={() => 'x'} status="error" error="불러오지 못했습니다" />);

    expect(screen.getByRole('cell', { name: '불러오지 못했습니다' })).toHaveAttribute('colspan', '3');
  });
});
