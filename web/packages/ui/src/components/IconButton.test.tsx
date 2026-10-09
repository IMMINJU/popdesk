// screens.md 5절 2 IconButton.
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, test, vi } from 'vitest';
import { expectNoAxeViolations, spokenText } from '../__tests__/support';
import { IconButton } from './IconButton';

const icon = <svg data-icon="x" />;

describe('IconButton', () => {
  test('IconButton 이름: label이 접근 가능한 이름이고 아이콘은 읽지 않는다', () => {
    render(<IconButton label="닫기" icon={icon} />);

    const b = screen.getByRole('button', { name: '닫기' });
    expect(b).toHaveAttribute('type', 'button');
    const svg = b.querySelector('svg');
    expect(svg, '아이콘').not.toBeNull();
    expect(svg?.closest('[aria-hidden="true"]') ?? null, '아이콘은 aria-hidden').not.toBeNull();
  });

  test('IconButton 전환: pressed가 aria-pressed로 나가고 누르면 onClick', async () => {
    const onClick = vi.fn();
    const { rerender } = render(<IconButton label="비밀번호 보이기" icon={icon} pressed={false} onClick={onClick} />);
    const user = userEvent.setup();

    const b = screen.getByRole('button', { name: '비밀번호 보이기' });
    expect(b).toHaveAttribute('aria-pressed', 'false');
    await user.click(b);
    expect(onClick).toHaveBeenCalledTimes(1);
    rerender(<IconButton label="비밀번호 보이기" icon={icon} pressed onClick={onClick} />);
    expect(b).toHaveAttribute('aria-pressed', 'true');
  });

  test('IconButton 보내는 중: aria-disabled, 포커스를 잃지 않고 눌러도 onClick을 부르지 않는다, 회전 표시는 읽지 않는다', async () => {
    const onClick = vi.fn();
    render(<IconButton label="지우기" icon={icon} pending onClick={onClick} />);
    const user = userEvent.setup();

    const b = screen.getByRole('button', { name: '지우기' });
    expect(b).toHaveAttribute('aria-disabled', 'true');
    expect(b).not.toBeDisabled();
    b.focus();
    await user.keyboard('{Enter}');
    await user.click(b);
    expect(onClick).not.toHaveBeenCalled();
    expect(document.activeElement).toBe(b);
    expect(spokenText(b)).toBe('');
  });

  test('IconButton 막힘: disabled면 누를 수 없다', async () => {
    const onClick = vi.fn();
    render(<IconButton label="지우기" icon={icon} disabled onClick={onClick} />);
    const user = userEvent.setup();

    await user.click(screen.getByRole('button', { name: '지우기' }));
    expect(onClick).not.toHaveBeenCalled();
  });

  test('IconButton axe: 기본·눌림·보내는 중에 접근성 위반이 없다', async () => {
    render(
      <div>
        <IconButton label="닫기" icon={icon} />
        <IconButton label="비밀번호 보이기" icon={icon} pressed />
        <IconButton label="지우기" icon={icon} pending variant="danger" size="sm" />
      </div>,
    );

    await expectNoAxeViolations();
  });
});
