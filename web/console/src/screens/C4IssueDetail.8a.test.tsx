// 8a 묶음 screens.md 3절 C-4 문의 상세: 옆 정보의 "첫 응답" 아래 구역 "SLA". 대상이 아니면 없다. 목표 둘을 줄로 보인다:
// 목표 없음, OK·WARNING·BREACHED("{목표}: {기한 시각} 까지, {남은 시간}"과 배지), MET·MISSED("지킴", "못 지킴")(S8A-AC-30).
// 남은 시간은 서버가 준 remainingMinutes 그대로다: 기한은 일부러 그 분과 맞지 않게 멀리 둔다(acceptance.md 5절 8).
import { describe, expect, test } from 'vitest';
import { eventually, expectNoAxeViolations, found, renderAt, screen, state, text } from '../__tests__/console';
import { metaOf } from '../__tests__/console3';
import { NO_GOAL, badgesIn, goal, goalLine, issueWithSla, sla, slaPart, slaPartNow, type SlaGoal } from '../__tests__/console8a';

const URL_7 = '/issues/ACME-7';
const FAR = '2026-10-11T03:15:00Z';
const DUE_OTHER = '2026-10-12T05:30:00Z';

/** The C-4 head drawn: the "첫 응답" line of the value list, waited for generously. */
async function detailShown(): Promise<void> {
  await found(() => metaOf('첫 응답'), '옆 정보 "첫 응답"');
}

describe('C-4 문의 상세 SLA 구역 (8a 묶음)', () => {
  test('S8A-AC-30 C-4 SLA 대상이 아닌 문의(sla null): 구역 "SLA"가 없고 "첫 응답"은 그대로다', async () => {
    state.issue = issueWithSla(null);
    renderAt(URL_7);
    await detailShown();
    await eventually(() => {
      expect(screen.queryByRole('heading', { level: 1 })).not.toBeNull();
    });

    expect(slaPartNow(), '구역 "SLA"').toBeNull();
    expect(text(metaOf('첫 응답'))).toContain('아직 없음');
  });

  test('S8A-AC-30 C-4 구역 "SLA"는 옆 정보의 "첫 응답" 아래에 있고 줄은 첫 응답, 해결 차례다', async () => {
    state.issue = issueWithSla(sla(goal('OK', 200, FAR), goal('WARNING', 50, DUE_OTHER)));
    renderAt(URL_7);
    await detailShown();

    const part = await slaPart();
    const firstResponseTerm = screen.queryAllByRole('term').find((t) => text(t) === '첫 응답') as Element;
    expect(firstResponseTerm.compareDocumentPosition(part) & Node.DOCUMENT_POSITION_FOLLOWING, '"첫 응답" 아래').not.toBe(0);
    const first = goalLine(part, '첫 응답');
    const resolution = goalLine(part, '해결');
    expect(first, '첫 응답 줄').not.toBeNull();
    expect(resolution, '해결 줄').not.toBeNull();
    expect((first as Element).compareDocumentPosition(resolution as Element) & Node.DOCUMENT_POSITION_FOLLOWING, '첫 응답 다음 해결').not.toBe(0);
    await expectNoAxeViolations();
  });

  test.each<{ what: string; value: SlaGoal; words: string[]; badge: { text: string; tone: string } | null; due: string | null }>([
    { what: '목표 없음', value: NO_GOAL, words: ['해결: 목표 없음'], badge: null, due: null },
    { what: 'OK', value: goal('OK', 200, FAR), words: ['해결:', '까지, 3시간 20분 남음'], badge: null, due: FAR },
    { what: 'WARNING', value: goal('WARNING', 50, FAR), words: ['해결:', '까지, 50분 남음'], badge: { text: '곧 넘김', tone: 'neutral' }, due: FAR },
    { what: 'BREACHED', value: goal('BREACHED', -1560, FAR), words: ['해결:', '까지, 1일 2시간 넘김'], badge: { text: '넘김', tone: 'warning' }, due: FAR },
    { what: 'MET', value: goal('MET', null, FAR), words: ['해결: 지킴'], badge: { text: '지킴', tone: 'neutral' }, due: null },
    { what: 'MISSED', value: goal('MISSED', null, FAR), words: ['해결: 못 지킴'], badge: { text: '못 지킴', tone: 'warning' }, due: null },
  ])('S8A-AC-30 C-4 해결 목표 $what', async ({ value, words, badge, due }) => {
    // 첫 응답은 이미 지킨 목표: 해결 줄만 본다.
    state.issue = issueWithSla(sla(goal('MET', null, DUE_OTHER), value), { firstResponseAt: '2026-10-08T03:40:00Z' });
    renderAt(URL_7);
    await detailShown();

    const part = await slaPart();
    const line = await found(() => goalLine(part, '해결'), '해결 줄');
    for (const w of words) expect(text(line)).toContain(w);
    expect(badgesIn(line), '배지').toEqual(badge ? [badge] : []);
    const time = line.querySelector('time');
    if (due === null) {
      expect(text(line), '기한 글 없음').not.toContain('까지');
    } else {
      expect(time, '기한의 <time>').not.toBeNull();
      expect(Date.parse(time?.getAttribute('datetime') ?? ''), '기한 시각').toBe(Date.parse(due));
    }
    expect(text(line), '"까지" 앞은 기한 시각이다').not.toMatch(/해결: 까지/);
  });

  test('S8A-AC-30 C-4 첫 응답 목표 없음과 넘김: "첫 응답: 목표 없음", 해결은 넘김 배지와 넘긴 시간', async () => {
    state.issue = issueWithSla(sla(NO_GOAL, goal('BREACHED', -45, FAR)));
    renderAt(URL_7);
    await detailShown();

    const part = await slaPart();
    expect(text(goalLine(part, '첫 응답'))).toContain('첫 응답: 목표 없음');
    expect(badgesIn(goalLine(part, '첫 응답') as Element)).toEqual([]);
    expect(text(goalLine(part, '해결'))).toContain('45분 넘김');
    expect(badgesIn(goalLine(part, '해결') as Element)).toEqual([{ text: '넘김', tone: 'warning' }]);
  });
});
