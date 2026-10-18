// 10a 묶음 C-9 고객사 상세의 소속 더하기: 고객 찾기는 anonymized=false로 불러 익명 계정을 보이지 않는다(S10A-AC-36).
import { describe, expect, test } from 'vitest';
import { account, callsTo, dialog, found, member, renderAt, sent, state, within } from '../__tests__/console';
import { region } from '../__tests__/console1a';
import { ANON_NAME, anonymous } from '../__tests__/console10a';

describe('C-9 고객사 상세의 고객 찾기 (10a 묶음)', () => {
  test('S10A-AC-36 C-9 소속 더하기: 찾기 요청에 anonymized=false를 싣고, 익명 계정은 선택지에 없다', async () => {
    state.customers = [member()];
    state.accounts = [
      account(),
      account({ id: 13, email: 'park@acme.test', name: '박신규', role: 'CUSTOMER' }),
      anonymous(14, { email: 'deleted-14@invalid' }),
    ];
    const { user } = renderAt('/companies/ACME');
    const list = await region(/소속 고객/);
    await user.click(await found(() => within(list).queryByRole('button', { name: /더하기/ }), '소속 고객의 "더하기"'));
    const box = await dialog();
    // 둘 다에 맞는 글자: 익명 이메일과 박신규의 이메일 모두 "e"를 가진다.
    await user.type(await found(() => within(box).queryByRole('searchbox') ?? within(box).queryByRole('textbox'), '찾기 칸'), 'e');

    await found(() => within(box).queryByRole('radio', { name: /박신규/ }), '찾은 고객 "박신규"');
    await sent('GET', '/api/console/accounts');
    const query = new URL(callsTo('GET', '/api/console/accounts').at(-1)?.url ?? 'http://x/').searchParams;
    expect(query.get('role')).toBe('CUSTOMER');
    expect(query.get('anonymized')).toBe('false');
    expect(within(box).queryByRole('radio', { name: new RegExp(ANON_NAME) }), '익명 계정').toBeNull();
  });
});
