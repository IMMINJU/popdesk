// S6A-AC-84, screens.md 5절 (라벨 칩 글자색), design.md 9절 표의 labelTextColor 줄.
// 기대값은 WCAG 2.1 대비를 손으로 셈한 고정값이다. 괄호 안은 (검정과의 대비, 흰색과의 대비).
import { describe, expect, test } from 'vitest';
import { labelTextColor } from './color';

const BLACK = '#000000';
const WHITE = '#FFFFFF';

describe('labelTextColor', () => {
  test('S6A-AC-84 경계 밝기: #767676(밝기 0.181)은 검정, #757575(0.178)는 흰색', () => {
    expect(labelTextColor('#767676')).toBe(BLACK); // (4.62, 4.54)
    expect(labelTextColor('#757575')).toBe(WHITE); // (4.56, 4.61)
  });

  test.each([
    ['#808080', BLACK], // (5.32, 3.95)
    ['#C0262D', WHITE], // (3.56, 5.90)
    ['#B07A00', BLACK], // (5.63, 3.73)
    ['#F2C14E', BLACK], // (12.51, 1.68)
    ['#1F7A3A', WHITE], // (3.90, 5.38)
    ['#0F766E', WHITE], // (3.84, 5.47)
    ['#245FB0', WHITE], // (3.34, 6.29)
    ['#6D28D9', WHITE], // (2.96, 7.10)
  ])('S6A-AC-84 견본색: %s 위 글자는 %s', (background, expected) => {
    expect(labelTextColor(background)).toBe(expected);
  });

  test.each([
    ['#000000', WHITE],
    ['#FFFFFF', BLACK],
    ['#FF0000', BLACK], // (5.25, 4.00)
    ['#00FF00', BLACK], // (15.30, 1.37)
    ['#0000FF', WHITE], // (2.44, 8.59)
  ])('S6A-AC-84 순수색: %s 위 글자는 %s', (background, expected) => {
    expect(labelTextColor(background)).toBe(expected);
  });

  test.each([
    ['#f2c14e', BLACK],
    ['#6d28d9', WHITE],
    ['F2C14E', BLACK],
    ['6d28d9', WHITE],
    ['  #F2C14E  ', BLACK],
    [' 245FB0 ', WHITE],
  ])('S6A-AC-84 받는 꼴(소문자, # 없음, 앞뒤 공백): %j 위 글자는 %s', (background, expected) => {
    expect(labelTextColor(background)).toBe(expected);
  });

  test.each([
    [''],
    ['#FFF'],
    ['FFF'],
    ['#F2C14EFF'],
    ['#GGGGGG'],
    ['#F2C14Z'],
    ['red'],
    ['rgb(242, 193, 78)'],
  ])('S6A-AC-84 틀린 값: %j 이면 null', (background) => {
    expect(labelTextColor(background)).toBeNull();
  });
});
