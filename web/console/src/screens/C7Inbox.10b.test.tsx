// 10b 묶음 screens.md "C-7 알림 문장": 문의가 없는 관리자 항목 셋(S10B-AC-27). 내보내기 둘은 "고객사 {코드} …"이고 그 고객사
// 상세로 가고, 자동 정리 첫날 항목은 수와 일수를 넣은 문장이고 휴지통(/trash)으로 간다. "누가"가 없고 "새 알림이 있습니다"도
// 아니다. 견본은 계약 검증을 지난다(console8b.ts의 inboxOf).
import { waitFor } from '@testing-library/react';
import { describe, expect, test } from 'vitest';
import { atPath, expectNoAxeViolations, hrefPath, renderAt, screen, sent, text, within } from '../__tests__/console';
import { NOTIFICATIONS, type Notification } from '../__tests__/console4';
import { URL_TRASH } from '../__tests__/console7a';
import { inboxOf } from '../__tests__/console8b';
import { armedItem, armedText, exportItem, failedText, readyText } from '../__tests__/console10b';

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

const ITEMS: { what: string; item: Notification; says: string; to: string }[] = [
  { what: 'COMPANY_EXPORT_READY', item: exportItem('COMPANY_EXPORT_READY', { id: 861 }), says: readyText('ACME'), to: '/companies/ACME' },
  { what: 'COMPANY_EXPORT_FAILED', item: exportItem('COMPANY_EXPORT_FAILED', { id: 862, companyCode: 'BETA' }), says: failedText('BETA'), to: '/companies/BETA' },
  { what: 'ISSUE_RETENTION_ARMED', item: armedItem(90, 37, { id: 863 }), says: armedText(90, 37), to: URL_TRASH },
];

describe('C-7 알림함의 내보내기와 자동 정리 항목 (10b 묶음)', () => {
  test.each(ITEMS)('S10B-AC-27 C-7 $what: "$says"이고 "누가"가 없으며 $to 로 간다', async ({ item, says, to }) => {
    inboxOf([item]);
    renderAt('/inbox');

    const a = await itemWith(says);
    expect(text(a), '누가 한 일').not.toContain('님이');
    expect(text(a), '시스템이 한 일').not.toContain('시스템이');
    expect(text(a)).not.toContain('새 알림이 있습니다');
    expect(text(a), '문의가 없는 항목').not.toMatch(/[A-Z]+-\d+/);
    expect(Date.parse(a.querySelector('time')?.getAttribute('datetime') ?? '')).toBe(Date.parse(item.createdAt));
    expect(hrefPath(a)).toBe(to);
    const at = new URL(a.href);
    expect([...at.searchParams.entries()], '주소의 조건').toEqual([]);
    expect(at.hash).toBe('');
  });

  test('S10B-AC-27 C-7 자동 정리 첫날 항목은 수와 일수를 그 항목의 값으로 쓴다(180일, 1건)', async () => {
    inboxOf([armedItem(180, 1, { id: 864 })]);
    renderAt('/inbox');

    const a = await itemWith(armedText(180, 1));
    expect(text(a)).not.toContain('90일');
  });

  test('S10B-AC-27 C-7 내보내기 끝남 항목을 누르면 읽음 요청을 보내고 그 고객사 상세로 간다. 화면에 axe 위반이 없다', async () => {
    inboxOf(ITEMS.map((i) => i.item));
    const { user } = renderAt('/inbox');
    const a = await itemWith(readyText('ACME'));
    await itemWith(failedText('BETA'));
    await itemWith(armedText(90, 37));
    await expectNoAxeViolations();

    await user.click(a);

    await sent('POST', `${NOTIFICATIONS}/861/read`);
    await atPath('/companies/ACME');
  });

  test('S10B-AC-27 C-7 자동 정리 첫날 항목을 누르면 읽음 요청을 보내고 휴지통으로 간다', async () => {
    inboxOf([armedItem(90, 37, { id: 863 })]);
    const { user } = renderAt('/inbox');
    const a = await itemWith(armedText(90, 37));

    await user.click(a);

    await sent('POST', `${NOTIFICATIONS}/863/read`);
    await atPath(URL_TRASH);
  });
});
