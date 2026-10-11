// 2번 묶음 screens.md 2절 P-6 내 정보: 소속 고객사 목록에서 내가 담당자인 고객사 옆에 "담당자" 배지(S2-AC-07).
import { describe, expect, test } from 'vitest';
import { found, renderAt, text, within } from '../__tests__/portal';
import { region, signInWith } from '../__tests__/portal1a';

async function companyItem(name: string): Promise<HTMLElement> {
  const profile = await region('프로필');
  return found(() => within(profile).queryAllByRole('listitem').find((li) => text(li).includes(name)), `소속 고객사 "${name}"`);
}

describe('P-6 내 정보 (2번 묶음)', () => {
  test('S2-AC-07 P-6 소속 고객사: 내가 담당자인 고객사 옆에만 "담당자" 배지', async () => {
    signInWith({
      companies: [
        { code: 'ACME', name: '에이크미', status: 'ACTIVE', manager: true },
        { code: 'BETA', name: '베타상사', status: 'ACTIVE', manager: false },
      ],
    });
    renderAt('/me');

    expect(text(await companyItem('베타상사'))).not.toContain('담당자');
    expect(text(await companyItem('에이크미'))).toContain('담당자');
  });
});
