// 6b 묶음 screens.md 4절 C-7 알림함: TASK_ASSIGNED의 문장 "{who} 작업을 나에게 맡겼습니다: {작업 제목}"과
// 그 작업으로 가는 링크 /issues/{key}#task-{id}(S6B-AC-88).
import { waitFor } from '@testing-library/react';
import { describe, expect, test } from 'vitest';
import { HttpResponse } from 'msw/http';
import { ADMIN_PERSON, atPath, expectNoAxeViolations, on, paged, renderAt, screen, sent, text, within } from '../__tests__/console';
import { NOTIFICATIONS, notification } from '../__tests__/console4';
import { contract } from '../__tests__/console6a';

const ITEM = notification({ id: 701, kind: 'TASK_ASSIGNED', actor: ADMIN_PERSON, commentId: null, task: { id: 41, title: '로그 모으기' } });
const SAYS = '최관리 님이 작업을 나에게 맡겼습니다: 로그 모으기';

/** The inbox holds the one task item, answered through the contract check. */
function showItem() {
  on('get', NOTIFICATIONS, () => HttpResponse.json(contract('listNotifications', 200, paged([ITEM]))));
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

describe('C-7 알림함 작업 알림 (6b 묶음)', () => {
  test('S6B-AC-88 C-7 TASK_ASSIGNED: "최관리 님이 작업을 나에게 맡겼습니다: 로그 모으기"와 문의 키·제목이 보인다', async () => {
    showItem();
    renderAt('/inbox');

    const a = await item(SAYS);
    expect(text(a)).toContain('ACME-7');
    expect(text(a)).toContain('로그인이 안 됩니다');
    expect(text(a), '모르는 종류의 문장').not.toContain('새 알림이 있습니다');
    await expectNoAxeViolations();
  });

  test('S6B-AC-88 C-7 TASK_ASSIGNED 누르기: 읽음을 보내고 /issues/ACME-7#task-41로 간다', async () => {
    showItem();
    const { user } = renderAt('/inbox');
    const a = await item(SAYS);
    expect(new URL(a.href).pathname + new URL(a.href).hash).toBe('/issues/ACME-7#task-41');

    await user.click(a);

    await sent('POST', `${NOTIFICATIONS}/701/read`);
    await atPath('/issues/ACME-7');
    expect(window.location.hash).toBe('#task-41');
  });
});
