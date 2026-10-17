// 9번 묶음 screens.md 2절 C-14 브랜딩(관리자, /branding): 메뉴 끝 자리와 권한, 구역 셋(표시 이름, 강조색, 로고), 기본 색 쓰기
// (켜기·끄기와 글 되살림), 강조색 미리 보기(0.3초 뒤 부름, 늦은 답 버림, 받을 수 없는 색, 꼴 틀림, 미리 보기 실패), 저장(바뀐 것
// 없음, 성공과 화면 전체가 바로 바뀜, 400 넷, LOW_CONTRAST, 409와 다시 불러오기), 로고 칸(상태 다섯, 바로 올림, 오류 문장, 초점),
// 읽기 실패(S9-AC-28~32).
import { act, fireEvent } from '@testing-library/react';
import { HttpResponse } from 'msw/http';
import { describe, expect, test, vi } from 'vitest';
import {
  AGENT,
  callsTo,
  describedText,
  errors,
  eventually,
  expectNoAxeViolations,
  found,
  gate,
  hrefPath,
  isBlocked,
  menuLinks,
  pageText,
  renderAt,
  screen,
  sent,
  state,
  text,
  type Part,
} from '../__tests__/console';
import { answered, rejected, route, settle } from '../__tests__/console1a';
import {
  ACCENT,
  BAD_FORMAT,
  BRANDING,
  CONFLICT,
  DEFAULT_COLOR,
  LOGO_HELP,
  LOGO_TOO_BIG,
  LOGO_TOO_MANY_PIXELS,
  LOGO_UNSUPPORTED,
  LOW_CONTRAST,
  NAME_BAD,
  NAME_HELP,
  NAME_REQUIRED,
  NAME_TOO_LONG,
  NOTHING_CHANGED,
  NO_LOGO,
  NO_VARIABLES,
  PREVIEW_FAILED,
  PUBLIC_BRANDING,
  READ_FAILED,
  RELOAD,
  SAVED,
  SQUARE_LOGO,
  TITLE,
  UPLOADING,
  URL_BRANDING,
  VIOLET,
  WIDE_LOGO,
  acceptLogo,
  accentField,
  brandVariables,
  brandingResponse,
  colorInput,
  contrastLine,
  defaultBox,
  defaultBranding,
  fileInputOf,
  image,
  imagesIn,
  logoField,
  logoPath,
  nameField,
  pane,
  paneNow,
  previewResponse,
  publicOf,
  removeButtonOf,
  s9,
  saveButton,
  section,
  slowly,
  title1,
  uploadLabelOf,
  variablesOf,
  violetBranding,
  type Branding,
  type UpdateBrandingRequest,
} from '../__tests__/console9';

type User = ReturnType<typeof renderAt>['user'];

/** Renders C-14 with `b` on the server and waits until the branding it read is drawn. */
async function openBranding(b: Branding): Promise<User> {
  s9.branding = b;
  s9.public = publicOf(b);
  const { user } = renderAt(URL_BRANDING);
  await title1(TITLE);
  await sent('GET', BRANDING);
  const name = await nameField();
  await slowly(() => {
    expect(name.value, '읽은 표시 이름').toBe(b.displayName);
  });
  return user;
}

/** Clears `field` and types `value` (nothing for an empty value). */
async function retype(user: User, field: HTMLInputElement, value: string): Promise<void> {
  await user.clear(field);
  if (value !== '') await user.type(field, value);
}

/** The `color` of every preview request sent so far. */
function previewColors(): (string | null)[] {
  return callsTo('GET', ACCENT).map((c) => new URL(c.url).searchParams.get('color'));
}

/** The body of the last save as it was sent. */
async function lastSaved(): Promise<UpdateBrandingRequest> {
  const call = (await sent('PUT', BRANDING)).at(-1);
  return (await call?.body) as UpdateBrandingRequest;
}

