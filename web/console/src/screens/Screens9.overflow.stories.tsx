// 9번 묶음이 더하거나 바꾼 콘솔 화면을 상태마다 360·820·1280 폭, 밝게·어둡게에서 그리고, play에서 테마가 그것인지, 페이지와
// 화면의 부분이 가로로 넘치지 않는지, axe(대비 포함) 위반이 없는지 본다(screens.md 5절):
// C-14 브랜딩(긴 이름, 받는 색, 받을 수 없는 색, 로고 둘), 머리(로고 있음·긴 이름, 820과 360은 접힌 띠), 로그인 전 C-1(긴 이름·로고).
// 머리와 로그인 전 화면은 data-theme에 맞는 로고 하나만 보이는지도 본다(jsdom은 테마 조건을 계산하지 않는다). 접힌 띠에는 로고가
// 보이지 않는다. 화면은 가짜 fetch(__tests__/storyFetch.ts, storyFetch9.ts)의 답으로 그리고, 로고 파일은 play가 그린다(drawLogos).
//
// 받을 수 없는 색 줄은 play가 강조색 칸에 #E11D48을 쓰고 미리 보기를 기다려 만든다.
//
// The fake fetch comes first: src/api.ts keeps the fetch it finds when it is first imported.
import { ADMIN, company, issue, scenario, setScenario, summary } from '../__tests__/storyFetch';
import { setTaskScenario } from '../__tests__/storyFetch6b';
import { setLinkScenario } from '../__tests__/storyFetch6c';
import { setPolicyScenario, offPolicy } from '../__tests__/storyFetch8a';
import {
  LOGO_DARK_URL,
  LOGO_LIGHT_URL,
  LONG_LATIN,
  LONG_NAME,
  VIOLET,
  defaultBranding,
  drawLogos,
  setBrandingScenario,
  type BrandingScenario,
} from '../__tests__/storyFetch9';
import type { ReactElement } from 'react';
import '@popdesk/ui/tokens.css';
import '@popdesk/ui/styles.css';
import '../console.css';
import { at, expectDrawnRight, textOf, waitFor, type Theme, type Width } from '../../../packages/ui/src/__tests__/overflow';
import { App } from '../App';

interface PlayContext {
  canvasElement: HTMLElement;
}

interface Story {
  render: () => ReactElement;
  play: (context: PlayContext) => Promise<void>;
  parameters: ReturnType<typeof at>['parameters'] & { layout: 'fullscreen' };
  globals: ReturnType<typeof at>['globals'];
}

const meta = { title: 'Overflow/9 콘솔 화면' };
export default meta;

type Data = { branding: BrandingScenario; path: string };
type Found = { el: Element; what: string }[];

const WIDE = { url: LOGO_LIGHT_URL, width: 512, height: 64 };
const SQUARE = { url: LOGO_DARK_URL, width: 128, height: 128 };

function data(path: string, over: Partial<BrandingScenario>): Data {
  return { branding: { branding: defaultBranding(), signedOut: false, previewFails: false, ...over }, path };
}

// C-14
const C14_LONG = data('/branding', { branding: defaultBranding({ displayName: LONG_NAME }) });
const C14_TAKEN = data('/branding', { branding: defaultBranding({ version: 4, displayName: 'Acme 지원', accent: '#6D28D9', colors: VIOLET, contrast: { light: 6.22, dark: 8.01 } }) });
const C14_LOGOS = data('/branding', { branding: defaultBranding({ displayName: LONG_LATIN, logos: { light: WIDE, dark: SQUARE } }) });

// 머리: 로고 둘과 긴 이름, 밝은 로고 하나와 공백 없는 라틴 이름
const HEAD_LONG = data('/issues', { branding: defaultBranding({ displayName: LONG_NAME, accent: '#6D28D9', colors: VIOLET, logos: { light: WIDE, dark: SQUARE } }) });
const HEAD_LATIN = data('/issues', { branding: defaultBranding({ displayName: LONG_LATIN, logos: { light: WIDE, dark: null } }) });

// 로그인 전 C-1
const LOGIN_LONG = data('/login', { signedOut: true, branding: defaultBranding({ displayName: LONG_NAME, logos: { light: WIDE, dark: SQUARE } }) });
const LOGIN_LATIN = data('/login', { signedOut: true, branding: defaultBranding({ displayName: LONG_LATIN }) });

