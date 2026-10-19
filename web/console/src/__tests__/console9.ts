// More test support for slice 9 (design/archive/9-branding/screens.md): the branding of the whole
// app (the public branding, the head, the screens before log in, the tab title), the branding
// screen C-14 and the audit words of C-12. The earlier support files stay as they are; this file
// adds the branding endpoints to the fake server, in the shape of docs/integrate/openapi-console.json,
// and the helpers the slice 9 console tests share.
//
// Every answer made here goes through `contract` of console6a.ts: a fixture that does not fit the
// contract file fails the test that used it.
import type { ConsoleComponents } from '@popdesk/api/console';
import { waitFor } from '@testing-library/react';
import { HttpResponse, http } from 'msw/http';
import { beforeEach, expect } from 'vitest';
import { errors, found, readParts, screen, server, state, text, within } from './console';
import { contract, rejectedWith } from './console6a';

type Schemas = ConsoleComponents['schemas'];
export type PublicBranding = Schemas['PublicBranding'];
export type Branding = Schemas['Branding'];
export type AccentColors = Schemas['AccentColors'];
export type AccentPreview = Schemas['AccentPreview'];
export type BrandingLogo = Schemas['BrandingLogo'];
export type UpdateBrandingRequest = Schemas['UpdateBrandingRequest'];
export type Theme = 'light' | 'dark';

export const URL_BRANDING = '/branding';
export const PUBLIC_BRANDING = '/api/public/branding';
export const BRANDING = '/api/console/branding';
export const ACCENT = '/api/console/branding/accent';
export function logoPath(theme: Theme): string {
  return `/api/console/branding/logos/${theme}`;
}

/** The eight variables `tokens.css` reads (design.md 4절), in the order of `brandingStyle`. */
export const BRAND_VARIABLES = [
  '--brand-accent-light',
  '--brand-accent-dark',
  '--brand-accent-hover-light',
  '--brand-accent-hover-dark',
  '--brand-on-accent-light',
  '--brand-on-accent-dark',
  '--brand-accent-subtle-light',
  '--brand-accent-subtle-dark',
] as const;

// screens.md 2절의 문구
export const TITLE = '브랜딩';
export const NAME_HELP = '로그인 화면, 화면 머리, 탭 제목, 메일의 보내는 이름에 쓰입니다. 1~50자.';
export const LOGO_HELP = 'PNG, JPEG, WebP, 512KB까지. 가로 512px, 세로 128px 안으로 줄여 저장합니다. 하나만 올리면 두 테마에 같이 씁니다.';
export const USE_DEFAULT = '기본 색 쓰기';
export const PICK_COLOR = '강조색 고르기';
export const LOW_CONTRAST = '대비가 모자랍니다(4.5:1 이상이어야 합니다)';
export const BAD_FORMAT = '#과 16진수 여섯 자리로 쓰세요. 예: #047857';
export const PREVIEW_FAILED = '미리 보기를 읽지 못했습니다.';
export const DEFAULT_COLOR = '기본 색';
export const NOTHING_CHANGED = '바뀐 것이 없습니다.';
export const SAVED = '브랜딩을 저장했습니다.';
export const READ_FAILED = '브랜딩을 읽지 못했습니다. 다시 해 보세요.';
export const CONFLICT = '다른 관리자가 먼저 바꿨습니다. 다시 읽은 뒤 고쳐 주세요.';
export const RELOAD = '다시 불러오기';
export const NAME_REQUIRED = '표시 이름을 쓰세요.';
export const NAME_TOO_LONG = '50자까지 쓸 수 있습니다.';
export const NAME_BAD = '표시 이름에 쓸 수 없는 글자가 있습니다.';
export const LOGO_TOO_BIG = '로고가 512KB를 넘습니다. 더 작은 파일을 올리세요.';
export const LOGO_UNSUPPORTED = 'PNG, JPEG, WebP 이미지만 올릴 수 있습니다. 파일이 깨지지 않았는지도 확인하세요.';
export const LOGO_TOO_MANY_PIXELS = '이미지의 가로×세로가 너무 큽니다. 더 작게 줄인 이미지를 올리세요.';
export const UPLOADING = '올리는 중…';
export const NO_LOGO = '로고 없음';
export const LOGO_LABEL: Readonly<Record<Theme, string>> = { light: '밝은 테마 로고', dark: '어두운 테마 로고' };
export const PANE_LABEL: Readonly<Record<Theme, string>> = { light: '밝은 테마', dark: '어두운 테마' };

