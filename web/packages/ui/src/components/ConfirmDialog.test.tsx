// screens.md 5절 12 ConfirmDialog. Dialog 위에 확인·취소. 지우기, 링크 다시 만들기, 떠나기.
import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, test, vi } from 'vitest';
import { expectNoAxeViolations, found, text } from '../__tests__/support';
import { ConfirmDialog, type ConfirmDialogProps } from './ConfirmDialog';

function props(over: Partial<ConfirmDialogProps> = {}): ConfirmDialogProps {
  return {
    open: true,
    title: '초대 링크 다시 만들기',
    message: '새 링크를 만들면 이전 링크는 쓸 수 없게 됩니다.',
    confirmLabel: '새 링크 만들기',
    cancelLabel: '취소',
    onConfirm: () => undefined,
    onCancel: () => undefined,
    ...over,
  };
}

const box = () =>
  found(
    () => screen.queryByRole('alertdialog', { name: /초대 링크 다시 만들기|첨부 지우기/ }) ?? screen.queryByRole('dialog', { name: /초대 링크 다시 만들기|첨부 지우기/ }),
    '확인 대화상자',
  );

describe('ConfirmDialog', () => {
  test('ConfirmDialog 열림: 제목이 이름이고 문구, 확인·취소 버튼이 있다', async () => {
    render(<ConfirmDialog {...props()} />);

    const d = await box();
    expect(text(d)).toContain('새 링크를 만들면 이전 링크는 쓸 수 없게 됩니다.');
    expect(within(d).getByRole('button', { name: '새 링크 만들기' })).toBeInTheDocument();
    expect(within(d).queryAllByRole('button', { name: '취소' }).length, '취소 버튼').toBeGreaterThan(0);
  });

  test('ConfirmDialog 확인·취소: 누른 버튼의 콜백만 부른다', async () => {
    const onConfirm = vi.fn();
    const onCancel = vi.fn();
    render(<ConfirmDialog {...props({ onConfirm, onCancel })} />);
    const user = userEvent.setup();
    const d = await box();

    await user.click(within(d).getByRole('button', { name: '새 링크 만들기' }));
    expect(onConfirm).toHaveBeenCalledTimes(1);
    expect(onCancel).not.toHaveBeenCalled();
  });

  test('ConfirmDialog Esc: Esc는 취소와 같다', async () => {
    const onConfirm = vi.fn();
    const onCancel = vi.fn();
    render(<ConfirmDialog {...props({ onConfirm, onCancel })} />);
    const user = userEvent.setup();
    await box();

    await user.keyboard('{Escape}');
    expect(onCancel).toHaveBeenCalledTimes(1);
    expect(onConfirm).not.toHaveBeenCalled();
  });

  test('ConfirmDialog 포커스: 열면 포커스가 대화상자 안의 버튼에 있다', async () => {
    render(<ConfirmDialog {...props()} />);

    const d = await box();
    expect(d.contains(document.activeElement), '포커스가 대화상자 안').toBe(true);
    expect(document.activeElement?.tagName).toBe('BUTTON');
  });

  test('ConfirmDialog 보내는 중: 확인 버튼은 aria-disabled이고 다시 눌러도 부르지 않는다', async () => {
    const onConfirm = vi.fn();
    render(<ConfirmDialog {...props({ onConfirm, pending: true, tone: 'danger', title: '첨부 지우기', confirmLabel: '지우기' })} />);
    const user = userEvent.setup();
    const d = await box();

    const confirm = within(d).getByRole('button', { name: /지우기/ });
    expect(confirm).toHaveAttribute('aria-disabled', 'true');
    await user.click(confirm);
    expect(onConfirm).not.toHaveBeenCalled();
  });

  test('ConfirmDialog 닫힘: open이 false면 그리지 않는다', () => {
    render(<ConfirmDialog {...props({ open: false })} />);

    expect(screen.queryByRole('dialog')).toBeNull();
    expect(screen.queryByRole('alertdialog')).toBeNull();
  });

  test('ConfirmDialog axe: 기본과 위험 확인에 접근성 위반이 없다', async () => {
    render(<ConfirmDialog {...props({ tone: 'danger', title: '첨부 지우기', confirmLabel: '지우기', message: 'log.txt를 지웁니다.' })} />);
    await box();

    await expectNoAxeViolations();
  });
});
