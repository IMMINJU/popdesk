// screens.md 5절 20 PriorityLabel. 막대 아이콘 태그와 글자, 색만으로 가르지 않는다(tokens.md 7절).
import { render } from '@testing-library/react';
import { describe, expect, test } from 'vitest';
import { expectNoAxeViolations, spokenText, text } from '../__tests__/support';
import type { Priority } from '../types';
import { PriorityLabel } from './PriorityLabel';

const words: Record<Priority, string> = { LOW: '낮음', NORMAL: '보통', HIGH: '높음', URGENT: '긴급' };

describe('PriorityLabel', () => {
  for (const [priority, word] of Object.entries(words) as [Priority, string][]) {
    test(`PriorityLabel ${priority}: 글자 "${word}"를 보이고 값 이름(${priority})은 보이지 않는다`, () => {
      const { container } = render(<PriorityLabel priority={priority} />);

      expect(spokenText(container)).toBe(word);
      expect(text(container)).not.toContain(priority);
    });
  }

  test('PriorityLabel 아이콘: 막대 아이콘이 있고 읽지 않는다(aria-hidden), 글자는 늘 함께 있다', () => {
    const { container } = render(<PriorityLabel priority="HIGH" />);

    expect(container.querySelectorAll('[aria-hidden="true"]').length, '막대 아이콘(aria-hidden)').toBeGreaterThan(0);
    expect(spokenText(container)).toBe('높음');
  });

  test('PriorityLabel axe: 네 값에 접근성 위반이 없다', async () => {
    render(
      <div>
        <PriorityLabel priority="LOW" />
        <PriorityLabel priority="NORMAL" />
        <PriorityLabel priority="HIGH" />
        <PriorityLabel priority="URGENT" />
      </div>,
    );

    await expectNoAxeViolations();
  });
});
