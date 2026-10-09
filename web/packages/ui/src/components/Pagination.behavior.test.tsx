// screens.md 5절 15 Pagination. C-10 변이 테스트가 찾은 빈칸: 보이는 쪽과 "…" 자리, 이전·다음 링크, 쪽 이름.
import { render, screen, within } from '@testing-library/react';
import { describe, expect, test } from 'vitest';
import { text } from '../__tests__/support';
import { Pagination, type PaginationProps } from './Pagination';

const href = (p: number) => `/issues?page=${String(p)}`;

function pager(over: Partial<PaginationProps> & Pick<PaginationProps, 'page' | 'pageCount'>) {
  return <Pagination getHref={href} label="쪽 넘기기" {...over} />;
}

/** What the bar shows, in order: page numbers and "…". */
function shown(): string[] {
  const nav = screen.getByRole('navigation', { name: '쪽 넘기기' });
  return Array.from(nav.querySelectorAll('li')).map((li) => text(li));
}

describe('Pagination 동작', () => {
  test.each([
    [1, 2, ['1', '2']],
    [1, 10, ['1', '2', '3', '…', '10']],
    [5, 10, ['1', '2', '3', '4', '5', '6', '7', '…', '10']],
    [6, 12, ['1', '…', '4', '5', '6', '7', '8', '…', '12']],
    [10, 10, ['1', '…', '8', '9', '10']],
    [4, 7, ['1', '2', '3', '4', '5', '6', '7']],
  ])('Pagination 보이는 쪽: %i/%i쪽이면 처음·끝과 앞뒤 두 쪽, 한 쪽만 비면 그 쪽을, 더 비면 "…"', (page, pageCount, want) => {
    render(pager({ page, pageCount }));

    expect(shown()).toEqual(want);
  });

  test('Pagination "…": 읽지 않는다(aria-hidden)', () => {
    render(pager({ page: 6, pageCount: 12 }));

    const nav = screen.getByRole('navigation', { name: '쪽 넘기기' });
    const gaps = Array.from(nav.querySelectorAll('li')).filter((li) => text(li) === '…');
    expect(gaps).toHaveLength(2);
    expect(gaps.every((li) => li.getAttribute('aria-hidden') === 'true')).toBe(true);
  });

  test('Pagination 이전·다음: 이름과 주소가 앞뒤 쪽이고, 처음 쪽에는 이전이, 끝 쪽에는 다음이 없다', () => {
    const { rerender } = render(pager({ page: 3, pageCount: 5, previousLabel: '이전 쪽', nextLabel: '다음 쪽' }));
    expect(screen.getByRole('link', { name: '이전 쪽' })).toHaveAttribute('href', '/issues?page=2');
    expect(screen.getByRole('link', { name: '다음 쪽' })).toHaveAttribute('href', '/issues?page=4');

    rerender(pager({ page: 1, pageCount: 5, previousLabel: '이전 쪽', nextLabel: '다음 쪽' }));
    expect(screen.queryByRole('link', { name: '이전 쪽' })).toBeNull();
    expect(screen.getByRole('link', { name: '다음 쪽' })).toHaveAttribute('href', '/issues?page=2');

    rerender(pager({ page: 5, pageCount: 5, previousLabel: '이전 쪽', nextLabel: '다음 쪽' }));
    expect(screen.getByRole('link', { name: '이전 쪽' })).toHaveAttribute('href', '/issues?page=4');
    expect(screen.queryByRole('link', { name: '다음 쪽' })).toBeNull();
  });

  test('Pagination 이전·다음 이름 없음: previousLabel·nextLabel을 안 주면 그 링크를 빼고 쪽 링크만 둔다', () => {
    render(pager({ page: 3, pageCount: 5 }));

    const links = within(screen.getByRole('navigation', { name: '쪽 넘기기' })).getAllByRole('link');
    expect(links.map((a) => text(a))).toEqual(['1', '2', '3', '4', '5']);
  });

  test('Pagination 쪽 이름: pageLabel을 주면 쪽 링크의 이름이 그것이다', () => {
    render(pager({ page: 2, pageCount: 3, pageLabel: (p) => `${String(p)}쪽` }));

    expect(screen.getByRole('link', { name: '3쪽' })).toHaveAttribute('href', '/issues?page=3');
    expect(screen.getByRole('link', { name: '2쪽' })).toHaveAttribute('aria-current', 'page');
  });
});