function screenStory(width: Width, theme: Theme, given: Data, parts: (canvas: HTMLElement, width: Width, theme: Theme) => Promise<Found>): Story {
  const { parameters, globals } = at(width, theme, 'console');
  return {
    parameters: { ...parameters, layout: 'fullscreen' },
    globals,
    render: () => {
      const one = issue();
      setScenario(scenario({ me: ADMIN, companies: [company()], issue: one, issues: [summary(one)] }));
      setTaskScenario({ tasks: [], mine: [] });
      setLinkScenario({ links: [], others: [], linkError: null });
      setPolicyScenario({ policy: offPolicy(), conflict: false });
      setBrandingScenario(given.branding);
      window.history.replaceState(null, '', given.path);
      return <App />;
    },
    play: async ({ canvasElement }) => {
      await expectDrawnRight(width, theme, await parts(canvasElement, width, theme));
    },
  };
}

function mainOf(canvas: HTMLElement): HTMLElement | null {
  return canvas.querySelector('main') ?? document.querySelector('main');
}

/** True when `el` takes room on the page now. */
function shown(el: Element): boolean {
  const box = el.getBoundingClientRect();
  return getComputedStyle(el).display !== 'none' && getComputedStyle(el).visibility !== 'hidden' && box.width > 0 && box.height > 0;
}

/** The logos (`data-logo` that `drawLogos` set: light or dark) shown inside `root`. */
function shownLogos(root: ParentNode): string[] {
  return Array.from(root.querySelectorAll<HTMLImageElement>('img[data-logo]')).filter(shown).map((img) => img.dataset['logo'] ?? '');
}

/** Throws unless the logos shown inside `root` are `wanted`. */
function expectLogos(root: ParentNode, wanted: string[], what: string): void {
  const now = shownLogos(root);
  if (now.join(',') !== wanted.join(',')) throw new Error(`${what}에 보이는 로고가 [${wanted.join(', ')}]가 아니다: [${now.join(', ')}]`);
}

/** Sets the value of a React-controlled input the way typing does. */
function typeInto(input: HTMLInputElement, value: string): void {
  Reflect.set(HTMLInputElement.prototype, 'value', value, input);
  input.dispatchEvent(new Event('input', { bubbles: true }));
}

// C-14 브랜딩

/** C-14 drawn: its h1 "브랜딩" and the 표시 이름 field holding `name`. */
async function brandingShows(canvas: HTMLElement, name: string): Promise<{ main: HTMLElement; found: Found }> {
  await waitFor(() => Array.from(document.querySelectorAll('h1')).find((h) => textOf(h) === '브랜딩'), 'C-14의 h1 "브랜딩"', 8000);
  const main = await waitFor(() => mainOf(canvas), 'C-14의 main');
  await waitFor(() => Array.from(main.querySelectorAll<HTMLInputElement>('input')).find((i) => i.value === name), `C-14의 표시 이름 "${name}"`);
  const sections = Array.from(main.querySelectorAll('section')).map((s, i) => ({ el: s, what: `C-14의 구역 ${String(i + 1)}` }));
  return { main, found: [{ el: main, what: 'C-14의 main' }, ...sections] };
}

const c14Long = async (canvas: HTMLElement) => (await brandingShows(canvas, LONG_NAME)).found;

async function c14Taken(canvas: HTMLElement): Promise<Found> {
  const { main, found } = await brandingShows(canvas, 'Acme 지원');
  await waitFor(() => (textOf(main).includes('#B8A5FF') ? true : null), 'C-14 어두운 칸의 "#B8A5FF"');
  return found;
}

async function c14Refused(canvas: HTMLElement): Promise<Found> {
  const { main, found } = await brandingShows(canvas, 'Acme 지원');
  const field = await waitFor(
    () => Array.from(main.querySelectorAll<HTMLInputElement>('input')).find((i) => Array.from(i.labels ?? []).some((l) => textOf(l).startsWith('강조색'))),
    'C-14의 칸 "강조색"',
  );
  typeInto(field, '#E11D48');
  await waitFor(() => (textOf(main).includes('대비가 모자랍니다(4.5:1 이상이어야 합니다)') ? true : null), 'C-14의 "대비가 모자랍니다"', 6000);
  return found;
}

