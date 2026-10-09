// screens.md 5절 21·22 파일 크기 글자(formatBytes)와 글자 수(codePoints). C-10 변이 테스트가 찾은 빈칸: 단위 경계.
import { describe, expect, test } from 'vitest';
import { codePoints, formatBytes } from './format';

describe('formatBytes 동작', () => {
  test('formatBytes 단위 경계: 1024 미만은 B, 1024부터 KB, 1MB부터 MB', () => {
    expect(formatBytes(0)).toBe('0 B');
    expect(formatBytes(1023)).toBe('1023 B');
    expect(formatBytes(1024)).toBe('1.0 KB');
    expect(formatBytes(1536)).toBe('1.5 KB');
    expect(formatBytes(1024 * 1024 - 1)).toBe('1024.0 KB');
    expect(formatBytes(1024 * 1024)).toBe('1.0 MB');
    expect(formatBytes(10 * 1024 * 1024)).toBe('10.0 MB');
  });

  test('codePoints: 이모지 하나는 한 글자로 센다(서버와 같은 셈)', () => {
    expect(codePoints('가😀a')).toBe(3);
  });
});
