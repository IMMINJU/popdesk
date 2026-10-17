// 9번 묶음 screens.md 1절 콘솔의 뿌리: 화면이 열리면 공개 브랜딩을 한 번 읽어 <html>의 --brand-* 여덟 변수(기본이면 없음),
// 머리와 로그인 전 화면의 표시 이름, 로고(<img alt="">, 두 테마, 같으면 하나), 탭 제목 "제목 - 이름", 초대 수락 환영 문장을
// 바꾼다(S9-AC-22, 23, 25). 읽기 실패와 꼴이 틀린 답은 이 브라우저에 남긴 값, 없으면 기본으로 그리고 오류를 보이지 않는다(S9-AC-24).
import { HttpResponse } from 'msw/http';
import { describe, expect, test, vi } from 'vitest';
import { callsTo, errors, expectNoAxeViolations, gate, link, liveRegions, pageText, renderAt, sent, state, text } from '../__tests__/console';
import { route, settle } from '../__tests__/console1a';
import {
  BRAND_VARIABLES,
  LOGO_DARK_URL,
  LOGO_LIGHT_URL,
  NO_VARIABLES,
  PUBLIC_BRANDING,
  VIOLET,
  brandVariables,
  head,
  imagesIn,
  publicBranding,
  publicResponse,
  s9,
  slowly,
  variablesOf,
  type PublicBranding,
} from '../__tests__/console9';

const ACME: PublicBranding = publicBranding({ displayName: 'Acme 지원', colors: VIOLET, logos: { light: LOGO_LIGHT_URL, dark: LOGO_DARK_URL } });

/** Renders the console at `/issues` and waits until the head is drawn. */
async function openList(): Promise<HTMLElement> {
  renderAt('/issues');
  await slowly(() => {
    expect(document.querySelector('[data-pd="AppShell"] header'), '화면 머리').not.toBeNull();
  });
  await sent('GET', PUBLIC_BRANDING);
  return head();
}

/** The `alt` of every `<img>` inside `root`. */
function altsIn(root: ParentNode): (string | null)[] {
  return Array.from(root.querySelectorAll('img')).map((img) => img.getAttribute('alt'));
}

/** The keys of `localStorage` whose value holds `part`. */
function keptKeysWith(part: string): string[] {
  return Object.keys(localStorage).filter((k) => (localStorage.getItem(k) ?? '').includes(part));
}

/** Shows ACME once so the console keeps it in this browser, then takes the app down again. */
async function keepAcme(): Promise<void> {
  s9.public = ACME;
  const first = renderAt('/issues');
  await slowly(() => {
    expect(brandVariables()).toEqual(variablesOf(VIOLET));
  });
  await settle();
  expect(keptKeysWith('Acme 지원'), '남긴 값').not.toEqual([]);
  first.unmount();
}

