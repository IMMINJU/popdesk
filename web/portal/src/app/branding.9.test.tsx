// 9번 묶음 screens.md 1절 포털의 뿌리: 화면이 열리면 공개 브랜딩을 한 번 읽어 <html>의 --brand-* 여덟 변수(기본이면 없음),
// 머리와 로그인 전 화면의 표시 이름, 로고(<img alt="">, 두 테마, 같으면 하나), 탭 제목 "제목 - 이름", 초대 수락 환영 문장을
// 바꾼다(S9-AC-22, 23, 25). 읽기 실패와 꼴이 틀린 답은 이 브라우저에 남긴 값, 없으면 기본으로 그리고 오류를 보이지 않는다(S9-AC-24).
//
// The answers go through the contract check of packages/api/src/testing, as in the console's console6a.ts.
import type { PortalComponents } from '@popdesk/api/portal';
import { waitFor } from '@testing-library/react';
import { HttpResponse } from 'msw/http';
import { describe, expect, test } from 'vitest';
import { checkResponse } from '../../../packages/api/src/testing/contract';
import { callsTo, errors, expectNoAxeViolations, link, liveRegions, on, pageText, renderAt, sent, state, text } from '../__tests__/portal';
import { settle } from '../__tests__/portal1a';
// The head asks for the unread count (slice 4): portal4.ts answers it.
import '../__tests__/portal4';

type PublicBranding = PortalComponents['schemas']['PublicBranding'];
type AccentColors = PortalComponents['schemas']['AccentColors'];

const PUBLIC_BRANDING = '/api/public/branding';
const LOGO_LIGHT_URL = '/api/public/branding/logos/light?v=0123456789abcdef';
const LOGO_DARK_URL = '/api/public/branding/logos/dark?v=fedcba9876543210';

/** `#6D28D9` (the accents are main's, design.md 3절; the rest is made up for the screen). */
const VIOLET: AccentColors = {
  light: { accent: '#6D28D9', accentHover: '#5512BF', onAccent: '#FFFFFF', accentSubtle: '#F1EBFF' },
  dark: { accent: '#B8A5FF', accentHover: '#D0C0FF', onAccent: '#191817', accentSubtle: '#2B2140' },
};

const ACME: PublicBranding = { displayName: 'Acme 지원', colors: VIOLET, logos: { light: LOGO_LIGHT_URL, dark: LOGO_DARK_URL } };

const VARIABLES = [
  '--brand-accent-light',
  '--brand-accent-dark',
  '--brand-accent-hover-light',
  '--brand-accent-hover-dark',
  '--brand-on-accent-light',
  '--brand-on-accent-dark',
  '--brand-accent-subtle-light',
  '--brand-accent-subtle-dark',
] as const;

const NO_VARIABLES = Object.fromEntries(VARIABLES.map((n) => [n, '']));

const VIOLET_VARIABLES = {
  '--brand-accent-light': VIOLET.light.accent,
  '--brand-accent-dark': VIOLET.dark.accent,
  '--brand-accent-hover-light': VIOLET.light.accentHover,
  '--brand-accent-hover-dark': VIOLET.dark.accentHover,
  '--brand-on-accent-light': VIOLET.light.onAccent,
  '--brand-on-accent-dark': VIOLET.dark.onAccent,
  '--brand-accent-subtle-light': VIOLET.light.accentSubtle,
  '--brand-accent-subtle-dark': VIOLET.dark.accentSubtle,
};

/** The eight variables on `<html>` now, in capitals; '' for one not set. */
function brandVariables(): Record<string, string> {
  return Object.fromEntries(VARIABLES.map((n) => [n, document.documentElement.style.getPropertyValue(n).trim().toUpperCase()]));
}

/** Answers the public branding with `b`, through the contract check. */
function answerWith(b: PublicBranding) {
  on('get', PUBLIC_BRANDING, () => HttpResponse.json(checkResponse('portal', 'getPublicBranding', 200, b)));
}

/** The `src` of every `<img>` inside `root`, as the path and query. */
function imagesIn(root: ParentNode): string[] {
  return Array.from(root.querySelectorAll('img')).map((img) => {
    const url = new URL(img.getAttribute('src') ?? '', window.location.href);
    return `${url.pathname}${url.search}`;
  });
}