/** Waits until both preview panes show the contrast lines of `light` and `dark`. */
async function panesShow(light: RegExp | string, dark: RegExp | string): Promise<void> {
  await slowly(() => {
    const l = text(paneNow('light'));
    const d = text(paneNow('dark'));
    if (typeof light === 'string') expect(l, '밝은 테마 칸').toContain(light);
    else expect(l, '밝은 테마 칸').toMatch(light);
    if (typeof dark === 'string') expect(d, '어두운 테마 칸').toContain(dark);
    else expect(d, '어두운 테마 칸').toMatch(dark);
  });
}

/** Turns "기본 색 쓰기" off (it is on for a branding without an accent). */
async function useOwnColor(user: User): Promise<HTMLInputElement> {
  const box = await defaultBox();
  if (box.checked) await user.click(box);
  const field = await accentField();
  await eventually(() => {
    expect(isBlocked(field), '강조색 칸이 열림').toBe(false);
  });
  return field;
}

/** Sets the accent text at once (one change event), as a paste does. */
function setAccentNow(field: HTMLInputElement, value: string): void {
  act(() => {
    fireEvent.change(field, { target: { value } });
  });
}

/** The value of the field "표시 이름" now. */
function nameNow(): string | undefined {
  return screen.queryAllByRole<HTMLInputElement>('textbox', { name: /^표시 이름/ })[0]?.value;
}

/** The head of the app shell now, or an empty div. */
function headNow(): Element {
  return document.querySelector('[data-pd="AppShell"] header') ?? document.createElement('div');
}

/** The focused element is the file input of `field` labelled `label`, or that label itself. */
function focusIsOn(field: HTMLElement, label: string): boolean {
  const input = fileInputOf(field);
  const active = document.activeElement;
  return (active === input && text(uploadLabelOf(field)) === label) || (active !== null && field.contains(active) && text(active) === label);
}

describe('C-14 브랜딩 자리와 권한 (9번 묶음)', () => {
  test('S9-AC-28 C-14 관리자 옆 메뉴의 "브랜딩"은 "시스템 상태" 다음, 맨 끝이고, 누르면 /branding의 h1 "브랜딩"이다', async () => {
    const { user } = renderAt('/issues');
    const menu = await found(() => screen.queryAllByRole('link', { name: TITLE }).find((a) => a.closest('nav') !== null), `메뉴 "${TITLE}"`);
    const links = menuLinks();
    expect(links.at(-1), '맨 끝').toBe(TITLE);
    expect(links.at(-2), '"시스템 상태" 다음').toBe('시스템 상태');
    expect(hrefPath(menu)).toBe(URL_BRANDING);

    await user.click(menu);

    await eventually(() => {
      expect(window.location.pathname).toBe(URL_BRANDING);
    });
    await title1(TITLE);
    await sent('GET', BRANDING);
  });

  test('S9-AC-28 C-14 상담원: 메뉴에 "브랜딩"이 없고, 주소로 오면 화면 전체가 "권한이 없습니다"이며 브랜딩을 부르지 않는다', async () => {
    state.me = AGENT;
    renderAt(URL_BRANDING);

    await slowly(() => {
      expect(pageText()).toContain('권한이 없습니다');
    });
    expect(menuLinks()).not.toContain(TITLE);
    expect(callsTo('GET', BRANDING)).toHaveLength(0);
  });
});

