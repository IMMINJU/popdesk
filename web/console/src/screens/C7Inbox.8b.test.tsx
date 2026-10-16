// 8b 묶음 screens.md 1절 C-7 알림함의 SLA 알림 다섯 종류(S8B-AC-19). 문의 넷은 "누가" 없이 문장만 쓰고(시간이 지난 것이라
// 누가 한 일이 아니다), 있는 InboxList 꼴대로 문의 키와 제목이 함께 보이고, 누르면 /issues/{key}로 간다. 다시 계산 요약
// SLA_RECALCULATED는 문의가 없는 항목이고, 0인 수를 빼고 쉼표로 잇고(1,234건 꼴), 누르면 /issues?sort=slaDue로 간다.
// 잠긴 C7Inbox.test.tsx의 'SLA_WARNING'(모르는 종류)은 그대로 둔다(screens.md 0절). 견본은 계약 검증을 지난다.
import { waitFor } from '@testing-library/react';
import { describe, expect, test } from 'vitest';
import { atPath, expectNoAxeViolations, hrefPath, renderAt, screen, sent, text, within } from '../__tests__/console';
import { NOTIFICATIONS } from '../__tests__/console4';
import { RECALCULATED, SLA_ISSUE_KINDS, inboxOf, slaItem, summaryItem } from '../__tests__/console8b';

/** The link of the item whose line holds `part`, inside `main`, waited for generously. */
async function itemWith(part: string): Promise<HTMLAnchorElement> {
  let a: HTMLAnchorElement | undefined;
  await waitFor(
    () => {
      const main = screen.queryByRole('main');
      a = main ? within(main).queryAllByRole<HTMLAnchorElement>('link').find((l) => text(l).includes(part)) : undefined;
      expect(a, `알림 항목 "${part}"`).toBeDefined();
    },
    { timeout: 5000 },
  );
  return a as HTMLAnchorElement;
}

describe('C-7 알림함의 SLA 항목 (8b 묶음)', () => {
  test.each(SLA_ISSUE_KINDS)('S8B-AC-19 C-7 $kind: "$says"이고 "누가"가 없다. 문의 키와 제목이 함께 있고 /issues/ACME-7 로 간다', async ({ kind, says }) => {
    const item = slaItem(kind, { id: 851, issue: { key: 'ACME-7', title: '로그인이 안 됩니다' } });
    inboxOf([item]);
    renderAt('/inbox');

    const a = await itemWith(says);
    expect(text(a)).toContain('ACME-7');
    expect(text(a)).toContain('로그인이 안 됩니다');
    expect(text(a), '누가 한 일').not.toContain('님이');
    expect(text(a), '시스템이 한 일').not.toContain('시스템이');
    expect(text(a)).not.toContain('새 알림이 있습니다');
    expect(Date.parse(a.querySelector('time')?.getAttribute('datetime') ?? '')).toBe(Date.parse(item.createdAt));
    expect(hrefPath(a)).toBe('/issues/ACME-7');
    expect(new URL(a.href).hash, '댓글이나 할 일로 가지 않는다').toBe('');
  });

  test('S8B-AC-19 C-7 문의 항목(해결 넘김)을 누르면 읽음 요청을 보내고 그 문의로 간다. 화면에 axe 위반이 없다', async () => {
    inboxOf([slaItem('SLA_RESOLUTION_BREACHED', { id: 852 }), summaryItem({ becameBreached: 3, becameWarning: 2, released: 1 }, { id: 853 })]);
    const { user } = renderAt('/inbox');
    const a = await itemWith('해결 기한을 넘겼습니다');
    await itemWith(RECALCULATED);
    await expectNoAxeViolations();

    await user.click(a);

    await sent('POST', `${NOTIFICATIONS}/852/read`);
    await atPath('/issues/ACME-7');
  });

  test.each([
    { what: '셋 다', summary: { becameBreached: 3, becameWarning: 2, released: 1 }, says: 'SLA 기한을 다시 계산했습니다: 새로 넘김 3건, 새로 곧 넘김 2건, 넘김·곧 넘김에서 벗어남 1건' },
    { what: '새로 넘김 0', summary: { becameBreached: 0, becameWarning: 5, released: 2 }, says: 'SLA 기한을 다시 계산했습니다: 새로 곧 넘김 5건, 넘김·곧 넘김에서 벗어남 2건' },
    { what: '벗어남만', summary: { becameBreached: 0, becameWarning: 0, released: 7 }, says: 'SLA 기한을 다시 계산했습니다: 넘김·곧 넘김에서 벗어남 7건' },
    { what: '1,234건', summary: { becameBreached: 1234, becameWarning: 0, released: 0 }, says: 'SLA 기한을 다시 계산했습니다: 새로 넘김 1,234건' },
  ])('S8B-AC-19 C-7 SLA_RECALCULATED($what): "$says"이고 0인 수는 쓰지 않는다. 문의가 없는 항목이고 /issues?sort=slaDue 로 간다', async ({ summary, says }) => {
    const item = summaryItem(summary, { id: 855 });
    inboxOf([item]);
    renderAt('/inbox');

    const a = await itemWith(RECALCULATED);
    expect(text(a)).toContain(says);
    expect(text(a), '0인 수').not.toMatch(/[^\d,]0건/);
    expect(text(a), '문의가 없는 항목').not.toMatch(/[A-Z]+-\d+/);
    expect(text(a)).not.toContain('정책이 바뀌어');
    expect(Date.parse(a.querySelector('time')?.getAttribute('datetime') ?? '')).toBe(Date.parse(item.createdAt));
    const to = new URL(a.href);
    expect(to.pathname).toBe('/issues');
    expect([...to.searchParams.entries()]).toEqual([['sort', 'slaDue']]);
    expect(to.hash).toBe('');
  });

  test('S8B-AC-19 C-7 SLA_RECALCULATED를 누르면 읽음 요청을 보내고 기한순 문의 목록(/issues?sort=slaDue)으로 간다', async () => {
    inboxOf([summaryItem({ becameBreached: 1, becameWarning: 0, released: 0 }, { id: 856 })]);
    const { user } = renderAt('/inbox');
    const a = await itemWith(RECALCULATED);

    await user.click(a);

    await sent('POST', `${NOTIFICATIONS}/856/read`);
    await atPath('/issues');
    expect(new URLSearchParams(window.location.search).get('sort')).toBe('slaDue');
  });
});
