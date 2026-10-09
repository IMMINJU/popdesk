// screens.md 5절 10 Toast. C-10 변이 테스트가 찾은 빈칸: 멈췄다 다시 셀 때 남은 시간, 토스트 안에서 옮겨 간 포커스.
import { act, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, test, vi } from 'vitest';
import { Toast } from './Toast';

function advance(ms: number) {
  act(() => {
    vi.advanceTimersByTime(ms);
  });
}

afterEach(() => {
  vi.useRealTimers();
});

describe('Toast 동작', () => {
  test('Toast 남은 시간: 3초 뒤 멈췄다가 다시 세면 남은 2초가 지나야 사라진다', () => {
    vi.useFakeTimers();
    const onDismiss = vi.fn();
    render(<Toast toasts={[{ id: 'a', tone: 'success', message: '저장했습니다' }]} onDismiss={onDismiss} />);
    const item = screen.getByText('저장했습니다');

    advance(3000);
    fireEvent.mouseEnter(item);
    advance(60_000);
    fireEvent.mouseLeave(item);
    advance(1900);
    expect(onDismiss, '남은 2초가 지나기 전').not.toHaveBeenCalled();
    advance(200);
    expect(onDismiss).toHaveBeenCalledWith('a');
  });

  test('Toast 안에서 옮긴 포커스: 포커스가 토스트 영역 안에서 옮겨 가면 계속 멈춰 있다', () => {
    vi.useFakeTimers();
    const onDismiss = vi.fn();
    render(
      <Toast
        toasts={[
          { id: 'a', tone: 'success', message: '저장했습니다' },
          { id: 'b', tone: 'info', message: '복사했습니다' },
        ]}
        onDismiss={onDismiss}
      />,
    );
    const region = screen.getByRole('status');

    fireEvent.focusIn(region);
    fireEvent.focusOut(region, { relatedTarget: screen.getByText('복사했습니다') });
    advance(10_000);
    expect(onDismiss).not.toHaveBeenCalled();
  });
});
