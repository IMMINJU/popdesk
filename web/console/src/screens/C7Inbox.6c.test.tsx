// 6c 묶음 screens.md 3절 C-7 알림함: 연결된 문의가 해결·종료됐다는 항목(LINKED_ISSUE_RESOLVED, LINKED_ISSUE_CLOSED)의
// 문장 "{who} 연결된 문의를 해결했습니다: {키} {제목}"과, 누르면 바뀐 문의(linkedIssue)로 가는 링크(S6C-AC-54).
// 항목 아래의 문의는 담당자의 문의(issue)다.
import { waitFor } from '@testing-library/react';
import { describe, expect, test } from 'vitest';
import { HttpResponse } from 'msw/http';
import { CUSTOMER_PERSON, OTHER_AGENT_PERSON, atPath, expectNoAxeViolations, on, paged, renderAt, screen, sent, text, within } from '../__tests__/console';
import { NOTIFICATIONS, notification, type Notification } from '../__tests__/console4';
import { contract } from '../__tests__/console6a';

const CHANGED = { key: 'BETA-3', title: '결제가 두 번 됩니다' };

function linkedItem(over: Partial<Notification>): Notification {
  return notification({ id: 702, kind: 'LINKED_ISSUE_RESOLVED', actor: OTHER_AGENT_PERSON, commentId: null, task: null, linkedIssue: CHANGED, ...over });
}

/** The inbox holds `items`, answered through the contract check. */
function showItems(items: Notification[]) {
  on('get', NOTIFICATIONS, () => HttpResponse.json(contract('listNotifications', 200, paged(items))));
}

/** The link of the item whose text holds `part`, inside `main`, waited for generously. */
async function item(part: string): Promise<HTMLAnchorElement> {
  let found: HTMLAnchorElement | undefined;
  await waitFor(
    () => {
      const main = screen.queryByRole('main');
      found = main ? within(main).queryAllByRole<HTMLAnchorElement>('link').find((a) => text(a).includes(part)) : undefined;
      expect(found, `알림 항목 ${part}`).toBeDefined();
    },
    { timeout: 5000 },
  );
  return found as HTMLAnchorElement;
}

describe('C-7 알림함 연결 알림 (6c 묶음)', () => {
  test.each([
    { what: 'LINKED_ISSUE_RESOLVED', given: linkedItem({}), says: '정상담 님이 연결된 문의를 해결했습니다: BETA-3 결제가 두 번 됩니다' },
    { what: 'LINKED_ISSUE_CLOSED', given: linkedItem({ kind: 'LINKED_ISSUE_CLOSED' }), says: '정상담 님이 연결된 문의를 종료했습니다: BETA-3 결제가 두 번 됩니다' },
    {
      what: '고객이 닫은 LINKED_ISSUE_CLOSED',
      given: linkedItem({ kind: 'LINKED_ISSUE_CLOSED', actor: CUSTOMER_PERSON }),
      says: '김고객 님이 연결된 문의를 종료했습니다: BETA-3 결제가 두 번 됩니다',
    },
    { what: '일으킨 사람이 null', given: linkedItem({ actor: null }), says: '누군가 연결된 문의를 해결했습니다: BETA-3 결제가 두 번 됩니다' },
  ])('S6C-AC-54 C-7 $what: "$says"이고 항목 아래의 문의는 담당자의 문의(ACME-7)다', async ({ given, says }) => {
    showItems([given]);
    renderAt('/inbox');

    const a = await item(says);
    expect(text(a)).toContain('ACME-7');
    expect(text(a)).toContain('로그인이 안 됩니다');
    expect(text(a), '모르는 종류의 문장').not.toContain('새 알림이 있습니다');
  });

  test('S6C-AC-54 C-7 연결 알림 누르기: 읽음을 보내고 바뀐 문의 /issues/BETA-3으로 간다', async () => {
    showItems([linkedItem({})]);
    const { user } = renderAt('/inbox');
    const a = await item('연결된 문의를 해결했습니다');
    expect(new URL(a.href).pathname).toBe('/issues/BETA-3');
    await expectNoAxeViolations();

    await user.click(a);

    await sent('POST', `${NOTIFICATIONS}/702/read`);
    await atPath('/issues/BETA-3');
  });
});
