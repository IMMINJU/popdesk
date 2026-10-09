// screens.md 5절 19 ProgressBar. C-10 변이 테스트가 찾은 빈칸: 보이는 막대 채움, 범위 밖 값.
import { render, screen } from '@testing-library/react';
import { describe, expect, test } from 'vitest';
import { ProgressBar } from './ProgressBar';

describe('ProgressBar 동작', () => {
  test('ProgressBar 채움: 보이는 막대가 지금 비율만큼 찬다', () => {
    const { container } = render(<ProgressBar label="log.txt" value={0.4} />);

    const fill = container.querySelector<HTMLElement>('[role="progressbar"] [aria-hidden="true"] > span');
    expect(fill?.style.width).toBe('40%');
  });

  test('ProgressBar 넘침: 0~1 밖의 값은 0과 100으로 자른다', () => {
    render(
      <div>
        <ProgressBar label="a.txt" value={1.7} />
        <ProgressBar label="b.txt" value={-0.2} />
      </div>,
    );

    expect(screen.getByRole('progressbar', { name: 'a.txt' })).toHaveAttribute('aria-valuenow', '100');
    expect(screen.getByRole('progressbar', { name: 'b.txt' })).toHaveAttribute('aria-valuenow', '0');
  });
});
