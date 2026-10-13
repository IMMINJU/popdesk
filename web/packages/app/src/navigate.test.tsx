// 라우터는 화면보다 오래 산다. 이미 사라진 화면이 부른 navigate는 지금 화면의 주소를 바꾸므로 버린다(S1A-AC-58).
// 본문을 읽지 않고 시그니처와 주석으로 썼다.
import { act, render } from '@testing-library/react';
import { useState } from 'react';
import { createMemoryRouter, RouterProvider } from 'react-router';
import { describe, expect, it } from 'vitest';
import { useLiveNavigate } from './navigate';

type Go = ReturnType<typeof useLiveNavigate>;

/** A screen at `/issues` that hands its navigate out, and a switch that takes the screen away. */
function opened() {
  const held: { go: Go; hide: () => void } = { go: () => undefined, hide: () => undefined };
  function Screen() {
    held.go = useLiveNavigate();
    return <p>문의 목록</p>;
  }
  function Holder() {
    const [shown, setShown] = useState(true);
    held.hide = () => {
      setShown(false);
    };
    return shown ? <Screen /> : <p>다른 화면</p>;
  }
  const router = createMemoryRouter(
    [
      { path: '/issues', element: <Holder /> },
      { path: '*', element: <p>옮겨 간 화면</p> },
    ],
    { initialEntries: ['/companies', '/issues'], initialIndex: 1 },
  );
  const view = render(<RouterProvider router={router} />);
  return { router, held, view };
}

describe('useLiveNavigate', () => {
  it('S1A-AC-58 화면이 있는 동안에는 주소를 옮긴다', async () => {
    const { router, held } = opened();

    await act(async () => {
      await held.go('/issues/ACME-7?tab=all');
    });

    expect(router.state.location.pathname).toBe('/issues/ACME-7');
    expect(router.state.location.search).toBe('?tab=all');
    expect(router.state.historyAction).toBe('PUSH');
  });

  it('S1A-AC-58 옵션(replace, state)을 그대로 넘긴다', async () => {
    const { router, held } = opened();

    await act(async () => {
      await held.go('/login', { replace: true, state: { expired: true } });
    });

    expect(router.state.location.pathname).toBe('/login');
    expect(router.state.historyAction).toBe('REPLACE');
    expect(router.state.location.state).toEqual({ expired: true });
  });

  it('S1A-AC-58 화면이 사라진 뒤에 부르면 주소가 그대로다', async () => {
    const { router, held } = opened();
    const late = held.go;
    act(() => {
      held.hide();
    });

    await act(async () => {
      await late('/login', { replace: true });
    });

    expect(router.state.location.pathname).toBe('/issues');
    expect(router.state.historyAction).toBe('POP');
  });

  it('S1A-AC-58 화면이 다시 그려져도 같은 함수이고, 다시 그려진 뒤에도 주소를 옮긴다', async () => {
    const { router, held, view } = opened();
    const first = held.go;
    view.rerender(<RouterProvider router={router} />);
    await act(async () => {
      await router.navigate('/issues?page=2');
    });

    expect(held.go).toBe(first);
    await act(async () => {
      await first('/companies');
    });
    expect(router.state.location.pathname).toBe('/companies');
  });

  it('S1A-AC-58 화면 틀이 통째로 내려간 뒤에 불러도 주소가 그대로다', async () => {
    const { router, held, view } = opened();
    const late = held.go;
    view.unmount();

    await late('/login');

    expect(router.state.location.pathname).toBe('/issues');
  });
});