/** "강조색 #6ECBA5 · 가장 낮은 대비 7.27:1" (screens.md 2.1). The middle dot may sit between any spaces. */
export function contrastLine(accent: string, contrast: string): RegExp {
  return new RegExp(`강조색 ${accent} ?· ?가장 낮은 대비 ${contrast.replace('.', '\\.')}:1`);
}

// ---------------------------------------------------------------------------
// Fixtures

/**
 * `#047857` put in by hand: the values main measured (design.md 3절의 검산 값). Light 4.81, dark 7.27
 * (cut, not rounded: design.md 9절), so it is taken.
 */
export const EMERALD: AccentColors = {
  light: { accent: '#047857', accentHover: '#005E43', onAccent: '#FFFFFF', accentSubtle: '#DBF8EB' },
  dark: { accent: '#6ECBA5', accentHover: '#88E5BF', onAccent: '#191817', accentSubtle: '#0A3123' },
};
export const EMERALD_PREVIEW: AccentPreview = { colors: EMERALD, contrast: { light: 4.81, dark: 7.27 }, acceptable: true };

/**
 * `#6D28D9`: the accents, the light subtle `#F0EEFF` and both contrasts are what the server gives
 * (the server C-7 check measured light 6.2181 and dark 6.8982, cut to 6.21 and 6.89). The hover
 * values and the dark subtle are made up for the screen: it only shows what it gets.
 */
export const VIOLET: AccentColors = {
  light: { accent: '#6D28D9', accentHover: '#5512BF', onAccent: '#FFFFFF', accentSubtle: '#F0EEFF' },
  dark: { accent: '#B8A5FF', accentHover: '#D0C0FF', onAccent: '#191817', accentSubtle: '#2B2140' },
};
export const VIOLET_PREVIEW: AccentPreview = { colors: VIOLET, contrast: { light: 6.21, dark: 6.89 }, acceptable: true };

/**
 * `#E11D48`: not taken, light 4.10 (design.md 3절). The other values are made up for the screen.
 */
export const ROSE: AccentColors = {
  light: { accent: '#E11D48', accentHover: '#B8002C', onAccent: '#FFFFFF', accentSubtle: '#FFEDEE' },
  dark: { accent: '#FF8A97', accentHover: '#FFA5AF', onAccent: '#191817', accentSubtle: '#3A1A1E' },
};
export const ROSE_PREVIEW: AccentPreview = { colors: ROSE, contrast: { light: 4.1, dark: 7.02 }, acceptable: false };

/** The fake server's preview of each color it knows; another color in the right form answers 400. */
export const PREVIEWS: Readonly<Record<string, AccentPreview>> = {
  '#047857': EMERALD_PREVIEW,
  '#6D28D9': VIOLET_PREVIEW,
  '#E11D48': ROSE_PREVIEW,
};

export const LOGO_LIGHT_URL = '/api/public/branding/logos/light?v=0123456789abcdef';
export const LOGO_DARK_URL = '/api/public/branding/logos/dark?v=fedcba9876543210';
/** A wide logo (512×64) of the light theme. */
export const WIDE_LOGO: BrandingLogo = { url: LOGO_LIGHT_URL, width: 512, height: 64 };
/** A square logo (128×128) of the dark theme. */
export const SQUARE_LOGO: BrandingLogo = { url: LOGO_DARK_URL, width: 128, height: 128 };

/** 50 Korean characters with spaces, the longest display name. */
export const LONG_NAME = '에이크미 고객 지원 센터 서울 본사 상담 콘솔 운영팀 공식 브랜드 표시 이름 오십 자 끝냄';
/** 50 Latin letters without a space. */
export const LONG_LATIN = 'A'.repeat(50);

export function publicBranding(over: Partial<PublicBranding> = {}): PublicBranding {
  return { displayName: 'popdesk', colors: null, logos: { light: null, dark: null }, ...over };
}

/** The branding of an installation that never saved one (S9-AC-05): version 1, the defaults. */
export function defaultBranding(over: Partial<Branding> = {}): Branding {
  return { version: 1, displayName: 'popdesk', accent: null, colors: null, contrast: null, logos: { light: null, dark: null }, ...over };
}

/** "Acme 지원", `#6D28D9`, no logo, version 4. */
export function violetBranding(over: Partial<Branding> = {}): Branding {
  return { version: 4, displayName: 'Acme 지원', accent: '#6D28D9', colors: VIOLET, contrast: VIOLET_PREVIEW.contrast, logos: { light: null, dark: null }, ...over };
}

