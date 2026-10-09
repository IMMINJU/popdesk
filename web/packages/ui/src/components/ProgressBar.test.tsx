// screens.md 5절 29 ProgressBar. 비율, 모름(회전). 첨부 올리기의 파일마다 progressbar.
import { render, screen } from '@testing-library/react';
import { describe, expect, test } from 'vitest';
import { expectNoAxeViolations } from '../__tests__/support';
import { ProgressBar } from './ProgressBar';

describe('ProgressBar', () => {
  test('ProgressBar 비율: role="progressbar", 이름은 파일 이름, 0~100에 지금 값', () => {
    render(<ProgressBar label="trace.log" value={0.42} />);

    const bar = screen.getByRole('progressbar', { name: 'trace.log' });
    expect(bar).toHaveAttribute('aria-valuemin', '0');
    expect(bar).toHaveAttribute('aria-valuemax', '100');
    expect(bar).toHaveAttribute('aria-valuenow', '42');
  });

  test('ProgressBar 모름: value가 없으면 aria-valuenow를 두지 않는다', () => {
    render(<ProgressBar label="dump.bin" />);

    expect(screen.getByRole('progressbar', { name: 'dump.bin' })).not.toHaveAttribute('aria-valuenow');
  });

  test('ProgressBar 끝: 1이면 100', () => {
    render(<ProgressBar label="log.txt" value={1} />);

    expect(screen.getByRole('progressbar', { name: 'log.txt' })).toHaveAttribute('aria-valuenow', '100');
  });

  test('ProgressBar axe: 비율과 모름에 접근성 위반이 없다', async () => {
    render(
      <div>
        <ProgressBar label="trace.log" value={0.42} />
        <ProgressBar label="dump.bin" />
      </div>,
    );

    await expectNoAxeViolations();
  });
});
