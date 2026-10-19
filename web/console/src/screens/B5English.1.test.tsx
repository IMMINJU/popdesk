// b5 묶음 ① 몫(acceptance.md 4절 7): 로그인 전 화면 C-1, C-2, C-16, C-17과 C-6을 영어로 연다(B5-AC-05),
// 그리고 언어 장치를 콘솔 전체로 본다(B5-AC-01~04, 07).
// 영어 글은 구현 탭이 짓는다(4절 6). 그래서 영어 문구로 찾지 않는다: 칸과 단추는 꼴(type, form)로,
// 데이터는 영어 견본으로 찾고, 단추 이름이 꼭 필요하면 "지금 한국어 또는 용어표의 영어"로 찾는다.
import { describe, expect, test } from 'vitest';
import {
  ADMIN,
  errors,
  eventually,
  found,
  heading1,
  menuLinks,
  on,
  renderAt,
  screen,
  sent,
  state,
  text,
  within,
} from '../__tests__/console';
import { cleanupJob } from '../__tests__/console4b';
import { RESET_LINK, region, rejected, route, settle, signInAs } from '../__tests__/console1a';
import {
  AGENT_EN,
  ISSUE_TITLE_EN,
  browserLanguages,
  expectEnglishPage,
  expectNoHangul,
  hangulIn,
  openInEnglish,
  signedOutInEnglish,
} from '../__tests__/consoleB5';

type User = ReturnType<typeof renderAt>['user'];

const HANGUL = /[가-힣]/;

/** The one form of a signed-out screen and its submit button, whatever their words. */
const form = () => found(() => document.querySelector('form'), '화면의 form');
const submit = async () => found(() => document.querySelector<HTMLButtonElement>('form button[type="submit"]'), 'form의 보내기 단추');
const emailInput = () => found(() => document.querySelector<HTMLInputElement>('input[type="email"]'), '이메일 칸');
const passwordInputs = () => Array.from(document.querySelectorAll<HTMLInputElement>('input[type="password"]'));

/** Waits until the screen has at least `n` password fields and gives them. */
async function passwordFields(n: number): Promise<HTMLInputElement[]> {
  await eventually(() => {
    expect(passwordInputs().length, `비밀번호 칸 ${String(n)}개`).toBeGreaterThanOrEqual(n);
  });
  return passwordInputs();
}

/** The banner and the menu of a signed-in screen. */
const banner = () => found(() => screen.queryByRole('banner'), '머리(banner)');
const menu = () => found(() => screen.queryAllByRole('navigation')[0], '옆 메뉴(navigation)');

const INVITE_URL = '/invite#token=inv-Zq81mWc4Lp0a';

async function signInFails(user: User) {
  await user.type(await emailInput(), 'lee@popdesk.test');
  await user.type(passwordInputs()[0] as HTMLInputElement, 'wrong password 1');
  await user.click(await submit());
}

