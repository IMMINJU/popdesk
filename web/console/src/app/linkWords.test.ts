// S6C-AC-50·51·53·54, 6c screens.md 1절(종류 낱말, 라디오), 2절(이력 문장), 3절(알림함 문장).
// linkWords의 시그니처와 JSDoc만 보고 썼다. 화면은 지금 한국어뿐이다(locale.ts).
import { describe, expect, test } from 'vitest';
import type { Schemas } from '../api';
import { LINK_KIND_WORDS, LINK_TYPE_CHOICES, linkChangeText, linkedIssueText } from './linkWords';

type HistoryLink = Schemas['IssueHistoryLink'];

const WHO = '민주 님이';
const TITLE = '로그인이 안 됩니다';

function link(type: string, key = 'ACME-12', title = TITLE): HistoryLink {
  return { type: type as HistoryLink['type'], issue: { key, title } };
}

describe('LINK_KIND_WORDS', () => {
  test('S6C-AC-50 종류 낱말 셋', () => {
    expect(LINK_KIND_WORDS).toEqual({
      DUPLICATE_OF: '이 문의의 원본',
      DUPLICATED_BY: '이 문의의 중복',
      RELATED: '관련',
    });
  });
});

describe('LINK_TYPE_CHOICES', () => {
  test('S6C-AC-51 라디오 셋의 차례와 이름표, 첫째가 기본 "관련"', () => {
    expect(LINK_TYPE_CHOICES).toEqual([
      { value: 'RELATED', label: '관련' },
      { value: 'DUPLICATE_OF', label: '이 문의가 찾은 문의의 중복' },
      { value: 'DUPLICATED_BY', label: '찾은 문의가 이 문의의 중복' },
    ]);
  });
});

describe('linkChangeText', () => {
  test('S6C-AC-53 이음 DUPLICATE_OF: 이 문의를 {키}의 중복으로 이었습니다', () => {
    expect(linkChangeText(WHO, null, link('DUPLICATE_OF'))).toEqual({
      before: '민주 님이 이 문의를 ',
      key: 'ACME-12',
      after: '의 중복으로 이었습니다',
      title: TITLE,
    });
  });

  test('S6C-AC-53 이음 DUPLICATED_BY: {키}를 이 문의의 중복으로 이었습니다', () => {
    expect(linkChangeText(WHO, null, link('DUPLICATED_BY'))).toEqual({
      before: '민주 님이 ',
      key: 'ACME-12',
      after: '를 이 문의의 중복으로 이었습니다',
      title: TITLE,
    });
  });

  test('S6C-AC-53 이음 RELATED: {키}와 관련 문의로 이었습니다', () => {
    expect(linkChangeText(WHO, null, link('RELATED'))).toEqual({
      before: '민주 님이 ',
      key: 'ACME-12',
      after: '와 관련 문의로 이었습니다',
      title: TITLE,
    });
  });

  test.each([['DUPLICATE_OF'], ['DUPLICATED_BY'], ['RELATED']])(
    'S6C-AC-53 끊음 %s: 종류 상관없이 {키}와의 연결을 끊었습니다',
    (type) => {
      expect(linkChangeText(WHO, link(type, 'BETA-3', '결제 오류'), null)).toEqual({
        before: '민주 님이 ',
        key: 'BETA-3',
        after: '와의 연결을 끊었습니다',
        title: '결제 오류',
      });
    },
  );

  test('S6C-AC-53 키 뒤 조사는 고정이다(숫자로 끝나는 키도 "를", "와")', () => {
    expect(linkChangeText(WHO, null, link('DUPLICATED_BY', 'ACME-10'))).toMatchObject({ after: '를 이 문의의 중복으로 이었습니다' });
    expect(linkChangeText(WHO, null, link('RELATED', 'ACME-1'))).toMatchObject({ after: '와 관련 문의로 이었습니다' });
  });

  test('S6C-AC-53 제목은 고치지 않고 그대로 준다', () => {
    const long = '아주 긴 제목 '.repeat(20).trim();
    expect(linkChangeText(WHO, null, link('RELATED', 'ACME-12', long))).toMatchObject({ title: long });
  });

  test('S6C-AC-53 둘 다 null: 연결을 바꿨습니다', () => {
    expect(linkChangeText(WHO, null, null)).toEqual({ text: '민주 님이 연결을 바꿨습니다' });
  });

  test('S6C-AC-53 둘 다 있음: 연결을 바꿨습니다', () => {
    expect(linkChangeText(WHO, link('RELATED', 'ACME-1'), link('DUPLICATE_OF', 'ACME-2'))).toEqual({
      text: '민주 님이 연결을 바꿨습니다',
    });
  });

  test.each([['BLOCKS'], ['related'], ['']])('S6C-AC-53 모르는 종류 %s를 이음: 연결을 바꿨습니다', (type) => {
    expect(linkChangeText(WHO, null, link(type))).toEqual({ text: '민주 님이 연결을 바꿨습니다' });
  });

  test.each([['BLOCKS'], ['duplicate_of']])('S6C-AC-53 모르는 종류 %s를 끊음: 연결을 바꿨습니다', (type) => {
    expect(linkChangeText(WHO, link(type), null)).toEqual({ text: '민주 님이 연결을 바꿨습니다' });
  });

  test('S6C-AC-53 시스템이 한 항목: 주어는 "시스템이"', () => {
    expect(linkChangeText('시스템이', null, link('RELATED'))).toEqual({
      before: '시스템이 ',
      key: 'ACME-12',
      after: '와 관련 문의로 이었습니다',
      title: TITLE,
    });
    expect(linkChangeText('시스템이', null, null)).toEqual({ text: '시스템이 연결을 바꿨습니다' });
  });
});

describe('linkedIssueText', () => {
  const linked = { key: 'BETA-3', title: '결제 오류' };

  test('S6C-AC-54 LINKED_ISSUE_RESOLVED: 연결된 문의를 해결했습니다: {키} {제목}', () => {
    expect(linkedIssueText('LINKED_ISSUE_RESOLVED', WHO, linked)).toBe('민주 님이 연결된 문의를 해결했습니다: BETA-3 결제 오류');
  });

  test('S6C-AC-54 LINKED_ISSUE_CLOSED: 연결된 문의를 종료했습니다: {키} {제목}', () => {
    expect(linkedIssueText('LINKED_ISSUE_CLOSED', WHO, linked)).toBe('민주 님이 연결된 문의를 종료했습니다: BETA-3 결제 오류');
  });

  test('S6C-AC-54 누군가가 한 것: 주어는 "누군가"', () => {
    expect(linkedIssueText('LINKED_ISSUE_CLOSED', '누군가', linked)).toBe('누군가 연결된 문의를 종료했습니다: BETA-3 결제 오류');
  });

  test('S6C-AC-54 linked가 null이면 키와 제목이 빈다(RESOLVED)', () => {
    expect(linkedIssueText('LINKED_ISSUE_RESOLVED', WHO, null)).toBe('민주 님이 연결된 문의를 해결했습니다:  ');
  });

  test('S6C-AC-54 linked가 null이면 키와 제목이 빈다(CLOSED)', () => {
    expect(linkedIssueText('LINKED_ISSUE_CLOSED', WHO, null)).toBe('민주 님이 연결된 문의를 종료했습니다:  ');
  });
});
