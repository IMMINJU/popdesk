// An answer that comes back after its screen is gone must not move the address bar.
// The router outlives the screen, so a late 401 or a late "accepted" used to send
// whatever screen came next to /login or /issues (CI, C-2 tests after C-2 성공).
import { describe, expect, test } from 'vitest';
import { button, errors, field, gate, on, renderAt, sent, state } from '../__tests__/console';

/** Lets an answer already released reach the screen code: two more full round trips. */
async function settle() {
  for (let i = 0; i < 2; i += 1) {
    await fetch(new URL('/api/public/csrf', window.location.href));
  }
}

describe('늦게 온 답', () => {
  test('C-3을 떠난 뒤 온 목록 401은 주소를 /login으로 바꾸지 않는다', async () => {
    const release = gate();
    on('get', '/api/console/issues', async () => {
      await release.opened;
      return errors.unauthenticated();
    });
    const { unmount } = renderAt('/issues');
    await sent('GET', '/api/console/issues');

    unmount();
    window.history.replaceState(null, '', '/invite');
    release.open();
    await settle();

    expect(window.location.pathname).toBe('/invite');
  });

  test('C-2를 떠난 뒤 온 수락 성공은 주소를 /issues로 바꾸지 않는다', async () => {
    state.signedIn = false;
    const release = gate();
    on('post', '/api/public/invitations/accept', async () => {
      await release.opened;
      state.signedIn = true;
      return new Response(null, { status: 204 });
    });
    const { user, unmount } = renderAt('/invite#token=inv-late-answer');
    await user.type(await field('비밀번호', 'input'), 'new password 2026');
    await user.type(await field('비밀번호 확인', 'input'), 'new password 2026');
    await user.click(await button('시작하기'));
    await sent('POST', '/api/public/invitations/accept');

    unmount();
    window.history.replaceState(null, '', '/login');
    release.open();
    await sent('GET', '/api/console/me');
    await settle();

    expect(window.location.pathname).toBe('/login');
  });
});
