// b6 묶음 screens.md 4절: 자동 정리 켜짐 두 문장의 영어에 단수·복수가 있고 머리가 "Automatic issue cleanup is on."으로 같다
// (B6-AC-05). 문장은 화면이 쓰는 `@popdesk/app`의 `tf`로 채워 본다. tf는 `<html lang>`으로 언어를 고르므로, 이 패키지의 node
// 환경에서는 그 하나만 가진 document를 잠깐 둔다. 한국어 두 문장은 지금 그대로다.
import { afterEach, describe, expect, it } from 'vitest';
import { tf } from '../../app/src/locale';
import { messages } from './index';

const HEAD = 'Automatic issue cleanup is on.';

/** Runs `read` with `<html lang>` set to `lang`, the way `tf` finds the screen language. */
function inLanguage<T>(lang: 'ko' | 'en', read: () => T): T {
  Object.defineProperty(globalThis, 'document', { value: { documentElement: { lang } }, configurable: true, writable: true });
  return read();
}

afterEach(() => {
  Reflect.deleteProperty(globalThis, 'document');
});

describe('자동 정리 켜짐 문장의 영어 (b6 묶음)', () => {
  it.each([
    [1, 1, 'Automatic issue cleanup is on. From tomorrow, 1 issue closed more than 1 day ago moves to the trash.'],
    [2, 1, 'Automatic issue cleanup is on. From tomorrow, 2 issues closed more than 1 day ago move to the trash.'],
    [1, 30, 'Automatic issue cleanup is on. From tomorrow, 1 issue closed more than 30 days ago moves to the trash.'],
    [2, 30, 'Automatic issue cleanup is on. From tomorrow, 2 issues closed more than 30 days ago move to the trash.'],
  ] as const)('B6-AC-05 inbox.retentionArmed en, count %i, days %i: 수에 맞는 단수·복수와 같은 머리', (count, days, want) => {
    const said = inLanguage('en', () => tf('inbox.retentionArmed', { count: String(count), days: String(days) }));
    expect(said).toBe(want);
    expect(said.startsWith(HEAD)).toBe(true);
  });

  it.each([
    [1, 'Automatic issue cleanup is on. No issue has been closed for over 1 day yet.'],
    [30, 'Automatic issue cleanup is on. No issue has been closed for over 30 days yet.'],
  ] as const)('B6-AC-05 inbox.retentionArmedNone en, days %i: 수에 맞는 단수·복수와 같은 머리', (days, want) => {
    const said = inLanguage('en', () => tf('inbox.retentionArmedNone', { days: String(days) }));
    expect(said).toBe(want);
    expect(said.startsWith(HEAD)).toBe(true);
  });

  it('B6-AC-05 한국어 두 문장은 그대로다', () => {
    expect([messages.ko['inbox.retentionArmed'], messages.ko['inbox.retentionArmedNone']]).toEqual([
      '문의 자동 정리가 켜졌습니다. 내일부터 종료된 지 {days}일이 지난 문의 {count}건을 휴지통으로 옮깁니다',
      '문의 자동 정리가 켜졌습니다. 지금은 종료된 지 {days}일이 지난 문의가 없습니다.',
    ]);
    expect(inLanguage('ko', () => tf('inbox.retentionArmed', { count: '1', days: '1' }))).toBe('문의 자동 정리가 켜졌습니다. 내일부터 종료된 지 1일이 지난 문의 1건을 휴지통으로 옮깁니다');
  });
});