async function c14Logos(canvas: HTMLElement): Promise<Found> {
  const { main, found } = await brandingShows(canvas, LONG_LATIN);
  await waitFor(() => (main.querySelectorAll('img').length >= 2 ? true : null), 'C-14 로고 칸의 로고 둘');
  await drawLogos(main);
  const fields = Array.from(main.querySelectorAll('[data-pd="LogoField"]')).map((f, i) => ({ el: f, what: `C-14의 로고 칸 ${String(i + 1)}` }));
  return [...found, ...fields];
}

// 머리

/** The head drawn with the logos; at 1280 the theme's logo shows, folded (820, 360) none. */
function headWith(name: string) {
  return async (canvas: HTMLElement, width: Width, theme: Theme): Promise<Found> => {
    const head = await waitFor(() => document.querySelector<HTMLElement>('[data-pd="AppShell"] header'), '머리', 8000);
    await waitFor(() => (textOf(head).includes(name) ? true : null), `머리의 "${name}"`);
    await waitFor(() => (head.querySelectorAll('img').length > 0 ? true : null), '머리의 로고');
    await drawLogos(head);
    const main = await waitFor(() => mainOf(canvas), '화면의 main');
    expectLogos(head, width === 1280 ? [theme] : [], `${String(width)} 폭 ${theme} 머리`);
    return [
      { el: head, what: '머리' },
      { el: main, what: '화면의 main' },
    ];
  };
}

/** HEAD_LATIN has only the light logo: both themes show that one. */
function headLatin() {
  return async (canvas: HTMLElement, width: Width): Promise<Found> => {
    const head = await waitFor(() => document.querySelector<HTMLElement>('[data-pd="AppShell"] header'), '머리', 8000);
    await waitFor(() => (textOf(head).includes(LONG_LATIN) ? true : null), '머리의 라틴 이름');
    await waitFor(() => (head.querySelectorAll('img').length > 0 ? true : null), '머리의 로고');
    await drawLogos(head);
    expectLogos(head, width === 1280 ? ['light'] : [], `${String(width)} 폭 머리`);
    const main = await waitFor(() => mainOf(canvas), '화면의 main');
    return [
      { el: head, what: '머리' },
      { el: main, what: '화면의 main' },
    ];
  };
}

// 로그인 전 C-1

function loginWith(name: string, logos: boolean) {
  return async (canvas: HTMLElement, _width: Width, theme: Theme): Promise<Found> => {
    await waitFor(() => Array.from(document.querySelectorAll('input[type="password"]'))[0], 'C-1의 비밀번호 칸', 8000);
    const root = canvas.querySelector('main') ?? canvas;
    await waitFor(() => (textOf(root).includes(name) ? true : null), `C-1의 "${name}"`);
    if (logos) {
      await waitFor(() => (root.querySelectorAll('img').length > 0 ? true : null), 'C-1의 로고');
      await drawLogos(root);
      expectLogos(root, [theme], `C-1 ${theme}`);
    }
    return [{ el: root, what: 'C-1' }];
  };
}

const headLong = headWith(LONG_NAME);
const headLatinParts = headLatin();
const loginLong = loginWith(LONG_NAME, true);
const loginLatin = loginWith(LONG_LATIN, false);

export const C14LongName360Light = screenStory(360, 'light', C14_LONG, c14Long);
export const C14LongName360Dark = screenStory(360, 'dark', C14_LONG, c14Long);
export const C14LongName820Light = screenStory(820, 'light', C14_LONG, c14Long);
export const C14LongName820Dark = screenStory(820, 'dark', C14_LONG, c14Long);
export const C14LongName1280Light = screenStory(1280, 'light', C14_LONG, c14Long);
export const C14LongName1280Dark = screenStory(1280, 'dark', C14_LONG, c14Long);

