// 4번 묶음 screens.md 1절 "머리의 알림 링크": 콘솔 머리의 알림 링크와 안 읽은 수(S4-AC-64, 69).
// 수는 레이아웃이 뜰 때 한 번, 그 뒤 60초마다 받는다. 탭이 보이지 않으면 그 차례를 건너뛰고, 다시 보이면 바로 한 번 받는다.
// 401이면 멈추고 로그인 화면으로 보내지 않는다. 실패하면 마지막 수를 두고 아무것도 띄우지 않는다.
import { act } from '@testing-library/react';
import { HttpResponse } from 'msw/http';
import { afterEach, describe, expect, test, vi } from 'vitest';
import { ADMIN, AGENT, callsTo, errors, eventually, hrefPath, link, menuLinks, on, renderAt, screen, sent, state, text, useFakeClock } from '../__tests__/console';
import { settle } from '../__tests__/console1a';
import { REQUESTS } from '../__tests__/console2';
import { UNREAD, inboxLink, liveText, notification, s4 } from '../__tests__/console4';

/** A screen that asks for nothing of the inbox itself: what is counted below is the frame's own requests. */
const SCREEN = '/me';

const asked = () => callsTo('GET', UNREAD).length;
const unread = (count: number) => Array.from({ length: count }, (_, i) => notification({ id: 500 + i }));
const digits = (a: HTMLElement) =>
  Array.from(a.querySelectorAll('[aria-hidden="true"]'))
    .map((el) => text(el))
    .filter((part) => /\d/.test(part));

/** Moves the fake clock one minute on, half a minute at a time. */
function halfMinute() {
  act(() => {
    vi.advanceTimersByTime(30_000);
  });
}

/** What a browser does when the tab is hidden or shown again. */
function setHidden(hidden: boolean) {
  Object.defineProperty(document, 'hidden', { configurable: true, get: () => hidden });
  Object.defineProperty(document, 'visibilityState', { configurable: true, get: () => (hidden ? 'hidden' : 'visible') });
  act(() => {
    document.dispatchEvent(new Event('visibilitychange'));
  });
}

/** Opens the frame and waits for its first count, one step at a time (the session first). */
async function opened(options: { fakeTimers?: boolean } = {}) {
  const view = renderAt(SCREEN, options);
  await sent('GET', '/api/console/me');
  await sent('GET', UNREAD);
  return view;
}

