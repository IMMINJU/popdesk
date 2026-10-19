// b5 묶음 C-10: 화면 테스트로만 지나던 tf의 단수·복수 고르기, formatNumber, tIn을 이 패키지에서 본다
// (B5-AC-07, B5-AC-08). 화면 언어는 <html lang>이다. 변이 테스트에서 닿지 않은 줄을 보려고 메인이 더했다.
import { en, ko } from '@popdesk/i18n';
import { afterEach, describe, expect, it } from 'vitest';
import { formatNumber, t, texts, tf, tIn } from './locale';

function screenIn(lang: string) {
  document.documentElement.lang = lang;
}

afterEach(() => {
  document.documentElement.lang = '';
});

describe('tf의 {count|one|other} (B5-AC-08)', () => {
  it.each([
    [1, 'Issue list, 1 issue'],
    [0, 'Issue list, 0 issues'],
    [2, 'Issue list, 2 issues'],
    ['1,234', 'Issue list, 1,234 issues'],
  ])('B5-AC-08 영어 화면에서 count %j → %s', (count, want) => {
    screenIn('en');
    expect(tf('issueList.caption', { count })).toBe(want);
  });

  it('B5-AC-08 한국어 화면에서는 고르기 없이 자리만 채운다: 1건', () => {
    screenIn('ko');
    expect(tf('issueList.caption', { count: 1 })).toBe('문의 목록, 1건');
  });

  it('B5-AC-08 values에 없는 이름의 자리는 그대로 남는다', () => {
    screenIn('en');
    expect(tf('issueList.caption', {})).toBe(en['issueList.caption']);
  });
});

describe('formatNumber (B5-AC-07)', () => {
  it.each([
    ['ko', 1234, '1,234'],
    ['en', 1234, '1,234'],
    ['en', 12, '12'],
  ])('B5-AC-07 화면 언어 %s에서 %d → %s', (lang, value, want) => {
    screenIn(lang);
    expect(formatNumber(value)).toBe(want);
  });
});

describe('tIn (B5-AC-03)', () => {
  it('B5-AC-03 화면 언어와 상관없이 고른 언어의 글이다', () => {
    screenIn('ko');
    expect(tIn('en', 'myInfo.save')).toBe(en['myInfo.save']);
    screenIn('en');
    expect(tIn('ko', 'myInfo.save')).toBe(ko['myInfo.save']);
    expect(t('myInfo.save')).toBe(en['myInfo.save']);
  });
});

describe('texts (B5-AC-01)', () => {
  it('B5-AC-01 한 번 만든 표가 읽을 때마다 화면 언어를 따르고, 필드를 펼쳐 볼 수 있다', () => {
    const table = texts({ save: 'myInfo.save' });
    screenIn('ko');
    expect(table.save).toBe(ko['myInfo.save']);
    screenIn('en');
    expect(table.save).toBe(en['myInfo.save']);
    expect({ ...table }).toEqual({ save: en['myInfo.save'] });
  });
});
