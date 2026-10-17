// The branding answers for the slice 9 screen stories (9번 묶음 screens.md 5절). storyFetch.ts answers the
// rest of the console API (and the default public branding); this file wraps the `fetch` in place and
// answers the public branding, reading and saving the branding (`getBranding`, `updateBranding`) and
// the accent preview (`previewAccent`) from a scenario the story sets. With `signedOut` the session
// read answers 401, so the screens before log in stay.
//
// The logo files (`/api/public/branding/logos/…`) are images, not `fetch`: a story's `play` draws
// them with `drawLogos`.
//
// Import this file before any module that reaches `src/api.ts`: openapi-fetch keeps the `fetch`
// it finds when the client is made.
import type { Schemas } from '../api';

type PublicBranding = Schemas['PublicBranding'];
type Branding = Schemas['Branding'];
type AccentColors = Schemas['AccentColors'];
type AccentPreview = Schemas['AccentPreview'];

export const LOGO_LIGHT_URL = '/api/public/branding/logos/light?v=0123456789abcdef';
export const LOGO_DARK_URL = '/api/public/branding/logos/dark?v=fedcba9876543210';

/** 50 Korean characters with spaces, the longest display name. */
export const LONG_NAME = '에이크미 고객 지원 센터 서울 본사 상담 콘솔 운영팀 공식 브랜드 표시 이름 오십 자 끝냄';
/** 50 Latin letters without a space. */
export const LONG_LATIN = 'W'.repeat(50);

/** `#047857` put in by hand (design.md 3절의 검산 값): taken. */
export const EMERALD: AccentColors = {
  light: { accent: '#047857', accentHover: '#005E43', onAccent: '#FFFFFF', accentSubtle: '#DBF8EB' },
  dark: { accent: '#6ECBA5', accentHover: '#88E5BF', onAccent: '#191817', accentSubtle: '#0A3123' },
};

/** `#6D28D9` (the accents are main's, design.md 3절; the rest is made up for the screen): taken. */
export const VIOLET: AccentColors = {
  light: { accent: '#6D28D9', accentHover: '#5512BF', onAccent: '#FFFFFF', accentSubtle: '#F1EBFF' },
  dark: { accent: '#B8A5FF', accentHover: '#D0C0FF', onAccent: '#191817', accentSubtle: '#2B2140' },
};

/** `#E11D48`: not taken, light 4.10 (design.md 3절); the rest is made up for the screen. */
export const ROSE: AccentColors = {
  light: { accent: '#E11D48', accentHover: '#B8002C', onAccent: '#FFFFFF', accentSubtle: '#FFEDEE' },
  dark: { accent: '#FF8A97', accentHover: '#FFA5AF', onAccent: '#191817', accentSubtle: '#3A1A1E' },
};

const PREVIEWS: Readonly<Record<string, AccentPreview>> = {
  '#047857': { colors: EMERALD, contrast: { light: 4.81, dark: 7.28 }, acceptable: true },
  '#6D28D9': { colors: VIOLET, contrast: { light: 6.22, dark: 8.01 }, acceptable: true },
  '#E11D48': { colors: ROSE, contrast: { light: 4.1, dark: 7.02 }, acceptable: false },
};

export function preview(color: string): AccentPreview | null {
  return PREVIEWS[color.toUpperCase()] ?? null;
}

export function defaultBranding(over: Partial<Branding> = {}): Branding {
  return { version: 1, displayName: 'popdesk', accent: null, colors: null, contrast: null, logos: { light: null, dark: null }, ...over };
}

/** The public answer for `b`: one logo in both places when only one theme has it (S9-AC-03). */
export function publicOf(b: Branding): PublicBranding {
  const light = b.logos.light?.url ?? b.logos.dark?.url ?? null;
  const dark = b.logos.dark?.url ?? b.logos.light?.url ?? null;
  return { displayName: b.displayName, colors: b.colors, logos: { light, dark } };
}

export interface BrandingScenario {
  /** The branding of the admin API; the public answer is made from it. */
  branding: Branding;
  /** The session read answers 401. */
  signedOut: boolean;
  /** The preview answers 500. */
  previewFails: boolean;
}

let current: BrandingScenario = { branding: defaultBranding(), signedOut: false, previewFails: false };

/** The branding the next requests are answered from. */
export function setBrandingScenario(next: BrandingScenario): void {
  current = next;
}

function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } });
}

const before = globalThis.fetch;

globalThis.fetch = (input: RequestInfo | URL, init?: RequestInit): Promise<Response> => {
  const request = input instanceof Request ? input : new Request(input, init);
  const url = new URL(request.url, window.location.href);
  const get = request.method === 'GET';
  if (get && url.pathname === '/api/console/me' && current.signedOut) return Promise.resolve(json({ code: 'UNAUTHENTICATED', message: 'Sign in first.' }, 401));
  if (get && url.pathname === '/api/public/branding') return Promise.resolve(json(publicOf(current.branding)));
  if (get && url.pathname === '/api/console/branding') return Promise.resolve(json(current.branding));
  if (get && url.pathname === '/api/console/branding/accent') {
    if (current.previewFails) return Promise.resolve(json({ code: 'INTERNAL_ERROR', message: 'Unexpected error.' }, 500));
    const found = preview(url.searchParams.get('color') ?? '');
    return Promise.resolve(found ? json(found) : json({ code: 'INVALID_INPUT', message: 'Bad color.', fields: [{ field: 'color', code: 'INVALID_FORMAT' }] }, 400));
  }
  if (request.method === 'PUT' && url.pathname === '/api/console/branding') {
    current = { ...current, branding: { ...current.branding, version: current.branding.version + 1 } };
    return Promise.resolve(json(current.branding));
  }
  return before(input, init);
};

/** A plain logo `width`×`height` as an SVG `data:` URL: a band of `color` with a darker mark. */
function logoImage(width: number, height: number, color: string): string {
  const svg = `<svg xmlns="http://www.w3.org/2000/svg" width="${String(width)}" height="${String(height)}"><rect width="100%" height="100%" fill="${color}"/><rect x="8" y="8" width="${String(Math.max(width / 3, 8))}" height="${String(height - 16)}" fill="#1F1F1F"/></svg>`;
  return `data:image/svg+xml,${encodeURIComponent(svg)}`;
}

/**
 * Draws the logo images the story's fake server named: the light logo 512×64, the dark one
 * 128×128 (screens.md 2절 폭: "가로로 긴 로고(512×64), 정사각 로고(128×128)"). The browser cannot
 * fetch them from the Storybook server, so `play` swaps their `src` and waits until they load.
 */
export async function drawLogos(root: ParentNode = document): Promise<void> {
  const images = Array.from(root.querySelectorAll<HTMLImageElement>('img')).filter((img) => (img.getAttribute('src') ?? '').includes('/api/public/branding/logos/'));
  await Promise.all(
    images.map(
      (img) =>
        new Promise<void>((resolve) => {
          const dark = (img.getAttribute('src') ?? '').includes('/logos/dark');
          // Which logo this was, for a `play` that checks the one its theme shows.
          img.dataset['logo'] = dark ? 'dark' : 'light';
          img.addEventListener('load', () => {
            resolve();
          }, { once: true });
          img.addEventListener('error', () => {
            resolve();
          }, { once: true });
          img.src = dark ? logoImage(128, 128, '#D9D4FF') : logoImage(512, 64, '#6D28D9');
        }),
    ),
  );
}
