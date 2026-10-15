// 7a 묶음 screens.md 3절 C-7 알림함: 문의 항목인데 일으킨 사람(actor)이 null이면 popdesk 자신이 한 일이라 {who}가
// "시스템이"다(S7A-AC-52). 7일 뒤 자동 종료가 담당자에게 보내는 두 항목의 꼴이다. ISSUE_CLOSED는 잠긴
// C7Inbox.test.tsx의 줄, LINKED_ISSUE_RESOLVED는 C7Inbox.6c.test.tsx의 줄이 본다.
import { waitFor } from '@testing-library/react';
import { describe, expect, test } from 'vitest';
import { HttpResponse } from 'msw/http';
import { on, paged, renderAt, screen, text, within } from '../__tests__/console';
import { NOTIFICATIONS, notification } from '../__tests__/console4';
import { contract } from '../__tests__/console6a';

describe('C-7 알림함의 시스템 항목 (7a 묶음)', () => {
  test('S7A-AC-52 C-7 자동 종료가 연결된 문의를 닫은 LINKED_ISSUE_CLOSED(actor null): "시스템이 연결된 문의를 종료했습니다: BETA-3 …"', async () => {
    const item = notification({ id: 703, kind: 'LINKED_ISSUE_CLOSED', actor: null, commentId: null, task: null, linkedIssue: { key: 'BETA-3', title: '결제가 두 번 됩니다' } });
    on('get', NOTIFICATIONS, () => HttpResponse.json(contract('listNotifications', 200, paged([item]))));
    renderAt('/inbox');

    let found: HTMLAnchorElement | undefined;
    await waitFor(
      () => {
        const main = screen.queryByRole('main');
        found = main ? within(main).queryAllByRole<HTMLAnchorElement>('link').find((a) => text(a).includes('연결된 문의를 종료했습니다')) : undefined;
        expect(found, '알림 항목').toBeDefined();
      },
      { timeout: 5000 },
    );
    expect(text(found)).toContain('시스템이 연결된 문의를 종료했습니다: BETA-3 결제가 두 번 됩니다');
    expect(text(found)).not.toContain('누군가');
  });
});
