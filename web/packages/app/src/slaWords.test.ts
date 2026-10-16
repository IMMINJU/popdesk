import { describe, expect, test } from 'vitest';
import { slaLength, slaWords } from './slaWords';

describe('slaWords', () => {
  test.each([
    [200, '3시간 20분 남음'],
    [50, '50분 남음'],
    [61, '1시간 1분 남음'],
    [0, '0분 남음'],
    [1440, '1일 남음'],
    [-1560, '1일 2시간 넘김'],
    [-1500, '1일 1시간 넘김'],
    [-45, '45분 넘김'],
    [-1051199, '729일 23시간 59분 넘김'],
  ])('%i분 → %s', (minutes, words) => {
    expect(slaWords(minutes)).toBe(words);
  });

  test.each([
    [480, '8시간'],
    [3120, '2일 4시간'],
    [525600, '365일'],
    [1, '1분'],
    [1500, '1일 1시간'],
  ])('길이 %i분 → %s', (minutes, words) => {
    expect(slaLength(minutes)).toBe(words);
  });
});
