// 1a screens.md 1절 C-17 비밀번호 재설정(P-10과 같다). 메일의 링크(#token=)로 온다.
// 토큰은 주소 조각으로만 받고 요청 본문으로 보낸다. 성공해도 로그인시키지 않는다(S1A-AC-09~11).
import { describe, expect, test } from 'vitest';
import {
  atPath,
  button,
  calls,
  callsTo,
  describedText,
  errors,
  eventually,
  expectFieldError,
  expectNoAxeViolations,
  field,
  gate,
  hrefPath,
  on,
  pageText,
  queryAction,
  renderAt,
  screen,
  sent,
  state,
  statusWith,
} from '../__tests__/console';
import { answered, rejected, settle } from '../__tests__/console1a';

const TOKEN = 'rst-Hk3sV8pQ2mTx';
const RESET_URL = `/reset-password#token=${TOKEN}`;
const CONFIRM = '/api/public/password-reset/confirm';
const INVALID_TEXT = '링크가 만료됐거나 이미 썼습니다.';
const DONE_TEXT = '비밀번호를 바꿨습니다. 새 비밀번호로 로그인하세요.';

const password = () => field('새 비밀번호', 'input');
const again = () => field(/한 번 더/, 'input');
const change = () => button('비밀번호 바꾸기');

type User = ReturnType<typeof renderAt>['user'];

async function fill(user: User, pw = 'new password 2026', second = pw) {
  await user.type(await password(), pw);
  await user.type(await again(), second);
}

async function expectInvalidLinkScreen() {
  await eventually(() => {
    expect(pageText()).toContain(INVALID_TEXT);
  });
  expect(screen.queryAllByLabelText('새 비밀번호', { selector: 'input' }), '안내로 바뀐 뒤의 비밀번호 칸').toHaveLength(0);
  const retry = queryAction('다시 요청하기');
  expect(retry, '"다시 요청하기"').not.toBeNull();
  return retry;
}

describe('C-17 비밀번호 재설정', () => {
  test('C-17 처음: 새 비밀번호 칸과 한 번 더 칸, 규칙 안내(10~72자), "비밀번호 바꾸기". 주소창에서 #token을 지우고 토큰을 미리 확인하지 않는다', async () => {
    state.signedIn = false;
    renderAt(RESET_URL);

    const pw = await password();
    expect(pw).toHaveAttribute('type', 'password');
    expect(pw).toHaveAttribute('autocomplete', 'new-password');
    await again();
    expect(describedText(pw), '비밀번호 규칙(10~72자)').toMatch(/10.*72/);
    await change();
    await eventually(() => {
      expect(window.location.hash).toBe('');
    });
    expect(window.location.pathname).toBe('/reset-password');
    expect(window.location.href).not.toContain(TOKEN);
    expect(callsTo('POST', CONFIRM), '토큰을 미리 확인하는 요청').toHaveLength(0);
  });

  test('S1A-AC-09 C-17 성공: 토큰을 본문으로만 보내고, 로그인시키지 않고 로그인 화면으로 가며 토스트를 보인다', async () => {
    state.signedIn = false;
    const { user } = renderAt(RESET_URL);
    await fill(user);
    await user.click(await change());

    const [confirm] = await sent('POST', CONFIRM);
    expect(await confirm?.body).toEqual({ token: TOKEN, password: 'new password 2026' });
    expect(calls.filter((c) => c.url.includes(TOKEN)), '토큰이 든 주소').toHaveLength(0);
    await atPath('/login');
    await statusWith(DONE_TEXT);
    await button('로그인');
    expect(state.signedIn, '재설정만으로 로그인되지 않는다').toBe(false);
  });

  test('C-17 두 칸이 다름: 서버에 보내지 않고 둘째 칸에 오류를 보인다', async () => {
    state.signedIn = false;
    const { user } = renderAt(RESET_URL);
    await fill(user, 'new password 2026', 'new password 2027');
    await user.click(await change());

    await expectFieldError(await again());
    expect(callsTo('POST', CONFIRM)).toHaveLength(0);
  });

  test.each(['TOO_SHORT', 'SAME_AS_EMAIL'])(
    'S1A-AC-11 C-17 400 INVALID_INPUT(%s): 규칙 오류를 칸 아래에 보이고, 같은 토큰으로 다시 낼 수 있다',
    async (code) => {
      state.signedIn = false;
      let tries = 0;
      on('post', CONFIRM, () => {
        tries += 1;
        return tries === 1 ? errors.invalid([{ field: 'password', code }]) : new Response(null, { status: 204 });
      });
      const { user } = renderAt(RESET_URL);
      await fill(user, 'short');
      await user.click(await change());

      const pw = await password();
      await expectFieldError(pw);
      expect(pageText()).not.toContain(INVALID_TEXT);

      await user.clear(pw);
      await user.clear(await again());
      await fill(user, 'a longer password 2026');
      await user.click(await change());

      const [, second] = await sent('POST', CONFIRM, 2);
      expect(await second?.body).toEqual({ token: TOKEN, password: 'a longer password 2026' });
      await atPath('/login');
    },
  );

  test('S1A-AC-10 C-17 400 링크 무효: 화면 전체를 "링크가 만료됐거나 이미 썼습니다." 안내로 바꾸고 "다시 요청하기"는 비밀번호 찾기로 간다', async () => {
    state.signedIn = false;
    on('post', CONFIRM, () => rejected('PASSWORD_RESET_INVALID'));
    const { user } = renderAt(RESET_URL);
    await fill(user);
    await user.click(await change());

    const retry = await expectInvalidLinkScreen();
    if (retry instanceof HTMLAnchorElement) expect(hrefPath(retry)).toBe('/forgot-password');
    if (retry) await user.click(retry);
    await atPath('/forgot-password');
  });

  test('S1A-AC-10 C-17 토큰 없이 옴: 서버에 묻지 않고 링크 무효와 같은 안내를 보인다', async () => {
    state.signedIn = false;
    renderAt('/reset-password');

    await expectInvalidLinkScreen();
    expect(callsTo('POST', CONFIRM)).toHaveLength(0);
  });

  test('S1A-AC-58 C-17 떠난 뒤 온 답: 화면을 떠난 뒤 온 성공은 주소를 /login으로 바꾸지 않는다', async () => {
    state.signedIn = false;
    const release = gate();
    on('post', CONFIRM, async () => {
      await release.opened;
      return new Response(null, { status: 204 });
    });
    const { user, unmount } = renderAt(RESET_URL);
    await fill(user);
    await user.click(await change());
    await sent('POST', CONFIRM);

    unmount();
    window.history.replaceState(null, '', '/invite');
    const done = answered('POST', CONFIRM);
    release.open();
    await done;
    await settle();

    expect(window.location.pathname).toBe('/invite');
  });

  test('C-17 axe: 두 칸이 다른 오류가 난 화면에 접근성 위반이 없다', async () => {
    state.signedIn = false;
    const { user } = renderAt(RESET_URL);
    await fill(user, 'new password 2026', 'new password 2027');
    await user.click(await change());
    await expectFieldError(await again());

    await expectNoAxeViolations();
  });
});