describe('C-14 브랜딩 읽기 (9번 묶음)', () => {
  test('S9-AC-28 C-14 기본 브랜딩: 구역 셋, "popdesk"와 도움말, "기본 색 쓰기"가 켜져 강조색 칸이 막히고, 두 미리 보기는 "기본 색", 로고 칸 둘은 "로고 없음"과 "올리기"', async () => {
    await openBranding(defaultBranding());

    for (const name of ['표시 이름', '강조색', '로고']) await section(name);
    const name = await nameField();
    expect(describedText(name), '표시 이름 도움말').toContain(NAME_HELP);
    expect(await defaultBox()).toBeChecked();
    expect(isBlocked(await accentField()), '강조색 칸').toBe(true);
    expect(isBlocked(await colorInput()), '색 고르기').toBe(true);
    await panesShow(DEFAULT_COLOR, DEFAULT_COLOR);
    for (const theme of ['light', 'dark'] as const) {
      const shown = text(await pane(theme));
      expect(shown, `${theme} 칸의 주 버튼 견본`).toContain('주 버튼');
      expect(shown, `${theme} 칸의 링크 견본`).toContain('링크');
      const logo = await logoField(theme);
      expect(text(logo)).toContain(NO_LOGO);
      expect(text(uploadLabelOf(logo))).toBe('올리기');
      expect(removeButtonOf(logo), '로고가 없으면 지우기가 없다').toBeNull();
      expect(imagesIn(logo)).toEqual([]);
    }
    expect(text(await section('로고'))).toContain(LOGO_HELP);
    expect(callsTo('GET', ACCENT), '기본 색은 미리 보기를 부르지 않는다').toHaveLength(0);
    await expectNoAxeViolations();
  });

  test('S9-AC-28 C-14 정한 브랜딩: 이름과 강조색 #6D28D9(기본 색 쓰기 꺼짐, 색 고르기도 그 색), 두 테마의 강조색과 가장 낮은 대비, 밝은 로고는 크기와 "바꾸기"·"지우기", 어두운 칸은 밝은 로고를 흐리게 쓴다', async () => {
    await openBranding(violetBranding({ logos: { light: WIDE_LOGO, dark: null } }));

    expect((await accentField()).value).toBe('#6D28D9');
    expect(await defaultBox()).not.toBeChecked();
    expect((await colorInput()).value.toUpperCase()).toBe('#6D28D9');
    await panesShow(contrastLine('#6D28D9', '6.22'), contrastLine('#B8A5FF', '8.01'));

    const light = await logoField('light');
    expect(imagesIn(light)).toEqual([WIDE_LOGO.url]);
    expect(text(light)).toMatch(/512 ?× ?64 ?px/);
    expect(text(uploadLabelOf(light))).toBe('바꾸기');
    expect(removeButtonOf(light)).not.toBeNull();
    const dark = await logoField('dark');
    expect(text(dark)).toContain('없음. 밝은 테마 로고를 같이 씁니다');
    expect(imagesIn(dark), '밝은 테마 로고를 흐리게').toEqual([WIDE_LOGO.url]);
    expect(text(uploadLabelOf(dark))).toBe('올리기');
    expect(removeButtonOf(dark)).toBeNull();
    await expectNoAxeViolations();
  });

  test('S9-AC-28 C-14 어두운 로고만 있으면 밝은 칸이 "없음. 어두운 테마 로고를 같이 씁니다"와 그 로고를 보인다', async () => {
    await openBranding(defaultBranding({ logos: { light: null, dark: SQUARE_LOGO } }));

    const light = await logoField('light');
    await eventually(() => {
      expect(text(light)).toContain('없음. 어두운 테마 로고를 같이 씁니다');
    });
    expect(imagesIn(light)).toEqual([SQUARE_LOGO.url]);
    expect(text(await logoField('dark'))).toMatch(/128 ?× ?128 ?px/);
  });

  test('S9-AC-28 C-14 읽기 실패(500): "브랜딩을 읽지 못했습니다. 다시 해 보세요."와 "다시 불러오기". 누르면 다시 읽어 칸이 나온다', async () => {
    s9.branding = violetBranding();
    let fail = true;
    route('get', BRANDING, () => (fail ? errors.internal() : brandingResponse()));
    const { user } = renderAt(URL_BRANDING);

    await slowly(() => {
      expect(screen.queryAllByRole('alert').some((a) => text(a).includes(READ_FAILED)), `role="alert" "${READ_FAILED}"`).toBe(true);
    });
    expect(screen.queryByRole('textbox', { name: /^표시 이름/ }), '실패를 빈 칸으로 보이지 않는다').toBeNull();
    const before = callsTo('GET', BRANDING).length;
    fail = false;

    await user.click(await found(() => screen.queryByRole('button', { name: RELOAD }), `"${RELOAD}"`));

    await eventually(() => {
      expect(callsTo('GET', BRANDING).length, '다시 읽기').toBeGreaterThan(before);
    });
    await eventually(() => {
      expect(nameNow()).toBe('Acme 지원');
    });
  });
});

