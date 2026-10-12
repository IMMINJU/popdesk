// 4번 묶음 screens.md 1절 C-7 알림함: 항목 목록, 종류마다의 문장, 항목 누르기, "모두 읽음", 화면 상태(S4-AC-65~69).
import { HttpResponse } from 'msw/http';
import { describe, expect, test } from 'vitest';
import {
  ADMIN_PERSON,
  CUSTOMER_PERSON,
  OTHER_AGENT_PERSON,
  alertWith,
  atPath,
  button,
  callsTo,
  errors,
  eventually,
  expectNoAxeViolations,
  found,
  gate,
  heading1,
  hrefPath,
  isBlocked,
  link,
  on,
  pageText,
  renderAt,
  screen,
  sent,
  text,
  within,
} from '../__tests__/console';
import { settle } from '../__tests__/console1a';
import { said } from '../__tests__/console2';
import { NOTIFICATIONS, READ_ALL, UNREAD, alertItem, inboxLink, inboxPage, notification, s4, type Notification, type NotificationKind } from '../__tests__/console4';

const URL_INBOX = '/inbox';
const SERVER_DOWN = '서버에 연결하지 못했습니다. 잠시 뒤 다시 하세요.';
const MARK_ALL = '모두 읽음';
const READ_AT = '2026-10-08T04:00:00Z';

const main = () => screen.queryByRole('main');
const mainText = () => text(main());
const markAll = () => screen.queryByRole('button', { name: MARK_ALL });
const lists = () => callsTo('GET', NOTIFICATIONS);
const lastPage = () => new URL(lists().at(-1)?.url ?? 'http://x/').searchParams.get('page') ?? '1';
const addressPage = () => new URLSearchParams(window.location.search).get('page');

/** The link of the item whose line holds `part`, inside `main`. */
function item(part: string | RegExp): Promise<HTMLAnchorElement> {
  return found(() => {
    const root = main();
    if (!root) return null;
    return within(root)
      .queryAllByRole<HTMLAnchorElement>('link')
      .find((a) => (typeof part === 'string' ? text(a).includes(part) : part.test(text(a))));
  }, `알림 항목 ${String(part)}`);
}

/** An item about its own issue, so a line is found by the title. */
function about(n: number, over: Partial<Notification> = {}): Notification {
  const no = String(n).padStart(3, '0');
  return notification({ id: 1000 - n, issue: { key: `ACME-${String(n)}`, title: `문의 제목 ${no}번` }, ...over });
}

const many = (count: number) => Array.from({ length: count }, (_, i) => about(i + 1));

/** A kind a later slice adds: not in this build's contract. */
const LATER_KIND = 'SLA_WARNING' as NotificationKind;

