// 11 묶음 screens.md "C-1·P-1 로그인": OIDC가 켜졌을 때의 단추 "회사 계정으로 로그인"과 "또는"(S11-AC-31), `oidc` 쿼리의
// 오류 띠 셋(S11-AC-32). 포털은 P1Login.11.test.tsx가 같은 것을 본다.
// 단추는 `location.assign`으로 페이지를 옮긴다. jsdom은 다른 문서로 옮기는 주소를 남기지 않으므로(mock 금지), 여기서는
// "이동 중…", 눌리지 않음, 요청이 아님(sign-in POST도 fetch도 없음)을 보고, 옮겨 가는 주소와 `next`는 같이 쓰는 조각의
// 테스트(packages/app/src/OidcSignIn.11.test.tsx)가 본다(10a 내보내기 내려받기와 같은 꼴).
import { HttpResponse } from 'msw/http';
import { describe, expect, test } from 'vitest';
import {
  alertWith,
  atPath,
  button,
  callsTo,
  errors,
  eventually,
  expectNoAxeViolations,
  field,
  gate,
  isBlocked,
  link,
  on,
  pageText,
  renderAt,
  screen,
  sent,
  state,
  text,
} from '../__tests__/console';
import { settle } from '../__tests__/console1a';
import { MOVING, OIDC_BANDS, OIDC_BUTTON, OPTIONS, OR, START, signInOptions } from '../__tests__/console11';

type User = ReturnType<typeof renderAt>['user'];

const email = () => field(/^이메일/, 'input');
const password = () => field('비밀번호', 'input');

const oidcButtonNow = () => screen.queryByRole('button', { name: OIDC_BUTTON });
/** The button also while it says "이동 중…". */
const goingNow = () => screen.queryAllByRole('button').find((b) => [OIDC_BUTTON, MOVING].includes(text(b))) ?? null;
const oidcBands = () => screen.queryAllByRole('alert').filter((el) => Object.values(OIDC_BANDS).some((t) => text(el).includes(t)));
const queryOf = () => new URLSearchParams(window.location.search);

/** True when `first` stands before `second` in the document. */
const before = (first: Element, second: Element) => (first.compareDocumentPosition(second) & Node.DOCUMENT_POSITION_FOLLOWING) !== 0;

/** The screen drawn with the password form: email, password, 로그인. */
async function formShows() {
  await email();
  await password();
  return button('로그인');
}

async function signInWithPassword(user: User) {
  await user.type(await email(), 'lee@popdesk.test');
  await user.type(await password(), 'correct horse battery');
  await user.click(await button('로그인'));
}

describe('C-1 로그인의 회사 계정 단추 (11 묶음)', () => {
  test('S11-AC-31 C-1 OIDC 켜짐: sign-in-options를 읽고, 비밀번호 폼과 "비밀번호를 잊었나요?" 아래에 "또는"과 단추 "회사 계정으로 로그인"', async () => {
    state.signedIn = false;
    signInOptions(true);
    renderAt('/login');
    const submit = await formShows();

    await sent('GET', OPTIONS);
    const oidc = await button(OIDC_BUTTON);
    const forgot = await link('비밀번호를 잊었나요?');
    expect(before(submit, oidc), '로그인 단추 뒤').toBe(true);
    expect(before(forgot, oidc), '"비밀번호를 잊었나요?" 뒤').toBe(true);
    expect(pageText()).toContain(OR);
    expect(oidc.closest('form') !== null && (oidc as HTMLButtonElement).type === 'submit', '폼을 보내는 단추가 아니다').toBe(false);
  });

  test.each([
    ['꺼짐', () => HttpResponse.json({ oidc: false })],
    ['500', () => errors.internal()],
    ['네트워크 오류', () => HttpResponse.error()],
  ])('S11-AC-31 C-1 sign-in-options %s: 단추와 "또는"이 없고 비밀번호 로그인은 그대로 된다', async (_, answer) => {
    state.signedIn = false;
    on('get', OPTIONS, answer);
    const { user } = renderAt('/login');
    await formShows();

    await sent('GET', OPTIONS);
    await settle();
    expect(oidcButtonNow(), `"${OIDC_BUTTON}"`).toBeNull();
    expect(pageText()).not.toContain(OR);
    expect(screen.queryAllByRole('alert').map(text).filter((t) => t !== ''), '오류 띠').toEqual([]);
    await signInWithPassword(user);
    await sent('POST', '/api/public/sign-in');
    await atPath('/issues');
  });

  test('S11-AC-31 C-1 sign-in-options를 읽는 중: 단추가 없고 비밀번호 폼은 쓸 수 있다. 답이 켜짐으로 오면 단추가 나온다', async () => {
    state.signedIn = false;
    const release = gate();
    on('get', OPTIONS, async () => {
      await release.opened;
      return HttpResponse.json({ oidc: true });
    });
    const { user } = renderAt('/login');
    await formShows();
    await sent('GET', OPTIONS);

    expect(oidcButtonNow(), '읽는 중의 단추').toBeNull();
    await user.type(await email(), 'lee@popdesk.test');
    expect(await email()).toHaveValue('lee@popdesk.test');
    release.open();
    await button(OIDC_BUTTON);
  });

  test('S11-AC-31 C-1 단추를 누름: "이동 중…"이 되고 눌리지 않는다. 비밀번호 로그인도 fetch도 보내지 않는다(주소 옮기기)', async () => {
    state.signedIn = false;
    signInOptions(true);
    const { user } = renderAt('/login?next=%2Fissues%2FACME-7');
    await formShows();
    await user.click(await button(OIDC_BUTTON));

    await eventually(() => {
      expect(text(goingNow())).toBe(MOVING);
    });
    const going = goingNow() as HTMLElement;
    expect(isBlocked(going), '옮기는 동안 눌리지 않음').toBe(true);
    await user.click(going);
    await settle();
    expect(text(goingNow())).toBe(MOVING);
    expect(callsTo('POST', '/api/public/sign-in'), '비밀번호 로그인').toHaveLength(0);
    expect(callsTo('GET', START), '시작 주소를 fetch로 부르지 않는다').toHaveLength(0);
    expect(window.location.pathname, '화면 안 이동이 아니다').toBe('/login');
  });
});

