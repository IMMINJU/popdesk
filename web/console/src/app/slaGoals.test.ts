// 8a screens.md 2·3절, acceptance.md 5절 12: 문의의 SLA 목표 가운데 어느 것을 보일지 고른다.
// 상태와 남은 분은 서버의 것이다. 본문을 읽지 않고 시그니처와 주석으로 썼다(C-10).
import { describe, expect, test } from 'vitest';
import type { Schemas } from '../api';
import { runs, worseGoal } from './slaGoals';

type SlaGoal = Schemas['SlaGoal'];
type IssueSla = Schemas['IssueSla'];

const EARLY = '2026-10-19T09:00:00Z';
const LATE = '2026-10-20T09:00:00Z';

const ok = (dueAt = EARLY, remainingMinutes = 300): SlaGoal => ({ state: 'OK', dueAt, remainingMinutes });
const warning = (dueAt = EARLY, remainingMinutes = 30): SlaGoal => ({ state: 'WARNING', dueAt, remainingMinutes });
const breached = (dueAt = EARLY, remainingMinutes = -30): SlaGoal => ({ state: 'BREACHED', dueAt, remainingMinutes });
const met = (dueAt = EARLY): SlaGoal => ({ state: 'MET', dueAt, remainingMinutes: null });
const missed = (dueAt = EARLY): SlaGoal => ({ state: 'MISSED', dueAt, remainingMinutes: null });
const none = (): SlaGoal => ({ state: null, dueAt: null, remainingMinutes: null });

const sla = (firstResponse: SlaGoal, resolution: SlaGoal): IssueSla => ({ firstResponse, resolution });

describe('runs', () => {
  test.each([
    ['OK', ok()],
    ['WARNING', warning()],
    ['BREACHED', breached()],
  ])('C-10 runs: %s인 목표는 시계가 돈다', (_state, goal) => {
    expect(runs(goal)).toBe(true);
  });

  test('C-10 runs: 기한 바로 그때(남은 0분) OK인 목표도 돈다', () => {
    expect(runs(ok(EARLY, 0))).toBe(true);
  });

  test.each([
    ['MET', met()],
    ['MISSED', missed()],
    ['목표 없음(state null)', none()],
  ])('C-10 runs: %s이고 남은 분이 null인 목표는 돌지 않는다', (_state, goal) => {
    expect(runs(goal)).toBe(false);
  });

  test('C-10 runs: 남은 분이 null이면 시계가 멈춘 것이다(OpenAPI SlaGoal.remainingMinutes)', () => {
    expect(runs({ state: 'OK', dueAt: EARLY, remainingMinutes: null })).toBe(false);
    expect(runs({ state: 'BREACHED', dueAt: EARLY, remainingMinutes: null })).toBe(false);
  });

  test('C-10 runs: MET·MISSED는 남은 분이 있어도 확정된 목표다', () => {
    expect(runs({ state: 'MET', dueAt: EARLY, remainingMinutes: 10 })).toBe(false);
    expect(runs({ state: 'MISSED', dueAt: EARLY, remainingMinutes: -10 })).toBe(false);
  });
});

describe('worseGoal', () => {
  test('C-10 worseGoal: SLA가 없으면 null', () => {
    expect(worseGoal(null)).toBeNull();
  });

  test('C-10 worseGoal: 둘 다 돌지 않으면 null', () => {
    expect(worseGoal(sla(met(), missed()))).toBeNull();
    expect(worseGoal(sla(none(), none()))).toBeNull();
    expect(worseGoal(sla(missed(), none()))).toBeNull();
  });

  test('C-10 worseGoal: 첫 응답만 돌면 첫 응답', () => {
    const first = ok(LATE);
    expect(worseGoal(sla(first, none()))).toEqual({ name: '첫 응답', goal: first });
  });

  test('C-10 worseGoal: 해결만 돌면 해결', () => {
    const resolution = ok(LATE);
    expect(worseGoal(sla(met(), resolution))).toEqual({ name: '해결', goal: resolution });
  });

  test('C-10 worseGoal: 확정된 목표는 넘김(MISSED)이어도 돌고 있는 괜찮음을 이기지 않는다', () => {
    const resolution = ok(LATE);
    expect(worseGoal(sla(missed(EARLY), resolution))).toEqual({ name: '해결', goal: resolution });
    const first = ok(LATE);
    expect(worseGoal(sla(first, missed(EARLY)))).toEqual({ name: '첫 응답', goal: first });
  });

  test('C-10 worseGoal: 목표 없음은 고르지 않는다', () => {
    const first = warning(LATE);
    expect(worseGoal(sla(first, none()))).toEqual({ name: '첫 응답', goal: first });
    const resolution = ok(LATE);
    expect(worseGoal(sla(none(), resolution))).toEqual({ name: '해결', goal: resolution });
  });

  test('C-10 worseGoal: 넘김은 곧 넘김을 이긴다(기한이 늦어도)', () => {
    const b = breached(LATE);
    expect(worseGoal(sla(warning(EARLY), b))).toEqual({ name: '해결', goal: b });
    const b2 = breached(LATE);
    expect(worseGoal(sla(b2, warning(EARLY)))).toEqual({ name: '첫 응답', goal: b2 });
  });

  test('C-10 worseGoal: 넘김은 괜찮음을 이긴다(기한이 늦어도)', () => {
    const b = breached(LATE);
    expect(worseGoal(sla(ok(EARLY), b))).toEqual({ name: '해결', goal: b });
    const b2 = breached(LATE);
    expect(worseGoal(sla(b2, ok(EARLY)))).toEqual({ name: '첫 응답', goal: b2 });
  });

  test('C-10 worseGoal: 곧 넘김은 괜찮음을 이긴다(기한이 늦어도)', () => {
    const w = warning(LATE);
    expect(worseGoal(sla(ok(EARLY), w))).toEqual({ name: '해결', goal: w });
    const w2 = warning(LATE);
    expect(worseGoal(sla(w2, ok(EARLY)))).toEqual({ name: '첫 응답', goal: w2 });
  });

  test.each([
    ['OK', ok],
    ['WARNING', warning],
    ['BREACHED', breached],
  ] as const)('C-10 worseGoal: 둘 다 %s이면 기한이 이른 쪽', (_state, make) => {
    const earlyResolution = make(EARLY);
    expect(worseGoal(sla(make(LATE), earlyResolution))).toEqual({ name: '해결', goal: earlyResolution });
    const earlyFirst = make(EARLY);
    expect(worseGoal(sla(earlyFirst, make(LATE)))).toEqual({ name: '첫 응답', goal: earlyFirst });
  });

  test('C-10 worseGoal: 기한은 글자가 아니라 시각으로 견준다(같은 날 몇 분 차이)', () => {
    const first = ok('2026-10-19T09:59:00Z');
    const resolution = ok('2026-10-19T10:00:00Z');
    expect(worseGoal(sla(resolution, first))?.goal).toBe(first);
    expect(worseGoal(sla(first, resolution))?.goal).toBe(first);
  });

  test('C-10 worseGoal: 고른 목표는 받은 그 객체다', () => {
    const first = breached();
    expect(worseGoal(sla(first, ok(LATE)))?.goal).toBe(first);
  });
});
