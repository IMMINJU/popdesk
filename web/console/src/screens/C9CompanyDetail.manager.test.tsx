// 2번 묶음 screens.md 2절 C-9 고객사 상세의 소속 고객 표: 담당자 배지, "담당자로 지정"·"담당자 해제",
// 보관된 고객사, 담당자를 뺄 때의 확인 문구(S2-AC-06, 48).
// 지정·해제는 줄 안의 버튼이고 "빼기" 앞에 온다. 이름에 사람 이름을 넣지 않는다(screens.md 5절).
import { describe, expect, test } from 'vitest';
import { company, dialog, eventually, found, member, openDialog, renderAt, screen, sent, state, text, within } from '../__tests__/console';
import { region, rowOf } from '../__tests__/console1a';
import { cellUnder, said } from '../__tests__/console2';

const URL_ACME = '/companies/ACME';
const MANAGER = '/api/console/companies/ACME/customers/11/manager';
const MANAGER_NOTE = '이 사람은 담당자입니다. 빼면 담당자 표시도 사라집니다';

const KIM = member({ accountId: 11, email: 'kim@acme.test', name: '김고객' });
const KIM_MANAGER = member({ ...KIM, manager: true });
const CHOI = member({ accountId: 12, email: 'choi@acme.test', name: '최동료' });

type User = ReturnType<typeof renderAt>['user'];

const customers = () => region(/소속 고객/);

/** The names of the buttons in one customer's row, in the order drawn. */
async function buttonsOf(name: string): Promise<string[]> {
  const row = await rowOf(name, await customers());
  return within(row)
    .queryAllByRole('button')
    .map((b) => text(b));
}

/** The text of the 담당자 cell of one customer, as the table shows it now; null without the row or the column. */
function badgeNow(name: string): string | null {
  const list = screen.queryByRole('region', { name: /소속 고객/ });
  const row = list ? within(list).queryAllByRole('row').find((r) => within(r).queryAllByRole('cell').some((c) => text(c).includes(name))) : undefined;
  const cell = list && row ? cellUnder(list, row, '담당자') : null;
  return cell ? text(cell) : null;
}

async function badgeOf(name: string): Promise<string | null> {
  await rowOf(name, await customers());
  return badgeNow(name);
}

/** Opens 빼기 of one customer and returns the confirm dialog. */
async function openRemove(user: User, name: string): Promise<HTMLElement> {
  const row = await rowOf(name, await customers());
  await user.click(await found(() => within(row).queryByRole('button', { name: /빼기/ }), `${name} 줄의 "빼기"`));
  return dialog();
}

describe('C-9 고객사 상세: 소속 고객의 담당자 (2번 묶음)', () => {
  test('S2-AC-48 C-9 소속 고객 표: 담당자 칸에 담당자이면 "담당자" 배지, 아니면 빈칸', async () => {
    state.customers = [KIM_MANAGER, CHOI];
    renderAt(URL_ACME);

    expect(await badgeOf('김고객')).toBe('담당자');
    expect(await badgeOf('최동료')).toBe('');
  });

  test.each([
    { what: '담당자로 지정', before: KIM, method: 'PUT', toast: '담당자로 지정했습니다', badge: '담당자' },
    { what: '담당자 해제', before: KIM_MANAGER, method: 'DELETE', toast: '담당자에서 해제했습니다', badge: '' },
  ])('S2-AC-48 C-9 줄의 동작 "$what": "빼기" 앞의 버튼이고, 확인 없이 바로 하고 토스트 "$toast"', async ({ what, before, method, toast, badge }) => {
    state.customers = [before, CHOI];
    const { user } = renderAt(URL_ACME);
    const row = await rowOf('김고객', await customers());
    expect(await buttonsOf('김고객')).toEqual([what, '빼기']);

    await user.click(await found(() => within(row).queryByRole('button', { name: what }), `김고객 줄의 "${what}"`));

    await sent(method, MANAGER);
    await said(toast);
    expect(openDialog(), '확인 대화상자').toBeNull();
    await eventually(() => {
      expect(badgeNow('김고객')).toBe(badge);
    });
  });

  test('S2-AC-06 S2-AC-48 C-9 보관된 고객사: "담당자 해제"는 있고 "담당자로 지정"은 없다', async () => {
    state.companies = [company({ status: 'ARCHIVED' })];
    state.customers = [KIM_MANAGER, CHOI];
    renderAt(URL_ACME);

    expect(await buttonsOf('김고객')).toEqual(['담당자 해제', '빼기']);
    expect(await buttonsOf('최동료')).toEqual(['빼기']);
  });

  test('S2-AC-48 C-9 담당자를 소속에서 뺄 때: 확인 문구에 "이 사람은 담당자입니다. 빼면 담당자 표시도 사라집니다"를 더한다. 담당자가 아니면 없다', async () => {
    state.customers = [KIM_MANAGER, CHOI];
    const { user } = renderAt(URL_ACME);

    const first = await openRemove(user, '김고객');
    expect(text(first)).toContain(MANAGER_NOTE);
    await user.click(within(first).getByRole('button', { name: '취소' }));
    await eventually(() => {
      expect(openDialog()).toBeNull();
    });

    const second = await openRemove(user, '최동료');
    expect(text(second)).toContain('최동료');
    expect(text(second)).not.toContain(MANAGER_NOTE);
  });
});
