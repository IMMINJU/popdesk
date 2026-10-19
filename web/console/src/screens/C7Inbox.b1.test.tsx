// b1 묶음 screens.md 3절 C-7 알림함: 안 읽은 알림 하나를 열면 읽음 요청이 성공한 뒤에 머리의 안 읽은 수를 한 번 다시 묻는다.
// 읽음 요청이 끝나기 전에는 묻지 않는다. 실패(500)면 묻지 않고 띠도 없다. 이미 읽은 알림은 읽음 요청도 다시 묻기도 없다.
// 옮겨 가기가 읽음 답을 기다리지 않는 것(S4-AC-66)은 C7Inbox.test.tsx가 본다.
// 1분 조회가 끼어들지 않게 가짜 시계(useFakeClock)를 쓰고, 시간을 옮기지 않는다.
import { describe, expect, test } from 'vitest';
import { ADMIN_PERSON, atPath, callsTo, errors, eventually, expectNoAxeViolations, found, gate, on, renderAt, screen, sent, text, useFakeClock, within } from '../__tests__/console';
import { answered, settle } from '../__tests__/console1a';
import { NOTIFICATIONS, UNREAD, inboxLink, noContent, notification, s4 } from '../__tests__/console4';

const URL_INBOX = '/inbox';
const READ_AT = '2026-10-08T04:00:00Z';
const ISSUE_TITLE = '로그인이 안 됩니다';

type User = ReturnType<typeof renderAt>['user'];

const main = () => screen.queryByRole('main');
const asked = () => callsTo('GET', UNREAD).length;
const readPath = (id: number) => `${NOTIFICATIONS}/${String(id)}/read`;

/** The link of the item whose line holds `part`, inside `main`. */
function item(part: string): Promise<HTMLAnchorElement> {
  return found(() => {
    const root = main();
    if (!root) return null;
    return within(root)
      .queryAllByRole<HTMLAnchorElement>('link')
      .find((a) => text(a).includes(part));
  }, `알림 항목 ${part}`);
}

/** C-7 drawn with the head's first count in (after `useFakeClock`): returns the number of count requests so far. */
async function inboxShown(part: string): Promise<{ user: User; counted: number }> {
  const { user } = renderAt(URL_INBOX, { fakeTimers: true });
  await item(part);
  await sent('GET', UNREAD);
  await settle();
  return { user, counted: asked() };
}

/** Back to C-7 through the head's link, its list drawn again. */
async function backToInbox(user: User, part: string) {
  await user.click(await inboxLink());
  await atPath(URL_INBOX);
  await item(part);
  await settle();
}

describe('C-7 알림 하나를 읽은 뒤 머리의 수 (B1-AC-04)', () => {
  test('B1-AC-04 C-7 안 읽은 알림을 열면: 읽음 요청이 끝나기 전에는 수를 다시 묻지 않고, 성공(204)한 뒤 한 번 묻는다', async () => {
    s4.inbox = [notification({ id: 501, kind: 'ASSIGNED', actor: ADMIN_PERSON })];
    const release = gate();
    on('post', `${NOTIFICATIONS}/:id/read`, async () => {
      await release.opened;
      return noContent();
    });
    useFakeClock();
    const { user, counted } = await inboxShown('최관리 님이 이 문의를 나에게 맡겼습니다');
    await expectNoAxeViolations();

    await user.click(await item('최관리 님이 이 문의를 나에게 맡겼습니다'));
    await sent('POST', readPath(501));
    await atPath('/issues/ACME-7');
    await found(() => screen.queryByRole('heading', { level: 1, name: ISSUE_TITLE }), '문의 상세의 제목');
    await settle();
    expect(asked(), '읽음 답이 오기 전').toBe(counted);

    const done = answered('POST', readPath(501));
    release.open();
    await done;
    await eventually(() => {
      expect(asked(), '읽음이 성공한 뒤').toBe(counted + 1);
    });
    await settle();
    expect(asked(), '한 번만 다시 묻는다').toBe(counted + 1);
    expect(window.location.pathname, '옮겨 간 화면에 남는다').toBe('/issues/ACME-7');
  });

  test('B1-AC-04 C-7 읽음 요청이 실패(500)하면 수를 다시 묻지 않고 띠도 없다. 다음 알림의 읽음이 성공하면 그때 한 번 묻는다', async () => {
    s4.inbox = [
      notification({ id: 601, kind: 'ASSIGNED', actor: ADMIN_PERSON }),
      notification({ id: 602, kind: 'CUSTOMER_COMMENT', commentId: 12, issue: { key: 'ACME-7', title: ISSUE_TITLE } }),
    ];
    on('post', `${NOTIFICATIONS}/:id/read`, ({ params }) => (params.id === '601' ? errors.internal() : noContent()));
    useFakeClock();
    const { user, counted } = await inboxShown('최관리 님이 이 문의를 나에게 맡겼습니다');

    const failed = answered('POST', readPath(601));
    await user.click(await item('최관리 님이 이 문의를 나에게 맡겼습니다'));
    await failed;
    await atPath('/issues/ACME-7');
    await found(() => screen.queryByRole('heading', { level: 1, name: ISSUE_TITLE }), '문의 상세의 제목');
    await settle();
    expect(asked(), '읽음이 실패한 뒤').toBe(counted);
    expect(screen.queryAllByRole('alert'), '실패를 띠로 알리지 않는다').toHaveLength(0);

    await backToInbox(user, '김고객 님이');
    const before = asked();
    const done = answered('POST', readPath(602));
    await user.click(await item('김고객 님이'));
    await done;
    await eventually(() => {
      expect(asked(), '다음 알림의 읽음이 성공한 뒤').toBe(before + 1);
    });
  });

  test('B1-AC-04 C-7 이미 읽은 알림을 열면 읽음 요청도 다시 묻기도 없다. 안 읽은 알림을 열면 둘 다 있다', async () => {
    s4.inbox = [
      notification({ id: 701, kind: 'ASSIGNED', actor: ADMIN_PERSON, readAt: READ_AT }),
      notification({ id: 702, kind: 'CUSTOMER_COMMENT', commentId: 12, issue: { key: 'ACME-7', title: ISSUE_TITLE } }),
    ];
    useFakeClock();
    const { user, counted } = await inboxShown('최관리 님이 이 문의를 나에게 맡겼습니다');

    await user.click(await item('최관리 님이 이 문의를 나에게 맡겼습니다'));
    await atPath('/issues/ACME-7');
    await found(() => screen.queryByRole('heading', { level: 1, name: ISSUE_TITLE }), '문의 상세의 제목');
    await settle();
    expect(callsTo('POST', /\/api\/console\/notifications\/\d+\/read$/), '읽은 알림의 읽음 요청').toHaveLength(0);
    expect(asked(), '읽은 알림을 연 뒤').toBe(counted);

    await backToInbox(user, '김고객 님이');
    const before = asked();
    const done = answered('POST', readPath(702));
    await user.click(await item('김고객 님이'));
    await done;
    await eventually(() => {
      expect(asked(), '안 읽은 알림의 읽음이 성공한 뒤').toBe(before + 1);
    });
    expect(callsTo('POST', readPath(701)), '읽은 알림은 끝까지 요청하지 않는다').toHaveLength(0);
  });
});
