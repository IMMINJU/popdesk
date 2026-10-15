// 6b 묶음 screens.md 4절 C-3 문의 목록: 열린 작업이 있는 문의의 제목 칸에 "작업 n"(중립 배지). 0이면 없고 새 열도 없다(S6B-AC-88).
import { waitFor } from '@testing-library/react';
import { describe, expect, test } from 'vitest';
import { expectNoAxeViolations, renderAt, screen, state, summary, text, within } from '../__tests__/console';
import { issueRow } from '../__tests__/console3';

describe('C-3 문의 목록 열린 작업 수 (6b 묶음)', () => {
  test('S6B-AC-88 C-3 "작업 n": openTaskCount가 3인 줄의 제목 칸에 "작업 3"이 있고 0인 줄에는 없다. 열 이름에 "작업"이 없다', async () => {
    state.issues = [summary({ key: 'ACME-7', openTaskCount: 3 }), summary({ key: 'ACME-8', title: '결제가 두 번 됩니다', openTaskCount: 0 })];
    renderAt('/issues');
    await waitFor(
      () => {
        expect(screen.queryAllByRole('row').some((r) => text(r).includes('ACME-8')), 'C-3의 ACME-8 줄').toBe(true);
      },
      { timeout: 5000 },
    );

    const seven = await issueRow('ACME-7');
    const titleCell = within(seven)
      .queryAllByRole('cell')
      .find((c) => text(c).includes('로그인이 안 됩니다'));
    expect(titleCell, 'ACME-7의 제목 칸').toBeDefined();
    expect(text(titleCell)).toContain('작업 3');
    expect(text(await issueRow('ACME-8'))).not.toMatch(/작업 \d+/);
    expect(
      screen.queryAllByRole('columnheader').map((h) => text(h)),
      '새 열은 없다',
    ).not.toContain('작업');
    await expectNoAxeViolations();
  });
});