/** What the public answer says for `b`: one logo in both places when only one theme has it (S9-AC-03). */
export function publicOf(b: Branding): PublicBranding {
  const light = b.logos.light?.url ?? b.logos.dark?.url ?? null;
  const dark = b.logos.dark?.url ?? b.logos.light?.url ?? null;
  return { displayName: b.displayName, colors: b.colors, logos: { light, dark } };
}

/** A picked file whose `size` says what the test needs. Its bytes are its own name. */
export function image(name: string, size = 20 * 1024, type = 'image/png'): File {
  const f = new File([name], name, { type });
  Object.defineProperty(f, 'size', { value: size });
  return f;
}

// ---------------------------------------------------------------------------
// Fake server

/** State the slice 9 handlers read. Tests change it before rendering. */
export const s9: {
  /** The public branding answer. */
  public: PublicBranding;
  /** The branding as the admin API holds it. */
  branding: Branding;
  /** The logo the next upload of each theme stores. */
  nextLogo: Record<Theme, BrandingLogo>;
} = { public: publicBranding(), branding: defaultBranding(), nextLogo: { light: WIDE_LOGO, dark: SQUARE_LOGO } };

function requireAdmin(): Response | undefined {
  if (!state.signedIn) return errors.unauthenticated();
  return state.me.role === 'ADMIN' ? undefined : errors.forbidden();
}

const HEX = /^#[0-9A-Fa-f]{6}$/;
/** Line breaks, control characters and direction marks (S9-AC-09), as code point ranges. */
const BAD_NAME_RANGES: readonly (readonly [number, number])[] = [
  [0x00, 0x1f],
  [0x7f, 0x9f],
  [0x2028, 0x2029],
  [0x202a, 0x202e],
  [0x2066, 0x2069],
];

function hasBadCharacter(name: string): boolean {
  return Array.from(name).some((c) => {
    const point = c.codePointAt(0) ?? 0;
    return BAD_NAME_RANGES.some(([from, to]) => point >= from && point <= to);
  });
}

/** The field errors the server finds in `body` (design.md 2절). */
export function fieldErrorsOf(body: UpdateBrandingRequest): Schemas['FieldError'][] {
  const fields: Schemas['FieldError'][] = [];
  const name = body.displayName.trim().normalize('NFC');
  if (body.displayName === '') fields.push({ field: 'displayName', code: 'REQUIRED' });
  else if (name === '') fields.push({ field: 'displayName', code: 'BLANK' });
  else if (Array.from(name).length > 50) fields.push({ field: 'displayName', code: 'TOO_LONG' });
  else if (hasBadCharacter(name)) fields.push({ field: 'displayName', code: 'INVALID_FORMAT' });
  if (body.accent !== null) {
    if (!HEX.test(body.accent)) fields.push({ field: 'accent', code: 'INVALID_FORMAT' });
    else if (PREVIEWS[body.accent.toUpperCase()]?.acceptable === false) fields.push({ field: 'accent', code: 'LOW_CONTRAST' });
  }
  return fields;
}

/** The 200 of `getBranding` for `b`, through the contract check. */
export function brandingResponse(b: Branding = s9.branding): Response {
  return HttpResponse.json(contract('getBranding', 200, b));
}

/** The 200 of `getPublicBranding` for `b`, through the contract check. */
export function publicResponse(b: PublicBranding = s9.public): Response {
  return HttpResponse.json(contract('getPublicBranding', 200, b));
}

/** The 200 of `previewAccent` for `color`, through the contract check; 400 for a color it does not know. */
export function previewResponse(color: string | null): Response {
  if (color === null) return rejectedWith('previewAccent', 'INVALID_INPUT', 400, [{ field: 'color', code: 'REQUIRED' }]);
  const known = HEX.test(color) ? PREVIEWS[color.toUpperCase()] : undefined;
  if (!known) return rejectedWith('previewAccent', 'INVALID_INPUT', 400, [{ field: 'color', code: 'INVALID_FORMAT' }]);
  return HttpResponse.json(contract('previewAccent', 200, known));
}

/** What the server does with a save: 400, 409, the same branding, or the next version (S9-AC-07~11). */
export async function saveBranding(request: Request): Promise<Response> {
  const denied = requireAdmin();
  if (denied) return denied;
  const body = (await request.json()) as UpdateBrandingRequest;
  const fields = fieldErrorsOf(body);
  if (fields.length > 0) return rejectedWith('updateBranding', 'INVALID_INPUT', 400, fields);
  if (body.version !== s9.branding.version) return rejectedWith('updateBranding', 'VERSION_CONFLICT', 409);
  const accent = body.accent === null ? null : body.accent.toUpperCase();
  const displayName = body.displayName.trim().normalize('NFC');
  if (displayName !== s9.branding.displayName || accent !== s9.branding.accent) {
    const preview = accent === null ? null : (PREVIEWS[accent] ?? null);
    s9.branding = {
      ...s9.branding,
      version: s9.branding.version + 1,
      displayName,
      accent,
      colors: preview?.colors ?? null,
      contrast: preview?.contrast ?? null,
    };
    s9.public = publicOf(s9.branding);
  }
  return HttpResponse.json(contract('updateBranding', 200, s9.branding));
}

