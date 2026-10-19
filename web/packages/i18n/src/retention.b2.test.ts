// b2 묶음 screens.md 2절: 자동 정리가 켜졌는데 옮길 문의가 0건일 때의 문장(B2-AC-06). 1건 이상의 문장은 그대로다.
import { describe, expect, it } from 'vitest';
import { messages } from './index';

const KEY = 'inbox.retentionArmedNone';

/** The text of `key` in `locale`, or undefined when the table has no such key. */
const said = (locale: 'ko' | 'en', key: string): string | undefined => (messages[locale] as Record<string, string | undefined>)[key];

describe('inbox.retentionArmedNone (b2 묶음)', () => {
  it.each([
    ['ko', '문의 자동 정리가 켜졌습니다. 지금은 종료된 지 {days}일이 지난 문의가 없습니다.'],
    ['en', 'Automatic issue cleanup is on. No issue has been closed for over {days} days yet.'],
  ] as const)('B2-AC-06 %s: 0건 문장은 따로 두고 {days} 자리 하나만 쓴다', (locale, want) => {
    expect(said(locale, KEY)).toBe(want);
  });

  it.each([
    ['ko', '문의 자동 정리가 켜졌습니다. 내일부터 종료된 지 {days}일이 지난 문의 {count}건을 휴지통으로 옮깁니다'],
    ['en', 'Issue cleanup is on. From tomorrow, {count} issues closed more than {days} days ago move to the trash'],
  ] as const)('B2-AC-06 %s: 1건 이상의 inbox.retentionArmed는 그대로이고, 0건 문장도 있다', (locale, want) => {
    expect(said(locale, 'inbox.retentionArmed')).toBe(want);
    expect(said(locale, KEY), '0건 문장').toBeDefined();
  });
});
