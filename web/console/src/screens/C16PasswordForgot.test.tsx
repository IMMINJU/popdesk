// 1a screens.md 1절 C-16 비밀번호 찾기(P-9와 같다). 로그인하지 않은 채로 본다.
// 계정이 있든 없든 서버는 같은 202를 주고 화면은 같은 문구를 보인다(S1A-AC-59).
import { describe, expect, test } from 'vitest';
import {
  alertWith,
  button,
  callsTo,
  errors,
  eventually,
  expectFieldError,
  expectNoAxeViolations,
  field,
  gate,
  hrefPath,
  isBlocked,
  link,
  on,
  pageText,
  renderAt,
  screen,
  sent,
  state,
} from '../__tests__/console';
import { answered, settle } from '../__tests__/console1a';

const REQUEST = '/api/public/password-reset/request';
const SENT_TEXT = '그 주소의 계정이 있으면 메일을 보냈습니다. 1시간 안에 메일의 링크를 여세요.';

const email = () => field(/^이메일/, 'input');
const send = () => button('재설정 메일 받기');

describe('C-16 비밀번호 찾기', () => {
  test('C-16 처음: 이메일 칸 하나, "재설정 메일 받기" 버튼, 로그인으로 돌아가는 링크. 서버를 부르지 않는다', async () => {
    state.signedIn = false;
    renderAt('/forgot-password');

    expect(await email()).toHaveAttribute('type', 'email');
    await send();
    expect(hrefPath(await link(/로그인/))).toBe('/login');
    expect(document.querySelectorAll('input:not([type="hidden"])'), '칸은 이메일 하나').toHaveLength(1);
    expect(callsTo('POST', REQUEST)).toHaveLength(0);
  });

  test.each([
    ['계정이 있는 주소', 'lee@popdesk.test'],
  ])('S1A-AC-59 C-16 보낸 뒤(202) %s: 칸을 지우고 같은 안내와 로그인 링크를 보인다', async (_, address) => {
    state.signedIn = false;
    const { user } = renderAt('/forgot-password');
    await user.type(await email(), address);
    await user.click(await send());

    const [request] = await sent('POST', REQUEST);
    expect(await request?.body).toEqual({ email: address });
    await eventually(() => {
      expect(pageText()).toContain(SENT_TEXT);
    });
    expect(screen.queryAllByLabelText(/^이메일/, { selector: 'input' }), '보낸 뒤의 이메일 칸').toHaveLength(0);
    expect(hrefPath(await link(/로그인/))).toBe('/login');
  });

  test('C-16 이메일 꼴이 아님: 칸 아래 오류를 보이고 보내지 않는다', async () => {
    state.signedIn = false;
    const { user } = renderAt('/forgot-password');
    const box = await email();
    await user.type(box, 'lee.popdesk');
    await user.click(await send());

    await expectFieldError(box);
    expect(callsTo('POST', REQUEST)).toHaveLength(0);
  });

  test('C-16 429: "요청이 많습니다. N초 뒤 다시 하세요." 띠와 버튼 막힘, 쓴 주소는 남는다', async () => {
    state.signedIn = false;
    on('post', REQUEST, () => errors.rateLimited(20));
    const { user } = renderAt('/forgot-password');
    await user.type(await email(), 'lee@popdesk.test');
    await user.click(await send());

    await alertWith(/요청이 많습니다\. (20|19)초 뒤 다시 하세요\./);
    expect(isBlocked(await send())).toBe(true);
    expect(await email()).toHaveValue('lee@popdesk.test');
  });

  test('S1A-AC-58 C-16 떠난 뒤 온 답: 보내는 중에 로그인으로 돌아가면, 뒤에 온 202의 안내를 로그인 화면에 보이지 않는다', async () => {
    state.signedIn = false;
    const release = gate();
    on('post', REQUEST, async () => {
      await release.opened;
      return new Response(null, { status: 202 });
    });
    const { user } = renderAt('/forgot-password');
    await user.type(await email(), 'lee@popdesk.test');
    await user.click(await send());
    await sent('POST', REQUEST);
    await user.click(await link(/로그인/));
    await button('로그인');

    const done = answered('POST', REQUEST);
    release.open();
    await done;
    await settle();

    expect(window.location.pathname).toBe('/login');
    expect(pageText()).not.toContain('메일을 보냈습니다');
  });

  test('C-16 axe: 이메일 꼴 오류가 난 화면에 접근성 위반이 없다', async () => {
    state.signedIn = false;
    const { user } = renderAt('/forgot-password');
    const box = await email();
    await user.type(box, 'lee.popdesk');
    await user.click(await send());
    await expectFieldError(box);

    await expectNoAxeViolations();
  });
});
