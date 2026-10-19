// 11 묶음 design.md 0절 9: 두 로그인 화면(C-1, P-1)이 같이 쓰는 OIDC 조각. 화면 테스트(C1Login.11, P1Login.11)는 jsdom이
// 다른 문서로 옮기는 주소를 남기지 않아 "이동 중…"까지만 본다. 여기서는 옮겨 가는 주소를 본다: 단추를 누르면 페이지를
// `/api/public/oidc/start`로 한 번 옮기고, 화면이 받은 `next`를 쿼리로 넘기고(없으면 쿼리 없음), 옮기는 동안 다시 누르면
// 옮기지 않는다(S11-AC-31, screens.md "단추를 누름"). 10a의 내보내기 확인 도움(console/src/app/exportDownload.10a.test.ts)처럼
// 옮기는 함수를 조각에 넘겨 받는다.
//
// 이 파일만 구현의 이름에 기댄다: `./OidcSignIn`의 `OidcSignIn`이 `next`(문자열 또는 null)와 `navigate`(주소를 받아 옮김)를
// 받는다고 본다. 켜짐을 조각이 받는지 스스로 읽는지는 구현 몫이라, 켜짐을 줄 만한 값(`oidc`, `enabled`, `load`)과 켜진
// sign-in-options 답을 모두 둔다. 모듈은 구현과 함께 생긴다. 그 전에는 불러오기 실패가 아니라 단언에서 실패한다.
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { createElement, type ComponentType } from 'react';
import { MemoryRouter } from 'react-router';
import { beforeEach, describe, expect, test } from 'vitest';
import { server, settle } from './__tests__/server';

const START = '/api/public/oidc/start';
const BUTTON = '회사 계정으로 로그인';
const MOVING = '이동 중…';

interface Props {
  next: string | null;
  navigate: (url: string) => void;
}

async function piece(): Promise<ComponentType<Props>> {
  const where = './OidcSignIn';
  let made: unknown;
  try {
    made = await import(/* @vite-ignore */ where);
  } catch {
    made = undefined;
  }
  const component = (made as { OidcSignIn?: unknown } | undefined)?.OidcSignIn;
  expect(typeof component, './OidcSignIn.tsx의 OidcSignIn').toBe('function');
  return component as ComponentType<Props>;
}

/** Draws the piece with OIDC on and keeps where it went. */
async function drawn(next: string | null) {
  const OidcSignIn = await piece();
  const went: string[] = [];
  const on = { oidc: true, enabled: true, load: () => Promise.resolve({ ok: true, data: { oidc: true }, response: new Response() }) };
  const props = { ...on, next, navigate: (url: string) => went.push(url) } as Props;
  render(createElement(MemoryRouter, null, createElement(OidcSignIn, props)));
  const button = await waitFor(() => {
    const found = screen.queryByRole('button', { name: BUTTON });
    expect(found, `단추 "${BUTTON}"`).not.toBeNull();
    return found as HTMLElement;
  });
  return { went, button, user: userEvent.setup() };
}

beforeEach(() => {
  server.use(http.get('*/api/public/sign-in-options', () => HttpResponse.json({ oidc: true })));
});

describe('OIDC 조각 (11 묶음)', () => {
  test.each([
    ['보던 화면', '/issues/ACME-7?status=RECEIVED'],
    ['첫 화면', '/'],
  ])('S11-AC-31 단추를 누름(next = %s): 시작 주소로 한 번 옮기고 next를 그대로 넘긴다', async (_, next) => {
    const { went, button, user } = await drawn(next);

    await user.click(button);

    expect(went, '옮겨 간 주소').toHaveLength(1);
    const url = new URL(went[0] ?? '', 'http://console.localhost');
    expect(url.pathname).toBe(START);
    expect(url.searchParams.get('next')).toBe(next);
    expect([...url.searchParams.keys()], '쿼리 이름').toEqual(['next']);
  });

  test('S11-AC-31 단추를 누름(next 없음): 쿼리 없이 시작 주소로 옮긴다', async () => {
    const { went, button, user } = await drawn(null);

    await user.click(button);

    expect(went).toEqual([START]);
  });

  test('S11-AC-31 옮기는 동안: 단추가 "이동 중…"이고 눌리지 않으며, 다시 눌러도 옮기지 않는다', async () => {
    const { went, button, user } = await drawn('/issues');

    await user.click(button);
    const moving = await waitFor(() => {
      const found = screen.queryAllByRole('button').find((b) => b.textContent.trim() === MOVING);
      expect(found ?? null, `"${MOVING}"`).not.toBeNull();
      return found as HTMLElement;
    });
    expect(moving.getAttribute('aria-disabled') === 'true' || (moving as HTMLButtonElement).disabled, '눌리지 않음').toBe(true);
    await user.click(moving);
    await settle();

    expect(went, '옮긴 수').toHaveLength(1);
  });
});
