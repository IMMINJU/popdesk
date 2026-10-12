// 4b screens.md 6절 6 C-7 알림함: 한 채널의 보내기가 한 시간 넘게 실패할 때의 경보 셋(S4B-AC-29).
// 있던 운영 경보 둘과 같은 꼴이다: 문의가 없는 항목이고 누르면 시스템 상태로 간다.
import { describe, expect, test } from 'vitest';
import { atPath, found, hrefPath, renderAt, screen, sent, text, within } from '../__tests__/console';
import { NOTIFICATIONS, alertItem, s4 } from '../__tests__/console4';

const URL_INBOX = '/inbox';

/** The link of the item whose line holds `part`, inside `main`. */
function item(part: string): Promise<HTMLAnchorElement> {
  return found(() => {
    const root = screen.queryByRole('main');
    if (!root) return null;
    return within(root)
      .queryAllByRole<HTMLAnchorElement>('link')
      .find((a) => text(a).includes(part));
  }, `알림 항목 ${part}`);
}

describe('C-7 알림함 (4b)', () => {
  test.each([
    { kind: 'MAIL_STALLED', says: '메일 보내기가 한 시간 넘게 실패하고 있습니다' },
    { kind: 'WEBHOOK_STALLED', says: '웹훅 보내기가 한 시간 넘게 실패하고 있습니다' },
    { kind: 'AUDIT_FORWARD_STALLED', says: '감사 바깥 사본 보내기가 한 시간 넘게 실패하고 있습니다' },
  ] as const)('S4B-AC-29 C-7 경보 $kind: "$says"이고, 누르면 읽음 요청을 보내고 /system 으로 간다', async ({ kind, says }) => {
    s4.inbox = [alertItem(kind, { id: 811 })];
    const { user } = renderAt(URL_INBOX);

    const a = await item(says);
    expect(text(a), '문의가 없는 항목').not.toMatch(/[A-Z]+-\d+/);
    expect(Date.parse(a.querySelector('time')?.getAttribute('datetime') ?? '')).toBe(Date.parse(s4.inbox[0]?.createdAt ?? ''));
    expect(hrefPath(a)).toBe('/system');
    expect(new URL(a.href).hash).toBe('');

    await user.click(a);

    await sent('POST', `${NOTIFICATIONS}/811/read`);
    await atPath('/system');
  });
});
