// 7b 묶음 screens.md 3절 C-7 알림함: 정리 작업이 주기 + 24시간 넘게 성공하지 못할 때의 경보 CLEANUP_STALLED(S7B-AC-12).
// 4b의 운영 경보 셋(C7Inbox.4b.test.tsx)과 같은 꼴이다: 문의가 없는 항목이고 누르면 읽음 요청을 보내고 시스템 상태로 간다.
// 잠긴 4b의 표에 줄을 더하지 않고 이 파일에 둔다: 기준 ID가 S7B-AC-12이고 견본은 계약 검증을 지나야 한다.
import { HttpResponse } from 'msw/http';
import { waitFor } from '@testing-library/react';
import { describe, expect, test } from 'vitest';
import { atPath, expectNoAxeViolations, hrefPath, on, paged, renderAt, screen, sent, text, within } from '../__tests__/console';
import { NOTIFICATIONS, alertItem } from '../__tests__/console4';
import { contract } from '../__tests__/console6a';

const SAYS = '정리 작업이 하루 넘게 돌지 않았습니다';

describe('C-7 알림함의 정리 작업 경보 (7b 묶음)', () => {
  test('S7B-AC-12 C-7 경보 CLEANUP_STALLED: "정리 작업이 하루 넘게 돌지 않았습니다"이고, 누르면 읽음 요청을 보내고 /system 으로 간다', async () => {
    const item = alertItem('CLEANUP_STALLED', { id: 821 });
    on('get', NOTIFICATIONS, () => HttpResponse.json(contract('listNotifications', 200, paged([item]))));
    const { user } = renderAt('/inbox');

    let a: HTMLAnchorElement | undefined;
    await waitFor(
      () => {
        const main = screen.queryByRole('main');
        a = main ? within(main).queryAllByRole<HTMLAnchorElement>('link').find((l) => text(l).includes(SAYS)) : undefined;
        expect(a, `알림 항목 "${SAYS}"`).toBeDefined();
      },
      { timeout: 5000 },
    );
    const link = a as HTMLAnchorElement;
    expect(text(link), '문의가 없는 항목').not.toMatch(/[A-Z]+-\d+/);
    expect(Date.parse(link.querySelector('time')?.getAttribute('datetime') ?? '')).toBe(Date.parse(item.createdAt));
    expect(hrefPath(link)).toBe('/system');
    expect(new URL(link.href).hash).toBe('');
    await expectNoAxeViolations();

    await user.click(link);

    await sent('POST', `${NOTIFICATIONS}/821/read`);
    await atPath('/system');
  });
});