describe('콘솔의 브랜딩 (9번 묶음)', () => {
  test('S9-AC-22 콘솔 머리: 공개 브랜딩을 한 번 읽어 <html>에 --brand-* 여덟 변수, 머리에 표시 이름과 두 테마 로고를 보인다', async () => {
    s9.public = ACME;
    const top = await openList();

    await slowly(() => {
      expect(text(top)).toContain('Acme 지원');
      expect(brandVariables()).toEqual(variablesOf(VIOLET));
      expect(imagesIn(top)).toEqual([LOGO_LIGHT_URL, LOGO_DARK_URL]);
    });
    await settle();
    expect(callsTo('GET', PUBLIC_BRANDING), '공개 브랜딩 요청').toHaveLength(1);
    expect(pageText()).not.toContain('popdesk');
  });

  test('S9-AC-25 콘솔 머리: 로고는 alt=""이고 홈 링크의 이름은 표시 이름이다. 한 테마만 있어 주소가 같으면 <img>는 하나다', async () => {
    s9.public = publicBranding({ displayName: 'Acme 지원', logos: { light: LOGO_LIGHT_URL, dark: LOGO_LIGHT_URL } });
    const top = await openList();

    await slowly(() => {
      expect(imagesIn(top)).toEqual([LOGO_LIGHT_URL]);
    });
    expect(altsIn(top)).toEqual(['']);
    const home = await link('Acme 지원');
    expect(top.contains(home), '머리 안의 홈 링크').toBe(true);
    expect(brandVariables(), '색이 없으면 변수도 없다').toEqual(NO_VARIABLES);
    await expectNoAxeViolations();
  });

  test('S9-AC-22 콘솔 기본 브랜딩: 변수 없음, 로고 없음, 머리 이름 "popdesk"', async () => {
    const top = await openList();

    await slowly(() => {
      expect(text(top)).toContain('popdesk');
    });
    await settle();
    expect(brandVariables()).toEqual(NO_VARIABLES);
    expect(imagesIn(top)).toEqual([]);
  });

  test('S9-AC-23 콘솔 탭 제목은 "{화면 제목} - {표시 이름}"이다', async () => {
    s9.public = ACME;
    await openList();

    await slowly(() => {
      expect(document.title).toMatch(/^.+ - Acme 지원$/);
    });
    expect(document.title).not.toContain('popdesk');
  });

  test('S9-AC-22 S9-AC-23 콘솔 로그인 화면: 표시 이름과 로고(alt=""), 탭 제목 "로그인 - {표시 이름}"', async () => {
    state.signedIn = false;
    s9.public = ACME;
    renderAt('/login');

    await slowly(() => {
      expect(pageText()).toContain('Acme 지원');
      expect(imagesIn(document.body)).toEqual([LOGO_LIGHT_URL, LOGO_DARK_URL]);
      expect(document.title).toBe('로그인 - Acme 지원');
    });
    expect(altsIn(document.body)).toEqual(['', '']);
    expect(brandVariables()).toEqual(variablesOf(VIOLET));
    expect(pageText()).not.toContain('popdesk');
    expect(callsTo('GET', PUBLIC_BRANDING)).toHaveLength(1);
  });

  test('S9-AC-23 콘솔 초대 수락: 환영 문장 "{표시 이름} 상담 콘솔에 오신 것을 환영합니다"와 탭 제목 "초대 수락 - {표시 이름}"', async () => {
    state.signedIn = false;
    s9.public = publicBranding({ displayName: 'Acme 지원' });
    renderAt('/invite#token=inv-Hk3sV8pQ2mTx');

    await slowly(() => {
      expect(pageText()).toContain('Acme 지원 상담 콘솔에 오신 것을 환영합니다');
      expect(document.title).toBe('초대 수락 - Acme 지원');
    });
    expect(pageText()).not.toContain('popdesk');
  });

  test.each([
    { what: '500', answer: () => errors.internal() },
    { what: '연결 없음', answer: () => HttpResponse.error() },
    { what: '목록 꼴 200', answer: () => HttpResponse.json({ items: [], page: 1, size: 20, totalElements: 0, totalPages: 0 }) },
    { what: '이름 51자', answer: () => HttpResponse.json({ ...ACME, displayName: '가'.repeat(51) }) },
    { what: '빈 이름', answer: () => HttpResponse.json({ ...ACME, displayName: '' }) },
    { what: '색이 #RRGGBB가 아님', answer: () => HttpResponse.json({ ...ACME, colors: { ...VIOLET, dark: { ...VIOLET.dark, accent: 'red' } } }) },
    { what: '로고 주소가 다른 곳', answer: () => HttpResponse.json({ ...ACME, logos: { light: 'https://evil.test/logo.png', dark: LOGO_DARK_URL } }) },
  ])('S9-AC-24 콘솔 공개 브랜딩 답이 나쁘고($what) 남긴 값이 없으면 기본으로 그리고 오류를 보이지 않는다', async ({ answer }) => {
    route('get', PUBLIC_BRANDING, answer);
    const top = await openList();
    await settle();

    expect(text(top)).toContain('popdesk');
    expect(text(top)).not.toContain('Acme 지원');
    expect(brandVariables()).toEqual(NO_VARIABLES);
    expect(imagesIn(top)).toEqual([]);
    expect(liveRegions().filter((r) => text(r) !== '').map(text), '오류 띠와 알림').toEqual([]);
  });

  test('S9-AC-24 콘솔은 읽은 브랜딩을 이 브라우저에 남기고, 다음에 읽지 못하면 남긴 값으로 그린다', async () => {
    s9.public = ACME;
    const first = renderAt('/issues');
    await slowly(() => {
      expect(brandVariables()).toEqual(variablesOf(VIOLET));
    });
    await settle();
    expect(keptKeysWith('Acme 지원'), '남긴 값').not.toEqual([]);
    first.unmount();

    route('get', PUBLIC_BRANDING, () => errors.internal());
    const top = await openList();
    await settle();

    expect(text(top)).toContain('Acme 지원');
    expect(brandVariables()).toEqual(variablesOf(VIOLET));
    expect(imagesIn(top)).toEqual([LOGO_LIGHT_URL, LOGO_DARK_URL]);
  });

  test('S9-AC-24 콘솔은 남긴 값도 같은 검사를 거친다: 꼴이 틀리게 바뀐 남긴 값은 버리고 기본으로 그린다', async () => {
    s9.public = ACME;
    const first = renderAt('/issues');
    await slowly(() => {
      expect(brandVariables()).toEqual(variablesOf(VIOLET));
    });
    await settle();
    const keys = keptKeysWith('Acme 지원');
    expect(keys, '남긴 값').not.toEqual([]);
    first.unmount();
    for (const key of keys) {
      localStorage.setItem(key, (localStorage.getItem(key) ?? '').replace(VIOLET.light.accent, 'javascript:alert(1)'));
    }

    route('get', PUBLIC_BRANDING, () => errors.internal());
    const top = await openList();
    await settle();

    expect(brandVariables()).toEqual(NO_VARIABLES);
    expect(text(top)).toContain('popdesk');
    expect(document.documentElement.getAttribute('style') ?? '').not.toContain('javascript');
  });

  test('S9-AC-24 콘솔 저장소가 막혀도(읽기·쓰기가 던짐) 읽은 브랜딩을 그리고 오류를 보이지 않는다', async () => {
    const getItem = vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => {
      throw new DOMException('blocked', 'SecurityError');
    });
    const setItem = vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
      throw new DOMException('blocked', 'SecurityError');
    });
    try {
      s9.public = ACME;
      const top = await openList();

      await slowly(() => {
        expect(text(top)).toContain('Acme 지원');
        expect(brandVariables()).toEqual(variablesOf(VIOLET));
      });
      expect(liveRegions().filter((r) => text(r) !== '').map(text)).toEqual([]);
    } finally {
      getItem.mockRestore();
      setItem.mockRestore();
    }
  });

  test('S9-AC-22 콘솔은 <html>에 --brand-* 여덟만 넣는다: style 속성 수가 8이고 light-dark()와 --focus가 없다', async () => {
    s9.public = ACME;
    await openList();

    await slowly(() => {
      expect(brandVariables()).toEqual(variablesOf(VIOLET));
    });
    await settle();
    const style = document.documentElement.style;
    expect(Array.from({ length: style.length }, (_, i) => style.item(i)).sort(), '<html>의 style 속성').toEqual([...BRAND_VARIABLES].sort());
    expect(style.length).toBe(8);
    const attribute = document.documentElement.getAttribute('style') ?? '';
    expect(attribute).not.toContain('light-dark');
    expect(attribute).not.toContain('--focus');
  });

  test('S9-AC-22 S9-AC-24 콘솔은 남긴 값이 있으면 공개 브랜딩 답이 오기 전에 이미 그 이름·변수·로고로 그린다', async () => {
    await keepAcme();
    const release = gate();
    route('get', PUBLIC_BRANDING, async () => {
      await release.opened;
      return publicResponse(ACME);
    });

    renderAt('/issues');

    expect(brandVariables(), '첫 그림의 변수').toEqual(variablesOf(VIOLET));
    try {
      const top = await head();
      await sent('GET', PUBLIC_BRANDING);
      await slowly(() => {
        expect(text(top)).toContain('Acme 지원');
      });
      expect(imagesIn(top)).toEqual([LOGO_LIGHT_URL, LOGO_DARK_URL]);
      expect(brandVariables()).toEqual(variablesOf(VIOLET));
    } finally {
      release.open();
    }
  });

  test('S9-AC-22 콘솔은 남긴 보라 뒤 공개 답이 기본이면 <html>의 --brand-* 여덟을 모두 지운다', async () => {
    await keepAcme();
    const release = gate();
    route('get', PUBLIC_BRANDING, async () => {
      await release.opened;
      return publicResponse(publicBranding());
    });
    renderAt('/issues');
    expect(brandVariables(), '남긴 값으로 그린 변수').toEqual(variablesOf(VIOLET));
    const top = await head();
    await sent('GET', PUBLIC_BRANDING);

    release.open();

    await slowly(() => {
      expect(text(top)).toContain('popdesk');
      expect(brandVariables()).toEqual(NO_VARIABLES);
    });
    expect(document.documentElement.style.length, '<html>의 style 속성 수').toBe(0);
    expect(imagesIn(top)).toEqual([]);
  });

  test.each([
    { what: '목록 꼴 200', answer: () => HttpResponse.json({ items: [], page: 1, size: 20, totalElements: 0, totalPages: 0 }) },
    { what: '이름 51자', answer: () => HttpResponse.json({ ...ACME, displayName: '가'.repeat(51) }) },
  ])('S9-AC-24 콘솔은 남긴 값이 있을 때 꼴이 틀린 200($what)이 남긴 값을 덮지 않는다', async ({ answer }) => {
    await keepAcme();
    route('get', PUBLIC_BRANDING, answer);

    const top = await openList();
    await settle();

    expect(text(top)).toContain('Acme 지원');
    expect(brandVariables()).toEqual(variablesOf(VIOLET));
    expect(imagesIn(top)).toEqual([LOGO_LIGHT_URL, LOGO_DARK_URL]);
    expect(keptKeysWith('Acme 지원'), '남긴 값').not.toEqual([]);
  });

  test('S9-AC-23 콘솔 없는 주소(404)의 탭 제목도 "{화면 제목} - {표시 이름}"이다', async () => {
    s9.public = ACME;
    renderAt('/no-such-screen');

    await slowly(() => {
      expect(pageText()).toContain('찾을 수 없습니다');
      expect(document.title).toBe('찾을 수 없습니다 - Acme 지원');
    });
  });

  test.each([
    { where: 'C-1 로그인', path: '/login' },
    { where: 'C-2 초대 수락', path: '/invite#token=inv-Hk3sV8pQ2mTx' },
    { where: 'C-2 쓸 수 없는 초대 링크', path: '/invite' },
    { where: 'C-16 비밀번호 찾기', path: '/forgot-password' },
    { where: 'C-17 비밀번호 다시 정하기', path: '/reset-password#token=rst-Hk3sV8pQ2mTx' },
    { where: 'C-17 쓸 수 없는 링크', path: '/reset-password' },
  ])('S9-AC-22 콘솔 로그인 전 $where: 표시 이름과 두 테마 로고를 보이고 "popdesk"가 없다', async ({ path }) => {
    state.signedIn = false;
    s9.public = ACME;
    renderAt(path);

    await slowly(() => {
      expect(pageText()).toContain('Acme 지원');
      expect(imagesIn(document.body)).toEqual([LOGO_LIGHT_URL, LOGO_DARK_URL]);
      expect(document.title).toMatch(/ - Acme 지원$/);
    });
    expect(pageText()).not.toContain('popdesk');
  });

  test('S9-AC-22 콘솔이 내려가면 <html>의 브랜딩 변수를 지운다', async () => {
    s9.public = ACME;
    const view = renderAt('/issues');
    await slowly(() => {
      expect(brandVariables()).toEqual(variablesOf(VIOLET));
    });

    view.unmount();

    expect(brandVariables()).toEqual(NO_VARIABLES);
  });
});
