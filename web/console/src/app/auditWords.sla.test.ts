// 8a screens.md 4절: C-12 감사 로그의 SLA_POLICY_CHANGED 바뀐 값을 부분(목표, 업무 시간, 쉬는 날)마다 한 줄로 쓴다.
// 본문을 읽지 않고 시그니처와 주석으로 썼다(C-10).
import { describe, expect, test } from 'vitest';
import { changeLines } from './auditWords';

const ACTION = 'SLA_POLICY_CHANGED';

type Target = { priority: 'LOW' | 'NORMAL' | 'HIGH' | 'URGENT'; firstResponseMinutes: number | null; resolutionMinutes: number | null };
type Hours = { weekday: string; opens: string; closes: string };

const target = (priority: Target['priority'], firstResponseMinutes: number | null, resolutionMinutes: number | null): Target => ({
  priority,
  firstResponseMinutes,
  resolutionMinutes,
});

const TARGETS: Target[] = [target('LOW', 480, 2880), target('NORMAL', 240, 720), target('HIGH', 60, 240), target('URGENT', 30, 120)];

/** TARGETS with the given priorities replaced. */
function targetsWith(...changed: Target[]): Target[] {
  return TARGETS.map((t) => changed.find((c) => c.priority === t.priority) ?? t);
}

const day = (weekday: string, opens = '09:00', closes = '18:00'): Hours => ({ weekday, opens, closes });
const WEEK: Hours[] = ['MONDAY', 'TUESDAY', 'WEDNESDAY', 'THURSDAY', 'FRIDAY'].map((w) => day(w));

function targetsLine(from: unknown, to: unknown): string[] {
  return changeLines(ACTION, { targets: { from, to } });
}
function hoursLine(from: unknown, to: unknown): string[] {
  return changeLines(ACTION, { hours: { from, to } });
}
function holidaysLine(from: unknown, to: unknown): string[] {
  return changeLines(ACTION, { holidays: { from, to } });
}

describe('changeLines SLA_POLICY_CHANGED 목표', () => {
  test('C-10 목표 둘 다 바뀐 우선순위 하나: "보통 첫 응답 4시간 → 2시간, 해결 12시간 → 10시간"', () => {
    expect(targetsLine(TARGETS, targetsWith(target('NORMAL', 120, 600)))).toEqual(['목표: 보통 첫 응답 4시간 → 2시간, 해결 12시간 → 10시간']);
  });

  test('C-10 첫 응답만 바뀌면 첫 응답만', () => {
    expect(targetsLine(TARGETS, targetsWith(target('NORMAL', 120, 720)))).toEqual(['목표: 보통 첫 응답 4시간 → 2시간']);
  });

  test('C-10 해결만 바뀌면 해결만', () => {
    expect(targetsLine(TARGETS, targetsWith(target('LOW', 480, 3120)))).toEqual(['목표: 낮음 해결 2일 → 2일 4시간']);
  });

  test('C-10 목표가 없어지면 "→ 없음", 생기면 "없음 →"', () => {
    expect(targetsLine(TARGETS, targetsWith(target('HIGH', null, 240)))).toEqual(['목표: 높음 첫 응답 1시간 → 없음']);
    expect(targetsLine(targetsWith(target('URGENT', null, null)), TARGETS)).toEqual(['목표: 긴급 첫 응답 없음 → 30분, 해결 없음 → 2시간']);
  });

  test('C-10 바뀐 우선순위가 여럿이면 " · "로 잇고, 바뀌지 않은 우선순위는 빠진다', () => {
    const to = targetsWith(target('NORMAL', 120, 600), target('HIGH', null, 240));
    const lines = targetsLine(TARGETS, to);
    expect(lines).toEqual(['목표: 보통 첫 응답 4시간 → 2시간, 해결 12시간 → 10시간 · 높음 첫 응답 1시간 → 없음']);
    expect(lines[0]).not.toContain('낮음');
    expect(lines[0]).not.toContain('긴급');
  });

  test('C-10 우선순위의 차례는 "to" 목록의 차례다', () => {
    const to = [target('URGENT', 20, 120), target('HIGH', 60, 240), target('NORMAL', 240, 720), target('LOW', 600, 2880)];
    expect(targetsLine(TARGETS, to)).toEqual(['목표: 긴급 첫 응답 30분 → 20분 · 낮음 첫 응답 8시간 → 10시간']);
  });

  test('C-10 분은 길이 글로: 하루를 넘는 값과 분이 붙은 값', () => {
    expect(targetsLine(TARGETS, targetsWith(target('LOW', 1441, 525600)))).toEqual(['목표: 낮음 첫 응답 8시간 → 1일 1분, 해결 2일 → 365일']);
  });

  test('C-10 앞 목록에 없던 우선순위는 앞 목표가 "없음"이다', () => {
    const from = TARGETS.filter((t) => t.priority !== 'URGENT');
    expect(targetsLine(from, TARGETS)).toEqual(['목표: 긴급 첫 응답 없음 → 30분, 해결 없음 → 2시간']);
  });

  test('C-10 아무것도 바뀌지 않으면 "목표: 없음"', () => {
    expect(targetsLine(TARGETS, TARGETS.map((t) => ({ ...t })))).toEqual(['목표: 없음']);
  });
});

