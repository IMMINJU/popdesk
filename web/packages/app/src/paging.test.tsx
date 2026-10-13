// COM-4, screens.md P-3 쪽 넘기기: 쪽은 주소의 `?page=`에 있고, 쪽을 옮기면 목록 제목에 포커스를 두고 한 번 알린다.
// 본문을 읽지 않고 시그니처, 주석, 앱 테스트(paging.behavior, messages.behavior)가 고정한 동작으로 썼다.
import { fireEvent, render, renderHook, screen } from '@testing-library/react';
import { useRef, type ReactNode } from 'react';
import { MemoryRouter } from 'react-router';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { PAGE_SIZE, pageHref, usePageMove, usePageParam, type PageAnnouncement, type PageInfo } from './paging';

function pageAt(url: string, name?: string): number {
  const { result } = renderHook(() => usePageParam(name), {
    wrapper: ({ children }: { children: ReactNode }) => <MemoryRouter initialEntries={[url]}>{children}</MemoryRouter>,
  });
  return result.current;
}

describe('usePageParam', () => {
  it('COM-4 한 쪽은 20건이다', () => {
    expect(PAGE_SIZE).toBe(20);
  });

  it.each([
    ['/issues', 1],
    ['/issues?status=OPEN', 1],
    ['/issues?page=1', 1],
    ['/issues?page=2', 2],
    ['/issues?page=37', 37],
    ['/issues?status=OPEN&page=4', 4],
    ['/issues?page=0', 1],
    ['/issues?page=-1', 1],
    ['/issues?page=-2', 1],
    ['/issues?page=1.5', 1],
    ['/issues?page=2.5', 1],
    ['/issues?page=abc', 1],
    ['/issues?page=2abc', 1],
    ['/issues?page=', 1],
    ['/issues?page=NaN', 1],
    ['/issues?page=Infinity', 1],
    ['/issues?page=-0', 1],
    ['/issues?page=0.5', 1],
    ['/issues?page=-1.5', 1],
    ['/issues?p=3', 1],
  ])('COM-4 usePageParam %s → %i쪽', (url, want) => {
    expect(pageAt(url)).toBe(want);
  });

  it.each([
    ['/audit?p=3', 'p', 3],
    ['/audit?page=3', 'p', 1],
    ['/audit?p=0', 'p', 1],
    ['/audit?page=5&p=2', 'p', 2],
    ['/audit?page=5&p=2', 'page', 5],
  ])('COM-4 usePageParam 다른 이름 %s (%s) → %i쪽', (url, name, want) => {
    expect(pageAt(url, name)).toBe(want);
  });
});

describe('pageHref', () => {
  afterEach(() => {
    window.history.replaceState(null, '', '/');
  });

  it.each([
    ['/issues', '/issues', 1, '/issues'],
    ['/issues', '/issues', 2, '/issues?page=2'],
    ['/issues?page=3', '/issues', 1, '/issues'],
    ['/issues?page=3', '/issues', 3, '/issues?page=3'],
    ['/issues?page=3', '/issues', 4, '/issues?page=4'],
    ['/issues?status=OPEN&page=3', '/issues', 2, '/issues?status=OPEN&page=2'],
    ['/issues?status=OPEN&page=3', '/issues', 1, '/issues?status=OPEN'],
    ['/issues?status=OPEN', '/issues', 1, '/issues?status=OPEN'],
    ['/issues?status=OPEN', '/issues', 20, '/issues?status=OPEN&page=20'],
    ['/companies/ACME?page=2', '/companies/ACME', 3, '/companies/ACME?page=3'],
  ])('COM-4 pageHref 지금 주소 %s, %s의 %i쪽 → %s', (now, path, p, want) => {
    window.history.replaceState(null, '', now);

    expect(pageHref(path, p)).toBe(want);
  });

  it.each([
    ['/audit?actor=7&p=3', 2, { actor: '7', p: '2' }],
    ['/audit?actor=7&p=3', 1, { actor: '7' }],
    ['/audit?page=4&p=3', 2, { page: '4', p: '2' }],
    ['/audit?page=4&p=3', 1, { page: '4' }],
    ['/audit', 5, { p: '5' }],
  ])('COM-4 pageHref 다른 이름: 지금 주소 %s, %i쪽은 그 이름만 바꾼다', (now, p, want) => {
    window.history.replaceState(null, '', now);

    const url = new URL(pageHref('/audit', p, 'p'), 'http://popdesk.test');

    expect(url.pathname).toBe('/audit');
    expect(Object.fromEntries(url.searchParams)).toEqual(want);
  });

  it('COM-4 pageHref 조건의 값이 한글이어도 그대로 읽힌다', () => {
    window.history.replaceState(null, '', `/issues?q=${encodeURIComponent('로그인 오류')}&page=2`);

    const url = new URL(pageHref('/issues', 3), 'http://popdesk.test');

    expect(Object.fromEntries(url.searchParams)).toEqual({ q: '로그인 오류', page: '3' });
  });
});

