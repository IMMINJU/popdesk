// screens.md P-1 세션 끝남: 이 탭에서 세션을 한 번이라도 봤으면, 뒤의 401은 세션이 끝났다는 뜻이다.
import { act, render, renderHook, screen } from '@testing-library/react';
import type { ReactNode } from 'react';
import { describe, expect, it } from 'vitest';
import { SessionProvider } from './session';
import { useSession } from './sessionContext';

interface Me {
  name: string;
}

const wrapper = ({ children }: { children: ReactNode }) => <SessionProvider>{children}</SessionProvider>;

describe('SessionProvider', () => {
  it('P-1 처음: 로그인한 사람이 없고 세션을 본 적도 없다. 안쪽을 그대로 그린다', () => {
    render(
      <SessionProvider>
        <p>안쪽 화면</p>
      </SessionProvider>,
    );
    const { result } = renderHook(() => useSession<Me>(), { wrapper });

    expect(screen.getByText('안쪽 화면')).toBeInTheDocument();
    expect(result.current.me).toBeNull();
    expect(result.current.hadSession).toBe(false);
  });

  it('P-1 로그인: setMe로 넣은 사람을 주고, 세션을 봤다고 적는다', () => {
    const { result } = renderHook(() => useSession<Me>(), { wrapper });
    const me = { name: '김민주' };

    act(() => {
      result.current.setMe(me);
    });

    expect(result.current.me).toBe(me);
    expect(result.current.hadSession).toBe(true);
  });

  it('P-1 세션 끝남: 사람을 비워도 세션을 봤다는 것은 남는다', () => {
    const { result } = renderHook(() => useSession<Me>(), { wrapper });
    act(() => {
      result.current.setMe({ name: '김민주' });
    });

    act(() => {
      result.current.setMe(null);
    });

    expect(result.current.me).toBeNull();
    expect(result.current.hadSession).toBe(true);
  });

  it('P-1 로그인 전의 401: 비어 있던 세션을 비워도 세션을 봤다고 적지 않는다', () => {
    const { result } = renderHook(() => useSession<Me>(), { wrapper });

    act(() => {
      result.current.setMe(null);
    });

    expect(result.current.me).toBeNull();
    expect(result.current.hadSession).toBe(false);
  });

  it('P-1 setMe는 그릴 때마다 같은 함수다', () => {
    const { result } = renderHook(() => useSession<Me>(), { wrapper });
    const first = result.current.setMe;

    act(() => {
      result.current.setMe({ name: '이서연' });
    });

    expect(result.current.setMe).toBe(first);
    expect(result.current.me).toEqual({ name: '이서연' });
  });
});
