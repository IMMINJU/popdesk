// screens.md 5절 11 Dialog. 포커스 가둠, Esc, 돌아갈 포커스(C-8 대화상자 규칙).
import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { useState } from 'react';
import { describe, expect, test, vi } from 'vitest';
import { describedText, expectNoAxeViolations, found, text } from '../__tests__/support';
import { Dialog } from './Dialog';

function Harness(props: { onClose?: () => void; pending?: boolean }) {
  const [open, setOpen] = useState(false);
  return (
    <>
      <button type="button" onClick={() => {
          setOpen(true);
        }}>
        고객사 만들기
      </button>
      <Dialog
        open={open}
        title="고객사 만들기"
        description="코드는 나중에 바꿀 수 없습니다"
        closeLabel="닫기"
        pending={props.pending ?? false}
        onClose={() => {
          props.onClose?.();
          setOpen(false);
        }}
        footer={
          <button type="submit" form="company">
            만들기
          </button>
        }
      >
        <form id="company">
          <label>
            이름
            <input name="name" />
          </label>
          <label>
            코드
            <input name="code" />
          </label>
        </form>
      </Dialog>
    </>
  );
}

const dialog = () => found(() => screen.queryByRole('dialog', { name: '고객사 만들기' }), '대화상자 "고객사 만들기"');

async function openIt() {
  const user = userEvent.setup();
  await user.click(screen.getByRole('button', { name: '고객사 만들기' }));
  return user;
}

describe('Dialog', () => {
  test('Dialog 닫힘·열림: 닫혀 있으면 없고, 열면 제목이 이름인 모달 대화상자, 설명을 aria-describedby로', async () => {
    render(<Harness />);
    expect(screen.queryByRole('dialog')).toBeNull();

    await openIt();
    const d = await dialog();
    expect(d).toHaveAttribute('aria-modal', 'true');
    expect(describedText(d)).toContain('코드는 나중에 바꿀 수 없습니다');
  });

  test('Dialog 처음 포커스: 열면 첫 칸에 포커스', async () => {
    render(<Harness />);
    await openIt();
    const d = await dialog();

    expect(document.activeElement).toBe(within(d).getByRole('textbox', { name: '이름' }));
  });

  test('Dialog 포커스 가둠: Tab·Shift+Tab이 대화상자 안을 돈다', async () => {
    render(<Harness />);
    const user = await openIt();
    const d = await dialog();

    for (let i = 0; i < 6; i += 1) {
      await user.tab();
      expect(d.contains(document.activeElement), `Tab ${String(i + 1)}번째`).toBe(true);
    }
    for (let i = 0; i < 6; i += 1) {
      await user.tab({ shift: true });
      expect(d.contains(document.activeElement), `Shift+Tab ${String(i + 1)}번째`).toBe(true);
    }
  });

  test('Dialog Esc: Esc를 누르면 onClose', async () => {
    const onClose = vi.fn();
    render(<Harness onClose={onClose} />);
    const user = await openIt();
    await dialog();

    await user.keyboard('{Escape}');
    expect(onClose).toHaveBeenCalledTimes(1);
  });

  test('Dialog 돌아갈 포커스: 닫으면 연 버튼으로 포커스가 돌아간다', async () => {
    render(<Harness />);
    const user = await openIt();
    const d = await dialog();

    await user.click(within(d).getByRole('button', { name: '닫기' }));
    expect(screen.queryByRole('dialog')).toBeNull();
    expect(document.activeElement).toBe(screen.getByRole('button', { name: '고객사 만들기' }));
  });

  test('Dialog 닫기 버튼: closeLabel이 이름이고 "×" 글자는 읽지 않는다', async () => {
    render(<Harness />);
    await openIt();
    const d = await dialog();

    const close = within(d).getByRole('button', { name: '닫기' });
    expect(close.querySelector('[aria-hidden="true"]') ?? (text(close) === '' ? close : null), '× 는 aria-hidden').not.toBeNull();
  });

  test('Dialog 보내는 중: 닫기는 그대로 된다', async () => {
    const onClose = vi.fn();
    render(<Harness pending onClose={onClose} />);
    const user = await openIt();
    const d = await dialog();

    await user.click(within(d).getByRole('button', { name: '닫기' }));
    expect(onClose).toHaveBeenCalledTimes(1);
  });

  test('Dialog axe: 열린 대화상자에 접근성 위반이 없다', async () => {
    render(<Harness />);
    await openIt();
    await dialog();

    await expectNoAxeViolations();
  });
});
