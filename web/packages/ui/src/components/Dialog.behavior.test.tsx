// screens.md 5절 11 Dialog (C-8 대화상자 규칙). C-10 변이 테스트가 찾은 빈칸:
// 포커스 가둠의 양 끝, 숨긴 칸 건너뛰기, 포커스가 밖으로 빠졌을 때의 Esc·Tab, 겹친 대화상자,
// 바깥 덮개, 닫기 버튼 없음, 보내는 중 aria-busy, 처음 포커스 순서.
import { fireEvent, render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { useRef, useState } from 'react';
import { describe, expect, test, vi } from 'vitest';
import { found } from '../__tests__/support';
import { Dialog, type DialogProps } from './Dialog';

function open(over: Partial<DialogProps> = {}) {
  return (
    <Dialog open title="고객사 만들기" closeLabel="닫기" onClose={() => undefined} {...over}>
      {over.children ?? (
        <>
          <input aria-label="이름" />
          <input aria-label="코드" />
        </>
      )}
    </Dialog>
  );
}

const dialog = (name = '고객사 만들기') => found(() => screen.queryByRole('dialog', { name }), `대화상자 "${name}"`);

describe('Dialog 동작', () => {
  test('Dialog 포커스 가둠: 마지막에서 Tab은 처음으로, 처음에서 Shift+Tab은 마지막으로, 가운데서는 다음 칸으로', async () => {
    render(open({ footer: <button type="button">만들기</button> }));
    const user = userEvent.setup();
    await dialog();
    const close = screen.getByRole('button', { name: '닫기' });
    const make = screen.getByRole('button', { name: '만들기' });
    const name = screen.getByRole('textbox', { name: '이름' });

    make.focus();
    await user.tab();
    expect(document.activeElement).toBe(close);

    await user.tab({ shift: true });
    expect(document.activeElement).toBe(make);

    name.focus();
    await user.tab();
    expect(document.activeElement).toBe(screen.getByRole('textbox', { name: '코드' }));

    name.focus();
    await user.tab({ shift: true });
    expect(document.activeElement).toBe(close);
  });

  test('Dialog 포커스 가둠: 숨긴(hidden) 부분의 버튼은 건너뛴다', async () => {
    render(
      open({
        children: (
          <>
            <button type="button">보내기</button>
            <div hidden>
              <button type="button">숨은 버튼</button>
            </div>
          </>
        ),
      }),
    );
    const user = userEvent.setup();
    await dialog();

    screen.getByRole('button', { name: '보내기' }).focus();
    await user.tab();
    expect(document.activeElement).toBe(screen.getByRole('button', { name: '닫기' }));
  });

  test('Dialog 처음 포커스: 칸이 없으면 아래 버튼 줄의 첫 버튼, initialFocus를 주면 그것', async () => {
    const { unmount } = render(open({ children: <p>지울까요?</p>, footer: <button type="button">지우기</button> }));
    await dialog();
    expect(document.activeElement).toBe(screen.getByRole('button', { name: '지우기' }));
    unmount();

    function WithInitial() {
      const ref = useRef<HTMLButtonElement>(null);
      return open({
        initialFocus: ref,
        footer: (
          <button ref={ref} type="button">
            취소
          </button>
        ),
      });
    }
    render(<WithInitial />);
    await dialog();
    expect(document.activeElement).toBe(screen.getByRole('button', { name: '취소' }));
  });

  test('Dialog 처음 포커스: 칸도 아래 버튼 줄도 없으면 닫기 버튼', async () => {
    render(open({ children: <p>안내</p> }));
    await dialog();

    expect(document.activeElement).toBe(screen.getByRole('button', { name: '닫기' }));
  });

  test('Dialog 밖으로 빠진 포커스: 포커스가 페이지로 떨어져도 Esc는 onClose, Tab은 대화상자 첫 칸으로', async () => {
    const onClose = vi.fn();
    render(open({ onClose }));
    const user = userEvent.setup();
    await dialog();

    (document.activeElement as HTMLElement).blur();
    expect(document.activeElement).toBe(document.body);
    await user.keyboard('{Tab}');
    expect(document.activeElement).toBe(screen.getByRole('textbox', { name: '이름' }));

    (document.activeElement as HTMLElement).blur();
    await user.keyboard('{Escape}');
    expect(onClose).toHaveBeenCalledTimes(1);
  });

  test('Dialog 안의 Esc: onClose를 한 번만 부른다', async () => {
    const onClose = vi.fn();
    render(open({ onClose }));
    const user = userEvent.setup();
    await dialog();

    await user.keyboard('{Escape}');
    expect(onClose).toHaveBeenCalledTimes(1);
  });

  test('Dialog 다른 키: 밖으로 빠진 포커스에서 Esc·Tab이 아닌 키는 아무것도 하지 않는다', async () => {
    const onClose = vi.fn();
    render(open({ onClose }));
    const user = userEvent.setup();
    await dialog();

    (document.activeElement as HTMLElement).blur();
    await user.keyboard('{Enter}a');
    expect(onClose).not.toHaveBeenCalled();
    expect(document.activeElement).toBe(document.body);
  });

  test('Dialog 겹침: 위 대화상자 안의 Esc는 위 것만 닫는다', async () => {
    const outerClose = vi.fn();
    function Nested() {
      const [inner, setInner] = useState(true);
      return (
        <Dialog open title="바깥" closeLabel="닫기" onClose={outerClose}>
          <input aria-label="바깥 칸" />
          <Dialog
            open={inner}
            title="안쪽"
            closeLabel="안쪽 닫기"
            onClose={() => {
              setInner(false);
            }}
          >
            <input aria-label="안쪽 칸" />
          </Dialog>
        </Dialog>
      );
    }
    render(<Nested />);
    const user = userEvent.setup();
    await dialog('안쪽');

    screen.getByRole('textbox', { name: '안쪽 칸' }).focus();
    await user.keyboard('{Escape}');
    expect(screen.queryByRole('dialog', { name: '안쪽' })).toBeNull();
    expect(outerClose).not.toHaveBeenCalled();
  });

  test('Dialog 겹침: 포커스가 밖으로 빠지면 Esc는 가장 나중에 연 대화상자에 간다', async () => {
    const firstClose = vi.fn();
    const secondClose = vi.fn();
    render(
      <>
        <Dialog open title="첫째" closeLabel="닫기" onClose={firstClose}>
          <input aria-label="첫째 칸" />
        </Dialog>
        <Dialog open title="둘째" closeLabel="닫기" onClose={secondClose}>
          <input aria-label="둘째 칸" />
        </Dialog>
      </>,
    );
    const user = userEvent.setup();
    await dialog('둘째');

    (document.activeElement as HTMLElement).blur();
    await user.keyboard('{Escape}');
    expect(secondClose).toHaveBeenCalledTimes(1);
    expect(firstClose).not.toHaveBeenCalled();
  });

  test('Dialog 닫힌 대화상자: 열린 대화상자 뒤에 닫힌 대화상자가 있어도 밖으로 빠진 Esc는 열린 것에 간다', async () => {
    const onClose = vi.fn();
    render(
      <>
        <Dialog open title="열림" closeLabel="닫기" onClose={onClose}>
          <input aria-label="칸" />
        </Dialog>
        <Dialog open={false} title="닫힘" onClose={() => undefined}>
          <input aria-label="닫힌 칸" />
        </Dialog>
      </>,
    );
    const user = userEvent.setup();
    await dialog('열림');

    (document.activeElement as HTMLElement).blur();
    await user.keyboard('{Escape}');
    expect(onClose).toHaveBeenCalledTimes(1);
  });

  test('Dialog 덮개: 대화상자 바깥 덮개를 누르면 onClose', async () => {
    const onClose = vi.fn();
    render(open({ onClose }));
    const d = await dialog();

    const overlay = d.previousElementSibling;
    expect(overlay, '덮개').not.toBeNull();
    fireEvent.click(overlay as Element);
    expect(onClose).toHaveBeenCalledTimes(1);
  });

  test('Dialog 닫기 버튼 없음: closable={false}면 머리에 닫기 버튼이 없다', async () => {
    render(open({ closable: false }));
    await dialog();

    expect(screen.queryByRole('button', { name: '닫기' })).toBeNull();
  });

  test('Dialog 보내는 중: pending이면 대화상자에 aria-busy="true", 아니면 없다', async () => {
    const { rerender } = render(open({ pending: true }));
    expect(await dialog()).toHaveAttribute('aria-busy', 'true');

    rerender(open());
    expect(await dialog()).not.toHaveAttribute('aria-busy');
  });
});
