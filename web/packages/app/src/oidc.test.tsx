// 11 묶음 design.md 0절 9: 두 로그인 화면이 같이 쓰는 OIDC 판단. 시작 주소(S11-AC-31), 돌아온 `oidc` 값의 띠(S11-AC-32),
// sign-in-options를 한 번 묻는 훅. 화면 테스트(C1Login.11, P1Login.11)는 알려진 값의 띠를 본다. 여기서는 그 둘레를 본다:
// 모르는 값과 Object의 이름 같은 값, 주소에서 값만 빼고 나머지 쿼리는 두는 것, 기록을 쌓지 않는 것, 묻는 동안과 실패.
import { act, render, renderHook, screen, waitFor } from '@testing-library/react';
import { createMemoryRouter, RouterProvider } from 'react-router';
import { describe, expect, it, test } from 'vitest';
import { OIDC_START, oidcStartUrl, useOidcBand, useSignInOptions } from './oidc';
import type { Result } from './result';
import { gate } from './__tests__/server';

describe('oidcStartUrl', () => {
  test('S11-AC-31 next가 없으면 쿼리 없는 시작 주소다', () => {
    expect(oidcStartUrl(null)).toBe('/api/public/oidc/start');
    expect(OIDC_START).toBe('/api/public/oidc/start');
  });

  it.each([
    ['/issues', '/api/public/oidc/start?next=%2Fissues'],
    ['/issues?page=2&q=a b', '/api/public/oidc/start?next=%2Fissues%3Fpage%3D2%26q%3Da+b'],
    ['', '/api/public/oidc/start?next='],
  ])('S11-AC-31 next "%s"는 받은 그대로 한 쿼리 값으로 넘긴다', (next, url) => {
    expect(oidcStartUrl(next)).toBe(url);
    expect(new URL(url, 'http://h').searchParams.get('next')).toBe(next);
  });
});

/** Draws the band of `search` on a router page and gives the router. */
function bandAt(search: string) {
  function Band() {
    const band = useOidcBand();
    return (
      <>
        <p data-testid="band">{band.text ?? '(없음)'}</p>
        <button type="button" onClick={band.clear}>
          지우기
        </button>
      </>
    );
  }
  const router = createMemoryRouter([{ path: '/login', element: <Band /> }], { initialEntries: [`/login${search}`] });
  render(<RouterProvider router={router} />);
  return router;
}

const FAILED = '회사 계정으로 로그인하지 못했습니다. 초대받은 이메일인지 확인하거나 관리자에게 문의하세요.';
const UNAVAILABLE = '회사 로그인 서비스에 연결하지 못했습니다. 잠시 뒤 다시 시도하거나 비밀번호로 로그인하세요.';
const BUSY = '시도가 너무 많습니다. 잠시 뒤 다시 시도하세요.';

describe('useOidcBand', () => {
  it.each([
    ['failed', FAILED],
    ['unavailable', UNAVAILABLE],
    ['busy', BUSY],
  ])('S11-AC-32 oidc=%s는 자기 띠 문구다', async (value, text) => {
    bandAt(`?oidc=${value}`);

    expect(await screen.findByText(text)).toBeInTheDocument();
  });

  it.each(['', 'FAILED', 'other', 'constructor', 'toString', '__proto__', 'hasOwnProperty'])(
    'S11-AC-32 모르는 oidc 값 "%s"는 띠가 없다(Object의 이름도 띠가 아니다)',
    async (value) => {
      const router = bandAt(`?oidc=${encodeURIComponent(value)}`);

      await waitFor(() => {
        expect(router.state.location.search).toBe('');
      });
      expect(screen.getByTestId('band')).toHaveTextContent('(없음)');
    },
  );

  test('S11-AC-32 oidc 값은 바로 주소에서 빠지고 다른 쿼리는 남으며, 기록을 쌓지 않고 바꾼다', async () => {
    const router = bandAt('?next=%2Fissues&oidc=failed&page=2');

    await waitFor(() => {
      expect(router.state.location.search).not.toContain('oidc');
    });
    const left = new URLSearchParams(router.state.location.search);
    expect([...left.entries()]).toEqual([
      ['next', '/issues'],
      ['page', '2'],
    ]);
    expect(router.state.historyAction).toBe('REPLACE');
    expect(screen.getByTestId('band')).toHaveTextContent(FAILED);
  });

  test('S11-AC-32 oidc 값이 없으면 주소를 건드리지 않는다', async () => {
    const router = bandAt('?next=%2Fissues');

    expect(await screen.findByText('(없음)')).toBeInTheDocument();
    expect(router.state.historyAction).toBe('POP');
    expect(router.state.location.search).toBe('?next=%2Fissues');
  });

  test('S11-AC-32 clear는 띠를 거둔다(비밀번호 로그인이 부른다)', async () => {
    bandAt('?oidc=busy');
    expect(await screen.findByText(BUSY)).toBeInTheDocument();

    act(() => {
      screen.getByRole('button', { name: '지우기' }).click();
    });

    expect(screen.getByTestId('band')).toHaveTextContent('(없음)');
  });
});

describe('useSignInOptions', () => {
  const answer = (oidc: boolean): Result<{ oidc: boolean }> => ({ ok: true, data: { oidc }, response: new Response() });

  test('S11-AC-31 묻는 동안은 false이고, 켜졌다는 답이 오면 true다. 한 번만 묻는다', async () => {
    const held = gate();
    let asked = 0;
    const load = async () => {
      asked += 1;
      await held.opened;
      return answer(true);
    };

    const { result, rerender } = renderHook(() => useSignInOptions(load));
    expect(result.current).toBe(false);

    held.open();
    await waitFor(() => {
      expect(result.current).toBe(true);
    });
    rerender();
    expect(asked).toBe(1);
  });

  it.each<[string, Result<{ oidc: boolean }>]>([
    ['꺼졌다는 답', answer(false)],
    ['서버 오류', { ok: false, failure: { kind: 'server' } }],
    ['연결 끊김', { ok: false, failure: { kind: 'network' } }],
  ])('S11-AC-31 %s이면 false 그대로다', async (_what, result) => {
    let settled = false;
    const load = async () => {
      await Promise.resolve();
      settled = true;
      return result;
    };

    const hook = renderHook(() => useSignInOptions(load));

    await waitFor(() => {
      expect(settled).toBe(true);
    });
    await act(async () => {
      await Promise.resolve();
    });
    expect(hook.result.current).toBe(false);
  });
});
