// 4번 묶음 screens.md 4절: ConfirmDialog의 선택 props `error`. 주면 대화상자 안에 role="alert"로 그 글이 보이고, 주지 않으면 없다.
// 이 props는 아직 타입에 없다. 값을 따로 둔 객체로 펼쳐 넘겨서(JSX의 펼침은 남는 칸을 타입 오류로 보지 않는다) 구현 전에도 tsc가 통과한다.
import { render, screen, within } from '@testing-library/react';
import { describe, expect, test } from 'vitest';
import { expectNoAxeViolations, found, text } from '../__tests__/support';
import { ConfirmDialog } from './ConfirmDialog';

const FAILED = '서버에 연결하지 못했습니다. 잠시 뒤 다시 하세요.';

function confirm(extra: { error?: string } = {}) {
  return (
    <ConfirmDialog
      open
      tone="danger"
      title="댓글 지우기"
      message="지운 댓글은 되돌릴 수 없습니다."
      confirmLabel="댓글 지우기"
      cancelLabel="그만두기"
      onConfirm={() => undefined}
      onCancel={() => undefined}
      {...extra}
    />
  );
}

const box = () => found(() => screen.queryByRole('alertdialog', { name: '댓글 지우기' }), '확인 대화상자');

describe('ConfirmDialog error (4번 묶음)', () => {
  test('S4-AC-62 ConfirmDialog error: 주지 않으면 대화상자에 alert가 없고, 주면 대화상자 안에 role="alert"로 그 글이 보인다. 두 버튼은 그대로다', async () => {
    const { rerender } = render(confirm());
    expect(within(await box()).queryAllByRole('alert')).toHaveLength(0);

    rerender(confirm({ error: FAILED }));

    const alerts = within(await box()).queryAllByRole('alert');
    expect(alerts.map((a) => text(a))).toEqual([FAILED]);
    expect(within(await box()).getByText('지운 댓글은 되돌릴 수 없습니다.')).toBeInTheDocument();
    expect(within(await box()).getByRole('button', { name: '댓글 지우기' })).not.toHaveAttribute('aria-disabled', 'true');
    expect(within(await box()).getByRole('button', { name: '그만두기' })).toBeInTheDocument();
    await expectNoAxeViolations();
  });
});