describe('C-7 알림함', () => {
  test('S4-AC-65 C-7 목록: 제목은 "알림함"이고 항목이 받은 순서(최신순) 그대로다. 항목 하나가 링크 하나이고 문의 키와 제목, 시각이 있다. 안 읽은 항목에만 숨긴 글자 "안 읽음"이 있다', async () => {
    s4.inbox = [
      about(1, { createdAt: '2026-10-08T03:19:00Z' }),
      about(2, { createdAt: '2026-10-08T03:10:00Z', readAt: READ_AT }),
      about(3, { createdAt: '2026-10-07T23:00:00Z' }),
    ];
    renderAt(URL_INBOX);

    const links = [await item('문의 제목 001번'), await item('문의 제목 002번'), await item('문의 제목 003번')];
    expect(text(await heading1())).toBe('알림함');
    expect(document.title).toContain('알림함');
    // In the order the server gave, one line each.
    const rows = links.map((a) => a.closest('li'));
    expect(rows.every((li) => li !== null), '항목은 li').toBe(true);
    expect(new Set(rows).size).toBe(3);
    const list = rows[0]?.parentElement;
    expect(list?.tagName).toBe('UL');
    const children = Array.from(list?.children ?? []);
    expect(rows.map((li) => children.indexOf(li as HTMLElement)), '받은 순서 그대로').toEqual([0, 1, 2]);
    for (const li of rows) expect(within(li as HTMLElement).queryAllByRole('link'), '줄마다 링크 하나').toHaveLength(1);
    expect(within(main() as HTMLElement).queryAllByRole('table'), '표로 그리지 않는다').toHaveLength(0);

    expect(text(links[0])).toContain('ACME-1');
    expect(text(links[0])).toContain('김고객 님이 댓글을 남겼습니다');
    const times = links.map((a) => a.querySelector('time')?.getAttribute('datetime'));
    expect(times.map((at) => Date.parse(at ?? ''))).toEqual(s4.inbox.map((n) => Date.parse(n.createdAt)));
    // The time adds no Tab stop to the line: one Tab for each item.
    for (const a of links) expect(a.querySelector('[tabindex]'), '줄 안의 Tab 자리').toBeNull();

    expect(text(rows[0])).toContain('안 읽음');
    expect(text(rows[1])).not.toContain('안 읽음');
    expect(text(rows[2])).toContain('안 읽음');
    expect(lastPage()).toBe('1');
  });

  test.each([
    { kind: 'CUSTOMER_COMMENT', item: notification({ kind: 'CUSTOMER_COMMENT', commentId: 12 }), says: '김고객 님이 댓글을 남겼습니다' },
    { kind: 'ISSUE_CLOSED', item: notification({ kind: 'ISSUE_CLOSED' }), says: '김고객 님이 문의를 종료했습니다' },
    { kind: 'ISSUE_REOPENED', item: notification({ kind: 'ISSUE_REOPENED' }), says: '김고객 님이 문의를 다시 열었습니다' },
    { kind: 'MENTIONED', item: notification({ kind: 'MENTIONED', actor: OTHER_AGENT_PERSON, commentId: 12 }), says: '정상담 님이 댓글에서 나를 언급했습니다' },
    { kind: 'ASSIGNED', item: notification({ kind: 'ASSIGNED', actor: ADMIN_PERSON }), says: '최관리 님이 이 문의를 나에게 맡겼습니다' },
    { kind: 'MEMBERSHIP_REQUESTED', item: alertItem('MEMBERSHIP_REQUESTED'), says: '고객사 ACME에 소속 요청이 있습니다' },
    { kind: 'DELIVERY_FAILED', item: alertItem('DELIVERY_FAILED'), says: '보내지 못한 메일이나 웹훅이 있습니다' },
    { kind: 'ATTACHMENT_QUOTA_WARNING', item: alertItem('ATTACHMENT_QUOTA_WARNING'), says: '첨부 용량이 한도의 80%를 넘었습니다' },
    { kind: '일으킨 사람이 null인 ISSUE_CLOSED', item: notification({ kind: 'ISSUE_CLOSED', actor: null }), says: /누군가.*문의를 종료했습니다/ },
    { kind: '콘솔에 오지 않는 STAFF_COMMENT', item: notification({ kind: 'STAFF_COMMENT', actor: ADMIN_PERSON, commentId: 12 }), says: '새 알림이 있습니다' },
    { kind: '콘솔에 오지 않는 ISSUE_RESOLVED', item: notification({ kind: 'ISSUE_RESOLVED', actor: ADMIN_PERSON }), says: '새 알림이 있습니다' },
    { kind: '콘솔에 오지 않는 ISSUE_FILED_ON_BEHALF', item: notification({ kind: 'ISSUE_FILED_ON_BEHALF', actor: ADMIN_PERSON }), says: '새 알림이 있습니다' },
    { kind: '모르는 종류', item: notification({ kind: LATER_KIND, actor: ADMIN_PERSON }), says: '새 알림이 있습니다' },
  ])('S4-AC-65 S4-AC-68 C-7 문장($kind): $says', async ({ item: given, says }) => {
    s4.inbox = [given];
    renderAt(URL_INBOX);

    const a = await item(says);
    if (given.issue) {
      expect(text(a)).toContain(given.issue.key);
      expect(text(a)).toContain(given.issue.title);
    } else {
      expect(text(a)).not.toMatch(/[A-Z]+-\d+/);
    }
    expect(Date.parse(a.querySelector('time')?.getAttribute('datetime') ?? '')).toBe(Date.parse(given.createdAt));
    // The portal's word for the staff is not the console's (S4-AC-68).
    expect(mainText()).not.toContain('지원팀');
  });

  test.each([
    { what: '문의의 항목', item: notification({ kind: 'ASSIGNED', actor: ADMIN_PERSON }), path: '/issues/ACME-7', hash: '' },
    { what: '댓글의 항목', item: notification({ kind: 'CUSTOMER_COMMENT', commentId: 12 }), path: '/issues/ACME-7', hash: '#comment-12' },
    { what: '보내기 실패 경보', item: alertItem('DELIVERY_FAILED'), path: '/system', hash: '' },
    { what: '첨부 용량 경보', item: alertItem('ATTACHMENT_QUOTA_WARNING'), path: '/system', hash: '' },
    { what: '소속 요청', item: alertItem('MEMBERSHIP_REQUESTED'), path: '/companies/ACME', hash: '' },
    { what: '모르는 종류인데 문의가 있음', item: notification({ kind: LATER_KIND }), path: '/issues/ACME-7', hash: '' },
  ])('S4-AC-66 C-7 항목 누르기($what): 읽음 요청을 보내고 $path 로 간다(댓글의 항목이면 #comment-ID)', async ({ item: given, path, hash }) => {
    s4.inbox = [given];
    const { user } = renderAt(URL_INBOX);
    const a = await found(() => (main() ? within(main() as HTMLElement).queryAllByRole<HTMLAnchorElement>('link')[0] : null), '알림 항목');
    expect(hrefPath(a)).toBe(path);
    expect(new URL(a.href).hash).toBe(hash);

    await user.click(a);

    await sent('POST', `${NOTIFICATIONS}/${String(given.id)}/read`);
    await atPath(path);
    expect(window.location.hash).toBe(hash);
  });

  test('S4-AC-66 S4-AC-69 C-7 읽음 실패: 읽음의 답을 기다리지 않고 옮긴다. 떠난 뒤에 온 5xx는 아무것도 띄우지 않는다', async () => {
    s4.inbox = [notification({ kind: 'ASSIGNED', actor: ADMIN_PERSON })];
    const release = gate();
    let answered = false;
    on('post', `${NOTIFICATIONS}/:id/read`, async () => {
      await release.opened;
      answered = true;
      return errors.internal();
    });
    const { user } = renderAt(URL_INBOX);
    await user.click(await item('최관리 님이 이 문의를 나에게 맡겼습니다'));

    await sent('POST', `${NOTIFICATIONS}/501/read`);
    await atPath('/issues/ACME-7');
    await found(() => screen.queryByRole('heading', { level: 1, name: '로그인이 안 됩니다' }), '문의 상세의 제목');

    release.open();
    await eventually(() => {
      expect(answered).toBe(true);
    });
    await settle();
    expect(screen.queryAllByRole('alert')).toHaveLength(0);
    expect(window.location.pathname).toBe('/issues/ACME-7');
  });

  test('S4-AC-67 C-7 모두 읽음: 안 읽은 것이 있을 때만 버튼이 있다. 누르면 read-all을 보내고(보내는 중 버튼 막힘) 목록과 머리의 수를 다시 받으며 토스트 "모두 읽음으로 했습니다"', async () => {
    s4.inbox = [about(1), about(2, { readAt: READ_AT }), about(3)];
    const release = gate();
    on('post', READ_ALL, async () => {
      await release.opened;
      s4.inbox = s4.inbox.map((n) => ({ ...n, readAt: n.readAt ?? READ_AT }));
      return new HttpResponse(null, { status: 204 });
    });
    const { user } = renderAt(URL_INBOX);
    await item('문의 제목 001번');
    await link('알림 2건');
    const listed = lists().length;
    const counted = callsTo('GET', UNREAD).length;

    await user.click(await button(MARK_ALL));
    await sent('POST', READ_ALL);
    await eventually(() => {
      const pending = screen.queryByRole('button', { name: /모두 읽음|보내는 중…/ });
      expect(pending !== null && isBlocked(pending), '보내는 중 버튼 막힘').toBe(true);
    });
    expect(lists().length, '답이 오기 전에는 다시 받지 않는다').toBe(listed);
    release.open();

    await said('모두 읽음으로 했습니다');
    await eventually(() => {
      expect(lists().length, '목록을 다시 받음').toBeGreaterThan(listed);
      expect(callsTo('GET', UNREAD).length, '머리의 수를 다시 받음').toBeGreaterThan(counted);
      expect(mainText()).not.toContain('안 읽음');
      expect(markAll(), '안 읽은 것이 없으면 버튼이 없다').toBeNull();
    });
    const head = await inboxLink();
    await eventually(() => {
      expect(head).toHaveAccessibleName('알림');
    });
    expect(callsTo('POST', READ_ALL)).toHaveLength(1);
    await item('문의 제목 003번');
  });

  test('S4-AC-67 C-7 모두 읽음 실패: 오류 띠가 보이고 목록은 그대로다. 버튼을 다시 누를 수 있다', async () => {
    s4.inbox = [about(1), about(2, { readAt: READ_AT })];
    on('post', READ_ALL, () => errors.internal());
    const { user } = renderAt(URL_INBOX);
    const first = await item('문의 제목 001번');

    await user.click(await button(MARK_ALL));

    await alertWith(SERVER_DOWN);
    expect(text(first.closest('li'))).toContain('안 읽음');
    await item('문의 제목 002번');
    await eventually(() => {
      const again = markAll();
      expect(again !== null && !isBlocked(again), '"모두 읽음"을 다시 누를 수 있다').toBe(true);
    });
    await link('알림 1건');
  });

  test('S4-AC-65 S4-AC-67 C-7 빈 상태와 읽은 것뿐: 항목이 없으면 "알림이 없습니다". 안 읽은 것이 없으면 "모두 읽음" 버튼이 없다', async () => {
    const empty = renderAt(URL_INBOX);

    await eventually(() => {
      expect(mainText()).toContain('알림이 없습니다');
    });
    expect(text(await heading1())).toBe('알림함');
    expect(markAll()).toBeNull();
    expect(mainText()).not.toContain('이 쪽에는');
    empty.unmount();

    s4.inbox = [about(1, { readAt: READ_AT }), about(2, { readAt: READ_AT })];
    renderAt(URL_INBOX);
    await item('문의 제목 002번');
    expect(markAll()).toBeNull();
    expect(mainText()).not.toContain('알림이 없습니다');
  });

  test('S4-AC-65 C-7 쪽 넘기기: 2쪽 링크를 누르면 주소의 page에 싣고 그 쪽을 받아 보인다', async () => {
    s4.inbox = many(45);
    const { user } = renderAt(URL_INBOX);
    await item('문의 제목 001번');
    const size = Number(new URL(lists().at(-1)?.url ?? 'http://x/').searchParams.get('size') ?? '20');
    expect(size, '한 쪽의 크기').toBeLessThan(45);

    await user.click(await found(() => screen.queryByRole('link', { name: /^2(쪽)?$/ }), '2쪽 링크'));

    await eventually(() => {
      expect(addressPage()).toBe('2');
      expect(lastPage()).toBe('2');
    });
    await item(`문의 제목 ${String(size + 1).padStart(3, '0')}번`);
    expect(mainText()).not.toContain('문의 제목 001번');
    expect(window.location.pathname).toBe(URL_INBOX);
  });

  test('S4-AC-65 C-7 없는 쪽: "이 쪽에는 알림이 없습니다"와 "1쪽으로"', async () => {
    s4.inbox = many(3);
    renderAt(`${URL_INBOX}?page=2`);

    await eventually(() => {
      expect(mainText()).toContain('이 쪽에는 알림이 없습니다');
    });
    expect(lastPage()).toBe('2');
    const first = await link('1쪽으로');
    expect(hrefPath(first)).toBe(URL_INBOX);
    expect(new URL(first.href).searchParams.has('page')).toBe(false);
  });

  test('S4-AC-65 C-7 불러오기 실패: 띠와 "다시 불러오기". 누르면 목록이 나온다', async () => {
    s4.inbox = many(2);
    let broken = true;
    on('get', NOTIFICATIONS, ({ request }) => (broken ? errors.internal() : inboxPage(request)));
    const { user } = renderAt(URL_INBOX);

    await alertWith(SERVER_DOWN);
    expect(pageText()).not.toContain('문의 제목 001번');
    broken = false;
    await user.click(await button('다시 불러오기'));

    await item('문의 제목 001번');
    expect(screen.queryAllByRole('alert')).toHaveLength(0);
  });

  test('S4-AC-69 C-7 쪽을 넘긴 뒤 온 답: 2쪽의 답이 늦게 와도 그 사이에 돌아온 1쪽의 항목을 덮지 않는다', async () => {
    s4.inbox = many(45);
    const { user } = renderAt(URL_INBOX);
    await item('문의 제목 001번');
    const size = Number(new URL(lists().at(-1)?.url ?? 'http://x/').searchParams.get('size') ?? '20');
    const release = gate();
    let late = false;
    on('get', NOTIFICATIONS, async ({ request }) => {
      if (new URL(request.url).searchParams.get('page') !== '2') return inboxPage(request);
      await release.opened;
      late = true;
      return inboxPage(request);
    });

    await user.click(await found(() => screen.queryByRole('link', { name: /^2(쪽)?$/ }), '2쪽 링크'));
    await eventually(() => {
      expect(lastPage()).toBe('2');
    });
    // Back to the first page by the head's link, while page 2 is still on its way.
    await user.click(await inboxLink());
    await eventually(() => {
      expect(addressPage()).toBeNull();
    });
    await item('문의 제목 001번');

    release.open();
    await eventually(() => {
      expect(late).toBe(true);
    });
    await settle();
    await item('문의 제목 001번');
    expect(mainText()).not.toContain(`문의 제목 ${String(size + 1).padStart(3, '0')}번`);
    expect(addressPage()).toBeNull();
  });

  test('S4-AC-69 C-7 떠난 뒤 온 답: 화면을 떠난 뒤에 온 목록의 401은 주소를 /login으로 바꾸지 않는다', async () => {
    const release = gate();
    let late = false;
    on('get', NOTIFICATIONS, async () => {
      await release.opened;
      late = true;
      return errors.unauthenticated();
    });
    const { unmount } = renderAt(URL_INBOX);
    await sent('GET', NOTIFICATIONS);

    unmount();
    window.history.replaceState(null, '', '/invite');
    release.open();
    await eventually(() => {
      expect(late).toBe(true);
    });
    await settle();

    expect(window.location.pathname).toBe('/invite');
  });

  test('C-7 axe: 안 읽음과 읽음, 문의의 항목과 경보가 섞인 목록에 접근성 위반이 없다', async () => {
    s4.inbox = [
      about(1),
      about(2, { kind: 'MENTIONED', actor: OTHER_AGENT_PERSON, commentId: 12, readAt: READ_AT }),
      alertItem('DELIVERY_FAILED', { id: 801 }),
      alertItem('MEMBERSHIP_REQUESTED', { id: 802, readAt: READ_AT }),
      about(3, { kind: LATER_KIND, actor: CUSTOMER_PERSON }),
    ];
    renderAt(URL_INBOX);
    await item('문의 제목 003번');
    await button(MARK_ALL);

    await expectNoAxeViolations();
  });
});