describe('C-14 강조색과 미리 보기 (9번 묶음)', () => {
  test('S9-AC-29 C-14 강조색을 쓰면 미리 보기 API를 그 색으로 부르고 두 칸에 만든 값과 대비를 보인다. 화면 전체 색은 저장 전에 바뀌지 않는다', async () => {
    const user = await openBranding(defaultBranding());
    const field = await useOwnColor(user);

    await retype(user, field, '#047857');

    await sent('GET', ACCENT);
    expect(previewColors().at(-1)).toBe('#047857');
    await panesShow(contrastLine('#047857', '4.81'), contrastLine('#6ECBA5', '7.28'));
    expect(text(await pane('light'))).toContain('주 버튼');
    expect(brandVariables(), '화면 전체의 변수(저장 전)').toEqual(NO_VARIABLES);
  });

  test('S9-AC-29 C-14 미리 보기는 맞는 꼴의 입력이 0.3초 쉰 뒤에 부르고, 그 전에 바뀐 입력은 부르지 않는다', async () => {
    const user = await openBranding(defaultBranding());
    const field = await useOwnColor(user);
    const before = callsTo('GET', ACCENT).length;
    vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout'] });
    try {
      setAccentNow(field, '#6D28D9');
      act(() => {
        vi.advanceTimersByTime(200);
      });
      setAccentNow(field, '#047857');
      act(() => {
        vi.advanceTimersByTime(250);
      });
      expect(callsTo('GET', ACCENT).length, '쉰 지 0.25초').toBe(before);
      act(() => {
        vi.advanceTimersByTime(100);
      });
    } finally {
      vi.useRealTimers();
    }

    await sent('GET', ACCENT, before + 1);
    await settle();
    expect(previewColors().slice(before), '0.3초 안에 바뀐 앞 입력은 부르지 않는다').toEqual(['#047857']);
  });

  test('S9-AC-29 C-14 늦게 온 앞 미리 보기 답은 버린다: 나중 색의 값이 그대로다', async () => {
    const release = gate();
    route('get', ACCENT, async ({ request }) => {
      const color = new URL(request.url).searchParams.get('color');
      if (color === '#6D28D9') await release.opened;
      return previewResponse(color);
    });
    const user = await openBranding(defaultBranding());
    const field = await useOwnColor(user);

    await retype(user, field, '#6D28D9');
    await sent('GET', ACCENT);
    await retype(user, field, '#047857');
    await panesShow(contrastLine('#047857', '4.81'), contrastLine('#6ECBA5', '7.28'));
    const done = answered('GET', ACCENT);
    release.open();
    await done;
    await settle();

    expect(text(paneNow('light')), '늦게 온 #6D28D9 답').not.toMatch(contrastLine('#6D28D9', '6.22'));
    expect(text(paneNow('light'))).toMatch(contrastLine('#047857', '4.81'));
  });

  test('S9-AC-29 C-14 받을 수 없는 색: 밝은 칸은 글자 견본 없이 색 견본만, 칸 아래 "대비가 모자랍니다(4.5:1 이상이어야 합니다)"를 강조색 칸의 설명으로 묶고(alert 아님), 저장은 요청 없이 칸 오류와 초점', async () => {
    const user = await openBranding(defaultBranding());
    const field = await useOwnColor(user);

    await retype(user, field, '#E11D48');

    await sent('GET', ACCENT);
    await slowly(() => {
      expect(describedText(field), '강조색 칸의 설명').toContain(LOW_CONTRAST);
    });
    const light = text(await pane('light'));
    expect(light, '받을 수 없는 칸의 버튼 글자').not.toContain('주 버튼');
    expect(light, '받을 수 없는 칸의 링크').not.toContain('링크');
    expect(text(await pane('dark')), '받는 어두운 칸').toContain('주 버튼');
    expect(screen.queryAllByRole('alert').some((a) => text(a).includes(LOW_CONTRAST)), 'role="alert"로 읽지 않는다').toBe(false);
    await expectNoAxeViolations();

    await user.click(await saveButton());

    await eventually(() => {
      expect(field).toHaveAttribute('aria-invalid', 'true');
      expect(document.activeElement, '초점은 강조색 칸').toBe(field);
    });
    expect(describedText(field)).toContain(LOW_CONTRAST);
    expect(callsTo('PUT', BRANDING), '받을 수 없다고 알려진 색은 보내지 않는다').toHaveLength(0);
  });

  test('S9-AC-29 C-14 꼴이 틀린 입력: 미리 보기를 부르지 않고 "#과 16진수 여섯 자리로 쓰세요. 예: #047857", 색 고르기는 마지막 맞는 값을 지닌다', async () => {
    await openBranding(violetBranding());
    const field = await accentField();
    const before = callsTo('GET', ACCENT).length;
    vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout'] });
    try {
      setAccentNow(field, '#12');
      act(() => {
        vi.advanceTimersByTime(400);
      });
    } finally {
      vi.useRealTimers();
    }
    await settle();

    expect(callsTo('GET', ACCENT).length, '꼴이 틀린 입력의 미리 보기 요청').toBe(before);
    await slowly(() => {
      expect(describedText(field)).toContain(BAD_FORMAT);
    });
    expect((await colorInput()).value.toUpperCase(), '색 고르기의 값').toBe('#6D28D9');
  });

  test.each([
    { what: '500', answer: () => errors.internal() },
    { what: '429', answer: () => errors.rateLimited(30) },
    { what: '연결 없음', answer: () => HttpResponse.error() },
  ])('S9-AC-29 C-14 미리 보기 실패($what): "미리 보기를 읽지 못했습니다."만 보이고 저장은 막지 않는다', async ({ answer }) => {
    route('get', ACCENT, answer);
    const user = await openBranding(defaultBranding());
    const field = await useOwnColor(user);

    await retype(user, field, '#6D28D9');

    await sent('GET', ACCENT);
    await slowly(() => {
      expect(text(sectionOr('강조색'))).toContain(PREVIEW_FAILED);
    });
    expect(text(paneNow('light')), '실패한 미리 보기의 대비 줄').not.toMatch(/가장 낮은 대비/);

    await user.click(await saveButton());

    expect((await lastSaved()).accent).toBe('#6D28D9');
  });

  test('S9-AC-29 C-14 "기본 색 쓰기": 켜면 칸이 막히고 꼴 오류가 사라지며 미리 보기는 "기본 색", 끄면 켜기 전의 글이 돌아오고, 켠 채 저장하면 accent null', async () => {
    const user = await openBranding(violetBranding());
    const field = await accentField();
    await retype(user, field, '#12');
    await slowly(() => {
      expect(describedText(field)).toContain(BAD_FORMAT);
    });
    const box = await defaultBox();

    await user.click(box);

    await eventually(() => {
      expect(isBlocked(field), '강조색 칸').toBe(true);
      expect(describedText(field), '꼴 오류').not.toContain(BAD_FORMAT);
    });
    expect(isBlocked(await colorInput()), '색 고르기').toBe(true);
    await panesShow(DEFAULT_COLOR, DEFAULT_COLOR);

    await user.click(box);
    await eventually(() => {
      expect(field.value, '켜기 전의 글').toBe('#12');
    });
    await user.click(box);
    await user.click(await saveButton());

    const body = await lastSaved();
    expect(body.accent).toBeNull();
    expect(body.displayName).toBe('Acme 지원');
    expect(body.version).toBe(4);
  });

  test('S9-AC-29 C-14 색 고르기에서 고르면 대문자로 강조색 칸에 넣는다', async () => {
    const user = await openBranding(defaultBranding());
    const field = await useOwnColor(user);
    const picker = await colorInput();

    act(() => {
      fireEvent.input(picker, { target: { value: '#6d28d9' } });
      fireEvent.change(picker, { target: { value: '#6d28d9' } });
    });

    await eventually(() => {
      expect(field.value).toBe('#6D28D9');
    });
    await sent('GET', ACCENT);
    expect(previewColors().at(-1)).toBe('#6D28D9');
  });
});

