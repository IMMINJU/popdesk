// screens.md 5절 10 Toast. 5초 뒤 사라지고, 마우스·포커스가 있으면 멈춘다. 한 번에 셋까지.
import { act, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, test, vi } from 'vitest';
import { expectNoAxeViolations, text } from '../__tests__/support';
import { Toast, type ToastMessage } from './Toast';

const toast = (id: string, message: string, tone: ToastMessage['tone'] = 'success'): ToastMessage => ({ id, tone, message });

function region(): HTMLElement {
  const statuses = screen.getAllByRole('status');
  expect(statuses, 'role="status" 영역 하나').toHaveLength(1);
  return statuses[0] as HTMLElement;
}

function advance(ms: number) {
  act(() => {
    vi.advanceTimersByTime(ms);
  });
}

afterEach(() => {
  vi.useRealTimers();
});

describe('Toast', () => {
  test('Toast 영역: role="status"와 aria-live="polite" 하나에 메시지를 쌓는다', () => {
    render(<Toast toasts={[toast('1', '문의를 올렸습니다(ACME-7)'), toast('2', '복사했습니다', 'info')]} onDismiss={() => undefined} />);

    const r = region();
    expect(r).toHaveAttribute('aria-live', 'polite');
    expect(text(r)).toContain('문의를 올렸습니다(ACME-7)');
    expect(text(r)).toContain('복사했습니다');
  });

  test('Toast 셋까지: 넷이면 가장 오래된 것이 빠지고 새 셋이 남는다', () => {
    render(
      <Toast
        toasts={[toast('1', '첫째'), toast('2', '둘째'), toast('3', '셋째'), toast('4', '넷째')]}
        onDismiss={() => undefined}
      />,
    );

    const t = text(region());
    expect(t).not.toContain('첫째');
    for (const m of ['둘째', '셋째', '넷째']) expect(t).toContain(m);
  });

  test('Toast 5초: 5초가 지나면 onDismiss(id), 그 전에는 부르지 않는다', () => {
    vi.useFakeTimers();
    const onDismiss = vi.fn();
    render(<Toast toasts={[toast('a', '초대 메일을 보냈습니다')]} onDismiss={onDismiss} />);

    advance(4900);
    expect(onDismiss).not.toHaveBeenCalled();
    advance(200);
    expect(onDismiss).toHaveBeenCalledWith('a');
  });

  test('Toast durationMs: 준 시간 뒤에 사라진다', () => {
    vi.useFakeTimers();
    const onDismiss = vi.fn();
    render(<Toast toasts={[toast('a', '복사했습니다', 'info')]} onDismiss={onDismiss} durationMs={2000} />);

    advance(2100);
    expect(onDismiss).toHaveBeenCalledWith('a');
  });

  test('Toast 마우스: 마우스가 올라가 있으면 시간이 멈추고, 떠나면 다시 센다', () => {
    vi.useFakeTimers();
    const onDismiss = vi.fn();
    render(<Toast toasts={[toast('a', '저장했습니다')]} onDismiss={onDismiss} />);
    const item = screen.getByText('저장했습니다');

    // React reads mouseenter from mouseover; Testing Library fires both.
    fireEvent.pointerEnter(item);
    fireEvent.mouseEnter(item);
    advance(10_000);
    expect(onDismiss, '마우스가 있는 동안').not.toHaveBeenCalled();
    fireEvent.pointerLeave(item);
    fireEvent.mouseLeave(item);
    advance(5100);
    expect(onDismiss).toHaveBeenCalledWith('a');
  });

  test('Toast 포커스: 토스트 안에 포커스가 있으면 시간이 멈춘다', () => {
    vi.useFakeTimers();
    const onDismiss = vi.fn();
    render(<Toast toasts={[toast('a', '저장했습니다')]} onDismiss={onDismiss} />);
    const r = region();

    fireEvent.focusIn(r);
    advance(10_000);
    expect(onDismiss, '포커스가 있는 동안').not.toHaveBeenCalled();
    fireEvent.focusOut(r);
    advance(5100);
    expect(onDismiss).toHaveBeenCalledWith('a');
  });

  test('Toast axe: 성공·안내에 접근성 위반이 없다', async () => {
    render(<Toast toasts={[toast('1', '저장했습니다'), toast('2', '복사했습니다', 'info')]} onDismiss={() => undefined} />);

    await expectNoAxeViolations();
  });
});
