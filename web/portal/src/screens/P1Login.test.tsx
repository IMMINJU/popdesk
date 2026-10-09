// screens.md 3절 P-1 로그인.
import { describe, expect, test } from 'vitest';
import {
  alertWith,
  atPath,
  button,
  callsTo,
  errors,
  eventually,
  expectFieldError,
  fieldState,
  expectNoAxeViolations,
  field,
  gate,
  heading1,
  isBlocked,
  on,
  pageText,
  renderAt,
  screen,
  sent,
  state,
  text,
} from '../__tests__/portal';

type User = ReturnType<typeof renderAt>['user'];

const email = () => field(/^이메일/, 'input');
const password = () => field('비밀번호', 'input');

async function fillAndSend(user: User, pw = 'wrong password 1') {
  await user.type(await email(), 'kim@acme.test');
  await user.type(await password(), pw);
  await user.click(await button('로그인'));
}

describe('P-1 로그인', () => {
  test('P-1 처음: 표시 이름, 이메일·비밀번호 칸, 보이기 전환, 로그인 버튼. 처음 포커스는 이메일 칸', async () => {
    state.signedIn = false;
    renderAt('/login');

    const emailField = await email();
    expect(emailField).toHaveAttribute('type', 'email');
    expect(emailField).toHaveAttribute('autocomplete', 'username');
    const pw = await password();
    expect(pw).toHaveAttribute('type', 'password');
    expect(pw).toHaveAttribute('autocomplete', 'current-password');
    await button(/비밀번호 (보이기|숨기기)/);
    await button('로그인');
    expect(pageText()).toContain('popdesk');
    await eventually(() => {
      expect(document.activeElement).toBe(emailField);
    });
  });

  test('P-1 접근성: 화면 제목을 document.title에 넣고 h1은 tabindex=-1', async () => {
    state.signedIn = false;
    renderAt('/login');
    await email();

    const h1 = await heading1();
    expect(h1).toHaveAttribute('tabindex', '-1');
    expect(document.title).toContain(text(h1));
  });

  test('P-1 키보드: 이메일 → 비밀번호 → 보이기 전환 → 로그인', async () => {
    state.signedIn = false;
    const { user } = renderAt('/login');
    const emailField = await email();
    emailField.focus();

    await user.tab();
    expect(document.activeElement).toBe(await password());
    await user.tab();
    expect(document.activeElement).toBe(await button(/비밀번호 (보이기|숨기기)/));
    await user.tab();
    expect(document.activeElement).toBe(await button('로그인'));
  });

  test('P-1 보이기 전환: 누르면 비밀번호가 글자로 보이고 다시 누르면 가린다', async () => {
    state.signedIn = false;
    const { user } = renderAt('/login');
    const pw = await password();
    await user.type(pw, 'secret-value');

    await user.click(await button(/비밀번호 (보이기|숨기기)/));
    expect(pw).toHaveAttribute('type', 'text');
    await user.click(await button(/비밀번호 (보이기|숨기기)/));
    expect(pw).toHaveAttribute('type', 'password');
  });

  test('P-1 성공: Enter로 보내면 이메일과 비밀번호를 보내고 P-3으로 간다', async () => {
    state.signedIn = false;
    const { user } = renderAt('/login');
    await user.type(await email(), 'kim@acme.test');
    await user.type(await password(), 'correct horse battery{Enter}');

    const [signIn] = await sent('POST', '/api/public/sign-in');
    expect(await signIn?.body).toEqual({ email: 'kim@acme.test', password: 'correct horse battery' });
    await atPath('/issues');
  });

  test('P-1 401: "이메일 또는 비밀번호가 맞지 않습니다." 띠, 비밀번호 칸만 비우고 포커스를 비밀번호 칸으로, 로그인 화면에 남는다', async () => {
    state.signedIn = false;
    on('post', '/api/public/sign-in', () =>
      Response.json({ code: 'SIGN_IN_FAILED', message: 'Sign-in failed.' }, { status: 401 }),
    );
    const { user } = renderAt('/login');
    await fillAndSend(user);

    await alertWith('이메일 또는 비밀번호가 맞지 않습니다.');
    const pw = await password();
    expect(pw).toHaveValue('');
    expect(await email()).toHaveValue('kim@acme.test');
    expect(document.activeElement).toBe(pw);
    expect(window.location.pathname).toBe('/login');
    expect(pageText()).not.toContain('로그인이 끝났습니다');
  });

  test('P-1 400 fields: 이메일 모양이 틀리면 이메일 칸 아래 오류, 포커스는 이메일 칸', async () => {
    state.signedIn = false;
    on('post', '/api/public/sign-in', () => errors.invalid([{ field: 'email', code: 'INVALID_FORMAT' }]));
    const { user } = renderAt('/login');
    const before = fieldState(await email());
    await fillAndSend(user);

    const emailField = await email();
    await expectFieldError(emailField, before);
    expect(document.activeElement).toBe(emailField);
  });

  test('P-1 429: "요청이 많습니다. N초 뒤 다시 하세요." 띠와 로그인 버튼 막힘', async () => {
    state.signedIn = false;
    on('post', '/api/public/sign-in', () => errors.rateLimited(20));
    const { user } = renderAt('/login');
    await fillAndSend(user);

    await alertWith(/요청이 많습니다\. (20|19)초 뒤 다시 하세요\./);
    expect(isBlocked(screen.getByRole('button', { name: '로그인' }))).toBe(true);
    expect(callsTo('POST', '/api/public/sign-in')).toHaveLength(1);
  });

  test('P-1 저장 중: 로그인 버튼 막힘', async () => {
    state.signedIn = false;
    const release = gate();
    on('post', '/api/public/sign-in', async () => {
      await release.opened;
      state.signedIn = true;
      return new Response(null, { status: 204 });
    });
    const { user } = renderAt('/login');
    const submit = await button('로그인');
    await user.type(await email(), 'kim@acme.test');
    await user.type(await password(), 'correct horse battery');
    await user.click(submit);

    await eventually(() => {
      expect(submit).toHaveAttribute('aria-disabled', 'true');
    });
    release.open();
    await atPath('/issues');
  });

  test('P-1 이미 로그인됨: P-3으로 보낸다', async () => {
    renderAt('/login');

    await atPath('/issues');
  });

  test('P-1 axe: 처음 화면에 접근성 위반이 없다', async () => {
    state.signedIn = false;
    renderAt('/login');
    await email();
    await password();
    await button('로그인');

    await expectNoAxeViolations();
  });
});
