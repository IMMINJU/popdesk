// screens.md 5절 17 Skeleton. 줄, 칸, 원. 읽지 않는다(aria-hidden), 불러오는 영역이 aria-busy를 둔다.
import { render } from '@testing-library/react';
import { describe, expect, test } from 'vitest';
import { expectNoAxeViolations, spokenText } from '../__tests__/support';
import { Skeleton } from './Skeleton';

describe('Skeleton', () => {
  test('Skeleton 읽지 않음: 세 모양 모두 aria-hidden이고 읽을 글자가 없다', () => {
    const { container } = render(
      <div>
        <Skeleton shape="line" count={5} />
        <Skeleton shape="block" height="120px" />
        <Skeleton shape="circle" width="32px" height="32px" />
      </div>,
    );

    const roots = Array.from(container.firstElementChild?.children ?? []);
    expect(roots).toHaveLength(3);
    for (const el of roots) expect(el).toHaveAttribute('aria-hidden', 'true');
    expect(spokenText(container)).toBe('');
  });

  test('Skeleton 포커스: 뼈대 안에는 Tab이 닿는 것이 없다', () => {
    const { container } = render(<Skeleton shape="line" count={3} />);

    expect(container.querySelectorAll('a, button, input, [tabindex]')).toHaveLength(0);
  });

  test('Skeleton axe: aria-busy 영역 안의 뼈대에 접근성 위반이 없다', async () => {
    render(
      <section aria-busy="true" aria-label="문의 목록">
        <Skeleton shape="line" count={5} />
      </section>,
    );

    await expectNoAxeViolations();
  });
});