function altsIn(root: ParentNode): (string | null)[] {
  return Array.from(root.querySelectorAll('img')).map((img) => img.getAttribute('alt'));
}

async function slowly(check: () => void): Promise<void> {
  await waitFor(check, { timeout: 5000 });
}

/** Renders the portal at `/issues` and waits until the head is drawn and the branding asked for. */
async function openList(): Promise<HTMLElement> {
  renderAt('/issues');
  let top: HTMLElement | null = null;
  await slowly(() => {
    top = document.querySelector<HTMLElement>('[data-pd="AppShell"] header');
    expect(top, '화면 머리').not.toBeNull();
  });
  await sent('GET', PUBLIC_BRANDING);
  return top as unknown as HTMLElement;
}

function keptKeysWith(part: string): string[] {
  return Object.keys(localStorage).filter((k) => (localStorage.getItem(k) ?? '').includes(part));
}

describe('포털의 브랜딩 (9번 묶음)', () => {
  test('S9-AC-22 S9-AC-23 포털 머리: 공개 브랜딩을 한 번 읽어 <html>에 --brand-* 여덟 변수, 머리에 표시 이름과 두 테마 로고, 탭 제목 "{화면 제목} - {표시 이름}"', async () => {
    answerWith(ACME);
    const top = await openList();

    await slowly(() => {
      expect(text(top)).toContain('Acme 지원');
      expect(brandVariables()).toEqual(VIOLET_VARIABLES);
      expect(imagesIn(top)).toEqual([LOGO_LIGHT_URL, LOGO_DARK_URL]);
      expect(document.title).toMatch(/^.+ - Acme 지원$/);
    });
    await settle();
    expect(callsTo('GET', PUBLIC_BRANDING), '공개 브랜딩 요청').toHaveLength(1);
    expect(pageText()).not.toContain('popdesk');
  });

  test('S9-AC-25 포털 머리: 로고는 alt=""이고 홈 링크의 이름은 표시 이름이다. 주소가 같으면 <img>는 하나다', async () => {
    answerWith({ displayName: 'Acme 지원', colors: null, logos: { light: LOGO_DARK_URL, dark: LOGO_DARK_URL } });
    const top = await openList();

    await slowly(() => {
      expect(imagesIn(top)).toEqual([LOGO_DARK_URL]);
    });
    expect(altsIn(top)).toEqual(['']);
    expect(top.contains(await link('Acme 지원')), '머리 안의 홈 링크').toBe(true);
    expect(brandVariables()).toEqual(NO_VARIABLES);
    await expectNoAxeViolations();
  });

  test('S9-AC-22 S9-AC-23 포털 로그인 화면: 표시 이름과 로고(alt=""), 탭 제목 "로그인 - {표시 이름}"', async () => {
    state.signedIn = false;
    answerWith(ACME);
    renderAt('/login');

    await slowly(() => {
      expect(pageText()).toContain('Acme 지원');
      expect(imagesIn(document.body)).toEqual([LOGO_LIGHT_URL, LOGO_DARK_URL]);
      expect(document.title).toBe('로그인 - Acme 지원');
    });
    expect(altsIn(document.body)).toEqual(['', '']);
    expect(brandVariables()).toEqual(VIOLET_VARIABLES);
    expect(pageText()).not.toContain('popdesk');
  });

  test('S9-AC-23 포털 초대 수락: 환영 문장 "{표시 이름} 고객 포털에 오신 것을 환영합니다"와 탭 제목 "초대 수락 - {표시 이름}"', async () => {
    state.signedIn = false;
    answerWith({ displayName: 'Acme 지원', colors: null, logos: { light: null, dark: null } });
    renderAt('/invite#token=inv-Hk3sV8pQ2mTx');

    await slowly(() => {
      expect(pageText()).toContain('Acme 지원 고객 포털에 오신 것을 환영합니다');
      expect(document.title).toBe('초대 수락 - Acme 지원');
    });
    expect(pageText()).not.toContain('popdesk');
  });

  test('S9-AC-23 포털 비밀번호 찾기 화면도 표시 이름을 보인다', async () => {
    state.signedIn = false;
    answerWith({ displayName: 'Acme 지원', colors: null, logos: { light: null, dark: null } });
    renderAt('/forgot-password');

    await slowly(() => {
      expect(pageText()).toContain('Acme 지원');
      expect(document.title).toMatch(/ - Acme 지원$/);
    });
  });

  test('S9-AC-23 포털 없는 주소(404)의 탭 제목도 "{화면 제목} - {표시 이름}"이다', async () => {
    answerWith(ACME);
    renderAt('/no-such-screen');

    await slowly(() => {
      expect(pageText()).toContain('찾을 수 없습니다');
      expect(document.title).toBe('찾을 수 없습니다 - Acme 지원');
    });
  });

  test.each([
    { where: 'P-1 로그인', path: '/login' },
    { where: 'P-2 초대 수락', path: '/invite#token=inv-Hk3sV8pQ2mTx' },
    { where: 'P-2 쓸 수 없는 초대 링크', path: '/invite' },
    { where: 'P-9 비밀번호 찾기', path: '/forgot-password' },
    { where: 'P-10 비밀번호 다시 정하기', path: '/reset-password#token=rst-Hk3sV8pQ2mTx' },
    { where: 'P-10 쓸 수 없는 링크', path: '/reset-password' },
  ])('S9-AC-22 포털 로그인 전 $where: 표시 이름과 두 테마 로고를 보이고 "popdesk"가 없다', async ({ path }) => {
    state.signedIn = false;
    answerWith(ACME);
    renderAt(path);

    await slowly(() => {
      expect(pageText()).toContain('Acme 지원');
      expect(imagesIn(document.body)).toEqual([LOGO_LIGHT_URL, LOGO_DARK_URL]);
      expect(document.title).toMatch(/ - Acme 지원$/);
    });
    expect(pageText()).not.toContain('popdesk');
  });

  test.each([
    { what: '500', answer: () => errors.internal() },
    { what: '연결 없음', answer: () => HttpResponse.error() },
    { what: '목록 꼴 200', answer: () => HttpResponse.json({ items: [], page: 1, size: 20, totalElements: 0, totalPages: 0 }) },
    { what: '이름 51자', answer: () => HttpResponse.json({ ...ACME, displayName: '가'.repeat(51) }) },
    { what: '색이 소문자 셋', answer: () => HttpResponse.json({ ...ACME, colors: { ...VIOLET, light: { ...VIOLET.light, accent: '#abc' } } }) },
    { what: '로고 주소가 다른 곳', answer: () => HttpResponse.json({ ...ACME, logos: { light: LOGO_LIGHT_URL, dark: '//evil.test/logo.png' } }) },
  ])('S9-AC-24 포털 공개 브랜딩 답이 나쁘고($what) 남긴 값이 없으면 기본으로 그리고 오류를 보이지 않는다', async ({ answer }) => {
    on('get', PUBLIC_BRANDING, answer);
    const top = await openList();
    await settle();

    expect(text(top)).toContain('popdesk');
    expect(text(top)).not.toContain('Acme 지원');
    expect(brandVariables()).toEqual(NO_VARIABLES);
    expect(imagesIn(top)).toEqual([]);
    expect(liveRegions().filter((r) => text(r) !== '').map(text), '오류 띠와 알림').toEqual([]);
  });

  test('S9-AC-24 포털은 읽은 브랜딩을 남기고, 다음에 읽지 못하면 남긴 값으로 그린다', async () => {
    answerWith(ACME);
    const first = renderAt('/issues');
    await slowly(() => {
      expect(brandVariables()).toEqual(VIOLET_VARIABLES);
    });
    await settle();
    expect(keptKeysWith('Acme 지원'), '남긴 값').not.toEqual([]);
    first.unmount();

    on('get', PUBLIC_BRANDING, () => errors.internal());
    const top = await openList();
    await settle();

    expect(text(top)).toContain('Acme 지원');
    expect(brandVariables()).toEqual(VIOLET_VARIABLES);
    expect(imagesIn(top)).toEqual([LOGO_LIGHT_URL, LOGO_DARK_URL]);
  });
});
