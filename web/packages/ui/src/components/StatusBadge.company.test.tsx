// 1a design.md 5-1절 상태 배지: 고객사 말(사용 중, 중지, 보관)은 kind `company`,
// "초대 취소됨"은 끝난 상태처럼 중립 색의 속 빈 점이다(screens.md C-8·C-10, tokens.md 7절).
import { render } from '@testing-library/react';
import { describe, expect, test } from 'vitest';
import { spokenText } from '../__tests__/support';
import type { AccountStatus } from '../types';
import { StatusBadge } from './StatusBadge';

/** The dot is the one decorative part of the badge; a hollow one says so with `data-hollow`. */
function dotOf(status: AccountStatus): { hollow: boolean; word: string } {
  const { container, unmount } = render(<StatusBadge kind="account" status={status} />);
  const dot = container.querySelector('[aria-hidden="true"]');
  const shown = { hollow: dot?.getAttribute('data-hollow') === 'true', word: spokenText(container) };
  unmount();
  return shown;
}

describe('StatusBadge 1a', () => {
  test('S1A-AC-49 StatusBadge 고객사 상태(kind company): ACTIVE 사용 중, SUSPENDED 중지, ARCHIVED 보관', () => {
    const words = { ACTIVE: '사용 중', SUSPENDED: '중지', ARCHIVED: '보관' } as const;
    for (const [status, word] of Object.entries(words) as [keyof typeof words, string][]) {
      const { container, unmount } = render(<StatusBadge kind="company" status={status} />);
      expect(spokenText(container), status).toBe(word);
      unmount();
    }
  });

  test('S1A-AC-42 StatusBadge 초대 취소됨: 글자는 "초대 취소됨", 점은 수락 전처럼 속이 비고 사용 중·정지는 속이 찬다', () => {
    expect(dotOf('INVITE_CANCELLED')).toEqual({ hollow: true, word: '초대 취소됨' });
    expect(dotOf('INVITED').hollow, '수락 전').toBe(true);
    expect(dotOf('ACTIVE').hollow, '사용 중').toBe(false);
    expect(dotOf('SUSPENDED').hollow, '정지').toBe(false);
  });
});