describe('changeLines SLA_POLICY_CHANGED 업무 시간', () => {
  test('C-10 요일이 쉬는 날로: "수요일 09:00–18:00 → 쉼"', () => {
    const to = WEEK.filter((d) => d.weekday !== 'WEDNESDAY');
    expect(hoursLine(WEEK, to)).toEqual(['업무 시간: 수요일 09:00–18:00 → 쉼']);
  });

  test('C-10 저장된 꼴(요일 일곱, 쉬는 요일은 opens·closes가 null)에서 요일이 쉬는 날로', () => {
    // design.md: hours는 요일 일곱이고 업무 시간이 없으면 둘 다 null이다.
    const closed = (weekday: string) => ({ weekday, opens: null, closes: null });
    const from = [...WEEK, closed('SATURDAY'), closed('SUNDAY')];
    const to = from.map((d) => (d.weekday === 'WEDNESDAY' ? closed('WEDNESDAY') : d));
    expect(hoursLine(from, to)).toEqual(['업무 시간: 수요일 09:00–18:00 → 쉼']);
  });

  test('C-10 쉬는 날이 업무일로: "토요일 쉼 → 10:00–14:00"', () => {
    expect(hoursLine(WEEK, [...WEEK, day('SATURDAY', '10:00', '14:00')])).toEqual(['업무 시간: 토요일 쉼 → 10:00–14:00']);
  });

  test('C-10 시각만 바뀐 요일', () => {
    const to = WEEK.map((d) => (d.weekday === 'MONDAY' ? day('MONDAY', '09:00', '17:30') : d));
    expect(hoursLine(WEEK, to)).toEqual(['업무 시간: 월요일 09:00–18:00 → 09:00–17:30']);
    const opens = WEEK.map((d) => (d.weekday === 'MONDAY' ? day('MONDAY', '08:30', '18:00') : d));
    expect(hoursLine(WEEK, opens)).toEqual(['업무 시간: 월요일 09:00–18:00 → 08:30–18:00']);
  });

  test('C-10 끝이 24:00인 하루', () => {
    const to = WEEK.map((d) => (d.weekday === 'FRIDAY' ? day('FRIDAY', '00:00', '24:00') : d));
    expect(hoursLine(WEEK, to)).toEqual(['업무 시간: 금요일 09:00–18:00 → 00:00–24:00']);
  });

  test('C-10 시작과 끝 사이는 붙임표가 아니라 en dash "–"', () => {
    const line = hoursLine(WEEK, WEEK.filter((d) => d.weekday !== 'TUESDAY')).join(' / ');
    expect(line).toContain('09:00–18:00');
    expect(line).not.toContain('09:00-18:00');
  });

  test('C-10 바뀐 요일이 여럿이면 " · "로 잇고, "to" 목록의 차례다. 바뀌지 않은 요일은 빠진다', () => {
    const to = [day('MONDAY', '10:00', '18:00'), day('TUESDAY'), day('WEDNESDAY'), day('THURSDAY'), day('FRIDAY'), day('SUNDAY', '12:00', '15:00')];
    expect(hoursLine(WEEK, to)).toEqual(['업무 시간: 월요일 09:00–18:00 → 10:00–18:00 · 일요일 쉼 → 12:00–15:00']);
  });

  test('C-10 요일 이름 일곱', () => {
    const all = ['MONDAY', 'TUESDAY', 'WEDNESDAY', 'THURSDAY', 'FRIDAY', 'SATURDAY', 'SUNDAY'];
    const words = ['월요일', '화요일', '수요일', '목요일', '금요일', '토요일', '일요일'];
    expect(hoursLine([], all.map((w) => day(w)))).toEqual([`업무 시간: ${words.map((w) => `${w} 쉼 → 09:00–18:00`).join(' · ')}`]);
  });

  test('C-10 아무것도 바뀌지 않으면 "업무 시간: 없음"', () => {
    expect(hoursLine(WEEK, WEEK.map((d) => ({ ...d })))).toEqual(['업무 시간: 없음']);
  });
});

