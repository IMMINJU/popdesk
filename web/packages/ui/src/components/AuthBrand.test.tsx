// 9번 묶음 screens.md 1절 로그인 전 화면의 AuthBrand: 로고가 있으면 <img alt=""> 둘(밝게, 어둡게, 같으면 하나)이 표시 이름
// 위에 있고, 없으면 지금처럼 표시 이름 글자만이다(S9-AC-22, 25).
import { render } from '@testing-library/react';
import { describe, expect, test } from 'vitest';
import { expectNoAxeViolations, text } from '../__tests__/support';
import { AuthBrand } from './AuthBrand';

const LIGHT = '/api/public/branding/logos/light?v=0123456789abcdef';
const DARK = '/api/public/branding/logos/dark?v=fedcba9876543210';

const sources = (root: ParentNode) => Array.from(root.querySelectorAll('img')).map((img) => img.getAttribute('src'));

/** True when `a` comes before `b` in the document. */
function before(a: Node, b: Node): boolean {
  return (a.compareDocumentPosition(b) & Node.DOCUMENT_POSITION_FOLLOWING) !== 0;
}

/** The deepest element whose text is `name`. */
function nameElement(root: HTMLElement, name: string): HTMLElement | null {
  const all = Array.from(root.querySelectorAll<HTMLElement>('*')).filter((el) => text(el) === name);
  return all.find((el) => !all.some((o) => o !== el && el.contains(o))) ?? null;
}

describe('AuthBrand (9번 묶음)', () => {
  test('S9-AC-22 S9-AC-25 AuthBrand 로고 둘: <img alt=""> 둘이 표시 이름 위에 있다', async () => {
    const { container } = render(<AuthBrand displayName="Acme 지원" logo={{ light: LIGHT, dark: DARK }} />);

    expect(sources(container)).toEqual([LIGHT, DARK]);
    expect(Array.from(container.querySelectorAll('img')).map((i) => i.getAttribute('alt'))).toEqual(['', '']);
    const name = nameElement(container, 'Acme 지원');
    expect(name, '표시 이름').not.toBeNull();
    const [first] = Array.from(container.querySelectorAll('img'));
    expect(first && name ? before(first, name) : false, '로고가 이름 위').toBe(true);
    await expectNoAxeViolations(container);
  });

  test('S9-AC-22 AuthBrand 두 주소가 같으면 <img>는 하나다', () => {
    const { container } = render(<AuthBrand displayName="Acme 지원" logo={{ light: DARK, dark: DARK }} />);

    expect(sources(container)).toEqual([DARK]);
  });

  test('S9-AC-22 AuthBrand 로고가 없으면 표시 이름 글자만이고(지금 그대로), 긴 이름(50자)도 글 그대로다', () => {
    const long = '가나다라마 '.repeat(10).trim().slice(0, 50);
    const { container } = render(<AuthBrand displayName={long} />);

    expect(sources(container)).toEqual([]);
    expect(text(container)).toBe(long);
    // 있음도 같이 본다: 로고를 주면 <img>가 생긴다.
    const { container: other } = render(<AuthBrand displayName="Acme" logo={{ light: LIGHT, dark: DARK }} />);
    expect(sources(other)).toEqual([LIGHT, DARK]);
  });
});