describe('콘솔 머리: 알림 링크와 안 읽은 수 (4번 묶음)', () => {
  afterEach(() => {
    // Back to jsdom's own `hidden` and `visibilityState` (on the prototype).
    Reflect.deleteProperty(document, 'hidden');
    Reflect.deleteProperty(document, 'visibilityState');
  });

  test.each([
    { who: '관리자', me: ADMIN, menu: ['문의', '내 작업', '통계', '고객사', '구성원', '휴지통', 'SLA 설정', '감사 로그', '시스템 상태'] },
    { who: '상담원', me: AGENT, menu: ['문의', '내 작업', '통계'] },
  ])('S4-AC-64 콘솔 머리($who): 레이아웃이 뜨면 안 읽은 수를 한 번 받고 링크의 이름이 "알림 3건"이다. /inbox로 가고 메뉴 nav의 항목은 그대로다', async ({ me, menu }) => {
    state.me = me;
    s4.inbox = unread(3);
    await opened();

    const a = await link('알림 3건');
    expect(hrefPath(a)).toBe('/inbox');
    expect(a.closest('nav'), '메뉴 nav 밖').toBeNull();
    expect(a.closest('header'), '머리 안').not.toBeNull();
    expect(digits(a), '보이는 숫자').toEqual(['3']);
    expect(a).not.toHaveAttribute('aria-current');
    await settle();
    expect(asked(), '안 읽은 수 요청').toBe(1);
    expect(menuLinks()).toEqual(menu);
    expect(screen.getAllByRole('navigation'), 'navigation은 하나').toHaveLength(1);
    // The count is the head's own request: nothing more of the session, the lists or the requests.
    expect(callsTo('GET', '/api/console/me')).toHaveLength(1);
    expect(callsTo('GET', '/api/console/issues')).toHaveLength(0);
    expect(callsTo('GET', '/api/console/companies')).toHaveLength(0);
    expect(callsTo('GET', REQUESTS).length, '소속 요청 수는 관리자일 때 한 번').toBe(me.role === 'ADMIN' ? 1 : 0);
  });

  test('S4-AC-64 콘솔 머리: 안 읽은 것이 없으면 숫자가 없고 이름은 "알림"이다. 100건을 넘으면 "99+"로 보이고 이름은 실제 수를 쓴다', async () => {
    const first = await opened();

    const none = await inboxLink();
    await settle();
    expect(none).toHaveAccessibleName('알림');
    expect(digits(none)).toEqual([]);
    first.unmount();

    s4.inbox = unread(120);
    renderAt(SCREEN);
    const many = await link('알림 120건');
    expect(digits(many)).toEqual(['99+']);
  });

  test('S4-AC-64 콘솔 머리: 알림함 화면에서는 링크가 aria-current="page"다', async () => {
    renderAt('/inbox');

    const a = await inboxLink();
    await eventually(() => {
      expect(a).toHaveAttribute('aria-current', 'page');
    });
    expect(a.closest('nav'), '메뉴 nav 밖').toBeNull();
  });

  test('S4-AC-64 콘솔 머리 60초: 30초가 지나도 다시 받지 않고, 60초가 지나면 한 번 다시 받아 수를 바꾼다. 화면을 옮겨도 다시 받지 않는다', async () => {
    useFakeClock();
    s4.inbox = unread(1);
    const { user } = await opened({ fakeTimers: true });
    await link('알림 1건');
    expect(asked()).toBe(1);

    // Moving to another screen is not a reason to ask again.
    await user.click(await link('문의'));
    await sent('GET', '/api/console/issues');
    s4.inbox = unread(4);
    halfMinute();
    await settle();
    expect(asked(), '30초 뒤').toBe(1);

    halfMinute();
    await sent('GET', UNREAD, 2);
    await link('알림 4건');
    await settle();
    expect(asked(), '60초 뒤').toBe(2);
  });

  test('S4-AC-64 콘솔 머리 숨은 탭: document.hidden이면 그 차례에 받지 않고, 다시 보이면 바로 한 번 받는다', async () => {
    useFakeClock();
    s4.inbox = unread(1);
    await opened({ fakeTimers: true });
    await link('알림 1건');

    setHidden(true);
    s4.inbox = unread(2);
    halfMinute();
    halfMinute();
    await settle();
    expect(asked(), '숨은 동안').toBe(1);

    setHidden(false);
    await sent('GET', UNREAD, 2);
    await link('알림 2건');
    await settle();
    expect(asked(), '다시 보인 뒤').toBe(2);

    // The skipped turn did not end the period: a minute on, it asks again.
    s4.inbox = unread(6);
    halfMinute();
    halfMinute();
    await sent('GET', UNREAD, 3);
    await link('알림 6건');
  });

  test('S4-AC-69 콘솔 머리 401: 안 읽은 수가 401이면 그 뒤로 받지 않고, 로그인 화면으로 가지 않으며 아무것도 띄우지 않는다', async () => {
    useFakeClock();
    on('get', UNREAD, () => errors.unauthenticated());
    await opened({ fakeTimers: true });

    const a = await inboxLink();
    await settle();
    expect(a).toHaveAccessibleName('알림');
    for (let i = 0; i < 4; i += 1) halfMinute();
    setHidden(true);
    setHidden(false);
    await settle();

    expect(asked(), '401 뒤의 요청').toBe(1);
    expect(window.location.pathname).toBe(SCREEN);
    expect(screen.queryAllByRole('alert')).toHaveLength(0);
    expect(liveText(), '토스트(status)로도 알리지 않는다').toEqual([]);
  });

  test('S4-AC-69 콘솔 머리 받은 뒤의 401: 수를 받은 뒤 다음 차례가 401이면 마지막 수가 그대로이고 그 뒤로 받지 않는다. 아무것도 띄우지 않는다', async () => {
    useFakeClock();
    s4.inbox = unread(3);
    await opened({ fakeTimers: true });
    await link('알림 3건');
    const before = liveText();

    on('get', UNREAD, () => errors.unauthenticated());
    halfMinute();
    halfMinute();
    await sent('GET', UNREAD, 2);
    await settle();
    expect(await inboxLink(), '마지막 수').toHaveAccessibleName('알림 3건');

    for (let i = 0; i < 4; i += 1) halfMinute();
    setHidden(true);
    setHidden(false);
    await settle();
    expect(asked(), '401 뒤의 요청').toBe(2);
    expect(await inboxLink()).toHaveAccessibleName('알림 3건');
    expect(window.location.pathname).toBe(SCREEN);
    expect(screen.queryAllByRole('alert')).toHaveLength(0);
    expect(liveText(), '토스트(status)로도 알리지 않는다').toEqual(before);
  });

  test.each([
    { what: '5xx', answer: () => errors.internal() },
    { what: '연결 실패', answer: () => HttpResponse.error() },
  ])('S4-AC-69 콘솔 머리 $what: 다시 받기가 실패하면 마지막 수가 그대로이고 alert가 없다. 다음 차례에 다시 받는다', async ({ answer }) => {
    useFakeClock();
    s4.inbox = unread(3);
    await opened({ fakeTimers: true });
    await link('알림 3건');
    const before = liveText();

    let broken = true;
    on('get', UNREAD, () => (broken ? answer() : HttpResponse.json({ count: 5 })));
    halfMinute();
    halfMinute();
    await sent('GET', UNREAD, 2);
    await settle();
    expect(await inboxLink()).toHaveAccessibleName('알림 3건');
    expect(screen.queryAllByRole('alert')).toHaveLength(0);
    expect(liveText(), '토스트(status)로도 알리지 않는다').toEqual(before);
    expect(window.location.pathname).toBe(SCREEN);

    broken = false;
    halfMinute();
    halfMinute();
    await sent('GET', UNREAD, 3);
    await link('알림 5건');
  });

  test('S4-AC-69 콘솔 머리를 떠남: 레이아웃이 사라지면 주기를 지워 더 받지 않는다', async () => {
    useFakeClock();
    s4.inbox = unread(1);
    const { unmount } = await opened({ fakeTimers: true });
    await link('알림 1건');

    unmount();
    for (let i = 0; i < 4; i += 1) halfMinute();
    setHidden(true);
    setHidden(false);
    await settle();

    expect(asked()).toBe(1);
  });
});