export const C14Taken360Light = screenStory(360, 'light', C14_TAKEN, c14Taken);
export const C14Taken360Dark = screenStory(360, 'dark', C14_TAKEN, c14Taken);
export const C14Taken820Light = screenStory(820, 'light', C14_TAKEN, c14Taken);
export const C14Taken820Dark = screenStory(820, 'dark', C14_TAKEN, c14Taken);
export const C14Taken1280Light = screenStory(1280, 'light', C14_TAKEN, c14Taken);
export const C14Taken1280Dark = screenStory(1280, 'dark', C14_TAKEN, c14Taken);

export const C14Refused360Light = screenStory(360, 'light', C14_TAKEN, c14Refused);
export const C14Refused360Dark = screenStory(360, 'dark', C14_TAKEN, c14Refused);
export const C14Refused820Light = screenStory(820, 'light', C14_TAKEN, c14Refused);
export const C14Refused820Dark = screenStory(820, 'dark', C14_TAKEN, c14Refused);
export const C14Refused1280Light = screenStory(1280, 'light', C14_TAKEN, c14Refused);
export const C14Refused1280Dark = screenStory(1280, 'dark', C14_TAKEN, c14Refused);

export const C14Logos360Light = screenStory(360, 'light', C14_LOGOS, c14Logos);
export const C14Logos360Dark = screenStory(360, 'dark', C14_LOGOS, c14Logos);
export const C14Logos820Light = screenStory(820, 'light', C14_LOGOS, c14Logos);
export const C14Logos820Dark = screenStory(820, 'dark', C14_LOGOS, c14Logos);
export const C14Logos1280Light = screenStory(1280, 'light', C14_LOGOS, c14Logos);
export const C14Logos1280Dark = screenStory(1280, 'dark', C14_LOGOS, c14Logos);

export const HeadLogoLong360Light = screenStory(360, 'light', HEAD_LONG, headLong);
export const HeadLogoLong360Dark = screenStory(360, 'dark', HEAD_LONG, headLong);
export const HeadLogoLong820Light = screenStory(820, 'light', HEAD_LONG, headLong);
export const HeadLogoLong820Dark = screenStory(820, 'dark', HEAD_LONG, headLong);
export const HeadLogoLong1280Light = screenStory(1280, 'light', HEAD_LONG, headLong);
export const HeadLogoLong1280Dark = screenStory(1280, 'dark', HEAD_LONG, headLong);

export const HeadLightLogoLatin360Light = screenStory(360, 'light', HEAD_LATIN, headLatinParts);
export const HeadLightLogoLatin360Dark = screenStory(360, 'dark', HEAD_LATIN, headLatinParts);
export const HeadLightLogoLatin820Light = screenStory(820, 'light', HEAD_LATIN, headLatinParts);
export const HeadLightLogoLatin820Dark = screenStory(820, 'dark', HEAD_LATIN, headLatinParts);
export const HeadLightLogoLatin1280Light = screenStory(1280, 'light', HEAD_LATIN, headLatinParts);
export const HeadLightLogoLatin1280Dark = screenStory(1280, 'dark', HEAD_LATIN, headLatinParts);

export const LoginLogoLong360Light = screenStory(360, 'light', LOGIN_LONG, loginLong);
export const LoginLogoLong360Dark = screenStory(360, 'dark', LOGIN_LONG, loginLong);
export const LoginLogoLong820Light = screenStory(820, 'light', LOGIN_LONG, loginLong);
export const LoginLogoLong820Dark = screenStory(820, 'dark', LOGIN_LONG, loginLong);
export const LoginLogoLong1280Light = screenStory(1280, 'light', LOGIN_LONG, loginLong);
export const LoginLogoLong1280Dark = screenStory(1280, 'dark', LOGIN_LONG, loginLong);

export const LoginLatin360Light = screenStory(360, 'light', LOGIN_LATIN, loginLatin);
export const LoginLatin360Dark = screenStory(360, 'dark', LOGIN_LATIN, loginLatin);
export const LoginLatin820Light = screenStory(820, 'light', LOGIN_LATIN, loginLatin);
export const LoginLatin820Dark = screenStory(820, 'dark', LOGIN_LATIN, loginLatin);
export const LoginLatin1280Light = screenStory(1280, 'light', LOGIN_LATIN, loginLatin);
export const LoginLatin1280Dark = screenStory(1280, 'dark', LOGIN_LATIN, loginLatin);
