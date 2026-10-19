// b2 묶음 screens.md 2절 C-7 자동 정리 켜짐 항목: retentionCount가 0이면 따로 둔 문장, 1 이상이면 10b 문장 그대로(B2-AC-06).
import { waitFor } from '@testing-library/react';
import { describe, expect, test } from 'vitest';
import { renderAt, screen, text, within } from '../__tests__/console';
import { armedItem, armedText } from '../__tests__/console10b';
import { inboxOf } from '../__tests__/console8b';

/** screens.md 2절의 0건 문장. */
const noneText = (days: number) => `문의 자동 정리가 켜졌습니다. 지금은 종료된 지 ${String(days)}일이 지난 문의가 없습니다.`;

/** The text of the one inbox link in `main`, waited for generously. */
async function onlyItemText(): Promise<string> {
  let said = '';
  await waitFor(
    () => {
      const main = screen.queryByRole('main');
      const links = main ? within(main).queryAllByRole('link').filter((l) => text(l).includes('문의 자동 정리')) : [];
      expect(links, '자동 정리 항목').toHaveLength(1);
      said = text(links[0]);
    },
    { timeout: 5000 },
  );
  return said;
}

describe('C-7 자동 정리 켜짐 항목의 문장 (b2 묶음)', () => {
  test.each([90, 180])('B2-AC-06 C-7 retentionCount 0, %i일: "지금은 종료된 지 N일이 지난 문의가 없습니다."이고 "0건"이나 "옮깁니다"가 없다', async (days) => {
    inboxOf([armedItem(days, 0, { id: 871 })]);
    renderAt('/inbox');

    const said = await onlyItemText();

    expect(said).toContain(noneText(days));
    expect(said).not.toContain('0건');
    expect(said).not.toContain('옮깁니다');
  });

  test('B2-AC-06 C-7 retentionCount 3: 10b 문장 그대로 "… 문의 3건을 휴지통으로 옮깁니다"', async () => {
    inboxOf([armedItem(90, 3, { id: 872 })]);
    renderAt('/inbox');

    const said = await onlyItemText();

    expect(said).toContain(armedText(90, 3));
    expect(said).not.toContain('지금은');
  });
});
