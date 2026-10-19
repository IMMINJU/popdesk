// b2 묶음 screens.md 1절 C-9 소속 고객 표의 익명 고객: 상태 칸 "익명화됨", "담당자로 지정" 없음, 담당자이면 "담당자 해제",
// 지정이 400 ACCOUNT_ANONYMIZED이면 C-10과 같은 띠와 다시 읽기(B2-AC-04). 익명 여부는 status가 아닌 anonymized로 본다.
import { describe, expect, test } from 'vitest';
import { callsTo, eventually, expectNoAxeViolations, found, member, renderAt, screen, sent, state, text, within } from '../__tests__/console';
import { ANONYMIZED_BADGE, ALREADY, ANON_NAME, anonEmail } from '../__tests__/console10a';
import { region, route, rowOf } from '../__tests__/console1a';
import { cellUnder } from '../__tests__/console2';
import { rejectedWith } from '../__tests__/console6a';

const URL_ACME = '/companies/ACME';
const CUSTOMERS = '/api/console/companies/ACME/customers';
const managerPath = (id: number) => `${CUSTOMERS}/${String(id)}/manager`;

/** An anonymized customer as the server gives it: anonymous name and email, SUSPENDED, anonymized. */
const anon = (accountId: number, manager = false) =>
  member({ accountId, name: ANON_NAME, email: anonEmail(accountId), status: 'SUSPENDED', anonymized: true, manager });

const GONE = anon(31);
const GONE_MANAGER = anon(32, true);
const STOPPED = member({ accountId: 33, email: 'jung@acme.test', name: '정정지', status: 'SUSPENDED' });
const KIM = member({ accountId: 11, email: 'kim@acme.test', name: '김고객' });

const customers = () => region(/소속 고객/);

/** The names of the buttons in the row that holds `key` (a name or an email), in the order drawn. */
async function buttonsOf(key: string): Promise<string[]> {
  const row = await rowOf(key, await customers());
  return within(row)
    .queryAllByRole('button')
    .map((b) => text(b));
}

/** The text of one column of the row that holds `key`. */
async function cellOf(key: string, column: string): Promise<string | null> {
  const list = await customers();
  const cell = cellUnder(list, await rowOf(key, list), column);
  return cell ? text(cell) : null;
}

const customerReads = () => callsTo('GET', CUSTOMERS).length;

describe('C-9 소속 고객 표의 익명 고객 (b2 묶음)', () => {
  test('B2-AC-04 C-9 익명 고객: 상태 칸 "익명화됨", 이름·이메일은 받은 값, "담당자로 지정"이 없고 "빼기"는 있다', async () => {
    state.customers = [GONE, KIM];
    renderAt(URL_ACME);

    expect(await cellOf(GONE.email, '계정 상태')).toBe(ANONYMIZED_BADGE);
    expect(await cellOf(GONE.email, '이름')).toContain(ANON_NAME);
    expect(await cellOf(GONE.email, '이메일')).toBe(GONE.email);
    expect(await buttonsOf(GONE.email)).toEqual(['빼기']);
    expect(await buttonsOf('김고객'), '익명 아닌 고객').toEqual(['담당자로 지정', '빼기']);
  });

  test('B2-AC-04 C-9 익명이면서 담당자인 고객: 상태 칸 "익명화됨", 담당자 배지, "담당자 해제"가 있다', async () => {
    state.customers = [GONE_MANAGER, KIM];
    renderAt(URL_ACME);

    expect(await cellOf(GONE_MANAGER.email, '계정 상태')).toBe(ANONYMIZED_BADGE);
    expect(await cellOf(GONE_MANAGER.email, '담당자')).toBe('담당자');
    expect(await buttonsOf(GONE_MANAGER.email)).toEqual(['담당자 해제', '빼기']);
  });

  test('B2-AC-04 C-9 정지 고객(익명 아님): 지금 그대로 상태 칸 "정지"와 "담당자로 지정". 익명 고객과 한 표에 있어도 status로 익명을 짐작하지 않는다', async () => {
    state.customers = [STOPPED, GONE];
    renderAt(URL_ACME);

    expect(await cellOf(GONE.email, '계정 상태')).toBe(ANONYMIZED_BADGE);
    expect(await cellOf('정정지', '계정 상태')).toBe('정지');
    expect(await buttonsOf('정정지')).toEqual(['담당자로 지정', '빼기']);
  });

  test('B2-AC-04 C-9 담당자로 지정이 400 ACCOUNT_ANONYMIZED: 표 밖 띠에 "이미 익명화한 계정입니다."를 보이고 표를 다시 읽어 그 줄이 "익명화됨"이 된다', async () => {
    state.customers = [KIM];
    route('put', managerPath(KIM.accountId), () => rejectedWith('markCompanyManager', 'ACCOUNT_ANONYMIZED'));
    const { user } = renderAt(URL_ACME);
    const row = await rowOf('김고객', await customers());
    // Another admin anonymized 김고객 after this table was read.
    state.customers = [anon(KIM.accountId)];
    const before = customerReads();

    await user.click(await found(() => within(row).queryByRole('button', { name: '담당자로 지정' }), '김고객 줄의 "담당자로 지정"'));

    await sent('PUT', managerPath(KIM.accountId));
    const band = await found(() => screen.queryAllByRole('alert').find((a) => text(a).includes(ALREADY)), `띠 "${ALREADY}"`);
    expect(band.closest('table'), '띠는 표 밖').toBeNull();
    await eventually(() => {
      expect(customerReads(), '소속 고객 표를 다시 읽음').toBeGreaterThan(before);
    });
    expect(await cellOf(anonEmail(KIM.accountId), '계정 상태')).toBe(ANONYMIZED_BADGE);
    expect(await buttonsOf(anonEmail(KIM.accountId))).toEqual(['빼기']);
  });

  test('B2-AC-04 C-9 axe (b2):익명 고객, 익명이면서 담당자, 정지 고객이 있는 소속 고객 표에 접근성 위반이 없다', async () => {
    state.customers = [GONE, GONE_MANAGER, STOPPED, KIM];
    renderAt(URL_ACME);
    expect(await cellOf(GONE.email, '계정 상태')).toBe(ANONYMIZED_BADGE);

    await expectNoAxeViolations();
  });
});