describe('changeLines SLA_POLICY_CHANGED 쉬는 날', () => {
  test('C-10 더함과 뺌: "더함 2027-01-01, 뺌 2026-12-25"', () => {
    expect(holidaysLine(['2026-12-25'], ['2027-01-01'])).toEqual(['쉬는 날: 더함 2027-01-01, 뺌 2026-12-25']);
  });

  test('C-10 더함만', () => {
    expect(holidaysLine(['2026-12-25'], ['2026-12-25', '2027-01-01'])).toEqual(['쉬는 날: 더함 2027-01-01']);
  });

  test('C-10 뺌만', () => {
    expect(holidaysLine(['2026-12-25', '2027-01-01'], ['2027-01-01'])).toEqual(['쉬는 날: 뺌 2026-12-25']);
  });

  test('C-10 한 묶음의 날짜끼리는 붙지 않는다', () => {
    const line = holidaysLine([], ['2026-12-25', '2027-01-01']).join(' / ');
    expect(line).toContain('2026-12-25');
    expect(line).toContain('2027-01-01');
    expect(line).not.toContain('2026-12-252027-01-01');
  });

  test('C-10 날짜는 정렬된다', () => {
    const added = holidaysLine([], ['2027-03-01', '2026-12-25', '2027-01-01']).join(' / ');
    expect(added.indexOf('2026-12-25')).toBeLessThan(added.indexOf('2027-01-01'));
    expect(added.indexOf('2027-01-01')).toBeLessThan(added.indexOf('2027-03-01'));
    const removed = holidaysLine(['2027-03-01', '2026-12-25', '2027-01-01'], []).join(' / ');
    expect(removed.indexOf('2026-12-25')).toBeLessThan(removed.indexOf('2027-01-01'));
    expect(removed.indexOf('2027-01-01')).toBeLessThan(removed.indexOf('2027-03-01'));
  });

  test('C-10 열 개면 다 보이고, 열한 개면 앞 열 개와 "외 1개"', () => {
    const days = Array.from({ length: 11 }, (_, i) => `2027-01-${String(i + 1).padStart(2, '0')}`);
    const ten = holidaysLine([], days.slice(0, 10)).join(' / ');
    expect(ten).toContain('2027-01-10');
    expect(ten).not.toContain('외');
    const eleven = holidaysLine([], [...days].reverse()).join(' / ');
    expect(eleven).toContain('2027-01-01');
    expect(eleven).toContain('2027-01-10');
    expect(eleven).not.toContain('2027-01-11');
    expect(eleven).toContain('외 1개');
    const removed = holidaysLine(days, []).join(' / ');
    expect(removed).toContain('뺌');
    expect(removed).not.toContain('2027-01-11');
    expect(removed).toContain('외 1개');
  });

  test('C-10 열두 개를 더하면 "외 2개"', () => {
    const days = Array.from({ length: 12 }, (_, i) => `2027-02-${String(i + 1).padStart(2, '0')}`);
    expect(holidaysLine([], days).join(' / ')).toContain('외 2개');
  });

  test('C-10 아무것도 바뀌지 않으면 "쉬는 날: 없음"', () => {
    expect(holidaysLine(['2026-12-25'], ['2026-12-25'])).toEqual(['쉬는 날: 없음']);
  });
});

