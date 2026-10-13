// screens.md 2절 처음 불러옴: 화면이 첫 데이터를 받는 동안 `main`에 `aria-busy`를 둔다. 틀이 넘긴 함수로 알린다.
// 본문을 읽지 않고 시그니처와 주석으로 썼다.
import { render, renderHook, screen } from '@testing-library/react';
import { useState, type ReactNode } from 'react';
import { describe, expect, it, vi } from 'vitest';
import { BusyContext, useMainBusy } from './busy';

/** The frame: `main` carries what the screen inside said last. */
function Frame(props: { children: ReactNode }) {
  const [busy, setBusy] = useState(false);
  return (
    <BusyContext value={setBusy}>
      <main aria-busy={busy}>{props.children}</main>
    </BusyContext>
  );
}

function Screen(props: { loading: boolean }) {
  useMainBusy(props.loading);
  return <p>문의 목록</p>;
}

describe('useMainBusy', () => {
  it.each([
    [true, 'true'],
    [false, 'false'],
  ])('screens.md 2절 불러오는 중 %s → main의 aria-busy는 %s', (loading, want) => {
    render(
      <Frame>
        <Screen loading={loading} />
      </Frame>,
    );

    expect(screen.getByRole('main')).toHaveAttribute('aria-busy', want);
  });

  it('screens.md 2절 다 불러오면 aria-busy가 꺼지고, 다시 불러오면 켜진다', () => {
    const { rerender } = render(
      <Frame>
        <Screen loading={true} />
      </Frame>,
    );
    rerender(
      <Frame>
        <Screen loading={false} />
      </Frame>,
    );
    expect(screen.getByRole('main')).toHaveAttribute('aria-busy', 'false');

    rerender(
      <Frame>
        <Screen loading={true} />
      </Frame>,
    );
    expect(screen.getByRole('main')).toHaveAttribute('aria-busy', 'true');
  });

  it('screens.md 2절 틀에 넘기는 값은 화면이 준 값 그대로다', () => {
    const setBusy = vi.fn<(busy: boolean) => void>();
    const wrapper = ({ children }: { children: ReactNode }) => <BusyContext value={setBusy}>{children}</BusyContext>;

    const { rerender } = renderHook(({ busy }) => {
      useMainBusy(busy);
    }, { wrapper, initialProps: { busy: true } });
    expect(setBusy).toHaveBeenLastCalledWith(true);

    rerender({ busy: false });
    expect(setBusy).toHaveBeenLastCalledWith(false);
  });

  it('screens.md 2절 불러오는 중에 화면이 내려가면 aria-busy를 끈다. 값이 그대로면 다시 알리지 않는다', () => {
    const setBusy = vi.fn<(busy: boolean) => void>();
    const wrapper = ({ children }: { children: ReactNode }) => <BusyContext value={setBusy}>{children}</BusyContext>;
    const { rerender, unmount } = renderHook(({ busy }) => {
      useMainBusy(busy);
    }, { wrapper, initialProps: { busy: true } });
    const calls = setBusy.mock.calls.length;

    rerender({ busy: true });
    expect(setBusy.mock.calls.length, '같은 값으로 다시 그림').toBe(calls);

    unmount();
    expect(setBusy).toHaveBeenLastCalledWith(false);
  });

  it('screens.md 2절 틀 밖에서 써도 오류가 없다', () => {
    const { result } = renderHook(() => {
      useMainBusy(true);
      return 'drawn';
    });

    expect(result.current).toBe('drawn');
  });
});
