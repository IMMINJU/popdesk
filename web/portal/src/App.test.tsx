// tasks.md 7절 "C-7 점검으로 정한 것": 포털의 `/`는 문의 목록 /issues(P-3)로 보낸다.
// 로그인하지 않았으면 P-3이 401을 받아 로그인으로 간다(screens.md 2절).
import { describe, expect, test } from 'vitest';
import { atPath, link, renderAt, state } from './__tests__/portal';

describe('포털 /', () => {
  test('P-3 /: 문의 목록 /issues로 보낸다', async () => {
    renderAt('/');

    await atPath('/issues');
    await link(/ACME-7/);
  });

  test('P-3 / 로그인 안 함: /issues가 401을 받아 /login?next=/issues로 간다', async () => {
    state.signedIn = false;
    renderAt('/');

    await atPath('/login');
    expect(new URLSearchParams(window.location.search).get('next')).toBe('/issues');
  });
});