describe('changeLines SLA_POLICY_CHANGED 여러 부분과 다른 꼴', () => {
  test('C-10 부분마다 한 줄', () => {
    const lines = changeLines(ACTION, {
      targets: { from: TARGETS, to: targetsWith(target('NORMAL', 120, 720)) },
      hours: { from: WEEK, to: WEEK.filter((d) => d.weekday !== 'WEDNESDAY') },
      holidays: { from: [], to: ['2027-01-01'] },
    });
    expect(lines).toEqual(['목표: 보통 첫 응답 4시간 → 2시간', '업무 시간: 수요일 09:00–18:00 → 쉼', '쉬는 날: 더함 2027-01-01']);
  });

  test('C-10 꼴이 다른 목표 값은 SLA 글로 쓰지 않는다', () => {
    for (const value of [{ from: 'a', to: 'b' }, { from: [1], to: [2] }, 'plain', { to: TARGETS }]) {
      const lines = changeLines(ACTION, { targets: value });
      expect(lines).toHaveLength(1);
      expect(lines[0]).not.toContain('첫 응답');
      expect(lines[0]).not.toMatch(/: 없음$/);
    }
  });

  test('C-10 꼴이 다른 업무 시간 값은 SLA 글로 쓰지 않는다', () => {
    for (const value of [{ from: 'a', to: 'b' }, { from: [1], to: [2] }, 'plain']) {
      const lines = changeLines(ACTION, { hours: value });
      expect(lines).toHaveLength(1);
      expect(lines[0]).not.toContain('쉼');
      expect(lines[0]).not.toMatch(/: 없음$/);
    }
  });

  test('C-10 꼴이 다른 쉬는 날 값은 SLA 글로 쓰지 않는다', () => {
    for (const value of [{ from: 'a', to: 'b' }, { from: [1], to: [2] }, 'plain']) {
      const lines = changeLines(ACTION, { holidays: value });
      expect(lines).toHaveLength(1);
      expect(lines[0]).not.toContain('더함');
      expect(lines[0]).not.toContain('뺌');
    }
  });

  test('C-10 앞뒤 가운데 한쪽만 꼴이 맞아도 SLA 글로 쓰지 않는다', () => {
    const cases: [string, unknown][] = [
      ['holidays', { from: ['2026-12-25'], to: 'x' }],
      ['holidays', { from: 'x', to: ['2026-12-25'] }],
      ['targets', { from: TARGETS, to: 'x' }],
      ['targets', { from: 'x', to: TARGETS }],
      ['hours', { from: WEEK, to: 'x' }],
      ['hours', { from: 'x', to: WEEK }],
    ];
    for (const [field, value] of cases) {
      const lines = changeLines(ACTION, { [field]: value });
      expect(lines).toHaveLength(1);
      expect(lines[0]).not.toMatch(/더함|뺌|첫 응답|쉼|: 없음$/);
    }
  });

  test('C-10 SLA_POLICY_CHANGED라도 targets·hours·holidays가 아닌 칸은 SLA 글로 쓰지 않는다', () => {
    const lines = changeLines(ACTION, { other: { from: [], to: [day('MONDAY')] } });
    expect(lines).toHaveLength(1);
    expect(lines[0]).not.toContain('월요일');
    expect(lines[0]).not.toContain('쉼');
  });

  test('C-10 다른 action이면 같은 칸이어도 SLA 글을 쓰지 않는다', () => {
    const lines = changeLines('ACCOUNT_CREATED', {
      targets: { from: TARGETS, to: targetsWith(target('NORMAL', 120, 720)) },
      hours: { from: WEEK, to: WEEK.filter((d) => d.weekday !== 'WEDNESDAY') },
      holidays: { from: [], to: ['2027-01-01'] },
    });
    expect(lines).toHaveLength(3);
    const text = lines.join('\n');
    expect(text).not.toContain('첫 응답 4시간');
    expect(text).not.toContain('수요일');
    expect(text).not.toContain('더함');
  });
});
