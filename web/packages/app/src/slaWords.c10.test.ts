// 8a screens.md 2·3·4절: SLA 분을 길이 글과 남은 시간 글로 쓴다. 하루는 24시간이다.
// 본문을 읽지 않고 시그니처와 주석으로 썼다(C-10).
import { describe, expect, test } from 'vitest';
import { slaLength, slaWords } from './slaWords';

describe('slaLength', () => {
  test.each([
    [0, '0분'],
    [1, '1분'],
    [59, '59분'],
    [60, '1시간'],
    [61, '1시간 1분'],
    [200, '3시간 20분'],
    [480, '8시간'],
    [1439, '23시간 59분'],
    [1440, '1일'],
    [1441, '1일 1분'],
    [1500, '1일 1시간'],
    [3120, '2일 4시간'],
    [525600, '365일'],
  ])('C-10 slaLength(%i)는 "%s"', (minutes, words) => {
    expect(slaLength(minutes)).toBe(words);
  });

  test('C-10 slaLength 하루는 24시간: 24시간은 "1일"이지 "24시간"이 아니다', () => {
    expect(slaLength(24 * 60)).not.toContain('시간');
  });
});

describe('slaWords', () => {
  test.each([
    [0, '0분 남음'],
    [1, '1분 남음'],
    [59, '59분 남음'],
    [60, '1시간 남음'],
    [61, '1시간 1분 남음'],
    [200, '3시간 20분 남음'],
    [1439, '23시간 59분 남음'],
    [1440, '1일 남음'],
    [1441, '1일 1분 남음'],
    [525600, '365일 남음'],
  ])('C-10 slaWords(%i)는 "%s"', (minutes, words) => {
    expect(slaWords(minutes)).toBe(words);
  });

  test.each([
    [-1, '1분 넘김'],
    [-59, '59분 넘김'],
    [-60, '1시간 넘김'],
    [-61, '1시간 1분 넘김'],
    [-1440, '1일 넘김'],
    [-1441, '1일 1분 넘김'],
    [-1560, '1일 2시간 넘김'],
    [-525600, '365일 넘김'],
  ])('C-10 slaWords(%i) 기한이 지나면 "%s"', (minutes, words) => {
    expect(slaWords(minutes)).toBe(words);
  });

  test('C-10 slaWords 넘긴 글에는 빼기 부호가 없다', () => {
    expect(slaWords(-90)).not.toContain('-');
  });
});
