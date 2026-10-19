// b5 묶음 ① 몫(acceptance.md 4절 7): 로그인 전 화면 P-1, P-2, P-9, P-10과 P-6을 영어로 연다(B5-AC-05),
// 그리고 언어 장치를 포털 전체로 본다(B5-AC-01~04, 07).
// 영어 글은 구현 탭이 짓는다(4절 6). 그래서 영어 문구로 찾지 않는다: 칸과 단추는 꼴(type, form)로,
// 데이터는 영어 견본으로 찾고, 단추 이름이 꼭 필요하면 "지금 한국어 또는 용어표의 영어"로 찾는다.
// 포털의 옆 메뉴는 매니저에게만 있다(2-manager screens.md P-8). 메뉴를 보는 경우는 매니저로 연다.
import { describe, expect, test } from 'vitest';
import { ACME, errors, eventually, found, heading1, on, renderAt, screen, sent, state, text, within } from '../__tests__/portal';
import { region, rejected, route, session, settle, signInWith } from '../__tests__/portal1a';
import '../__tests__/portal2';
import '../__tests__/portal4';
import {
  ACME_EN,
  ISSUE_TITLE_EN,
  ME_EN,
  browserLanguages,
  expectEnglishPage,
  expectNoHangul,
  hangulIn,
  openInEnglish,
  signedOutInEnglish,
} from '../__tests__/portalB5';

type User = ReturnType<typeof renderAt>['user'];

const HANGUL = /[가-힣]/;

/** An English-speaking manager of Acme: the portal draws the menu only for a manager. */
const MANAGER_EN = { ...ME_EN, companies: [{ ...ACME_EN, manager: true }] };

/** The one form of a signed-out screen and its submit button, whatever their words. */
const form = () => found(() => document.querySelector('form'), '화면의 form');
const submit = async () => found(() => document.querySelector<HTMLButtonElement>('form button[type="submit"]'), 'form의 보내기 단추');
const emailInput = () => found(() => document.querySelector<HTMLInputElement>('input[type="email"]'), '이메일 칸');
const passwordInputs = () => Array.from(document.querySelectorAll<HTMLInputElement>('input[type="password"]'));
/** Waits until `query` gives at least `count` elements and returns them. */
async function atLeast<T extends Element>(query: () => T[], count: number, what: string): Promise<T[]> {
  let got: T[] = [];
  await eventually(() => {
    got = query();
    expect(got.length, what).toBeGreaterThanOrEqual(count);
  });
  return got;
}
const passwordPair = () => atLeast(passwordInputs, 2, '비밀번호 칸 둘');

/** The banner and the menu of a signed-in screen. */
const banner = () => found(() => screen.queryByRole('banner'), '머리(banner)');
const menu = () => found(() => screen.queryAllByRole('navigation')[0], '옆 메뉴(navigation)');
/** The save button of the profile part of P-6, found by its place: the part that holds the language select. */
const profileSave = (select: HTMLElement) =>
  found(() => {
    const box = select.closest('section, form, [role="region"]');
    return box ? (box.querySelector<HTMLButtonElement>('button[type="submit"]') ?? within(box as HTMLElement).queryAllByRole('button').at(-1)) : null;
  }, '프로필 저장 단추');

const INVITE_URL = '/invite#token=inv-7Qx2pLk9VtA3';
const RESET_URL = '/reset-password#token=rst-Hk3sV8pQ2mTx';

async function signInFails(user: User) {
  await user.type(await emailInput(), 'kim@acme.test');
  await user.type(passwordInputs()[0] as HTMLInputElement, 'wrong password 1');
  await user.click(await submit());
}

