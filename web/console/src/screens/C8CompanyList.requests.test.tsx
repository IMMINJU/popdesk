// 2번 묶음 screens.md 2절 C-8 고객사 목록의 "소속 요청" 칸: 걸려 있는 요청의 수를 배지로, 없으면 빈칸(S2-AC-48).
import { describe, expect, test } from 'vitest';
import { ACME, BETA, atPath, found, hrefPath, renderAt, screen, state, text, within } from '../__tests__/console';
import { region } from '../__tests__/console1a';
import { cellUnder, pending, request } from '../__tests__/console2';

async function rowOf(code: string): Promise<HTMLElement> {
  return found(
    () => screen.queryAllByRole('row').find((r) => within(r).queryAllByRole('cell').some((c) => text(c).includes(code))),
    `${code} 줄`,
  );
}

describe('C-8 고객사 목록 (2번 묶음)', () => {
  test('S2-AC-48 C-8 "소속 요청" 칸: 수가 있으면 배지, 0이면 빈칸. 누르면 그 고객사 상세의 소속 요청 구역으로 간다', async () => {
    state.companies = [ACME, BETA];
    pending.requests = [request({ id: 301 }), request({ id: 302, name: '정입력' }), request({ id: 303, name: '이입력' })];
    const { user } = renderAt('/companies');

    const acme = await rowOf('ACME');
    const table = screen.getByRole('table');
    expect(
      within(table)
        .getAllByRole('columnheader')
        .map((h) => text(h)),
    ).toContain('소속 요청');
    const count = cellUnder(table, acme, '소속 요청');
    expect(text(count)).toContain('3');
    expect(text(cellUnder(table, await rowOf('BETA'), '소속 요청'))).toBe('');
    const toRequests = count?.querySelector('a') ?? null;
    expect(toRequests ? hrefPath(toRequests) : null, '배지의 링크').toBe('/companies/ACME');

    if (toRequests) await user.click(toRequests);

    await atPath('/companies/ACME');
    await region(/소속 요청/);
  });
});