function themeOf(value: string | readonly string[] | undefined): Theme | null {
  return value === 'light' || value === 'dark' ? value : null;
}

/** What the server does with a good upload: stores `s9.nextLogo` for the theme (S9-AC-15). */
export async function acceptLogo(request: Request, theme: Theme): Promise<Response> {
  const parts = await readParts(request);
  if (!parts.some((p) => p.name === 'file')) return rejectedWith('uploadBrandingLogo', 'INVALID_INPUT', 400, [{ field: 'file', code: 'REQUIRED' }]);
  const logo = s9.nextLogo[theme];
  s9.branding = { ...s9.branding, logos: { ...s9.branding.logos, [theme]: logo } };
  s9.public = publicOf(s9.branding);
  return HttpResponse.json(contract('uploadBrandingLogo', 200, logo));
}

const handlers9 = [
  http.get(`*${PUBLIC_BRANDING}`, () => publicResponse()),
  http.get(`*${BRANDING}`, () => requireAdmin() ?? brandingResponse()),
  http.put(`*${BRANDING}`, ({ request }) => saveBranding(request)),
  http.get(`*${ACCENT}`, ({ request }) => requireAdmin() ?? previewResponse(new URL(request.url).searchParams.get('color'))),
  http.put('*/api/console/branding/logos/:theme', async ({ request, params }) => {
    const denied = requireAdmin();
    if (denied) return denied;
    const theme = themeOf(params.theme);
    return theme ? acceptLogo(request, theme) : errors.notFound();
  }),
  http.delete('*/api/console/branding/logos/:theme', ({ params }) => {
    const denied = requireAdmin();
    if (denied) return denied;
    const theme = themeOf(params.theme);
    if (!theme) return errors.notFound();
    s9.branding = { ...s9.branding, logos: { ...s9.branding.logos, [theme]: null } };
    s9.public = publicOf(s9.branding);
    return new HttpResponse(null, { status: 204 });
  }),
];

beforeEach(() => {
  s9.public = publicBranding();
  s9.branding = defaultBranding();
  s9.nextLogo = { light: WIDE_LOGO, dark: SQUARE_LOGO };
  server.use(...handlers9);
});

// ---------------------------------------------------------------------------
// The branding as drawn

/** The value of one of the eight variables on `<html>` now; '' when it is not set. */
export function brandVariable(name: (typeof BRAND_VARIABLES)[number]): string {
  return document.documentElement.style.getPropertyValue(name).trim().toUpperCase();
}

/** The eight variables on `<html>` now, by name; '' for one not set. */
export function brandVariables(): Record<string, string> {
  return Object.fromEntries(BRAND_VARIABLES.map((n) => [n, brandVariable(n)]));
}

/** The eight variables `colors` gives (design.md 4절), in capitals. */
export function variablesOf(colors: AccentColors): Record<string, string> {
  return {
    '--brand-accent-light': colors.light.accent,
    '--brand-accent-dark': colors.dark.accent,
    '--brand-accent-hover-light': colors.light.accentHover,
    '--brand-accent-hover-dark': colors.dark.accentHover,
    '--brand-on-accent-light': colors.light.onAccent,
    '--brand-on-accent-dark': colors.dark.onAccent,
    '--brand-accent-subtle-light': colors.light.accentSubtle,
    '--brand-accent-subtle-dark': colors.dark.accentSubtle,
  };
}

/** No variable set: the defaults of `tokens.css`. */
export const NO_VARIABLES: Readonly<Record<string, string>> = Object.fromEntries(BRAND_VARIABLES.map((n) => [n, '']));

/** The `src` of every `<img>` inside `root`, as the path and query. */
export function imagesIn(root: ParentNode): string[] {
  return Array.from(root.querySelectorAll('img')).map((img) => {
    const src = img.getAttribute('src') ?? '';
    const url = new URL(src, window.location.href);
    return `${url.pathname}${url.search}`;
  });
}

