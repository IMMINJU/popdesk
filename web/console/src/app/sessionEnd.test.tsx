// verify.md 6절 V-3. 세션이 끝난 뒤 화면을 옮기면 떠난 화면의 401이 지금 화면의 401보다 먼저 올
// 수 있다. 떠난 화면의 401이 세션을 비우면 layout이 아무것도 그리지 않고, 지금 화면도 내려가
// 그 401도 버려져 빈 화면에 갇힌다. screens.md 2절 "401 (불러올 때)": 로그인 화면으로 가고,
// 돌아올 주소는 지금 화면이다. P-1 세션 끝남: 안내 띠를 보인다.
import { act } from '@testing-library/react';
import { describe, expect, test } from 'vitest';
import { atPath, errors, gate, link, on, renderAt, sent, server, state, statusWith } from '../__tests__/console';

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
  test('C-3 C-8 V-3 세션이 끝난 채 C-3에서 C-8로 옮기고 C-3의 401이 먼저 와도, 빈 화면이 아니라 로그인 화면으로 간다', async () => {
    const leftScreen = gate();
    const currentScreen = gate();
    on('get', '/api/console/issues', async () => {
      await leftScreen.opened;
      return errors.unauthenticated();
    });
    on('get', '/api/console/companies', async () => {
      await currentScreen.opened;
      return errors.unauthenticated();
    });
    const { user } = renderAt('/issues');
    // The frame read /me before C-3 opened; from here on the session has ended everywhere.
    await sent('GET', '/api/console/issues');
    state.signedIn = false;

    await user.click(await link('고객사'));
    await atPath('/companies');
    await sent('GET', '/api/console/companies');

    const leftAnswered = mocked('GET', '/api/console/issues');
    leftScreen.open();
    await leftAnswered;
    await settle();

    const currentAnswered = mocked('GET', '/api/console/companies');
    currentScreen.open();
    await currentAnswered;
    await settle();

    await atPath('/login');
    expect(new URLSearchParams(window.location.search).get('next'), '돌아올 주소').toBe('/companies');
    await statusWith('로그인이 끝났습니다. 다시 로그인하세요.');
  });
});
