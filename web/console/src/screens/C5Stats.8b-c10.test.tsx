// 8b 묶음 C-10에서 더한 테스트. 뮤테이션 테스트가 C5Stats.8b.test.tsx로 잡히지 않는 빈틈 둘을 찾았다.
// 1) 지킴 줄의 두 수는 toLocaleString('ko')로 쓴다(screens.md 4절 6, "1,234/1,250"). 분모만 1,000을 넘는 때도 본다.
// 2) 정책이 꺼져 있으면 주소의 days를 고치지 않는다(screens.md 4절 7, 2절). 모르는 값 14도, 켜져 있으면 빼는 30도 그대로 둔다.
import { describe, expect, test } from 'vitest';
import { eventually, pageText, renderAt, text, within } from '../__tests__/console';
import { settle } from '../__tests__/console1a';
import { COLUMNS, NOTICE, URL_STATS, stat, statsRow, statsTable } from '../__tests__/console7b';
import { PERIOD, SLA_COLUMNS, SLA_NOTICE, addressDays, goal, linesOf, periodNow, s8b, slaCellOf, slaStat } from '../__tests__/console8b';

function notices(): string[] {
  return Array.from(document.querySelectorAll<HTMLElement>('main p'))
    .map((p) => text(p))
    .filter((t) => t === NOTICE || t === SLA_NOTICE);
}

describe('C-5 지킴 줄의 수 모양 (8b 묶음, C-10)', () => {
  test.each([
    { what: '두 수 모두 1,000 넘음', met: 1234, missed: 16, line: '지킴 98% (1,234/1,250)' },
    { what: '분모만 1,000', met: 999, missed: 1, line: '지킴 99% (999/1,000)' },
  ])('S8B-AC-26 C-5 지킴 줄의 수는 자리 쉼표로 쓴다($what): "$line"', async ({ met, missed, line }) => {
    s8b.byDays['30'] = [slaStat({ firstResponse: goal(60, met, missed), resolution: goal(60, 1, 0) })];
    renderAt(URL_STATS);

    const row = await statsRow('ACME');
    await eventually(() => {
      expect(linesOf(slaCellOf(row, '첫 응답 SLA'))).toEqual(['중앙값 1시간', line]);
    });
  });
});

describe('C-5 정책이 꺼져 있을 때의 주소 (8b 묶음, C-10)', () => {
  test.each([
    { what: '모르는 값', days: '14' },
    { what: '기본값', days: '30' },
  ])('S8B-AC-26 C-5 정책이 꺼져 있으면 ?days=$days($what)로 열어도 주소의 days를 고치지 않고, 기간 고르기와 SLA 열이 없다', async ({ days }) => {
    s8b.byDays = { 7: [stat()], 30: [stat()], 90: [stat()] };
    const before = window.history.length;
    renderAt(`${URL_STATS}?days=${days}`);

    const table = await statsTable(1);
    await statsRow('ACME');
    await settle();

    expect(addressDays(), '주소의 days').toBe(days);
    expect(window.location.pathname).toBe(URL_STATS);
    expect(window.history.length, '새 기록').toBe(before);
    expect(within(table).getAllByRole('columnheader').map((h) => text(h))).toEqual([...COLUMNS]);
    for (const c of SLA_COLUMNS) expect(pageText()).not.toContain(c);
    expect(periodNow(), `고르기 "${PERIOD}"`).toBeNull();
    expect(pageText()).not.toContain(PERIOD);
    expect(notices()).toEqual([NOTICE]);
  });
});
