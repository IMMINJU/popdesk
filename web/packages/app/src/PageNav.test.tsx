// screens.md P-3 쪽 넘기기: 쪽은 라우터의 링크다. 한 쪽뿐이면 보이지 않는다.
import { fireEvent, render, screen, within } from '@testing-library/react';
import { createMemoryRouter, RouterProvider } from 'react-router';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { PageNav } from './PageNav';

function drawn(props: { page: number; pageCount: number; param?: string; path?: string }) {
  const onPageChange = vi.fn<() => void>();
  const path = props.path ?? '/issues';
  const router = createMemoryRouter(
    [{ path: '*', element: <PageNav path={path} page={props.page} pageCount={props.pageCount} onPageChange={onPageChange} {...(props.param === undefined ? {} : { param: props.param })} /> }],
    { initialEntries: [path] },
  );
  render(<RouterProvider router={router} />);
  return { router, onPageChange };
}

const hrefOf = (name: string) => screen.getByRole('link', { name }).getAttribute('href');

describe('PageNav', () => {
  afterEach(() => {
    window.history.replaceState(null, '', '/');
  });

  it.each([0, 1])('P-3 %i쪽뿐이면 아무것도 그리지 않는다', (pageCount) => {
    drawn({ page: 1, pageCount });

    expect(screen.queryByRole('navigation')).toBeNull();
    expect(screen.queryByRole('link')).toBeNull();
  });

  it('P-3 가운데 쪽: "쪽 넘기기" 안에 앞 쪽, 쪽마다 "n쪽", 다음 쪽 링크가 있고 지금 쪽에 aria-current가 있다', () => {
    drawn({ page: 2, pageCount: 3 });

    const nav = screen.getByRole('navigation', { name: '쪽 넘기기' });
    expect(within(nav).getAllByRole('link').map((a) => a.getAttribute('aria-label'))).toEqual(['앞 쪽', '1쪽', '2쪽', '3쪽', '다음 쪽']);
    expect(screen.getByRole('link', { name: '2쪽' })).toHaveAttribute('aria-current', 'page');
    expect(screen.getByRole('link', { name: '1쪽' })).not.toHaveAttribute('aria-current');
    expect(screen.getByRole('link', { name: '3쪽' })).toHaveClass('pd-page');
  });

  it('P-3 주소: 1쪽은 page가 없고 다른 쪽은 ?page=n이다. 앞 쪽과 다음 쪽은 옆 쪽으로 간다', () => {
    drawn({ page: 2, pageCount: 3 });

    expect(hrefOf('1쪽')).toBe('/issues');
    expect(hrefOf('앞 쪽')).toBe('/issues');
    expect(hrefOf('2쪽')).toBe('/issues?page=2');
    expect(hrefOf('3쪽')).toBe('/issues?page=3');
    expect(hrefOf('다음 쪽')).toBe('/issues?page=3');
  });

  it.each([
    [1, ['1쪽', '2쪽', '3쪽', '다음 쪽']],
    [3, ['앞 쪽', '1쪽', '2쪽', '3쪽']],
  ])('P-3 끝 쪽(%i쪽): 갈 곳이 없는 쪽의 링크는 없다', (page, names) => {
    drawn({ page, pageCount: 3 });

    expect(screen.getAllByRole('link').map((a) => a.getAttribute('aria-label'))).toEqual(names);
  });

  it('P-3 지금 주소의 다른 조건은 링크에 남는다', () => {
    window.history.replaceState(null, '', '/issues?status=OPEN&page=2');
    drawn({ page: 2, pageCount: 3 });

    expect(hrefOf('3쪽')).toBe('/issues?status=OPEN&page=3');
    expect(hrefOf('1쪽')).toBe('/issues?status=OPEN');
  });

  it('P-3 쪽 이름을 따로 주면 그 이름으로 주소를 만든다', () => {
    drawn({ page: 1, pageCount: 2, param: 'p', path: '/audit' });

    expect(hrefOf('2쪽')).toBe('/audit?p=2');
    expect(hrefOf('다음 쪽')).toBe('/audit?p=2');
  });

  it('P-3 누르면 onPageChange를 부르고 문서를 새로 받지 않고 라우터로 옮긴다', () => {
    const { router, onPageChange } = drawn({ page: 1, pageCount: 3 });

    const went = fireEvent.click(screen.getByRole('link', { name: '3쪽' }));

    expect(onPageChange).toHaveBeenCalledTimes(1);
    expect(went, '브라우저의 기본 이동은 막힌다').toBe(false);
    expect(router.state.location.pathname).toBe('/issues');
    expect(router.state.location.search).toBe('?page=3');
  });
});
