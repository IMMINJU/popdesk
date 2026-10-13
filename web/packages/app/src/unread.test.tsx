// 4번 묶음 screens.md "머리의 알림 링크": 안 읽은 수는 틀이 뜰 때 한 번, 그 뒤 60초마다 받는다. 탭이 보이지 않으면 그 차례를
// 건너뛰고 다시 보이면 바로 받는다. 401이면 멈추고, 다른 실패에는 마지막 수를 둔다(S4-AC-64, 69).
import { act, renderHook, waitFor } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import type { ReactNode } from 'react';
import { afterEach, describe, expect, it } from 'vitest';
import { ask, errorBody, gate, halfMinute, server, settle, useFakeClock } from './__tests__/server';
import { UNREAD_PERIOD_MS, UnreadContext, useUnread, useUnreadCount, type Unread } from './unread';

const UNREAD = '/api/portal/notifications/unread-count';
/** The app's own call: a module constant, so it keeps its identity. */
const load = () => ask<{ count: number }>(UNREAD);

/** The server's count, and how many times it was asked. `answer` replaces the 200 for one kind of failure. */
function counter(count: number) {
  const box: { count: number; asked: number; answer: (() => Response) | null; hold: Promise<void> | null } = { count, asked: 0, answer: null, hold: null };
  server.use(
    http.get(`*${UNREAD}`, async () => {
      box.asked += 1;
      const mine = box.answer ? box.answer() : HttpResponse.json({ count: box.count });
      if (box.hold) await box.hold;
      return mine;
    }),
  );
  return box;
}

function setHidden(hidden: boolean) {
  Object.defineProperty(document, 'hidden', { configurable: true, get: () => hidden });
  act(() => {
    document.dispatchEvent(new Event('visibilitychange'));
  });
}

function minute() {
  halfMinute();
  halfMinute();
}

/** Opens the hook and waits for its first count. */
async function opened(box: { count: number }, on = true) {
  const view = renderHook((props: { on: boolean }) => useUnreadCount(props.on, load), { initialProps: { on } });
  if (on) {
    await waitFor(() => {
      expect(view.result.current.count).toBe(box.count);
    });
  }
  return view;
}

