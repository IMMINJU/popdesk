// 12 묶음 screens.md "C-7 알림함": 메일 받기의 경보 둘 MAIL_IN_STALLED, MAIL_IN_QUARANTINED(S12-AC-39).
// 앞 운영 경보(C7Inbox.4b.test.tsx, C7Inbox.7b.test.tsx)와 같은 꼴이다: 문의가 없는 항목이고 누르면 읽음 요청을 보내고 시스템 상태로 간다.
import { HttpResponse } from 'msw/http';
import { waitFor } from '@testing-library/react';
import { describe, expect, test } from 'vitest';
import { atPath, expectNoAxeViolations, hrefPath, on, paged, renderAt, screen, sent, text, within } from '../__tests__/console';
import { NOTIFICATIONS, alertItem } from '../__tests__/console4';
import { contract } from '../__tests__/console6a';

describe('C-7 알림함의 메일 받기 경보 (12 묶음)', () => {
  test.each([
    { kind: 'MAIL_IN_STALLED', says: '메일 받기가 한 시간 넘게 실패하고 있습니다', id: 831 },
    { kind: 'MAIL_IN_QUARANTINED', says: '격리한 받은 메일이 있습니다', id: 832 },
  ] as const)('S12-AC-39 C-7 경보 $kind: "$says"이고, 누르면 읽음 요청을 보내고 /system 으로 간다', async ({ kind, says, id }) => {
    const item = alertItem(kind, { id });
    on('get', NOTIFICATIONS, () => HttpResponse.json(contract('listNotifications', 200, paged([item]))));
    const { user } = renderAt('/inbox');

    let a: HTMLAnchorElement | undefined;
    await waitFor(
      () => {
        const main = screen.queryByRole('main');
        a = main ? within(main).queryAllByRole<HTMLAnchorElement>('link').find((l) => text(l).includes(says)) : undefined;
        expect(a, `알림 항목 "${says}"`).toBeDefined();
      },
      { timeout: 5000 },
    );
    const link = a as HTMLAnchorElement;
    expect(text(link), '문의가 없는 항목').not.toMatch(/[A-Z]+-\d+/);
    expect(text(link), '종류 이름 그대로').not.toContain(kind);
    expect(Date.parse(link.querySelector('time')?.getAttribute('datetime') ?? '')).toBe(Date.parse(item.createdAt));
    expect(hrefPath(link)).toBe('/system');
    expect(new URL(link.href).hash).toBe('');

    await user.click(link);

    await sent('POST', `${NOTIFICATIONS}/${String(id)}/read`);
    await atPath('/system');
  });

  test('S12-AC-39 C-7 axe: 메일 받기 경보 둘이 있는 알림함에 접근성 위반이 없다', async () => {
    const items = [alertItem('MAIL_IN_QUARANTINED', { id: 842 }), alertItem('MAIL_IN_STALLED', { id: 841 })];
    on('get', NOTIFICATIONS, () => HttpResponse.json(contract('listNotifications', 200, paged(items))));
    renderAt('/inbox');

    await waitFor(
      () => {
        const main = screen.queryByRole('main');
        expect(main && text(main), '경보 둘').toContain('격리한 받은 메일이 있습니다');
        expect(main && text(main), '경보 둘').toContain('메일 받기가 한 시간 넘게 실패하고 있습니다');
      },
      { timeout: 5000 },
    );

    await expectNoAxeViolations();
  });
});
