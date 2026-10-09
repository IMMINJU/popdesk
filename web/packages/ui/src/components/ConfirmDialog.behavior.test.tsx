// screens.md 5절 12 ConfirmDialog, 13 CopyField, 9 FormErrorSummary. C-10 변이 테스트가 찾은 빈칸:
// 위험 확인은 취소에서 시작, 닫기 버튼 없음, 보내는 중 글자, 클립보드가 될 때는 포커스를 가져가지 않음,
// 없는 칸을 가리키는 오류 링크.
import { fireEvent, render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, test } from 'vitest';
import { found } from '../__tests__/support';
import { ConfirmDialog, type ConfirmDialogProps } from './ConfirmDialog';
import { CopyField } from './CopyField';
import { FormErrorSummary } from './FormErrorSummary';

function confirm(over: Partial<ConfirmDialogProps> = {}) {
  return (
    <ConfirmDialog
      open
      title="첨부를 지울까요?"
      message="지우면 되돌릴 수 없습니다"
      confirmLabel="지우기"
      cancelLabel="취소"
      onConfirm={() => undefined}
      onCancel={() => undefined}
      {...over}
    />
  );
}

const alertDialog = () => found(() => screen.queryByRole('alertdialog', { name: '첨부를 지울까요?' }), '확인 대화상자');

describe('ConfirmDialog 동작', () => {
  test('ConfirmDialog 위험 확인: 열면 포커스가 취소에 있어 Enter가 지우지 않는다', async () => {
    render(confirm({ tone: 'danger' }));
    await alertDialog();

    expect(document.activeElement).toBe(screen.getByRole('button', { name: '취소' }));
  });

  test('ConfirmDialog 기본 확인: 열면 포커스가 확인 버튼에 있다', async () => {
    render(confirm());
    await alertDialog();

    expect(document.activeElement).toBe(screen.getByRole('button', { name: '지우기' }));
  });

  test('ConfirmDialog 버튼: 취소와 확인 두 버튼뿐이고 따로 닫기 버튼이 없다', async () => {
    render(confirm());
    const d = await alertDialog();

    expect(Array.from(d.querySelectorAll('button')).map((b) => b.textContent)).toEqual(['취소', '지우기']);
  });

  test('ConfirmDialog 보내는 중: 확인 버튼이 pendingLabel 글자를 보이고 대화상자는 aria-busy', async () => {
    render(confirm({ pending: true, pendingLabel: '지우는 중…' }));
    const d = await alertDialog();

    expect(screen.getByRole('button', { name: /지우는 중…/ })).toHaveAttribute('aria-disabled', 'true');
    expect(d).toHaveAttribute('aria-busy', 'true');
  });

  test('ConfirmDialog 보내는 중 글자 없음: pendingLabel이 없으면 확인 글자를 그대로 둔다', async () => {
    render(confirm({ pending: true }));
    await alertDialog();

    expect(screen.getByRole('button', { name: /지우기/ })).toHaveAttribute('aria-disabled', 'true');
  });

  test('ConfirmDialog 기다림 아님: pending이 없으면 확인 버튼도 대화상자도 막히지 않는다', async () => {
    render(confirm());
    const d = await alertDialog();

    expect(screen.getByRole('button', { name: '지우기' })).not.toHaveAttribute('aria-disabled');
    expect(d).not.toHaveAttribute('aria-busy');
  });
});

describe('CopyField 동작', () => {
  test('CopyField 기본: 클립보드를 쓸 수 있으면 포커스를 칸으로 옮기지 않는다', () => {
    render(
      <div>
        <button type="button">초대 링크 만들기</button>
        <CopyField label="초대 링크" value="https://popdesk.example/invite#t" copyLabel="복사" onCopy={() => undefined} />
      </div>,
    );

    expect(document.activeElement).not.toBe(screen.getByRole('textbox', { name: '초대 링크' }));
  });

  test('CopyField 포커스: 칸에 포커스가 오면 링크 전체를 고른다', async () => {
    render(<CopyField label="초대 링크" value="https://popdesk.example/invite#t" copyLabel="복사" onCopy={() => undefined} />);
    const user = userEvent.setup();

    await user.tab();
    const input = screen.getByRole<HTMLInputElement>('textbox', { name: '초대 링크' });
    expect(document.activeElement).toBe(input);
    expect([input.selectionStart, input.selectionEnd]).toEqual([0, input.value.length]);
  });

  test('CopyField 맞춤법 검사: 링크 칸은 맞춤법 검사를 하지 않는다', () => {
    render(<CopyField label="초대 링크" value="https://popdesk.example/invite#t" copyLabel="복사" onCopy={() => undefined} />);

    expect(screen.getByRole('textbox', { name: '초대 링크' })).toHaveAttribute('spellcheck', 'false');
  });
});

describe('FormErrorSummary 동작', () => {
  test('FormErrorSummary 없는 칸: 가리키는 칸이 화면에 없으면 링크의 기본 동작(주소의 #)을 막지 않는다', () => {
    render(<FormErrorSummary title="1개 칸을 고쳐 주세요" errors={[{ fieldId: 'gone', message: '이름을 쓰세요' }]} />);

    const link = screen.getByRole('link', { name: '이름을 쓰세요' });
    const notPrevented = fireEvent.click(link);
    expect(notPrevented).toBe(true);
  });

  test('FormErrorSummary 있는 칸: 칸으로 포커스를 옮기고 주소는 바꾸지 않는다(기본 동작을 막는다)', () => {
    render(
      <div>
        <input id="name" aria-label="이름" />
        <FormErrorSummary title="1개 칸을 고쳐 주세요" errors={[{ fieldId: 'name', message: '이름을 쓰세요' }]} />
      </div>,
    );

    const notPrevented = fireEvent.click(screen.getByRole('link', { name: '이름을 쓰세요' }));
    expect(notPrevented).toBe(false);
    expect(document.activeElement).toBe(screen.getByRole('textbox', { name: '이름' }));
  });

  test('FormErrorSummary 칸 오류 없음: 서버 문구만 있으면 목록을 그리지 않는다', () => {
    render(<FormErrorSummary title="저장하지 못했습니다" errors={[]} serverMessage="다시 시도하세요" />);

    expect(screen.queryByRole('list')).toBeNull();
    expect(screen.getByRole('alert')).toHaveTextContent('다시 시도하세요');
  });
});
