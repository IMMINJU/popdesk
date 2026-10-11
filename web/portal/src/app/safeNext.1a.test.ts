// 1a design.md 5-1절: 로그인 뒤 돌아갈 `next`로 /forgot-password, /reset-password는 받지 않는다(/login, /invite와 같게).
import { describe, expect, test } from 'vitest';
import { safeNext } from './sessionContext';

describe('P-1 safeNext (1a)', () => {
  test.each([
    ['비밀번호 찾기', '/forgot-password'],
    ['비밀번호 찾기와 쿼리', '/forgot-password?x=1'],
    ['비밀번호 재설정', '/reset-password'],
    ['비밀번호 재설정과 토큰 조각', '/reset-password#token=rst-Hk3sV8pQ2mTx'],
    ['비밀번호 재설정 아래', '/reset-password/again'],
  ])('P-1 safeNext 막음: %s → /issues', (_, next) => {
    expect(safeNext(next)).toBe('/issues');
  });
});
