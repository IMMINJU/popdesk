// 9번 묶음 design.md 4절의 순수 함수 둘: brandingStyle(받은 색 → <html>의 --brand-* 여덟 변수)과 parseBranding(공개 응답과
// 저장소 값의 꼴 검사, S9-AC-24). 경계는 C-10 변이 테스트 뒤 단위 테스트가 더 본다.
import { describe, expect, test } from 'vitest';
import { brandingStyle, parseBranding, type AccentColors, type PublicBranding } from './branding';

/** `#047857` put in by hand (design.md 3절의 검산 값). */
const EMERALD: AccentColors = {
  light: { accent: '#047857', accentHover: '#005E43', onAccent: '#FFFFFF', accentSubtle: '#DBF8EB' },
  dark: { accent: '#6ECBA5', accentHover: '#88E5BF', onAccent: '#191817', accentSubtle: '#0A3123' },
};

const LOGO_LIGHT = '/api/public/branding/logos/light?v=0123456789abcdef';
const LOGO_DARK = '/api/public/branding/logos/dark?v=fedcba9876543210';

const ACME: PublicBranding = { displayName: 'Acme 지원', colors: EMERALD, logos: { light: LOGO_LIGHT, dark: LOGO_DARK } };

describe('brandingStyle (9번 묶음)', () => {
  test('S9-AC-22 brandingStyle: 두 테마의 네 값을 --brand-* 여덟 변수의 hex로 준다(light-dark() 글이 아니다)', () => {
    expect(brandingStyle(EMERALD)).toEqual({
      '--brand-accent-light': '#047857',
      '--brand-accent-dark': '#6ECBA5',
      '--brand-accent-hover-light': '#005E43',
      '--brand-accent-hover-dark': '#88E5BF',
      '--brand-on-accent-light': '#FFFFFF',
      '--brand-on-accent-dark': '#191817',
      '--brand-accent-subtle-light': '#DBF8EB',
      '--brand-accent-subtle-dark': '#0A3123',
    });
  });

  test('S9-AC-22 brandingStyle: 색이 null이면 빈 객체(tokens.css의 기본 값)다', () => {
    expect(brandingStyle(null)).toEqual({});
    // 있음도 같이 본다: 색이 있으면 비지 않는다.
    expect(Object.keys(brandingStyle(EMERALD))).toHaveLength(8);
  });
});

describe('parseBranding (9번 묶음)', () => {
  test.each([
    { what: '색과 로고 둘', value: ACME },
    { what: '기본', value: { displayName: 'popdesk', colors: null, logos: { light: null, dark: null } } },
    { what: '한 테마 로고를 두 칸에', value: { ...ACME, logos: { light: LOGO_LIGHT, dark: LOGO_LIGHT } } },
    { what: '이름 50자', value: { ...ACME, displayName: '가'.repeat(50) } },
    { what: '이름 한 자', value: { ...ACME, displayName: 'A' } },
  ])('S9-AC-24 parseBranding: 맞는 꼴($what)은 같은 값을 준다', ({ value }) => {
    expect(parseBranding(value)).toEqual(value);
  });

  test('S9-AC-24 parseBranding: 저장소에서 읽은 JSON 글을 다시 읽은 값도 받는다', () => {
    expect(parseBranding(JSON.parse(JSON.stringify(ACME)))).toEqual(ACME);
  });

  test.each([
    { what: 'null', value: null },
    { what: '글', value: 'popdesk' },
    { what: '목록 꼴 200(story 가짜 서버)', value: { items: [], page: 1, size: 20, totalElements: 0, totalPages: 0 } },
    { what: '빈 이름', value: { ...ACME, displayName: '' } },
    { what: '이름 51자', value: { ...ACME, displayName: '가'.repeat(51) } },
    { what: '이름이 글이 아님', value: { ...ACME, displayName: 7 } },
    { what: '색 칸 없음', value: { displayName: 'Acme', logos: ACME.logos } },
    { what: '색이 세 자리', value: { ...ACME, colors: { ...EMERALD, light: { ...EMERALD.light, accent: '#0A3' } } } },
    { what: '색이 이름', value: { ...ACME, colors: { ...EMERALD, dark: { ...EMERALD.dark, onAccent: 'white' } } } },
    { what: '색에 CSS 글', value: { ...ACME, colors: { ...EMERALD, light: { ...EMERALD.light, accentSubtle: '#FFFFFF; background: url(x)' } } } },
    { what: '한 테마 색 없음', value: { ...ACME, colors: { light: EMERALD.light } } },
    { what: '로고 칸 없음', value: { displayName: 'Acme', colors: null } },
    { what: '로고가 다른 호스트', value: { ...ACME, logos: { light: 'https://evil.test/api/public/branding/logos/light', dark: LOGO_DARK } } },
    { what: '로고가 다른 경로', value: { ...ACME, logos: { light: LOGO_LIGHT, dark: '/api/console/photos/1' } } },
    { what: '로고가 javascript:', value: { ...ACME, logos: { light: 'javascript:alert(1)', dark: null } } },
  ])('S9-AC-24 parseBranding: 꼴이 틀리면($what) null', ({ value }) => {
    expect(parseBranding(value)).toBeNull();
    // 있음도 같이 본다: 맞는 꼴은 null이 아니다.
    expect(parseBranding(ACME)).not.toBeNull();
  });
});
