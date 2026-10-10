// screens.md 2절(401로 로그인 보내기), C-3 쪽 넘기기. C-10 변이 테스트가 찾은 빈칸: 주소의 쪽 번호 읽기,
// 쪽을 옮긴 뒤에만 포커스·알림, 세션 밖에서 useSession, 로그인으로 보낼 때 세션을 비우고 주소를 바꾸는 방식.
import { act, fireEvent, render, renderHook, screen } from '@testing-library/react';
import { useRef, type ReactNode } from 'react';
import { createMemoryRouter, MemoryRouter, RouterProvider } from 'react-router';
import { describe, expect, test, vi } from 'vitest';
import { usePageMove, usePageParam, type PageInfo } from './paging';
import { SessionContext, useSession, useToLogin, type LoginState } from './sessionContext';

function pageAt(url: string): number {
  const { result } = renderHook(() => usePageParam(), {
    wrapper: ({ children }: { children: ReactNode }) => <MemoryRouter initialEntries={[url]}>{children}</MemoryRouter>,
  });
  return result.current;
}

describe('C-3 usePageParam', () => {
  test.each([
    ['/issues', 1],
    ['/issues?page=3', 3],
    ['/issues?page=1', 1],
    ['/issues?page=0', 1],
    ['/issues?page=-2', 1],
    ['/issues?page=1.5', 1],
    ['/issues?page=abc', 1],
  ])('C-3 usePageParam: %s → %i쪽', (url, want) => {
    expect(pageAt(url)).toBe(want);
  });
});

describe('C-3 usePageMove', () => {
  function List(props: { info: PageInfo }) {
    const heading = useRef<HTMLHeadingElement>(null);
    const { announcement, onPageChange } = usePageMove(props.info, heading);
    return (
      <>
        <h2 ref={heading} tabIndex={-1}>
          문의 목록
        </h2>
        <button type="button" onClick={onPageChange}>
          다음 쪽
        </button>
        <p role="status">{announcement}</p>
      </>
    );
  }

  const info = (page: number): PageInfo => ({ page, size: 20, totalElements: 45, totalPages: 3, items: Array.from({ length: page === 3 ? 5 : 20 }) });

  test('C-3 usePageMove: 쪽을 옮기지 않았으면 새 쪽이 와도 포커스와 알림을 건드리지 않는다', () => {
    const { rerender } = render(<List info={info(1)} />);
    rerender(<List info={info(2)} />);

    expect(document.activeElement).toBe(document.body);
    expect(screen.getByRole('status')).toHaveTextContent('');
  });

  test('C-3 usePageMove: 쪽을 옮긴 뒤 새 쪽이 오면 제목에 포커스, 알림 한 번, 그다음 새 쪽에는 다시 하지 않는다', () => {
    const { rerender } = render(<List info={info(1)} />);
    fireEvent.click(screen.getByRole('button', { name: '다음 쪽' }));
    rerender(<List info={info(2)} />);

    expect(document.activeElement).toBe(screen.getByRole('heading', { name: '문의 목록' }));
    expect(screen.getByRole('status')).toHaveTextContent('2쪽, 45건 가운데 21~40');

    (document.activeElement as HTMLElement).blur();
    rerender(<List info={info(3)} />);
    expect(document.activeElement).toBe(document.body);
    expect(screen.getByRole('status')).toHaveTextContent('2쪽, 45건 가운데 21~40');
  });
});

describe('C-1 세션', () => {
  test('C-1 useSession: SessionProvider 밖에서 부르면 오류', () => {
    const quiet = vi.spyOn(console, 'error').mockImplementation(() => undefined);
    expect(() => renderHook(() => useSession())).toThrow();
    quiet.mockRestore();
  });

  test('C-1 useToLogin: 세션을 비우고 /login?next=지금 주소로 바꾼다(되돌아가기 기록을 남기지 않는다), 세션이 있었으면 끝남 표시', () => {
    const setMe = vi.fn();
    let go: () => void = () => undefined;
    function Screen() {
      go = useToLogin();
      return <p>문의</p>;
    }
    const router = createMemoryRouter(
      [
        { path: '/issues/:key', element: <Screen /> },
        { path: '/login', element: <p>로그인</p> },
      ],
      { initialEntries: ['/companies', '/issues/ACME-7?tab=all'], initialIndex: 1 },
    );
    render(
      <SessionContext.Provider value={{ me: null, setMe, hadSession: true }}>
        <RouterProvider router={router} />
      </SessionContext.Provider>,
    );

    act(() => {
      go();
    });
    expect(setMe).toHaveBeenCalledWith(null);
    expect(router.state.location.pathname).toBe('/login');
    expect(new URLSearchParams(router.state.location.search).get('next')).toBe('/issues/ACME-7?tab=all');
    expect(router.state.historyAction).toBe('REPLACE');
    expect((router.state.location.state as LoginState).expired).toBe(true);
  });
});
