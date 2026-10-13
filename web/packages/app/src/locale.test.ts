// 화면은 지금 한국어뿐이다(S1A-AC-16). 문구는 @popdesk/i18n의 한국어 표에서 온다.
// 본문을 읽지 않고 시그니처와 주석으로 썼다.
import { ko, type MessageKey } from '@popdesk/i18n';
import { describe, expect, it } from 'vitest';
import { t, tf } from './locale';

const KEYS = Object.keys(ko) as MessageKey[];

describe('t', () => {
  it.each<[MessageKey, string]>([
    ['passwordForgot.title', '비밀번호 찾기'],
    ['passwordForgot.email', '이메일'],
    ['passwordReset.title', '비밀번호 재설정'],
  ])('S1A-AC-16 t(%s) → %s', (key, want) => {
    expect(t(key)).toBe(want);
  });

  it('S1A-AC-16 t: 모든 키가 한국어 표의 글 그대로다', () => {
    expect(KEYS.length).toBeGreaterThan(100);
    expect(KEYS.filter((key) => t(key) !== ko[key])).toEqual([]);
  });
});

describe('tf', () => {
  it.each<[MessageKey, Record<string, string | number>, string]>([
    ['inbox.count', { count: 3 }, '3건'],
    ['inbox.count', { count: 0 }, '0건'],
    ['inbox.count', { count: '120' }, '120건'],
    ['inbox.who', { name: '김민주' }, '김민주 님이'],
    ['inbox.who', { name: '' }, ' 님이'],
  ])('S1A-AC-16 tf(%s, %o) → %s', (key, values, want) => {
    expect(tf(key, values)).toBe(want);
  });

  it('S1A-AC-16 tf: 자리가 없는 글은 t와 같고, 글에 없는 값은 쓰이지 않는다', () => {
    expect(tf('passwordForgot.title', {})).toBe(t('passwordForgot.title'));
    expect(tf('passwordForgot.title', { count: 3 })).toBe('비밀번호 찾기');
    expect(tf('inbox.count', { count: 3, name: '김민주' })).toBe('3건');
  });

  it('S1A-AC-16 tf: 값에 든 자리 꼴의 글자는 다시 채우지 않는다', () => {
    expect(tf('inbox.who', { name: '{name}' })).toBe('{name} 님이');
  });
});
