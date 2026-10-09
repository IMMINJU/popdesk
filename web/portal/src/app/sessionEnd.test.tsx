// verify.md 6절 V-3. 세션이 끝난 뒤 화면을 옮기면 떠난 화면의 401이 지금 화면의 401보다 먼저 올
// 수 있다. 떠난 화면의 401이 세션을 비우면 layout이 아무것도 그리지 않고, 지금 화면도 내려가
// 그 401도 버려져 빈 화면에 갇힌다. screens.md 2절 "401 (불러올 때)": 로그인 화면으로 가고,
// 돌아올 주소는 지금 화면이다. P-1 세션 끝남: 안내 띠를 보인다.
import { act } from '@testing-library/react';
import { describe, expect, test } from 'vitest';
import { atPath, errors, gate, link, on, renderAt, sent, server, state, statusWith } from '../__tests__/portal';

/** Resolves when the fake server has answered `method path`. */
function mocked(method: string, path: string): Promise<void> {
  return new Promise((resolve) => {
    const listener = ({ request }: { request: Request }) => {
      if (request.method === method && new URL(request.url).pathname === path) {
        server.events.removeListener('response:mocked', listener);
        resolve();
      }
    };
    server.events.on('response:mocked', listener);
  });
}

/**
 * Lets an answer already released reach the screen code: two more full round
 * trips, inside `act` so React draws what the answer changed before the next step.
 */
async function settle() {
  await act(async () => {
    for (let i = 0; i < 2; i += 1) {
      await fetch(new URL('/api/public/csrf', window.location.href));
    }
  });
}

describe('세션 끝남과 화면 이동', () => {
  test('P-4 P-3 V-3 세션이 끝난 채 P-4에서 P-3으로 옮기고 P-4의 401이 먼저 와도, 빈 화면이 아니라 로그인 화면으로 간다', async () => {
    const leftScreen = gate();
    const currentScreen = gate();
    // P-4 reads the companies; P-3 reads the issues (and the companies, which P-4's release answers too).
    on('get', '/api/portal/companies', async () => {
      await leftScreen.opened;
      return errors.unauthenticated();
    });
    on('get', '/api/portal/issues', async () => {
      await currentScreen.opened;
      return errors.unauthenticated();
    });
    const { user } = renderAt('/issues/new');
    // The frame read /me before P-4 opened; from here on the session has ended everywhere.
    await sent('GET', '/api/portal/companies');
    state.signedIn = false;

    await user.click(await link(/popdesk/));
    await atPath('/issues');
    await sent('GET', '/api/portal/issues');

    const leftAnswered = mocked('GET', '/api/portal/companies');
    leftScreen.open();
    await leftAnswered;
    await settle();

    const currentAnswered = mocked('GET', '/api/portal/issues');
    currentScreen.open();
    await currentAnswered;
    await settle();

    await atPath('/login');
    expect(new URLSearchParams(window.location.search).get('next'), '돌아올 주소').toBe('/issues');
    await statusWith('로그인이 끝났습니다. 다시 로그인하세요.');
  });
});
