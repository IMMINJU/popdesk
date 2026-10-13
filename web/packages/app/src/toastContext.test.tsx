// screens.md 2절 저장 성공: 화면은 `useToast`로 받은 함수로 토스트를 띄운다. 토스트 틀 밖에서는 아무 일도 하지 않는다.
import { renderHook } from '@testing-library/react';
import type { ReactNode } from 'react';
import { describe, expect, it, vi } from 'vitest';
import { ToastContext, useToast, type ShowToast } from './toastContext';

describe('useToast', () => {
  it('screens.md 2절 틀 밖: 불러도 오류가 없다', () => {
    const { result } = renderHook(() => useToast());

    expect(() => {
      result.current('저장했습니다');
      result.current('메일을 보냈습니다', 'info');
    }).not.toThrow();
  });

  it('screens.md 2절 틀 안: 틀이 준 함수 그대로다', () => {
    const show = vi.fn<ShowToast>();
    const wrapper = ({ children }: { children: ReactNode }) => <ToastContext value={show}>{children}</ToastContext>;

    const { result } = renderHook(() => useToast(), { wrapper });
    result.current('저장했습니다', 'info');

    expect(result.current).toBe(show);
    expect(show.mock.calls).toEqual([['저장했습니다', 'info']]);
  });
});
