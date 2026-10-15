// S6B-AC-30, screens.md 3절 (시스템 항목의 문장), design.md 8절의 시스템 항목 문장 줄.
// taskChangeText의 시그니처와 JSDoc만 보고 썼다. 날짜는 JSDoc의 꼴(2026년 10월 20일)대로 쓴다.
import { describe, expect, test } from 'vitest';
import type { Schemas } from '../api';
import { taskChangeText, type TaskChange } from './taskWords';

type Person = Schemas['Person'];

function person(accountId: number, name: string): Person {
  return { accountId, name, photoUpdatedAt: null, role: 'AGENT' };
}

const MINJU = person(1, '민주');
const JIHO = person(2, '지호');
const SEOYEON = person(3, '서연');

function change(over: Partial<TaskChange>): TaskChange {
  return {
    author: MINJU,
    field: null,
    oldValue: null,
    newValue: null,
    oldPerson: null,
    newPerson: null,
    ...over,
  };
}

describe('taskChangeText', () => {
  test('S6B-AC-30 STATUS → DONE: 작업을 완료했습니다', () => {
    expect(taskChangeText(change({ field: 'STATUS', oldValue: 'OPEN', newValue: 'DONE' }))).toEqual({
      text: '민주 님이 작업을 완료했습니다',
    });
  });

  test('S6B-AC-30 STATUS → OPEN: 작업을 다시 열었습니다', () => {
    expect(taskChangeText(change({ field: 'STATUS', oldValue: 'DONE', newValue: 'OPEN' }))).toEqual({
      text: '민주 님이 작업을 다시 열었습니다',
    });
  });

  test('S6B-AC-30 ASSIGNEE 전이 없을 때: 담당자를 {새 사람} 님으로 정했습니다', () => {
    expect(taskChangeText(change({ field: 'ASSIGNEE', newPerson: JIHO }))).toEqual({
      text: '민주 님이 담당자를 지호 님으로 정했습니다',
    });
  });

  test('S6B-AC-30 ASSIGNEE 전이 있을 때: {전} 님에서 {새} 님으로 바꿨습니다', () => {
    expect(taskChangeText(change({ field: 'ASSIGNEE', oldPerson: JIHO, newPerson: SEOYEON }))).toEqual({
      text: '민주 님이 담당자를 지호 님에서 서연 님으로 바꿨습니다',
    });
  });

  test('S6B-AC-30 ASSIGNEE 비움: 담당자를 비웠습니다', () => {
    expect(taskChangeText(change({ field: 'ASSIGNEE', oldPerson: JIHO }))).toEqual({
      text: '민주 님이 담당자를 비웠습니다',
    });
  });

  test('S6B-AC-30 누가는 author의 이름이다', () => {
    expect(taskChangeText(change({ author: SEOYEON, field: 'ASSIGNEE', newPerson: SEOYEON }))).toEqual({
      text: '서연 님이 담당자를 서연 님으로 정했습니다',
    });
  });

  test('S6B-AC-30 DUE_DATE 전이 없을 때: 마감일을 {날짜}로 정했습니다', () => {
    expect(taskChangeText(change({ field: 'DUE_DATE', newValue: '2026-10-20' }))).toEqual({
      text: '민주 님이 마감일을 2026년 10월 20일로 정했습니다',
    });
  });

  test('S6B-AC-30 DUE_DATE 전이 있을 때: 마감일을 {전}에서 {뒤}로 바꿨습니다', () => {
    expect(taskChangeText(change({ field: 'DUE_DATE', oldValue: '2026-10-20', newValue: '2026-11-30' }))).toEqual({
      text: '민주 님이 마감일을 2026년 10월 20일에서 2026년 11월 30일로 바꿨습니다',
    });
  });

  test('S6B-AC-30 DUE_DATE 지움: 마감일을 지웠습니다', () => {
    expect(taskChangeText(change({ field: 'DUE_DATE', oldValue: '2026-10-20' }))).toEqual({
      text: '민주 님이 마감일을 지웠습니다',
    });
  });

  test('S6B-AC-30 TITLE: 제목을 고쳤습니다와 전·뒤 제목', () => {
    expect(taskChangeText(change({ field: 'TITLE', oldValue: '고객에게 전화', newValue: '고객에게 다시 전화' }))).toEqual({
      text: '민주 님이 제목을 고쳤습니다',
      titles: { before: '고객에게 전화', after: '고객에게 다시 전화' },
    });
  });

  test('S6B-AC-30 TITLE: 제목은 고치지 않고 그대로 준다', () => {
    const long = '아주 긴 제목 '.repeat(20).trim();
    expect(taskChangeText(change({ field: 'TITLE', oldValue: long, newValue: 'Call back' })).titles).toEqual({
      before: long,
      after: 'Call back',
    });
  });

  test.each([['PRIORITY'], ['LABEL'], ['status'], [''], [null]])(
    'S6B-AC-30 모르는 칸 %s: 작업을 바꿨습니다',
    (field) => {
      expect(taskChangeText(change({ field, oldValue: 'A', newValue: 'B' }))).toEqual({
        text: '민주 님이 작업을 바꿨습니다',
      });
    },
  );

  test.each([['CLOSED'], ['done'], ['WAITING']])('S6B-AC-30 STATUS의 모르는 상태 %s: 작업을 바꿨습니다', (status) => {
    expect(taskChangeText(change({ field: 'STATUS', oldValue: 'OPEN', newValue: status }))).toEqual({
      text: '민주 님이 작업을 바꿨습니다',
    });
  });
});
