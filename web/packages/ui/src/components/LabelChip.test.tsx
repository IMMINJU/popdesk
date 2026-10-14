// 6a 묶음 screens.md 4·5절 LabelChip: 이름을 글자로, 바탕은 라벨 색, 글자색은 검정·흰색 가운데
// 바탕과 대비가 큰 쪽이다(S6A-AC-84). 대비는 이 테스트가 WCAG 2.1 상대 밝기로 센다.
// jsdom은 스타일시트를 읽지 않으므로 칩에 직접 단 색(style)을 본다.
import { render, screen } from '@testing-library/react';
import { afterEach, describe, expect, test } from 'vitest';
import { expectNoAxeViolations, must } from '../__tests__/support';
import { LabelChip } from './LabelChip';

type Rgb = readonly [number, number, number];

const BLACK: Rgb = [0, 0, 0];
const WHITE: Rgb = [255, 255, 255];

/** `#RRGGBB`, `#RGB`, `rgb(r, g, b)` or the names black and white. */
function rgbOf(value: string): Rgb | null {
  const v = value.trim().toLowerCase();
  if (v === 'black') return BLACK;
  if (v === 'white') return WHITE;
  const hex = /^#([0-9a-f]{6})$/.exec(v)?.[1];
  if (hex) return [0, 2, 4].map((i) => parseInt(hex.slice(i, i + 2), 16)) as unknown as Rgb;
  const short = /^#([0-9a-f]{3})$/.exec(v)?.[1];
  if (short) return [0, 1, 2].map((i) => parseInt(short.charAt(i).repeat(2), 16)) as unknown as Rgb;
  const rgb = /^rgba?\(\s*(\d+)\s*,\s*(\d+)\s*,\s*(\d+)/.exec(v);
  return rgb ? [Number(rgb[1]), Number(rgb[2]), Number(rgb[3])] : null;
}

/** WCAG 2.1 relative luminance. */
function luminance([r, g, b]: Rgb): number {
  const lin = (c: number) => {
    const s = c / 255;
    return s <= 0.04045 ? s / 12.92 : ((s + 0.055) / 1.055) ** 2.4;
  };
  return 0.2126 * lin(r) + 0.7152 * lin(g) + 0.0722 * lin(b);
}

function contrast(a: Rgb, b: Rgb): number {
  const [hi, lo] = [luminance(a), luminance(b)].sort((x, y) => y - x) as [number, number];
  return (hi + 0.05) / (lo + 0.05);
}

/** The colour set (`style`) on the element itself or the nearest ancestor up to the drawn root. */
function ownColor(el: HTMLElement | null, read: (s: CSSStyleDeclaration) => string): string {
  for (let at = el; at && at !== document.body; at = at.parentElement) {
    const value = read(at.style);
    if (value !== '' && rgbOf(value) !== null) return value;
  }
  return '';
}

function chipColors(name: string): { background: Rgb | null; text: Rgb | null } {
  const el = must(screen.queryByText(name), `칩 글자 "${name}"`);
  return {
    background: rgbOf(ownColor(el, (s) => s.backgroundColor)),
    text: rgbOf(ownColor(el, (s) => s.color)),
  };
}

afterEach(() => {
  delete document.documentElement.dataset['theme'];
});

describe('LabelChip', () => {
  test.each([
    ['회색', '#808080'],
    ['빨강', '#C0262D'],
    ['주황', '#B07A00'],
    ['노랑', '#F2C14E'],
    ['초록', '#1F7A3A'],
    ['청록', '#0F766E'],
    ['파랑', '#245FB0'],
    ['보라', '#6D28D9'],
    ['소문자로 적은 노랑', '#f2c14e'],
    ['검정과 흰색의 대비가 거의 같은 회색(검정 4.62, 흰색 4.54)', '#767676'],
    ['검정과 흰색의 대비가 거의 같은 회색(검정 4.56, 흰색 4.61)', '#757575'],
  ])('S6A-AC-84 LabelChip %s %s: 이름이 글자로 보이고 바탕이 그 색이며, 글자색은 검정과 흰색 가운데 대비가 큰 쪽이고 4.5:1 이상이다', (_, color) => {
    render(<LabelChip name="긴급" color={color} />);
    const want = rgbOf(color) as Rgb;

    const { background, text } = chipColors('긴급');

    expect(background, '칩의 바탕색').toEqual(want);
    expect(text, '칩의 글자색').not.toBeNull();
    const better = contrast(BLACK, want) >= contrast(WHITE, want) ? BLACK : WHITE;
    expect(text, '검정과 흰색 가운데 대비가 큰 쪽').toEqual(better);
    expect(contrast(text as Rgb, want)).toBeGreaterThanOrEqual(4.5);
  });

  test('S6A-AC-84 LabelChip 어둡게: 테마가 바뀌어도 글자색은 바탕에서 정한 그대로다', () => {
    const { unmount } = render(<LabelChip name="결제" color="#245FB0" />);
    const light = chipColors('결제');
    unmount();
    document.documentElement.dataset['theme'] = 'dark';

    render(<LabelChip name="결제" color="#245FB0" />);

    const dark = chipColors('결제');
    expect(dark.text, '어둡게의 글자색').toEqual(WHITE);
    expect(dark).toEqual(light);
  });

  test('S6A-AC-84 LabelChip 30자 이름: 이름 전체가 글자로 있다. axe 위반이 없다', async () => {
    const name = '가'.repeat(30);
    render(<LabelChip name={name} color="#F2C14E" />);

    expect(screen.queryByText(name), '30자 이름').not.toBeNull();
    await expectNoAxeViolations();
  });
});