describe('B5 ① 포털 로그인 전 화면을 영어로', () => {
  test.each([
    ['P-1 로그인', '/login'],
    ['P-2 초대 수락', INVITE_URL],
    ['P-9 비밀번호 찾기', '/forgot-password'],
    ['P-10 비밀번호 재설정', RESET_URL],
  ])('B5-AC-05 B5-AC-02 %s 처음: 브라우저가 en-US면 글, 칸 이름, 단추에 한글이 없고 html lang은 en이다', async (what, path) => {
    signedOutInEnglish();
    renderAt(path);
    await submit();
    await heading1();

    await eventually(() => {
      expectNoHangul(what);
    });
    expectEnglishPage(what);
  });

  test('B5-AC-05 P-1 로그인 실패: 오류 띠도 영어다', async () => {
    signedOutInEnglish();
    on('post', '/api/public/sign-in', () => Response.json({ code: 'SIGN_IN_FAILED', message: 'Sign-in failed.' }, { status: 401 }));
    const { user } = renderAt('/login');
    await signInFails(user);

    await found(() => screen.queryAllByRole('alert').find((el) => text(el) !== ''), '로그인 실패 띠');
    expectNoHangul('P-1 로그인 실패');
  });

  test('B5-AC-05 P-1 요청이 많음(429): N초 뒤 띠도 영어다', async () => {
    signedOutInEnglish();
    on('post', '/api/public/sign-in', () => errors.rateLimited(30));
    const { user } = renderAt('/login');
    await signInFails(user);

    const band = await found(() => screen.queryAllByRole('alert').find((el) => /30|29/.test(text(el))), '429 띠');
    expectNoHangul('P-1 429', band);
    expectNoHangul('P-1 429');
  });

  test('B5-AC-05 P-1 세션이 끝나 돌아옴: 안내 띠도 영어다', async () => {
    browserLanguages(['en-US']);
    openInEnglish(MANAGER_EN);
    const { user } = renderAt('/issues');
    await found(() => screen.queryByText(ISSUE_TITLE_EN), '문의 목록의 견본 문의');
    state.signedIn = false;
    await user.click(await found(() => document.querySelector<HTMLAnchorElement>('nav a[href="/members"]'), '메뉴의 구성원 링크'));

    await emailInput();
    await eventually(() => {
      expectNoHangul('P-1 세션 끝남');
    });
  });

  test('B5-AC-05 P-9 보낸 뒤: 안내 글도 영어다', async () => {
    signedOutInEnglish();
    const { user } = renderAt('/forgot-password');
    await user.type(await emailInput(), 'kim@acme.test');
    await user.click(await submit());

    await sent('POST', '/api/public/password-reset/request');
    await settle();
    await eventually(() => {
      expect(document.querySelector('input[type="email"]'), '보낸 뒤 안내로 바뀜').toBeNull();
    });
    expectNoHangul('P-9 보낸 뒤');
  });

  test('B5-AC-05 P-10 링크를 쓸 수 없음: 안내 화면도 영어다', async () => {
    signedOutInEnglish();
    route('post', '/api/public/password-reset/confirm', () => rejected('PASSWORD_RESET_INVALID'));
    const { user } = renderAt(RESET_URL);
    const [first, second] = await passwordPair();
    await user.type(first as HTMLInputElement, 'new password 2026');
    await user.type(second as HTMLInputElement, 'new password 2026');
    await user.click(await submit());

    await sent('POST', '/api/public/password-reset/confirm');
    await eventually(() => {
      expect(passwordInputs(), '쓸 수 없음 안내로 바뀜').toEqual([]);
    });
    expectNoHangul('P-10 쓸 수 없음');
  });

  test('B5-AC-05 P-10 두 칸이 다름: 칸 오류도 영어다', async () => {
    signedOutInEnglish();
    const { user } = renderAt(RESET_URL);
    const [first, second] = await passwordPair();
    await user.type(first as HTMLInputElement, 'new password 2026');
    await user.type(second as HTMLInputElement, 'other password 2026');
    await user.click(await submit());

    await found(() => document.querySelector('[aria-invalid="true"]'), '틀린 칸');
    expectNoHangul('P-10 두 칸이 다름');
  });

  test('B5-AC-05 P-2 초대가 쓸 수 없음: 안내도 영어다', async () => {
    signedOutInEnglish();
    on('post', '/api/public/invitations/accept', () => Response.json({ code: 'INVITATION_INVALID', message: 'Invalid invitation.' }, { status: 400 }));
    const { user } = renderAt(INVITE_URL);
    for (const input of await passwordPair()) {
      await user.type(input, 'new password 2026');
    }
    await user.click(await submit());

    await sent('POST', '/api/public/invitations/accept');
    await eventually(() => {
      expect(passwordInputs(), '쓸 수 없음 안내로 바뀜').toEqual([]);
    });
    expectNoHangul('P-2 초대를 쓸 수 없음');
  });
});

