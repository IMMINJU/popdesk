// screens.md 2절 401(불러올 때): 로그인 화면으로 보내고 돌아올 주소(`next`)는 지금 화면이다. `next`는 이 앱 안의 경로만 받는다.
// 떠난 화면의 401은 아무것도 바꾸지 않는다(V-3, S1A-AC-58).
import { act, renderHook } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { framedHook } from './__tests__/frame';
import { safeNext, useSession, useToLogin, type LoginState } from './sessionContext';

describe('safeNext', () => {
  it.each<[string, string | null]>([
    ['null', null],
    ['빈 글', ''],
    ['/로 시작하지 않는 경로', 'issues'],
    ['바깥 주소', 'https://evil.example/issues'],
    ['스킴만 다른 바깥 주소', 'http://evil.example'],
    ['javascript:', 'javascript:alert(1)'],
    ['//로 시작', '//evil.example/issues'],
    ['//만', '//'],
    ['/\\로 시작', '/\\evil.example'],
    ['\\로 시작', '\\evil.example'],
    ['앞의 빈칸', ' /issues'],
    ['앞의 탭', '\t/issues'],
    ['경로 안의 탭', '/\t/evil.example'],
    ['끝의 줄바꿈', '/issues\n'],
    ['끝의 CR', '/issues\r'],
    ['NUL', '/issues\u0000'],
    ['0x1f', '/issues\u001f'],
    ['로그인 화면', '/login'],
    ['로그인 화면과 쿼리', '/login?next=%2Fissues'],
    ['초대 화면', '/invite'],
    ['초대 화면과 토큰 조각', '/invite#token=inv-1'],
    ['초대 화면 아래', '/invite/accept'],
    ['비밀번호 찾기', '/forgot-password'],
    ['비밀번호 찾기와 쿼리', '/forgot-password?x=1'],
    ['비밀번호 재설정', '/reset-password'],
    ['비밀번호 재설정과 토큰 조각', '/reset-password#token=rst-1'],
  ])('screens.md 2절 safeNext 막음 %s → /issues', (_, next) => {
    expect(safeNext(next)).toBe('/issues');
  });

  it.each([
    ['/'],
    ['/issues'],
    ['/issues?page=2&status=OPEN'],
    ['/issues/ACME-7#comment-3'],
    ['/companies/ACME'],
    ['/logins'],
    ['/login-history'],
    ['/me/login'],
    ['/issues?next=//evil.example'],
    ['/a//b'],
    ['/a\\b'],
    ['/%2F%2Fevil.example'],
    ['/issues?q=로그인 오류'],
    ['/issues?q=\u007f'],
  ])('screens.md 2절 safeNext 통과 %s', (next) => {
    expect(safeNext(next)).toBe(next);
  });
});

describe('useSession', () => {
  it('screens.md P-1 세션 밖에서 부르면 까닭을 적은 오류를 던진다', () => {
    const quiet = vi.spyOn(console, 'error').mockImplementation(() => undefined);

    expect(() => renderHook(() => useSession())).toThrow('useSession outside SessionProvider');

    quiet.mockRestore();
  });

  it('screens.md P-1 세션 안에서는 틀이 준 값을 준다', () => {
    const { now, setMe } = framedHook(() => useSession<{ name: string }>(), null);

    expect(now().me).toEqual({ name: '김민주' });
    expect(now().hadSession).toBe(true);
    expect(now().setMe).toBe(setMe);
  });
});

describe('useToLogin', () => {
  it.each([
    ['세션이 있었으면 끝남 표시', '/issues/ACME-7?tab=all&q=a%20b', true],
    ['세션이 없었으면 끝남 표시가 없다', '/companies', false],
  ])('screens.md 2절 401 %s: 세션을 비우고 /login?next=지금 주소로 바꾼다', (_, url, hadSession) => {
    const { now, router, setMe } = framedHook(() => useToLogin(), null, url, hadSession);

    act(() => {
      now()();
    });

    expect(setMe.mock.calls).toEqual([[null]]);
    expect(router.state.location.pathname).toBe('/login');
    expect(router.state.location.search).toBe(`?next=${encodeURIComponent(url)}`);
    expect(new URLSearchParams(router.state.location.search).get('next')).toBe(url);
    expect(router.state.historyAction, '되돌아가기 기록을 남기지 않는다').toBe('REPLACE');
    expect(router.state.location.state as LoginState).toEqual({ expired: hadSession });
  });

  it('V-3 화면이 사라진 뒤에 온 401은 세션도 주소도 바꾸지 않는다', () => {
    const { now, router, setMe, unmount } = framedHook(() => useToLogin(), null);
    const late = now();
    unmount();

    late();

    expect(setMe).not.toHaveBeenCalled();
    expect(router.state.location.pathname).toBe('/issues');
  });

  it('V-3 다른 화면으로 옮긴 뒤에 온 앞 화면의 401도 아무것도 바꾸지 않는다', async () => {
    const { now, router, setMe } = framedHook(() => useToLogin(), null);
    const late = now();
    await act(async () => {
      await router.navigate('/other');
    });

    late();

    expect(setMe).not.toHaveBeenCalled();
    expect(router.state.location.pathname).toBe('/other');
  });
});
