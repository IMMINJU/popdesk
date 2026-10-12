// tasks.md 7절 "C-7 점검으로 정한 것": 콘솔의 `/`는 문의 목록 /issues(C-3)로 보낸다.
// 3번 묶음부터 `/`는 "내 담당" 보기의 쿼리를 실어 보낸다(S3-AC-77).
// 로그인하지 않았으면 C-3이 401을 받아 로그인으로 간다(screens.md 2절).
import { describe, expect, test } from 'vitest';
import { atPath, found, hrefPath, renderAt, screen, state } from './__tests__/console';

describe('콘솔 /', () => {
  test('C-3 /: 문의 목록 /issues로 보낸다', async () => {
    renderAt('/');

    await atPath('/issues');
    const query = new URLSearchParams(window.location.search);
    expect(query.get('assignee'), '"내 담당"의 쿼리').toBe('me');
    expect(query.getAll('status').sort()).toEqual(['CONFIRMED', 'IN_PROGRESS', 'RECEIVED', 'RESOLVED']);
    const title = await found(() => screen.queryByRole('link', { name: /로그인이 안 됩니다/ }), '문의 제목 링크');
    expect(hrefPath(title)).toBe('/issues/ACME-7');
  });

  test('C-3 / 로그인 안 함: /issues가 401을 받아 /login으로 가고 next는 /issues로 시작한다', async () => {
    state.signedIn = false;
    renderAt('/');

    await atPath('/login');
    expect((new URLSearchParams(window.location.search).get('next') ?? '').split('?')[0]).toBe('/issues');
  });
});