describe('B5 ① 포털 P-6 내 정보를 영어로', () => {
  test('B5-AC-05 P-6 처음: 세 구역(프로필, 사진, 비밀번호)과 머리, 옆 메뉴에 한글이 없다', async () => {
    openInEnglish(MANAGER_EN);
    renderAt('/me');
    await found(() => document.querySelector('select'), '언어 칸');
    await found(() => document.querySelector('input[type="file"]'), '사진 칸');
    await atLeast(passwordInputs, 3, '비밀번호 칸 셋');
    await eventually(() => {
      expect(text(document.body)).toContain('Acme');
    });

    expectNoHangul('P-6 처음');
    expectEnglishPage('P-6 처음');
  });

  test('B5-AC-05 P-6 비밀번호 오류(지금 비밀번호가 틀림): 칸 오류도 영어다', async () => {
    openInEnglish();
    route('put', '/api/portal/me/password', () => rejected('CURRENT_PASSWORD_WRONG'));
    const { user } = renderAt('/me');
    const inputs = await atLeast(passwordInputs, 3, '비밀번호 칸 셋');
    await user.type(inputs[0] as HTMLInputElement, 'old password 2025');
    await user.type(inputs[1] as HTMLInputElement, 'new password 2026');
    await user.type(inputs[2] as HTMLInputElement, 'new password 2026');
    const box = (inputs[0] as HTMLInputElement).closest('section, form, [role="region"]') as HTMLElement;
    await user.click(await found(() => box.querySelector<HTMLButtonElement>('button[type="submit"]') ?? within(box).queryAllByRole('button').at(-1), '비밀번호 바꾸기 단추'));

    await found(() => document.querySelector('[aria-invalid="true"]'), '틀린 칸');
    expectNoHangul('P-6 비밀번호 오류');
  });

  test('B5-AC-05 P-6 사진 오류 띠: 영어다', async () => {
    openInEnglish();
    route('post', '/api/portal/me/photo', () => rejected('UNSUPPORTED_IMAGE'));
    const { user } = renderAt('/me');
    const input = await found(() => document.querySelector<HTMLInputElement>('input[type="file"]'), '사진 칸');
    await user.upload(input, new File(['me.png'], 'me.png', { type: 'image/png' }));

    await found(() => screen.queryAllByRole('alert').find((el) => text(el) !== ''), '사진 오류 띠');
    expectNoHangul('P-6 사진 오류');
  });
});