/** The section `name` now, or an empty div (for a `text` that waits). */
function sectionOr(name: string): Element {
  const h2 = screen.queryAllByRole('heading', { level: 2 }).find((h) => text(h) === name);
  return h2?.closest('section') ?? document.createElement('div');
}

describe('C-14 브랜딩 저장 (9번 묶음)', () => {
  test('S9-AC-31 C-14 바뀐 것 없이 "저장": 요청을 보내지 않고 "바뀐 것이 없습니다."', async () => {
    const user = await openBranding(violetBranding());

    await user.click(await saveButton());

    await slowly(() => {
      expect(pageText()).toContain(NOTHING_CHANGED);
    });
    expect(callsTo('PUT', BRANDING)).toHaveLength(0);
  });

  test('S9-AC-32 C-14 저장: 본문은 version, displayName, accent뿐이고, 화면 안 "브랜딩을 저장했습니다.", 다시 읽지 않고 화면 전체의 강조색 변수·머리 이름·탭 제목이 바로 바뀐다', async () => {
    const user = await openBranding(defaultBranding());
    await retype(user, await nameField(), 'Acme 지원');
    const field = await useOwnColor(user);
    await retype(user, field, '#6D28D9');
    await panesShow(contrastLine('#6D28D9', '6.22'), contrastLine('#B8A5FF', '8.01'));
    expect(brandVariables()).toEqual(NO_VARIABLES);
    const reads = callsTo('GET', PUBLIC_BRANDING).length;

    await user.click(await saveButton());

    const body = await lastSaved();
    expect(body).toEqual({ version: 1, displayName: 'Acme 지원', accent: '#6D28D9' });
    expect(Object.keys(body).sort(), '본문의 칸').toEqual(['accent', 'displayName', 'version']);
    await slowly(() => {
      expect(screen.queryAllByRole('alert').concat(screen.queryAllByRole('status')).some((a) => text(a).includes(SAVED)), `화면 안 "${SAVED}"`).toBe(true);
    });
    await eventually(() => {
      expect(brandVariables(), '화면 전체의 변수').toEqual(variablesOf(VIOLET));
    });
    const top = headNow();
    await eventually(() => {
      expect(text(top), '머리의 표시 이름').toContain('Acme 지원');
      expect(document.title).toBe(`${TITLE} - Acme 지원`);
    });
    expect(callsTo('GET', PUBLIC_BRANDING).length, '공개 브랜딩을 다시 읽지 않는다').toBe(reads);
    expect(s9.branding.version).toBe(2);
  });

  test.each([
    { code: 'REQUIRED', typed: '', words: NAME_REQUIRED },
    { code: 'BLANK', typed: '   ', words: NAME_REQUIRED },
    { code: 'TOO_LONG', typed: '가'.repeat(51), words: NAME_TOO_LONG },
    { code: 'INVALID_FORMAT', typed: `Acme${String.fromCodePoint(0x202e)}지원`, words: NAME_BAD },
  ])('S9-AC-31 C-14 저장 400 표시 이름 $code: 칸 아래 "$words", 칸은 aria-invalid. 서버의 브랜딩은 그대로', async ({ typed, words }) => {
    const user = await openBranding(violetBranding());
    const name = await nameField();
    await user.clear(name);
    if (typed !== '') fireEvent.change(name, { target: { value: typed } });

    await user.click(await saveButton());

    await eventually(() => {
      expect(name).toHaveAttribute('aria-invalid', 'true');
      expect(describedText(name)).toContain(words);
    });
    expect(s9.branding.version).toBe(4);
    expect(pageText()).not.toContain(SAVED);
  });

  test('S9-AC-29 C-14 미리 보기를 못 읽은 채 보낸 색에 서버가 400 LOW_CONTRAST로 답하면 강조색 칸에 "대비가 모자랍니다(4.5:1 이상이어야 합니다)"', async () => {
    route('get', ACCENT, () => errors.internal());
    const user = await openBranding(defaultBranding());
    const field = await useOwnColor(user);
    await retype(user, field, '#E11D48');
    await sent('GET', ACCENT);

    await user.click(await saveButton());

    await sent('PUT', BRANDING);
    await eventually(() => {
      expect(field).toHaveAttribute('aria-invalid', 'true');
      expect(describedText(field)).toContain(LOW_CONTRAST);
    });
    expect(s9.branding.version).toBe(1);
  });

  test('S9-AC-31 C-14 409: role="alert" "다른 관리자가 먼저 바꿨습니다. 다시 읽은 뒤 고쳐 주세요."와 "다시 불러오기". 누르기 전까지 고친 값 그대로, 누르면 다시 읽은 값', async () => {
    const user = await openBranding(violetBranding());
    // 다른 관리자가 먼저 저장했다: 서버는 버전 5, 이름 "Beta 지원".
    s9.branding = violetBranding({ version: 5, displayName: 'Beta 지원' });
    const name = await nameField();
    await retype(user, name, 'Acme 고객지원');

    await user.click(await saveButton());

    await found(() => screen.queryAllByRole('alert').find((a) => text(a).includes(CONFLICT)), `role="alert" "${CONFLICT}"`);
    const reload = await found(() => screen.queryByRole('button', { name: RELOAD }), `"${RELOAD}"`);
    expect(name.value, '누르기 전의 고친 값').toBe('Acme 고객지원');
    const reads = callsTo('GET', BRANDING).length;

    await user.click(reload);

    await eventually(() => {
      expect(callsTo('GET', BRANDING).length, '다시 읽기').toBeGreaterThan(reads);
    });
    await eventually(() => {
      expect(nameNow(), '다시 읽은 값').toBe('Beta 지원');
    });
    expect(screen.queryAllByRole('alert').some((a) => text(a).includes(CONFLICT)), '다시 읽은 뒤의 409 띠').toBe(false);
  });
});