/** The head of the app shell (`header`). */
export function head(): Promise<HTMLElement> {
  return found(() => document.querySelector<HTMLElement>('[data-pd="AppShell"] header'), '화면 머리(header)');
}

/** The h1 `name`, waited for generously: the first wait of a test may meet a busy machine. */
export async function title1(name: string): Promise<HTMLElement> {
  let el: HTMLElement | null = null;
  await waitFor(
    () => {
      el = screen.queryByRole('heading', { level: 1, name });
      expect(el, `h1 "${name}"`).not.toBeNull();
    },
    { timeout: 5000 },
  );
  return el as unknown as HTMLElement;
}

/** Waits until `check` stops throwing, with a long timeout. */
export async function slowly(check: () => void, timeout = 5000): Promise<void> {
  await waitFor(check, { timeout });
}

// ---------------------------------------------------------------------------
// Finding things on C-14 브랜딩 (screens.md 2절)

/** The `section` whose `h2` is `name`. */
export function sectionNow(name: string): HTMLElement | null {
  const h2 = screen.queryAllByRole('heading', { level: 2 }).find((h) => text(h) === name);
  return h2?.closest('section') ?? null;
}

export function section(name: string): Promise<HTMLElement> {
  return found(() => sectionNow(name), `구역 "${name}"(section, h2)`);
}

/** The text field "표시 이름". */
export function nameField(): Promise<HTMLInputElement> {
  return found(() => screen.queryAllByRole<HTMLInputElement>('textbox', { name: /^표시 이름/ })[0], '칸 "표시 이름"');
}

/** The text field "강조색". */
export function accentField(): Promise<HTMLInputElement> {
  return found(() => screen.queryAllByRole<HTMLInputElement>('textbox', { name: /^강조색/ })[0], '칸 "강조색"');
}

/** The color input "강조색 고르기". */
export function colorInput(): Promise<HTMLInputElement> {
  return found(
    () => Array.from(document.querySelectorAll<HTMLInputElement>('input[type="color"]')).find((i) => i.getAttribute('aria-label') === PICK_COLOR),
    `색 입력 "${PICK_COLOR}"`,
  );
}

/** The checkbox "기본 색 쓰기". */
export function defaultBox(): Promise<HTMLInputElement> {
  return found(() => screen.queryByRole<HTMLInputElement>('checkbox', { name: USE_DEFAULT }), `"${USE_DEFAULT}"`);
}

/**
 * The preview pane of `theme` now: inside the section 강조색, the widest element that names the
 * theme ("밝은 테마") and not the other.
 */
export function paneNow(theme: Theme): HTMLElement | null {
  const part = sectionNow('강조색');
  if (!part) return null;
  const mine = PANE_LABEL[theme];
  const other = PANE_LABEL[theme === 'light' ? 'dark' : 'light'];
  const panes = Array.from(part.querySelectorAll<HTMLElement>('*')).filter((el) => text(el).includes(mine) && !text(el).includes(other));
  return panes.find((el) => !panes.some((o) => o !== el && o.contains(el))) ?? null;
}

export function pane(theme: Theme): Promise<HTMLElement> {
  return found(() => paneNow(theme), `미리 보기 칸 "${PANE_LABEL[theme]}"`);
}

/** The logo field (`LogoField`, a group named "밝은 테마 로고") of `theme`. */
export function logoField(theme: Theme): Promise<HTMLElement> {
  return found(() => screen.queryByRole('group', { name: LOGO_LABEL[theme] }), `로고 칸 "${LOGO_LABEL[theme]}"`);
}

/** The file input of a logo field (its label is "올리기" or "바꾸기"). */
export function fileInputOf(field: HTMLElement): HTMLInputElement {
  const input = field.querySelector<HTMLInputElement>('input[type="file"]');
  expect(input, '로고 칸의 파일 입력').not.toBeNull();
  return input as HTMLInputElement;
}

/** The label of the file input of a logo field: "올리기" or "바꾸기". */
export function uploadLabelOf(field: HTMLElement): HTMLElement | null {
  const input = field.querySelector<HTMLInputElement>('input[type="file"]');
  return input ? (Array.from(field.querySelectorAll<HTMLLabelElement>('label')).find((l) => l.htmlFor === input.id) ?? null) : null;
}

/** The "지우기" button of a logo field, or null. */
export function removeButtonOf(field: HTMLElement): HTMLElement | null {
  return within(field).queryByRole('button', { name: '지우기' });
}

/** The save button "저장". */
export function saveButton(): Promise<HTMLElement> {
  return found(() => screen.queryByRole('button', { name: '저장' }), '"저장"');
}