describe('B5 언어 장치(포털)', () => {
  test('B5-AC-01 B5-AC-04 로그인해 있으면 Me.locale을 쓴다: en 계정은 머리, 옆 메뉴, 화면 제목, html lang, document.title이 영어다', async () => {
    browserLanguages(['ko-KR']);
    openInEnglish(MANAGER_EN);
    renderAt('/issues');
    await found(() => screen.queryByText(ISSUE_TITLE_EN), '문의 목록의 견본 문의');

    await eventually(() => {
      expect(document.documentElement.lang).toBe('en');
    });
    expectNoHangul('머리', await banner());
    expectNoHangul('옆 메뉴', await menu());
    expectNoHangul('화면 제목', await heading1());
    expectEnglishPage('P-3');
  });

  test('B5-AC-01 B5-AC-06 ko 계정은 브라우저가 en-US여도 한국어 그대로다: html lang은 ko, 화면 제목과 document.title은 지금 글이다', async () => {
    browserLanguages(['en-US']);
    signInWith({ companies: [{ ...ACME, manager: true }] });
    renderAt('/issues');
    await found(() => screen.queryByText('로그인이 안 됩니다'), '문의 목록의 견본 문의');

    expect(text(await heading1())).toMatch(HANGUL);
    expect(text(await menu())).toContain('문의');
    expect(document.documentElement.lang).toBe('ko');
    expect(document.title).toMatch(HANGUL);
  });

  test.each([
    [['ko-KR', 'en-US'], 'ko'],
    [['ko'], 'ko'],
    [['en-US', 'ko-KR'], 'en'],
    [['ja'], 'en'],
  ])('B5-AC-02 로그인 전 브라우저 언어 %j → %s', async (languages, want) => {
    signedOutInEnglish(languages);
    renderAt('/login');
    await submit();

    await eventually(() => {
      expect(document.documentElement.lang).toBe(want);
    });
    const korean = hangulIn(await form());
    if (want === 'ko') expect(korean.length, '한국어 화면의 form').toBeGreaterThan(0);
    else expect(korean, '영어 화면의 form').toEqual([]);
  });

  test('B5-AC-02 navigator.languages가 비면 navigator.language를 본다: en-GB → 영어', async () => {
    state.signedIn = false;
    browserLanguages([], 'en-GB');
    renderAt('/forgot-password');
    await submit();

    await eventually(() => {
      expect(document.documentElement.lang).toBe('en');
    });
    expectNoHangul('P-9, language만 en-GB');
  });

  test('B5-AC-02 로그아웃하면 브라우저 언어로 돌아간다: en 계정, 브라우저 ko → 로그아웃 뒤 로그인 화면은 한국어', async () => {
    browserLanguages(['ko-KR']);
    openInEnglish();
    const { user } = renderAt('/issues');
    await found(() => screen.queryByText(ISSUE_TITLE_EN), '문의 목록');
    await eventually(() => {
      expect(document.documentElement.lang).toBe('en');
    });

    const signOut = await found(
      () => within(screen.getByRole('banner')).queryAllByRole('button').find((b) => /sign out|log out|로그아웃/i.test(text(b) || (b.getAttribute('aria-label') ?? ''))),
      '로그아웃 단추',
    );
    await user.click(signOut);
    await sent('POST', '/api/public/sign-out');
    await submit();

    await eventually(() => {
      expect(document.documentElement.lang).toBe('ko');
    });
    expect(hangulIn(await form()).length, '로그인 화면은 한국어').toBeGreaterThan(0);
  });

  test('B5-AC-03 B5-AC-04 P-6에서 언어를 en으로 저장하면 새로고침 없이 머리, 옆 메뉴, 화면, html lang, document.title이 영어로 바뀐다', async () => {
    browserLanguages(['ko-KR']);
    signInWith({ name: 'Kim Customer', companies: [{ ...ACME_EN, manager: true }] });
    const { user } = renderAt('/me');
    expect(text(await heading1())).toBe('내 정보');
    const select = await found(() => document.querySelector('select'), '언어 칸');
    await user.selectOptions(select, 'en');
    await user.click(await profileSave(select));

    const [patch] = await sent('PATCH', '/api/portal/me');
    expect(await patch?.body).toMatchObject({ locale: 'en' });
    await eventually(() => {
      expect(document.documentElement.lang).toBe('en');
    });
    await eventually(() => {
      expectNoHangul('머리', screen.getByRole('banner'));
      expectNoHangul('옆 메뉴', screen.getAllByRole('navigation')[0]);
      expectNoHangul('화면 제목', screen.getByRole('heading', { level: 1 }));
    });
    expectEnglishPage('P-6 저장 뒤');
  });

  test('B5-AC-03 P-6에서 en 계정이 ko로 저장하면 화면이 한국어로 돌아온다', async () => {
    openInEnglish();
    const { user } = renderAt('/me');
    const select = await found(() => document.querySelector('select'), '언어 칸');
    await eventually(() => {
      expect(document.documentElement.lang).toBe('en');
    });
    await user.selectOptions(select, 'ko');
    await user.click(await profileSave(select));

    await sent('PATCH', '/api/portal/me');
    await eventually(() => {
      expect(document.documentElement.lang).toBe('ko');
      expect(text(screen.getByRole('heading', { level: 1 }))).toBe('내 정보');
    });
    expect(session.me.locale).toBe('ko');
  });

  test('B5-AC-03 P-6 저장이 실패하면 언어를 바꾸지 않는다(screens.md 1절 3)', async () => {
    openInEnglish();
    route('patch', '/api/portal/me', () => errors.internal());
    const { user } = renderAt('/me');
    const select = await found(() => document.querySelector('select'), '언어 칸');
    await eventually(() => {
      expect(document.documentElement.lang).toBe('en');
    });
    await user.selectOptions(select, 'ko');
    await user.click(await profileSave(select));

    await found(() => screen.queryAllByRole('alert').find((el) => text(el) !== ''), '저장 실패 띠');
    await settle();
    expect(document.documentElement.lang).toBe('en');
    expectNoHangul('P-6 저장 실패 뒤');
  });

  test('B5-AC-03 P-6 언어 칸의 도움말은 "화면과 메일의 언어"를 말한다(한국어 화면)', async () => {
    renderAt('/me');
    const box = await region('프로필');
    const select = await found(() => box.querySelector('select'), '언어 칸');
    const help = (select.getAttribute('aria-describedby') ?? '')
      .split(/\s+/)
      .filter(Boolean)
      .map((id) => text(document.getElementById(id)))
      .join(' ');
    const said = help || text(box);

    expect(said).toContain('화면');
    expect(said).toContain('메일');
    expect(text(box)).not.toContain('화면은 한국어로 보입니다');
  });

  test('B5-AC-07 수와 날짜는 화면 언어를 따른다: en 문의 목록의 시각은 영어(달 이름, ago)이고 한국어 날짜 꼴이 없다', async () => {
    openInEnglish();
    renderAt('/issues');
    await found(() => screen.queryByText(ISSUE_TITLE_EN), '문의 목록');

    const times = await atLeast(() => Array.from(document.querySelectorAll('time')), 1, '올린 시각(time)');
    await eventually(() => {
      expect(times.map((el) => el.getAttribute('title') ?? '').join(' ')).toMatch(/\bOctober\b/);
    });
    expect(times.map((el) => text(el)).join(' ')).not.toMatch(HANGUL);
    expect(text(document.body)).not.toMatch(/\d+년|\d+월 \d+일/);
  });
});
