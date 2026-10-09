// screens.md 4절 C-2 초대 수락. P-2와 같고, 성공하면 C-3으로 간다.
// 토큰은 주소 조각(#token=)으로만 받고 요청 본문으로 보낸다.
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
  heading1,
  hrefPath,
  on,
  pageText,
  renderAt,
  screen,
  sent,
  state,
  statusWith,
  text,
} from '../__tests__/console';

const TOKEN = 'inv-Hk3sV8pQ2mTx';
const INVITE_URL = `/invite#token=${TOKEN}`;

const password = () => field('비밀번호', 'input');
const confirm = () => field('비밀번호 확인', 'input');

type User = ReturnType<typeof renderAt>['user'];

async function fill(user: User, pw = 'new password 2026', again = pw) {
  await user.type(await password(), pw);
  await user.type(await confirm(), again);
}

describe('C-2 초대 수락', () => {
  test('C-2 처음: 비밀번호·확인 칸, 규칙을 aria-describedby로 잇고 "시작하기" 버튼, 처음 포커스는 비밀번호 칸, 토큰을 미리 확인하지 않는다', async () => {
    state.signedIn = false;
    renderAt(INVITE_URL);

    const pw = await password();
    expect(pw).toHaveAttribute('type', 'password');
    expect(pw).toHaveAttribute('autocomplete', 'new-password');
    await confirm();
    expect(describedText(pw), '비밀번호 규칙(10~72자)').toMatch(/10.*72/);
    await button('시작하기');
    await eventually(() => {
      expect(document.activeElement).toBe(pw);
    });
    expect(callsTo('POST', '/api/public/invitations/accept'), '토큰을 미리 확인하는 요청').toHaveLength(0);
  });

  test('C-2 토큰: 주소창에서 #token을 지운다(history.replaceState)', async () => {
    state.signedIn = false;
    renderAt(INVITE_URL);
    await password();

    await eventually(() => {
      expect(window.location.href).not.toContain(TOKEN);
    });
    expect(window.location.pathname).toBe('/invite');
  });

  test('C-2 성공: 토큰을 요청 본문으로만 보내고, 바로 로그인되어 C-3으로 가며 토스트 "환영합니다"', async () => {
    state.signedIn = false;
    const { user } = renderAt(INVITE_URL);
    await fill(user);
    await user.click(await button('시작하기'));

    const [accept] = await sent('POST', '/api/public/invitations/accept');
    expect(await accept?.body).toEqual({ token: TOKEN, password: 'new password 2026' });
    await atPath('/issues');
    await statusWith('환영합니다');
    expect(calls.filter((c) => c.url.includes(TOKEN)), '토큰을 주소에 담은 요청').toEqual([]);
  });

  test('C-2 두 칸이 다름: 서버에 보내지 않고 확인 칸에 오류를 보인다', async () => {
    state.signedIn = false;
    const { user } = renderAt(INVITE_URL);
    await fill(user, 'new password 2026', 'new password 2027');
    await user.click(await button('시작하기'));

    await expectFieldError(await confirm());
    expect(callsTo('POST', '/api/public/invitations/accept')).toHaveLength(0);
    expect(window.location.pathname).toBe('/invite');
  });

  test('C-2 400 링크 무효: 화면 전체를 "이 초대 링크는 쓸 수 없습니다" 안내와 로그인 링크로 바꾼다', async () => {
    state.signedIn = false;
    on('post', '/api/public/invitations/accept', () =>
      Response.json({ code: 'INVITATION_INVALID', message: 'Invalid invitation.' }, { status: 400 }),
    );
    const { user } = renderAt(INVITE_URL);
    await fill(user);
    await user.click(await button('시작하기'));

    await eventually(() => {
      expect(pageText()).toContain('이 초대 링크는 쓸 수 없습니다. 관리자에게 다시 보내 달라고 하세요.');
    });
    expect(screen.queryAllByLabelText('비밀번호', { selector: 'input' })).toHaveLength(0);
    const toLogin = screen.queryAllByRole('link').filter((a) => hrefPath(a) === '/login');
    expect(toLogin.length, '로그인으로 가는 링크').toBeGreaterThan(0);
  });

  test('C-2 400 fields: 비밀번호 규칙 위반을 칸 아래에, 포커스는 비밀번호 칸', async () => {
    state.signedIn = false;
    on('post', '/api/public/invitations/accept', () => errors.invalid([{ field: 'password', code: 'TOO_SHORT' }]));
    const { user } = renderAt(INVITE_URL);
    await fill(user, 'short pw 1');
    await user.click(await button('시작하기'));

    const pw = await password();
    await expectFieldError(pw);
    expect(document.activeElement).toBe(pw);
    expect(window.location.pathname).toBe('/invite');
  });

  test('C-2 저장 중: "시작하기" 버튼은 aria-disabled', async () => {
    state.signedIn = false;
    const release = gate();
    on('post', '/api/public/invitations/accept', async () => {
      await release.opened;
      state.signedIn = true;
      return new Response(null, { status: 204 });
    });
    const { user } = renderAt(INVITE_URL);
    await fill(user);
    const submit = await button('시작하기');
    await user.click(submit);

    await eventually(() => {
      expect(submit).toHaveAttribute('aria-disabled', 'true');
    });
    expect(callsTo('POST', '/api/public/invitations/accept')).toHaveLength(1);
    release.open();
    await atPath('/issues');
  });

  test('C-2 키보드: 비밀번호 → 보이기 → 확인 → 보이기 → 시작하기', async () => {
    state.signedIn = false;
    const { user } = renderAt(INVITE_URL);
    const pw = await password();
    pw.focus();

    const toggles = () => screen.queryAllByRole('button', { name: /보이기|숨기기/ });
    await user.tab();
    expect(toggles()[0], '비밀번호 보이기').toBe(document.activeElement);
    await user.tab();
    expect(document.activeElement).toBe(await confirm());
    await user.tab();
    expect(toggles()[1], '확인 칸 보이기').toBe(document.activeElement);
    await user.tab();
    expect(document.activeElement).toBe(await button('시작하기'));
  });

  test('C-2 접근성: 화면 제목을 document.title에 넣고 h1은 tabindex=-1', async () => {
    state.signedIn = false;
    renderAt(INVITE_URL);
    await password();

    const h1 = await heading1();
    expect(h1).toHaveAttribute('tabindex', '-1');
    expect(document.title).toContain(text(h1));
  });

  test('C-2 axe: 처음 화면에 접근성 위반이 없다', async () => {
    state.signedIn = false;
    renderAt(INVITE_URL);
    await password();
    await confirm();

    await expectNoAxeViolations();
  });
});
