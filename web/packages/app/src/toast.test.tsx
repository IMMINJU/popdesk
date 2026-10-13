// screens.md 2절 저장 성공: 토스트는 공손한 알림 영역 하나에 쌓이고, 가장 새 것 셋만 남는다.
import { act, render, screen, within } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { ToastProvider } from './toast';
import { useToast, type ShowToast } from './toastContext';

function drawn() {
  const held: { show: ShowToast; shows: ShowToast[] } = { show: () => undefined, shows: [] };
  function Screen() {
    held.show = useToast();
    held.shows.push(held.show);
    return <p>문의 화면</p>;
  }
  render(
    <ToastProvider>
      <Screen />
    </ToastProvider>,
  );
  const say = (...args: Parameters<ShowToast>) => {
    act(() => {
      held.show(...args);
    });
  };
  const stack = () => screen.getByRole('status');
  const shown = () => Array.from(stack().children).map((el) => [el.textContent, el.getAttribute('data-tone')]);
  return { held, say, stack, shown };
}

describe('ToastProvider', () => {
  it('screens.md 2절 처음: 안쪽 화면을 그리고 빈 알림 영역 하나를 둔다', () => {
    const { stack } = drawn();

    expect(screen.getByText('문의 화면')).toBeInTheDocument();
    expect(stack()).toBeEmptyDOMElement();
    expect(stack()).toHaveAttribute('aria-live', 'polite');
    expect(stack()).not.toContainElement(screen.getByText('문의 화면'));
  });

  it('screens.md 2절 저장 성공: 글을 알림 영역에 보인다. 결을 주지 않으면 성공이다', () => {
    const { say, stack, shown } = drawn();

    say('저장했습니다');

    expect(within(stack()).getByText('저장했습니다')).toBeInTheDocument();
    expect(shown()).toEqual([['저장했습니다', 'success']]);
  });

  it('screens.md 2절 안내 결을 주면 안내로 보인다', () => {
    const { say, shown } = drawn();

    say('메일을 보냈습니다', 'info');
    say('저장했습니다', 'success');

    expect(shown()).toEqual([
      ['메일을 보냈습니다', 'info'],
      ['저장했습니다', 'success'],
    ]);
  });

  it('screens.md 2절 넷째가 오면 가장 오래된 것이 빠지고 새 것이 맨 뒤다. 같은 글도 따로 쌓인다', () => {
    const { say, shown } = drawn();

    say('하나');
    say('둘');
    say('둘');
    expect(shown().map(([message]) => message)).toEqual(['하나', '둘', '둘']);

    say('넷');
    expect(shown().map(([message]) => message)).toEqual(['둘', '둘', '넷']);

    say('다섯');
    expect(shown().map(([message]) => message)).toEqual(['둘', '넷', '다섯']);
  });

  it('screens.md 2절 시간이 지나면 그 토스트만 사라진다', () => {
    vi.useFakeTimers();
    const { say, shown } = drawn();
    say('먼저');
    act(() => {
      vi.advanceTimersByTime(3000);
    });
    say('나중');

    act(() => {
      vi.advanceTimersByTime(2500);
    });
    expect(shown().map(([message]) => message)).toEqual(['나중']);

    act(() => {
      vi.advanceTimersByTime(3000);
    });
    expect(shown()).toEqual([]);
    vi.useRealTimers();
  });

  it('screens.md 2절 띄우는 함수는 토스트가 떠도 같은 함수로 남는다', () => {
    const { held, say } = drawn();

    say('저장했습니다');

    expect(held.shows.length).toBeGreaterThanOrEqual(1);
    expect(new Set(held.shows).size).toBe(1);
  });
});
