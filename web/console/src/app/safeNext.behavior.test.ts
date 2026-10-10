// screens.md 2절 401: 로그인 뒤 돌아갈 `next`는 이 앱 안의 경로만(C-1). C-10 변이 테스트에서 메인이 짚은 주소들:
// //host, /\host, 인코딩한 슬래시, 앞에 붙인 빈칸·탭, 스킴, 제어 문자, 로그인·초대 화면으로 되돌아가기.
import { describe, expect, test } from 'vitest';
import { safeNext } from './sessionContext';

describe('C-1 safeNext', () => {
  test.each([
    ['없음', null],
    ['빈 값', ''],
    ['//host', '//evil.example/issues'],
    ['/\\host', '/\\evil.example/issues'],
    ['인코딩이 한 번 풀린 //', '%2F%2Fevil.example'],
    ['스킴 있는 주소', 'https://evil.example/issues'],
    ['javascript:', 'javascript:alert(1)'],
    ['앞에 빈칸', ' /issues'],
    ['앞에 빈칸과 //', ' //evil.example'],
    ['앞에 탭', '\t//evil.example'],
    ['경로 안의 탭', '/\t/evil.example'],
    ['경로 안의 줄바꿈', '/issues\n'],
    ['경로 안의 NUL', '/issues\u0000'],
    ['로그인 화면', '/login'],
    ['로그인 화면과 next', '/login?next=%2F%2Fevil.example'],
    ['초대 화면', '/invite'],
    ['초대 화면 아래', '/invite/accept'],
  ])('C-1 safeNext 막음: %s → /issues', (_, next) => {
    expect(safeNext(next)).toBe('/issues');
  });

  test.each([
    ['문의 목록', '/issues'],
    ['문의와 쪽', '/issues?page=2'],
    ['고객사', '/companies/ACME'],
    ['경로 안의 인코딩한 슬래시는 이 앱 안의 경로다', '/%2F%2Fevil.example'],
    ['login으로 시작하는 다른 경로', '/logins'],
    ['빈칸(0x20)이 든 경로', '/issues?q=로그인 오류'],
  ])('C-1 safeNext 통과: %s', (_, next) => {
    expect(safeNext(next)).toBe(next);
  });
});
