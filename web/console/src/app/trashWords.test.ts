// S7A-AC-51·52, 7a screens.md 2절(영구 삭제 칸 "{n}일 뒤"/"오늘"), 3절(이력 TRASH 문장 셋).
// trashWords의 시그니처와 JSDoc만 보고 썼다. 시계에 기대지 않고 now를 넘긴다.
import { describe, expect, test } from 'vitest';
import { purgeInText, trashHistoryText } from './trashWords';

const SECOND = 1000;
const MINUTE = 60 * SECOND;
const HOUR = 60 * MINUTE;
const DAY = 24 * HOUR;

// 2026-10-16T03:00:00.000Z, 정수 ms.
const NOW = Date.UTC(2026, 9, 16, 3, 0, 0, 0);

function purgeAtIn(ms: number): string {
  return new Date(NOW + ms).toISOString();
}

describe('purgeInText', () => {
  test.each([
    ['방금 지운 문의(30일 정각)', 30 * DAY, '30일 뒤'],
    ['지운 지 1초 뒤(30일에서 1초 모자람)', 30 * DAY - SECOND, '30일 뒤'],
    ['29일 1ms', 29 * DAY + 1, '30일 뒤'],
    ['48시간 1ms', 2 * DAY + 1, '3일 뒤'],
    ['48시간 정각', 2 * DAY, '2일 뒤'],
    ['24시간 1분', DAY + MINUTE, '2일 뒤'],
    ['24시간 1ms', DAY + 1, '2일 뒤'],
    ['24시간 정각', DAY, '1일 뒤'],
    ['아주 먼 미래(400일)', 400 * DAY, '400일 뒤'],
    ['399일 1ms', 399 * DAY + 1, '400일 뒤'],
  ])('S7A-AC-51 남은 시간 %s: %s', (_name, left, text) => {
    expect(purgeInText(purgeAtIn(left), NOW)).toBe(text);
  });

  test.each([
    ['23:59:59.999', DAY - 1],
    ['1시간', HOUR],
    ['1ms', 1],
    ['0ms(바로 지금)', 0],
    ['1ms 지남', -1],
    ['하루 지남', -DAY],
    ['10일 지남', -10 * DAY],
  ])('S7A-AC-51 24시간 미만이거나 지났으면 오늘: %s', (_name, left) => {
    expect(purgeInText(purgeAtIn(left), NOW)).toBe('오늘');
  });

  test.each([
    ['빈 문자열', ''],
    ['아무 글', '내일쯤'],
    ['날짜 꼴이 아닌 글', 'not-a-date'],
  ])('S7A-AC-51 읽을 수 없는 purgeAt은 오늘: %s', (_name, purgeAt) => {
    expect(purgeInText(purgeAt, NOW)).toBe('오늘');
  });

  test('S7A-AC-51 시간대가 +09:00인 ISO도 같은 순간으로 센다', () => {
    // 2026-10-17T12:00:00+09:00 = 2026-10-17T03:00:00Z, NOW에서 정확히 24시간.
    expect(purgeInText('2026-10-17T12:00:00+09:00', NOW)).toBe('1일 뒤');
    // 1분 더 남으면 올림해서 2일.
    expect(purgeInText('2026-10-17T12:01:00+09:00', NOW)).toBe('2일 뒤');
    // 1분 덜 남으면 24시간 미만.
    expect(purgeInText('2026-10-17T11:59:00+09:00', NOW)).toBe('오늘');
  });

  test('S7A-AC-51 밀리초 없는 Z 꼴도 읽는다', () => {
    expect(purgeInText('2026-11-15T03:00:00Z', NOW)).toBe('30일 뒤');
  });

  test('S7A-AC-51 now가 바뀌면 같은 purgeAt도 남은 날이 준다', () => {
    const purgeAt = purgeAtIn(30 * DAY);
    expect(purgeInText(purgeAt, NOW + 10 * DAY)).toBe('20일 뒤');
    expect(purgeInText(purgeAt, NOW + 29 * DAY)).toBe('1일 뒤');
    expect(purgeInText(purgeAt, NOW + 29 * DAY + 1)).toBe('오늘');
  });
});

describe('trashHistoryText', () => {
  test('S7A-AC-52 뒤 값 TRASHED: 휴지통으로 옮겼습니다', () => {
    expect(trashHistoryText('최관리 님이', null, 'TRASHED')).toBe('최관리 님이 문의를 휴지통으로 옮겼습니다');
  });

  test('S7A-AC-52 전 값 TRASHED, 뒤 값 없음: 휴지통에서 복구했습니다', () => {
    expect(trashHistoryText('최관리 님이', 'TRASHED', null)).toBe('최관리 님이 문의를 휴지통에서 복구했습니다');
  });

  test('S7A-AC-52 who는 앞에 그대로 붙는다', () => {
    expect(trashHistoryText('시스템이', null, 'TRASHED')).toBe('시스템이 문의를 휴지통으로 옮겼습니다');
    expect(trashHistoryText('민주 님이', 'TRASHED', null)).toBe('민주 님이 문의를 휴지통에서 복구했습니다');
  });

  test.each<[string, string | null, string | null]>([
    ['영구 삭제(TRASHED → PURGED)', 'TRASHED', 'PURGED'],
    ['둘 다 없음', null, null],
    ['모르는 뒤 값', null, 'ARCHIVED'],
    ['모르는 전 값, 뒤 값 없음', 'ARCHIVED', null],
    ['전 값 TRASHED, 뒤 값 모름', 'TRASHED', 'SOMETHING'],
    ['소문자 trashed 뒤 값', null, 'trashed'],
    ['소문자 trashed 전 값', 'trashed', null],
    ['전 값 TRASHED, 뒤 값 빈 글', 'TRASHED', ''],
    ['전 값 빈 글, 뒤 값 없음', '', null],
  ])('S7A-AC-52 그 밖은 문의를 바꿨습니다: %s', (_name, oldValue, newValue) => {
    expect(trashHistoryText('최관리 님이', oldValue, newValue)).toBe('최관리 님이 문의를 바꿨습니다');
  });

  test('S7A-AC-52 시스템의 영구 삭제도 그 밖 문장', () => {
    expect(trashHistoryText('시스템이', 'TRASHED', 'PURGED')).toBe('시스템이 문의를 바꿨습니다');
  });
});
