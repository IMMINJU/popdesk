// b6 묶음 screens.md 4절 C-7 자동 정리 켜짐 항목의 영어: count 1, days 1이면 단수 "1 issue", "1 day", 동사 "moves"이고
// 머리는 "Automatic issue cleanup is on."이다(B6-AC-05). 영어 계정은 b5의 도움(consoleB5.ts)으로 연다.
import { waitFor } from '@testing-library/react';
import { beforeEach, describe, expect, test } from 'vitest';
import { renderAt, screen, text, within } from '../__tests__/console';
import { s4 } from '../__tests__/console4';
import { armedItem } from '../__tests__/console10b';
import { openInEnglish } from '../__tests__/consoleB5';

/** screens.md 4절의 문장, count 1과 days 1로 채운 것. */
const ONE_ONE = 'Automatic issue cleanup is on. From tomorrow, 1 issue closed more than 1 day ago moves to the trash.';

beforeEach(() => {
  openInEnglish();
});

describe('C-7 자동 정리 켜짐 항목의 영어 (b6 묶음)', () => {
  test('B6-AC-05 C-7 영어 count 1, days 1: "1 issue", "1 day", "moves"이고 "1 issues", "1 days"가 없다', async () => {
    s4.inbox = [armedItem(1, 1, { id: 881 })];
    renderAt('/inbox');

    let said = '';
    await waitFor(
      () => {
        const main = screen.queryByRole('main');
        const links = main ? within(main).queryAllByRole('link').filter((l) => /cleanup/i.test(text(l))) : [];
        expect(links, '자동 정리 항목').toHaveLength(1);
        said = text(links[0]);
      },
      { timeout: 5000 },
    );

    expect(said).toContain(ONE_ONE);
    expect(said).not.toContain('1 issues');
    expect(said).not.toContain('1 days');
  });
});
