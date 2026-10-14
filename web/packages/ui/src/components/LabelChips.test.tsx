// 6a 묶음 screens.md 2절 C-3 라벨 칸, 5절 LabelChips: `max`(기본 3)까지 칩, 넘으면 "+n"이고
// 그 `title`과 화면 읽기 글자가 `moreText(가린 것)`이다. 라벨이 없으면 아무것도 그리지 않는다(S6A-AC-82).
import { render, screen } from '@testing-library/react';
import { describe, expect, test, vi } from 'vitest';
import { expectNoAxeViolations, must, spokenText, text } from '../__tests__/support';
import type { LabelItem } from '../types';
import { LabelChips } from './LabelChips';

const LABELS: LabelItem[] = [
  { id: '1', name: '결제', color: '#245FB0' },
  { id: '2', name: '긴급', color: '#C0262D' },
  { id: '3', name: '모바일', color: '#F2C14E' },
  { id: '4', name: '보안', color: '#1F7A3A' },
  { id: '5', name: '웹', color: '#808080' },
  { id: '6', name: '정산', color: '#6D28D9' },
];

const moreText = (hidden: readonly LabelItem[]) => `라벨 ${String(hidden.length)}개 더: ${hidden.map((l) => l.name).join(', ')}`;

/** Names drawn as a chip of their own: an element whose whole text is the name. */
const chipsShown = (names: readonly string[]) => names.filter((n) => screen.queryAllByText(n, { exact: true }).length > 0);

/**
 * The "+n": an element with a `title` whose text starts with "+". A chip that cuts a long name
 * may carry the full name as its own `title`; that one is not the "+n".
 */
const more = () => Array.from(document.querySelectorAll<HTMLElement>('[title]')).find((el) => text(el).startsWith('+')) ?? null;

describe('LabelChips', () => {
  test('S6A-AC-82 LabelChips 0개: 아무것도 그리지 않는다', () => {
    const { container } = render(<LabelChips labels={[]} moreText={moreText} />);

    expect(container.innerHTML).toBe('');
  });

  test.each([
    { count: 2, max: undefined, visible: 2 },
    { count: 3, max: undefined, visible: 3 },
    { count: 3, max: 3, visible: 3 },
    { count: 2, max: 1, visible: 1 },
    { count: 4, max: undefined, visible: 3 },
  ])('S6A-AC-82 LabelChips 라벨 수 $count, max $max: 앞의 칩 수 $visible, 넘친 것만 "+n"이 된다', ({ count, max, visible }) => {
    const labels = LABELS.slice(0, count);
    render(<LabelChips labels={labels} {...(max === undefined ? {} : { max })} moreText={moreText} />);

    expect(chipsShown(labels.map((l) => l.name))).toEqual(labels.slice(0, visible).map((l) => l.name));
    if (visible === count) {
      expect(more(), '넘치지 않으면 "+n"이 없다').toBeNull();
      expect(document.body.textContent).not.toMatch(/\+\d/);
    } else {
      expect(text(must(more(), '"+n"'))).toContain(`+${String(count - visible)}`);
    }
  });

  test('S6A-AC-82 LabelChips 6개(기본 max 3): 칩 셋, "+3"의 title과 화면 읽기 글자가 가린 셋의 moreText다', async () => {
    const spy = vi.fn(moreText);
    const { container } = render(<LabelChips labels={LABELS} moreText={spy} />);
    const hidden = LABELS.slice(3);

    expect(chipsShown(LABELS.map((l) => l.name))).toEqual(['결제', '긴급', '모바일']);
    const plus = must(more(), '"+3"');
    expect(text(plus)).toContain('+3');
    expect(plus.getAttribute('title')).toBe('라벨 3개 더: 보안, 웹, 정산');
    expect(spokenText(container), '화면 읽기 글자').toContain('라벨 3개 더: 보안, 웹, 정산');
    expect(spy.mock.calls.at(-1)?.[0].map((l) => l.id)).toEqual(hidden.map((l) => l.id));
    await expectNoAxeViolations();
  });
});