describe('useUnreadCount', () => {
  afterEach(() => {
    // Back to jsdom's own `hidden` (on the prototype).
    Reflect.deleteProperty(document, 'hidden');
  });

  it('S4-AC-64 켜지면 한 번 받아 수를 준다. 받기 전에는 0이다', async () => {
    const box = counter(3);
    const view = renderHook(() => useUnreadCount(true, load));
    expect(view.result.current.count).toBe(0);

    await waitFor(() => {
      expect(view.result.current.count).toBe(3);
    });
    await settle();
    expect(box.asked).toBe(1);
  });

  it('S4-AC-64 꺼져 있으면(로그인 전) 받지 않는다. 다시 받기를 불러도, 탭이 다시 보여도, 1분이 지나도', async () => {
    useFakeClock();
    const box = counter(3);
    const view = await opened(box, false);

    act(() => {
      view.result.current.refresh();
    });
    setHidden(false);
    minute();
    await settle();

    expect(box.asked).toBe(0);
    expect(view.result.current.count).toBe(0);
  });

  it('S4-AC-64 60초: 30초에는 받지 않고 60초에 한 번 받아 수를 바꾼다. 그다음 60초에 또 받는다', async () => {
    useFakeClock();
    expect(UNREAD_PERIOD_MS).toBe(60_000);
    const box = counter(1);
    const view = await opened(box);

    box.count = 4;
    halfMinute();
    await settle();
    expect(box.asked, '30초 뒤').toBe(1);

    halfMinute();
    await waitFor(() => {
      expect(view.result.current.count).toBe(4);
    });
    expect(box.asked, '60초 뒤').toBe(2);

    box.count = 0;
    minute();
    await waitFor(() => {
      expect(view.result.current.count).toBe(0);
    });
    expect(box.asked, '120초 뒤').toBe(3);
  });

  it('S4-AC-64 숨은 탭: 그 차례에 받지 않고, 숨은 채 온 visibilitychange에도 받지 않는다. 다시 보이면 바로 한 번 받는다', async () => {
    useFakeClock();
    const box = counter(1);
    const view = await opened(box);

    setHidden(true);
    box.count = 2;
    minute();
    await settle();
    expect(box.asked, '숨은 동안').toBe(1);

    setHidden(false);
    await waitFor(() => {
      expect(view.result.current.count).toBe(2);
    });
    expect(box.asked, '다시 보인 뒤').toBe(2);

    // The skipped turn did not end the period.
    box.count = 6;
    minute();
    await waitFor(() => {
      expect(view.result.current.count).toBe(6);
    });
  });

  it('S4-AC-64 다시 받기: refresh를 부르면 차례를 기다리지 않고 바로 받는다', async () => {
    const box = counter(5);
    const view = await opened(box);
    const first = view.result.current;

    box.count = 0;
    act(() => {
      view.result.current.refresh();
    });

    await waitFor(() => {
      expect(view.result.current.count).toBe(0);
    });
    expect(box.asked).toBe(2);
    expect(view.result.current.refresh, 'refresh는 같은 함수로 남는다').toBe(first.refresh);
  });

  it('S4-AC-69 401: 그 뒤로 받지 않는다. 1분이 지나도, 탭이 다시 보여도, 다시 받기를 불러도. 마지막 수는 그대로다', async () => {
    useFakeClock();
    const box = counter(2);
    const view = await opened(box);

    box.answer = () => errorBody(401, 'UNAUTHENTICATED');
    minute();
    await waitFor(() => {
      expect(box.asked).toBe(2);
    });
    await settle();

    box.answer = null;
    box.count = 9;
    minute();
    setHidden(true);
    setHidden(false);
    act(() => {
      view.result.current.refresh();
    });
    await settle();

    expect(box.asked, '401 뒤').toBe(2);
    expect(view.result.current.count).toBe(2);
  });

  it.each<[string, () => Response]>([
    ['500', () => errorBody(500, 'SERVER_ERROR')],
    ['403', () => errorBody(403, 'FORBIDDEN')],
    ['429', () => errorBody(429, 'RATE_LIMITED')],
    ['끊긴 연결', () => HttpResponse.error()],
  ])('S4-AC-69 %s: 마지막 수를 두고 다음 차례에 다시 받는다', async (_, failure) => {
    useFakeClock();
    const box = counter(2);
    const view = await opened(box);

    box.answer = failure;
    minute();
    await waitFor(() => {
      expect(box.asked).toBe(2);
    });
    await settle();
    expect(view.result.current.count).toBe(2);

    box.answer = null;
    box.count = 7;
    minute();
    await waitFor(() => {
      expect(view.result.current.count).toBe(7);
    });
    expect(box.asked).toBe(3);
  });

  it('S4-AC-69 늦게 온 답: 뒤에 물은 답이 먼저 왔으면 앞의 답은 버린다', async () => {
    const box = counter(1);
    const view = await opened(box);

    const slow = gate();
    box.count = 8;
    box.hold = slow.opened;
    act(() => {
      view.result.current.refresh();
    });
    await waitFor(() => {
      expect(box.asked).toBe(2);
    });
    box.count = 3;
    box.hold = null;
    act(() => {
      view.result.current.refresh();
    });
    await waitFor(() => {
      expect(view.result.current.count).toBe(3);
    });

    slow.open();
    await settle();
    expect(view.result.current.count).toBe(3);
  });

  it('S4-AC-69 떠남: 훅이 내려가면 주기를 지워 더 받지 않고, 탭이 다시 보여도 받지 않는다', async () => {
    useFakeClock();
    const box = counter(1);
    const view = await opened(box);
    const { refresh } = view.result.current;

    view.unmount();
    minute();
    setHidden(true);
    setHidden(false);
    act(() => {
      refresh();
    });
    await settle();

    expect(box.asked).toBe(1);
  });

  it('S4-AC-69 떠난 뒤 온 답은 수를 바꾸지 않는다. 꺼지면(로그아웃) 멈추고, 다시 켜지면 바로 받는다', async () => {
    useFakeClock();
    const box = counter(1);
    const view = await opened(box);

    const slow = gate();
    box.count = 5;
    box.hold = slow.opened;
    act(() => {
      view.result.current.refresh();
    });
    await waitFor(() => {
      expect(box.asked).toBe(2);
    });
    view.rerender({ on: false });
    box.hold = null;
    slow.open();
    await settle();
    expect(view.result.current.count, '꺼진 뒤 온 답').toBe(1);

    minute();
    await settle();
    expect(box.asked, '꺼진 동안').toBe(2);

    view.rerender({ on: true });
    await waitFor(() => {
      expect(view.result.current.count).toBe(5);
    });
    expect(box.asked).toBe(3);
  });
});

describe('useUnread', () => {
  it('S4-AC-64 틀 밖: 수는 0이고 다시 받기는 아무 일도 하지 않는다', () => {
    const { result } = renderHook(() => useUnread());

    expect(result.current.count).toBe(0);
    expect(() => {
      result.current.refresh();
    }).not.toThrow();
  });

  it('S4-AC-64 틀 안: 틀이 준 수와 다시 받기를 그대로 준다', () => {
    const value: Unread = { count: 12, refresh: () => undefined };
    const wrapper = ({ children }: { children: ReactNode }) => <UnreadContext value={value}>{children}</UnreadContext>;

    const { result } = renderHook(() => useUnread(), { wrapper });

    expect(result.current).toBe(value);
  });
});