describe('C-1 로그인의 OIDC 오류 띠 (11 묶음)', () => {
  test.each(Object.entries(OIDC_BANDS))(
    'S11-AC-32 C-1 ?oidc=%s: 폼 위에 그 문장의 오류 띠, 첫 초점은 이메일 칸, 주소에서 oidc만 빠진다(replace)',
    async (value, says) => {
      state.signedIn = false;
      const length = window.history.length;
      renderAt(`/login?oidc=${value}&next=%2Fissues`);

      const band = await alertWith(says);
      const emailField = await email();
      expect(before(band, emailField), '폼 위').toBe(true);
      await eventually(() => {
        expect(document.activeElement, '첫 초점').toBe(emailField);
      });
      await eventually(() => {
        expect(queryOf().has('oidc'), '주소의 oidc').toBe(false);
      });
      expect(queryOf().get('next'), '다른 쿼리는 그대로').toBe('/issues');
      expect(window.location.pathname).toBe('/login');
      expect(window.history.length, '방문 기록을 더하지 않는다').toBe(length);
      expect(oidcBands(), '띠는 하나').toHaveLength(1);
    },
  );

  test.each(['other', 'FAILED', ''])('S11-AC-32 C-1 ?oidc=%s(모르는 값): OIDC 오류 띠가 없다', async (value) => {
    state.signedIn = false;
    renderAt(`/login?oidc=${value}`);
    await formShows();

    await settle();
    expect(oidcBands()).toEqual([]);
    expect(screen.queryAllByRole('alert').map(text).filter((t) => t !== ''), '오류 띠').toEqual([]);
  });

  test('S11-AC-32 C-1 띠가 있는데 비밀번호 로그인을 시도: 띠를 지우고 비밀번호 결과(401 문장)를 보인다', async () => {
    state.signedIn = false;
    on('post', '/api/public/sign-in', () => errors.signInFailed());
    const { user } = renderAt('/login?oidc=failed');
    await alertWith(OIDC_BANDS.failed);

    await user.type(await email(), 'lee@popdesk.test');
    await user.type(await password(), 'wrong password 1');
    await user.click(await button('로그인'));

    await alertWith('이메일 또는 비밀번호가 맞지 않습니다.');
    expect(oidcBands(), 'OIDC 띠').toEqual([]);
  });

  test('S11-AC-31 S11-AC-32 C-1 axe: 단추와 오류 띠가 다 있는 화면에 접근성 위반이 없다', async () => {
    state.signedIn = false;
    signInOptions(true);
    renderAt('/login?oidc=unavailable');
    await formShows();
    await button(OIDC_BUTTON);
    await alertWith(OIDC_BANDS.unavailable);

    await expectNoAxeViolations();
  });
});
