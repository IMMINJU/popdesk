// 8a 묶음 screens.md 4절 C-12 감사 로그: 종류 SLA_POLICY_CHANGED "SLA 정책 바꾸기"와 묶음 "설정"("보내기" 앞), 대상 "SLA 정책
// 버전 {version}", 바뀐 값 줄(목표는 바뀐 우선순위만, 업무 시간은 바뀐 요일만, 쉬는 날은 더함·뺌과 "외 {n}개")(S8A-AC-06).
import { describe, expect, test } from 'vitest';
import { expectNoAxeViolations, renderAt, text } from '../__tests__/console';
import { audit, column, entry, entryRows, kindSelect, listedKinds } from '../__tests__/console1b';
import { WEEKDAYS, hours, manyHolidays, onPolicy, target } from '../__tests__/console8a';

const URL_LOG = '/audit-log';
const BEFORE = onPolicy();
const SLA_TARGET = { type: 'SLA_POLICY', version: 4 };

/** An entry of 최관리 saving version 4 with `changes`. */
function policyChanged(changes: Record<string, unknown>, id = 8201) {
  return entry({ id, action: 'SLA_POLICY_CHANGED', target: SLA_TARGET, changes });
}

describe('C-12 감사 로그의 SLA 정책 항목 (8a 묶음)', () => {
  test('S8A-AC-06 C-12 일은 "SLA 정책 바꾸기"이고 대상은 "SLA 정책 버전 4"다', async () => {
    audit.entries = [policyChanged({ holidays: { from: ['2026-12-25'], to: [] } })];
    renderAt(URL_LOG);

    await entryRows(1);
    expect(column('일')).toEqual(['SLA 정책 바꾸기']);
    expect(column('대상')).toEqual(['SLA 정책 버전 4']);
    await expectNoAxeViolations();
  });

  test('S8A-AC-06 C-12 종류 고르기: 묶음 "설정"은 "보내기" 바로 앞이고 그 아래 "SLA 정책 바꾸기", "브랜딩 바꾸기", "로고 바꾸기" 차례다. 고르면 action이 그 셋이다', async () => {
    const { user } = renderAt(URL_LOG);
    await entryRows(audit.entries.length);
    const kind = await kindSelect();

    const groups = Array.from(kind.querySelectorAll('optgroup')).map((g) => g.label);
    expect(groups.indexOf('설정'), '"설정" 묶음').toBeGreaterThanOrEqual(0);
    expect(groups.indexOf('보내기'), '"보내기" 바로 앞').toBe(groups.indexOf('설정') + 1);
    const settings = Array.from(kind.querySelectorAll('optgroup')).find((g) => g.label === '설정');
    expect(Array.from(settings?.querySelectorAll('option') ?? []).map((o) => text(o))).toEqual(['SLA 정책 바꾸기', '브랜딩 바꾸기', '로고 바꾸기']);

    await user.selectOptions(kind, '설정');
    await listedKinds(['SLA_POLICY_CHANGED', 'BRANDING_CHANGED', 'BRANDING_LOGO_CHANGED']);
  });

  test('S8A-AC-06 C-12 바뀐 값의 목표: 바뀐 우선순위만 "{우선순위} 첫 응답 {앞} → {뒤}, 해결 {앞} → {뒤}"(분은 길이 글, 없으면 "없음")', async () => {
    // 하루(1440분)를 넘지 않는 값만 쓴다: 길이 글의 날 단위는 slaWords 단위 테스트가 본다.
    const before = [target('LOW', 480, 720), target('NORMAL', 240, 720), target('HIGH', 60, 480), target('URGENT', 20, 240)];
    const after = [target('LOW', 480, 720), target('NORMAL', 120, 600), target('HIGH', null, 480), target('URGENT', 20, 240)];
    audit.entries = [policyChanged({ targets: { from: before, to: after } })];
    renderAt(URL_LOG);

    await entryRows(1);
    const [shown] = column('바뀐 값');
    expect(shown).toContain('보통 첫 응답 4시간 → 2시간, 해결 12시간 → 10시간');
    expect(shown).toContain('높음 첫 응답 1시간 → 없음');
    expect(shown, '바뀐 우선순위 안의 그대로인 목표(높음 해결)').not.toMatch(/높음 첫 응답 1시간 → 없음, 해결/);
    expect(shown).not.toContain('8시간 → 8시간');
    expect(shown, '바뀌지 않은 우선순위').not.toContain('낮음');
    expect(shown).not.toContain('긴급');
  });

  test('S8A-AC-06 C-12 바뀐 값의 업무 시간: 바뀐 요일만 "{요일} {09:00–18:00} → {쉼}"', async () => {
    const after = WEEKDAYS.map((d) => (d === 'WEDNESDAY' ? hours(d, null, null) : (BEFORE.hours.find((h) => h.weekday === d) as (typeof BEFORE.hours)[number])));
    audit.entries = [policyChanged({ hours: { from: BEFORE.hours, to: after } })];
    renderAt(URL_LOG);

    await entryRows(1);
    const [shown] = column('바뀐 값');
    expect(shown).toMatch(/수(요일)? 09:00–18:00 → 쉼/);
    for (const day of ['월', '화', '목', '금', '토', '일']) {
      expect(shown, `바뀌지 않은 요일 ${day}`).not.toMatch(new RegExp(`(^|[^가-힣])${day}(요일)? `));
    }
  });

  test('S8A-AC-06 C-12 바뀐 값의 쉬는 날: "더함 {날짜들}, 뺌 {날짜들}"이고 열 개를 넘으면 "외 {n}개"', async () => {
    const added = manyHolidays(12);
    audit.entries = [
      policyChanged({ holidays: { from: ['2026-12-25'], to: ['2027-01-01'] } }, 8202),
      policyChanged({ holidays: { from: [], to: added } }, 8201),
    ];
    renderAt(URL_LOG);

    await entryRows(2);
    const [one, many] = column('바뀐 값');
    // 날짜는 2026-12-25 꼴만 받는다(screens.md 4절). 해를 빼는 꼴이 아니므로 테스트를 도는 해와 관계없다.
    expect(one).toContain('더함 2027-01-01, 뺌 2026-12-25');
    expect(many).toContain('더함 2027-01-01');
    expect(many, '열째 날짜까지는 적는다').toContain('2027-01-10');
    expect(many).toContain('외 2개');
    expect(many, '열한째 날짜는 "외"에 든다').not.toMatch(/2027-01-11|1월 11일/);
  });
});