describe('usePageMove', () => {
  const said = vi.fn<PageAnnouncement>((page, size, total, shown) => `${String(page)}쪽 ${String(size)} ${String(total)} ${String(shown)}`);

  function List(props: { info: PageInfo | undefined }) {
    const heading = useRef<HTMLHeadingElement>(null);
    const { announcement, onPageChange } = usePageMove(props.info, heading, said);
    return (
      <>
        <h2 ref={heading} tabIndex={-1}>
          문의 목록
        </h2>
        <button type="button" onClick={onPageChange}>
          옮기기
        </button>
        <p role="status">{announcement}</p>
      </>
    );
  }

  const info = (page: number, shown = 20): PageInfo => ({ page, size: 20, totalElements: 45, totalPages: 3, items: Array.from({ length: shown }) });
  const move = () => fireEvent.click(screen.getByRole('button', { name: '옮기기' }));

  afterEach(() => {
    said.mockClear();
  });

  it('P-3 쪽을 옮기지 않았으면 새 쪽이 와도 포커스와 알림을 건드리지 않는다', () => {
    const { rerender } = render(<List info={undefined} />);
    rerender(<List info={info(1)} />);
    rerender(<List info={info(2)} />);

    expect(document.activeElement).toBe(document.body);
    expect(screen.getByRole('status')).toHaveTextContent('');
  });

  it('P-3 쪽을 옮긴 뒤 새 쪽이 오면 제목에 포커스를 두고 그 쪽의 수로 알린다', () => {
    const { rerender } = render(<List info={info(1)} />);
    move();
    rerender(<List info={info(2)} />);

    expect(document.activeElement).toBe(screen.getByRole('heading', { name: '문의 목록' }));
    expect(screen.getByRole('status')).toHaveTextContent('2쪽 20 45 20');
    expect(said).toHaveBeenLastCalledWith(2, 20, 45, 20);
  });

  it('P-3 마지막 쪽은 그 쪽에 보이는 건수로 알린다', () => {
    const { rerender } = render(<List info={info(2)} />);
    move();
    rerender(<List info={info(3, 5)} />);

    expect(screen.getByRole('status')).toHaveTextContent('3쪽 20 45 5');
  });

  it('P-3 눌렀을 때는 아직 알리지 않는다. 새 쪽이 와야 알린다', () => {
    const first = info(1);
    const { rerender } = render(<List info={first} />);
    move();
    rerender(<List info={first} />);

    expect(document.activeElement).toBe(document.body);
    expect(screen.getByRole('status')).toHaveTextContent('');
  });

  it('P-3 쪽을 옮긴 뒤 목록이 아직 없으면(불러오는 중) 알리지 않는다', () => {
    const { rerender } = render(<List info={info(1)} />);
    move();
    rerender(<List info={undefined} />);

    expect(document.activeElement).toBe(document.body);
    expect(screen.getByRole('status')).toHaveTextContent('');
    expect(said).not.toHaveBeenCalled();
  });

  it('P-3 한 번 옮긴 것은 한 번만 알린다. 다시 옮기면 다시 알린다', () => {
    const { rerender } = render(<List info={info(1)} />);
    move();
    rerender(<List info={info(2)} />);
    screen.getByRole('heading', { name: '문의 목록' }).blur();

    rerender(<List info={info(3, 5)} />);
    expect(document.activeElement).toBe(document.body);
    expect(screen.getByRole('status')).toHaveTextContent('2쪽 20 45 20');

    move();
    rerender(<List info={info(1)} />);
    expect(document.activeElement).toBe(screen.getByRole('heading', { name: '문의 목록' }));
    expect(screen.getByRole('status')).toHaveTextContent('1쪽 20 45 20');
  });
});