describe('C-14 로고 (9번 묶음)', () => {
  test('S9-AC-30 C-14 로고 올리기: 고르면 저장 단추와 따로 바로 part `file`로 올리고, 올린 로고와 크기를 보이며 초점은 "바꾸기", 머리 로고도 바로 바뀐다', async () => {
    const user = await openBranding(defaultBranding());
    const light = await logoField('light');

    await user.upload(fileInputOf(light), image('logo.png'));

    const [upload] = await sent('PUT', logoPath('light'));
    expect(((await upload?.body) as Part[]).map((p) => p.name)).toEqual(['file']);
    await eventually(() => {
      expect(imagesIn(light)).toEqual([WIDE_LOGO.url]);
      expect(text(light)).toMatch(/512 ?× ?64 ?px/);
      expect(text(uploadLabelOf(light))).toBe('바꾸기');
      expect(focusIsOn(light, '바꾸기'), '초점은 "바꾸기"').toBe(true);
    });
    await eventually(() => {
      expect(imagesIn(headNow()), '머리 로고').toEqual([WIDE_LOGO.url]);
    });
    expect(text(await logoField('dark'))).toContain('없음. 밝은 테마 로고를 같이 씁니다');
    expect(callsTo('PUT', BRANDING), '저장 단추의 요청').toHaveLength(0);
  });

  test('S9-AC-30 C-14 올리는 중: "올리는 중…", 칸에 aria-busy, 파일 입력은 disabled가 아니라 aria-disabled', async () => {
    const release = gate();
    route('put', logoPath('light'), async ({ request }) => {
      await release.opened;
      return saveLogoNow(request);
    });
    const user = await openBranding(defaultBranding());
    const light = await logoField('light');

    await user.upload(fileInputOf(light), image('logo.png'));

    await sent('PUT', logoPath('light'));
    await eventually(() => {
      expect(text(light)).toContain(UPLOADING);
      expect(light.closest('[aria-busy="true"]') ?? light.querySelector('[aria-busy="true"]'), '칸의 aria-busy').not.toBeNull();
      const input = fileInputOf(light);
      expect(input).toHaveAttribute('aria-disabled', 'true');
      expect(input.disabled, 'disabled가 아니다').toBe(false);
    });
    release.open();
    await eventually(() => {
      expect(imagesIn(light)).toEqual([WIDE_LOGO.url]);
    });
  });

  test('S9-AC-30 C-14 로고 지우기: 묻지 않고 바로 지우고 "로고 없음"과 "올리기", 초점은 "올리기", 머리 로고도 없어진다', async () => {
    const user = await openBranding(defaultBranding({ logos: { light: WIDE_LOGO, dark: null } }));
    const light = await logoField('light');
    await eventually(() => {
      expect(imagesIn(headNow())).toEqual([WIDE_LOGO.url]);
    });

    await user.click(await found(() => removeButtonOf(light), '"지우기"'));

    await sent('DELETE', logoPath('light'));
    expect(screen.queryByRole('dialog') ?? screen.queryByRole('alertdialog'), '확인 대화상자').toBeNull();
    await eventually(() => {
      expect(text(light)).toContain(NO_LOGO);
      expect(text(uploadLabelOf(light))).toBe('올리기');
      expect(focusIsOn(light, '올리기'), '초점은 "올리기"').toBe(true);
    });
    await eventually(() => {
      expect(imagesIn(headNow()), '머리 로고').toEqual([]);
    });
  });

  test.each([
    { what: '413', answer: () => errors.payloadTooLarge(), words: LOGO_TOO_BIG },
    { what: 'UNSUPPORTED_IMAGE', answer: () => rejected('UNSUPPORTED_IMAGE'), words: LOGO_UNSUPPORTED },
    { what: 'IMAGE_TOO_LARGE', answer: () => rejected('IMAGE_TOO_LARGE'), words: LOGO_TOO_MANY_PIXELS },
    { what: '429', answer: () => errors.rateLimited(30), words: '요청이 많습니다. 30초 뒤 다시 하세요.' },
    { what: '503', answer: () => HttpResponse.json({ code: 'SERVER_BUSY', message: 'Busy.' }, { status: 503, headers: { 'Retry-After': '3' } }), words: '서버에 연결하지 못했습니다. 잠시 뒤 다시 하세요.' },
  ])('S9-AC-30 C-14 로고 실패($what): 그 칸 아래 "$words", 있던 로고는 그대로', async ({ answer, words }) => {
    route('put', logoPath('light'), answer);
    const user = await openBranding(defaultBranding({ logos: { light: WIDE_LOGO, dark: null } }));
    const light = await logoField('light');

    await user.upload(fileInputOf(light), image('logo.png'));

    await sent('PUT', logoPath('light'));
    await slowly(() => {
      expect(text(light)).toContain(words);
    });
    expect(text(light), '서버 문구').not.toContain('(server text)');
    expect(imagesIn(light), '있던 로고').toEqual([WIDE_LOGO.url]);
    expect(text(await logoField('dark')), '다른 칸의 오류').not.toContain(words);
  });

  test('S9-AC-30 C-14 두 테마를 한꺼번에 올려도 칸마다 따로 돈다: 밝은 칸이 올리는 중이어도 어두운 칸은 끝난다', async () => {
    const release = gate();
    route('put', logoPath('light'), async ({ request }) => {
      await release.opened;
      return saveLogoNow(request);
    });
    const user = await openBranding(defaultBranding());
    const light = await logoField('light');
    const dark = await logoField('dark');

    await user.upload(fileInputOf(light), image('light.png'));
    await user.upload(fileInputOf(dark), image('dark.png'));

    await eventually(() => {
      expect(imagesIn(dark)).toEqual([SQUARE_LOGO.url]);
      expect(text(light), '밝은 칸은 아직').toContain(UPLOADING);
    });
    release.open();
    await eventually(() => {
      expect(imagesIn(light)).toEqual([WIDE_LOGO.url]);
    });
    await eventually(() => {
      expect(text(light)).not.toContain(UPLOADING);
    });
    await expectNoAxeViolations();
  });
});

/** The default logo handler of console9.ts, for a test that holds the answer first. */
function saveLogoNow(request: Request): Promise<Response> {
  return acceptLogo(request, new URL(request.url).pathname.endsWith('/dark') ? 'dark' : 'light');
}