describe('B5 ① 콘솔 로그인 전 화면을 영어로', () => {
  test.each([
    ['C-1 로그인', '/login'],
    ['C-2 초대 수락', INVITE_URL],
    ['C-16 비밀번호 찾기', '/forgot-password'],
    ['C-17 비밀번호 재설정', `/reset-password${new URL(RESET_LINK).hash}`],
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

  test('B5-AC-05 C-1 로그인 실패: 오류 띠도 영어다', async () => {
    signedOutInEnglish();
    on('post', '/api/public/sign-in', () => errors.signInFailed());
    const { user } = renderAt('/login');
    await signInFails(user);

    await found(() => screen.queryAllByRole('alert').find((el) => text(el) !== ''), '로그인 실패 띠');
    expectNoHangul('C-1 로그인 실패');
  });

  test('B5-AC-05 C-1 요청이 많음(429): N초 뒤 띠도 영어다', async () => {
    signedOutInEnglish();
    on('post', '/api/public/sign-in', () => errors.rateLimited(30));
    const { user } = renderAt('/login');
    await signInFails(user);

    const band = await found(() => screen.queryAllByRole('alert').find((el) => /30|29/.test(text(el))), '429 띠');
    expectNoHangul('C-1 429', band);
    expectNoHangul('C-1 429');
  });

  test('B5-AC-05 C-1 세션이 끝나 돌아옴: 안내 띠도 영어다', async () => {
    browserLanguages(['en-US']);
    openInEnglish();
    const { user } = renderAt('/issues');
    await found(() => screen.queryByText(ISSUE_TITLE_EN), '문의 목록의 견본 문의');
    state.signedIn = false;
    await user.click(await found(() => document.querySelector<HTMLAnchorElement>('nav a[href="/tasks"]'), '메뉴의 내 작업 링크'));

    await emailInput();
    await found(() => screen.queryAllByRole('alert').concat(screen.queryAllByRole('status')).find((el) => text(el) !== ''), '세션이 끝났다는 안내');
    await eventually(() => {
      expectNoHangul('C-1 세션 끝남');
    });
  });

  test('B5-AC-05 C-16 보낸 뒤: 안내 글도 영어다', async () => {
    signedOutInEnglish();
    const { user } = renderAt('/forgot-password');
    await user.type(await emailInput(), 'lee@popdesk.test');
    await user.click(await submit());

    await sent('POST', '/api/public/password-reset/request');
    await settle();
    await eventually(() => {
      expect(document.querySelector('input[type="email"]'), '보낸 뒤 안내로 바뀜').toBeNull();
    });
    expectNoHangul('C-16 보낸 뒤');
  });

  test('B5-AC-05 C-17 링크를 쓸 수 없음: 안내 화면도 영어다', async () => {
    signedOutInEnglish();
    route('post', '/api/public/password-reset/confirm', () => rejected('PASSWORD_RESET_INVALID'));
    const { user } = renderAt(`/reset-password${new URL(RESET_LINK).hash}`);
    const [first, second] = await passwordFields(2);
    await user.type(first as HTMLInputElement, 'new password 2026');
    await user.type(second as HTMLInputElement, 'new password 2026');
    await user.click(await submit());

    await sent('POST', '/api/public/password-reset/confirm');
    await eventually(() => {
      expect(passwordInputs(), '쓸 수 없음 안내로 바뀜').toEqual([]);
    });
    expectNoHangul('C-17 쓸 수 없음');
  });

  test('B5-AC-05 C-17 두 칸이 다름: 칸 오류도 영어다', async () => {
    signedOutInEnglish();
    const { user } = renderAt(`/reset-password${new URL(RESET_LINK).hash}`);
    const [first, second] = await passwordFields(2);
    await user.type(first as HTMLInputElement, 'new password 2026');
    await user.type(second as HTMLInputElement, 'other password 2026');
    await user.click(await submit());

    await found(() => document.querySelector('[aria-invalid="true"]'), '틀린 칸');
    expectNoHangul('C-17 두 칸이 다름');
  });

  test('B5-AC-05 C-2 초대가 쓸 수 없음: 안내도 영어다', async () => {
    signedOutInEnglish();
    on('post', '/api/public/invitations/accept', () => Response.json({ code: 'INVITATION_INVALID', message: 'Invalid invitation.' }, { status: 400 }));
    const { user } = renderAt(INVITE_URL);
    for (const input of await passwordFields(1)) {
      await user.type(input, 'new password 2026');
    }
    const name = document.querySelector<HTMLInputElement>('form input[type="text"]');
    if (name && name.value === '') await user.type(name, 'Park Invitee');
    await user.click(await submit());

    await sent('POST', '/api/public/invitations/accept');
    await eventually(() => {
      expect(passwordInputs(), '쓸 수 없음 안내로 바뀜').toEqual([]);
    });
    expectNoHangul('C-2 초대를 쓸 수 없음');
  });
});

describe('B5 ① 콘솔 C-6 내 정보를 영어로', () => {
  test('B5-AC-05 C-6 처음: 세 구역(프로필, 사진, 비밀번호)과 머리, 옆 메뉴에 한글이 없다', async () => {
    openInEnglish(AGENT_EN);
    renderAt('/me');
    await found(() => document.querySelector('select'), '언어 칸');
    await found(() => document.querySelector('input[type="file"]'), '사진 칸');
    await passwordFields(3);
    await eventually(() => {
      expect(text(document.body)).toContain('Acme');
    });

    expectNoHangul('C-6 처음');
    expectEnglishPage('C-6 처음');
  });

  test('B5-AC-05 C-6 비밀번호 오류(지금 비밀번호가 틀림): 칸 오류도 영어다', async () => {
    openInEnglish();
    route('put', '/api/console/me/password', () => rejected('CURRENT_PASSWORD_WRONG'));
    const { user } = renderAt('/me');
    const inputs = await passwordFields(3);
    await user.type(inputs[0] as HTMLInputElement, 'old password 2025');
    await user.type(inputs[1] as HTMLInputElement, 'new password 2026');
    await user.type(inputs[2] as HTMLInputElement, 'new password 2026');
    const box = (inputs[0] as HTMLInputElement).closest('section, form, [role="region"]') as HTMLElement;
    await user.click(await found(() => box.querySelector<HTMLButtonElement>('button[type="submit"]') ?? within(box).queryAllByRole('button').at(-1), '비밀번호 바꾸기 단추'));

    await found(() => document.querySelector('[aria-invalid="true"]'), '틀린 칸');
    expectNoHangul('C-6 비밀번호 오류');
  });

  test('B5-AC-05 C-6 사진 오류 띠: 영어다', async () => {
    openInEnglish();
    route('post', '/api/console/me/photo', () => rejected('UNSUPPORTED_IMAGE'));
    const { user } = renderAt('/me');
    const input = await found(() => document.querySelector<HTMLInputElement>('input[type="file"]'), '사진 칸');
    await user.upload(input, new File(['me.png'], 'me.png', { type: 'image/png' }));

    await found(() => screen.queryAllByRole('alert').find((el) => text(el) !== ''), '사진 오류 띠');
    expectNoHangul('C-6 사진 오류');
  });
});

describe('B5 언어 장치(콘솔)', () => {
  test('B5-AC-01 B5-AC-04 로그인해 있으면 Me.locale을 쓴다: en 계정은 머리, 옆 메뉴, 화면 제목, html lang, document.title이 영어다', async () => {
    browserLanguages(['ko-KR']);
    openInEnglish();
    renderAt('/issues');
    await found(() => screen.queryByText(ISSUE_TITLE_EN), '문의 목록의 견본 문의');

    await eventually(() => {
      expect(document.documentElement.lang).toBe('en');
    });
    expectNoHangul('머리', await banner());
    expectNoHangul('옆 메뉴', await menu());
    expectNoHangul('화면 제목', await heading1());
    expectEnglishPage('C-3');
    expect(menuLinks(), '한국어 이름의 메뉴(nav "메뉴")는 없다').toEqual([]);
  });

  test('B5-AC-01 B5-AC-06 ko 계정은 브라우저가 en-US여도 한국어 그대로다: html lang은 ko, 머리와 메뉴는 지금 글이다', async () => {
    browserLanguages(['en-US']);
    signInAs(ADMIN);
    renderAt('/issues');
    await found(() => screen.queryByText('로그인이 안 됩니다'), '문의 목록의 견본 문의');

    expect(text(await heading1())).toMatch(HANGUL);
    expect(menuLinks()).toContain('문의');
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
    expectNoHangul('C-16, language만 en-GB');
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

  test('B5-AC-03 B5-AC-04 C-6에서 언어를 en으로 저장하면 새로고침 없이 머리, 옆 메뉴, 화면, html lang, document.title이 영어로 바뀐다', async () => {
    browserLanguages(['ko-KR']);
    const { user } = renderAt('/me');
    expect(text(await heading1())).toBe('내 정보');
    const select = await found(() => document.querySelector('select'), '언어 칸');
    await user.selectOptions(select, 'en');
    await user.click(await found(() => within(select.closest('section, form, [role="region"]') as HTMLElement).queryByRole('button', { name: '저장' }), '"저장" 단추'));

    const [patch] = await sent('PATCH', '/api/console/me');
    expect(await patch?.body).toMatchObject({ locale: 'en' });
    await eventually(() => {
      expect(document.documentElement.lang).toBe('en');
    });
    await eventually(() => {
      expectNoHangul('머리', screen.getByRole('banner'));
      expectNoHangul('옆 메뉴', screen.getAllByRole('navigation')[0]);
      expectNoHangul('화면 제목', screen.getByRole('heading', { level: 1 }));
    });
    expectEnglishPage('C-6 저장 뒤');
    expect(state.me.name, '견본 이름은 한글이라 이름 칸은 뺀다').toBe(ADMIN.name);
  });

  test('B5-AC-03 C-6에서 en 계정이 ko로 저장하면 화면이 한국어로 돌아온다', async () => {
    openInEnglish();
    const { user } = renderAt('/me');
    const select = await found(() => document.querySelector('select'), '언어 칸');
    await eventually(() => {
      expect(document.documentElement.lang).toBe('en');
    });
    await user.selectOptions(select, 'ko');
    await user.click(await found(() => (select.closest('section, form, [role="region"]') as HTMLElement).querySelector<HTMLButtonElement>('button[type="submit"]'), '저장 단추'));

    await sent('PATCH', '/api/console/me');
    await eventually(() => {
      expect(document.documentElement.lang).toBe('ko');
      expect(text(screen.getByRole('heading', { level: 1 }))).toBe('내 정보');
    });
    expect(menuLinks()).toContain('문의');
  });

  test('B5-AC-03 C-6 저장이 실패하면 언어를 바꾸지 않는다(screens.md 1절 3)', async () => {
    openInEnglish();
    route('patch', '/api/console/me', () => errors.internal());
    const { user } = renderAt('/me');
    const select = await found(() => document.querySelector('select'), '언어 칸');
    await eventually(() => {
      expect(document.documentElement.lang).toBe('en');
    });
    await user.selectOptions(select, 'ko');
    await user.click(await found(() => (select.closest('section, form, [role="region"]') as HTMLElement).querySelector<HTMLButtonElement>('button[type="submit"]'), '저장 단추'));

    await found(() => screen.queryAllByRole('alert').find((el) => text(el) !== ''), '저장 실패 띠');
    await settle();
    expect(document.documentElement.lang).toBe('en');
    expectNoHangul('C-6 저장 실패 뒤');
  });

  test('B5-AC-03 C-6 언어 칸의 도움말은 "화면과 메일의 언어"를 말한다(한국어 화면)', async () => {
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

  test('B5-AC-07 수와 날짜는 화면 언어를 따른다: en 시스템 상태의 날짜는 영어 달 이름이고, 수 1234는 1,234다', async () => {
    openInEnglish();
    state.status = {
      ...state.status,
      cleanupJobs: [cleanupJob({ name: 'attachment-cleanup', lastRunAt: '2026-10-08T03:15:00Z', deleted: 1234 })],
    };
    renderAt('/system');
    await heading1();

    await found(() => screen.queryAllByText(/1,234/)[0], '수 1,234');
    await eventually(() => {
      expect(text(document.body)).toMatch(/\b(Oct|October)\b/);
    });
    expect(text(document.body)).not.toMatch(/\d+년|\d+월|\d+일/);
  });
});
