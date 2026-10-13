// A router and a session around a hook under test, the way an app's frame gives them.
import { act, render } from '@testing-library/react';
import { useState } from 'react';
import { createMemoryRouter, Outlet, RouterProvider } from 'react-router';
import { vi } from 'vitest';
import { sessionContext, type SessionValue } from '../sessionContext';

const Session = sessionContext<unknown>();

/**
 * Runs the hook `use` in a screen at `url` (every path but `/login` and `/other` shows that
 * screen) inside a session. `hadSession` says whether a session was seen in this tab.
 */
export function framedHook<P, R>(use: (props: P) => R, initial: P, url = '/issues', hadSession = true) {
  const setMe = vi.fn<(me: unknown) => void>();
  const value: SessionValue<unknown> = { me: { name: '김민주' }, setMe, hadSession };
  const held: { current: R | undefined; set: (props: P) => void } = { current: undefined, set: () => undefined };
  function Screen() {
    const [props, setProps] = useState(initial);
    held.set = setProps;
    held.current = use(props);
    return <p>화면</p>;
  }
  const router = createMemoryRouter(
    [
      {
        element: (
          <Session value={value}>
            <Outlet />
          </Session>
        ),
        children: [
          { path: '/login', element: <p>로그인</p> },
          { path: '/other', element: <p>다른 화면</p> },
          { path: '*', element: <Screen /> },
        ],
      },
    ],
    { initialEntries: ['/start', url], initialIndex: 1 },
  );
  const view = render(<RouterProvider router={router} />);
  return {
    router,
    setMe,
    unmount: view.unmount,
    /** What the hook gave on the last draw. */
    now: () => held.current as R,
    rerender: (props: P) => {
      act(() => {
        held.set(props);
      });
    },
  };
}
