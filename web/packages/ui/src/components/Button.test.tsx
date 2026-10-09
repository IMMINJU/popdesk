// screens.md 5절 1 Button.
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, test, vi } from 'vitest';
import { expectNoAxeViolations, isBlocked, spokenText, text } from '../__tests__/support';
import { Button } from './Button';

describe('Button', () => {
  test('Button 기본: 글자가 이름이고 type은 button, 누르면 onClick', async () => {
    const onClick = vi.fn();
    render(<Button onClick={onClick}>올리기</Button>);
    const user = userEvent.setup();

    const b = screen.getByRole('button', { name: '올리기' });
    expect(b).toHaveAttribute('type', 'button');
    await user.click(b);
    expect(onClick).toHaveBeenCalledTimes(1);
  });

  test('Button 보내는 중: aria-disabled="true", "보내는 중…" 글자, disabled가 아니어서 포커스를 잃지 않는다', () => {
    const { rerender } = render(<Button pendingLabel="보내는 중…">올리기</Button>);
    const b = screen.getByRole('button', { name: '올리기' });
    b.focus();

    rerender(
      <Button pending pendingLabel="보내는 중…">
        올리기
      </Button>,
    );

    const pending = screen.getByRole('button', { name: /보내는 중…/ });
    expect(pending).toHaveAttribute('aria-disabled', 'true');
    expect(pending).not.toBeDisabled();
    expect(document.activeElement).toBe(pending);
  });

  test('Button 보내는 중: 회전 표시를 두고 그 표시는 읽지 않는다(aria-hidden)', () => {
    render(
      <Button pending pendingLabel="보내는 중…">
        올리기
      </Button>,
    );
    const b = screen.getByRole('button', { name: /보내는 중…/ });

    const hidden = b.querySelectorAll('[aria-hidden="true"]');
    expect(hidden.length, '회전 표시(aria-hidden)').toBeGreaterThan(0);
    expect(spokenText(b)).toBe('보내는 중…');
  });

  test('Button 보내는 중: 마우스로 눌러도, Enter·Space로 눌러도 onClick을 부르지 않는다(UX-9)', async () => {
    const onClick = vi.fn();
    render(
      <Button pending pendingLabel="보내는 중…" onClick={onClick}>
        올리기
      </Button>,
    );
    const user = userEvent.setup();
    const b = screen.getByRole('button', { name: /보내는 중…/ });

    await user.click(b);
    b.focus();
    await user.keyboard('{Enter}');
    await user.keyboard(' ');
    expect(onClick).not.toHaveBeenCalled();
  });

  test('Button 보내는 중: submit 버튼이면 폼을 다시 보내지 않는다', async () => {
    const onSubmit = vi.fn((e: { preventDefault: () => void }) => {
      e.preventDefault();
    });
    render(
      <form onSubmit={onSubmit}>
        <Button type="submit" pending pendingLabel="보내는 중…">
          올리기
        </Button>
      </form>,
    );
    const user = userEvent.setup();

    await user.click(screen.getByRole('button', { name: /보내는 중…/ }));
    expect(onSubmit).not.toHaveBeenCalled();
  });

  test('Button 보내는 중: pendingLabel이 없으면 원래 글자를 그대로 둔다', () => {
    render(<Button pending>올리기</Button>);

    const b = screen.getByRole('button', { name: /올리기/ });
    expect(b).toHaveAttribute('aria-disabled', 'true');
    expect(text(b)).toContain('올리기');
  });

  test('Button 막힘: disabled면 누를 수 없다', async () => {
    const onClick = vi.fn();
    render(
      <Button disabled onClick={onClick}>
        올리기
      </Button>,
    );
    const user = userEvent.setup();

    const b = screen.getByRole('button', { name: '올리기' });
    expect(isBlocked(b)).toBe(true);
    await user.click(b);
    expect(onClick).not.toHaveBeenCalled();
  });

  test('Button axe: 다섯 종류와 보내는 중에 접근성 위반이 없다', async () => {
    render(
      <div>
        <Button variant="primary">주</Button>
        <Button variant="secondary">보조</Button>
        <Button variant="quiet">조용한</Button>
        <Button variant="danger">지우기</Button>
        <Button variant="internal">내부 메모 남기기</Button>
        <Button size="sm" pending pendingLabel="보내는 중…">
          보내기
        </Button>
      </div>,
    );

    await expectNoAxeViolations();
  });
});
