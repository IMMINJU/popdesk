// 10a screens.md, design.md 0절 10 (S10A-AC-30): 익명화 대화상자의 이메일 맞춤. 친 글의 앞뒤 공백을 떼고 대소문자 없이 같을 때만 참이다.
import { describe, expect, test } from 'vitest';
import { matchesEmail } from './matchesEmail';

const EMAIL = 'Kim.Minju@Example.test';

describe('C-10 matchesEmail (10a)', () => {
  test.each([
    ['그대로', 'Kim.Minju@Example.test', EMAIL],
    ['친 쪽이 소문자', 'kim.minju@example.test', EMAIL],
    ['친 쪽이 대문자', 'KIM.MINJU@EXAMPLE.TEST', EMAIL],
    ['저장된 쪽이 대문자', 'kim.minju@example.test', 'KIM.MINJU@EXAMPLE.TEST'],
    ['앞뒤 공백', '  Kim.Minju@Example.test  ', EMAIL],
    ['앞뒤 탭과 줄바꿈', '\tkim.minju@example.test\n', EMAIL],
  ])('S10A-AC-30 맞음: %s', (_, typed, email) => {
    expect(matchesEmail(typed, email)).toBe(true);
  });

  test.each([
    ['빈 글', '', EMAIL],
    ['공백만', '   ', EMAIL],
    ['안에 공백', 'Kim.Minju @Example.test', EMAIL],
    ['앞부분만', 'Kim.Minju@Example.tes', EMAIL],
    ['뒷부분만', 'im.Minju@Example.test', EMAIL],
    ['한 글자 더', 'Kim.Minju@Example.testx', EMAIL],
    ['다른 사람', 'lee@example.test', EMAIL],
  ])('S10A-AC-30 틀림: %s', (_, typed, email) => {
    expect(matchesEmail(typed, email)).toBe(false);
  });
});
