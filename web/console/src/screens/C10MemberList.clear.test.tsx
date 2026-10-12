// 3번 묶음 screens.md 2절 C-10 구성원 목록: 정지 대화상자의 담당 비우기(S3-AC-85)와 필터를 주소에 싣기(S3-AC-86).
import { describe, expect, test } from 'vitest';
import { account, callsTo, eventually, expectNoAxeViolations, found, renderAt, screen, sent, state, text, within } from '../__tests__/console';
import { closed, openRowAction, rowOf, settle } from '../__tests__/console1a';
import { expectNotSaid, said } from '../__tests__/console2';
import { chosen, s3, select } from '../__tests__/console3';

const ACCOUNTS = '/api/console/accounts';
const CLEAR = '이 문의들의 담당을 비웁니다';

const LEE = account({ id: 2, email: 'lee@popdesk.test', name: '이상담', role: 'AGENT' });
const YOON = account({ id: 5, email: 'yoon@popdesk.test', name: '윤관리', role: 'ADMIN' });
const HAN = account({ id: 4, email: 'han@popdesk.test', name: '한정지', role: 'AGENT', status: 'SUSPENDED' });
const KIM = account({ id: 11, email: 'kim@acme.test', name: '김고객', role: 'CUSTOMER' });

function seed() {
  state.accounts = [account(), LEE, YOON, HAN, KIM];
}

const clearBox = (box: HTMLElement) => within(box).queryByRole<HTMLInputElement>('checkbox', { name: CLEAR });
const go = (box: HTMLElement) => within(box).getByRole('button', { name: '정지' });
const searchBox = () => found(() => screen.queryByRole<HTMLInputElement>('searchbox'), '검색 칸');
const lastList = () => new URL(callsTo('GET', ACCOUNTS).at(-1)?.url ?? 'http://x/').searchParams;
const address = () => new URLSearchParams(window.location.search);
const suspendQuery = (id: number) => new URL(callsTo('POST', `${ACCOUNTS}/${String(id)}/suspend`).at(-1)?.url ?? 'http://x/').searchParams;

describe('C-10 구성원 목록 (3번 묶음)', () => {
  test.each([
    ['상담원', LEE],
    ['관리자', YOON],
  ])('S3-AC-85 C-10 %s 정지: 모든 고객사에 걸친 담당인 열린 문의 수와 꺼진 체크 상자를 보인다. 체크하고 정지하면 clearAssignments=true로 보내고 토스트에 "담당 N건을 비웠습니다"', async (_, who) => {
    seed();
    s3.openAssignments = 4;
    const { user } = renderAt('/members');
    const box = await openRowAction(user, who.name, '정지');

    await sent('GET', `${ACCOUNTS}/${String(who.id)}/open-assignments`);
    await eventually(() => {
      expect(text(box)).toContain(`${who.name} 님이 담당인 열린 문의가 4건 있습니다.`);
    });
    const toList = await found(() => within(box).queryByRole<HTMLAnchorElement>('link', { name: /4건/ }), '"4건" 링크');
    expect(new URL(toList.href).pathname).toBe('/issues');
    expect(new URL(toList.href).searchParams.get('assignee')).toBe(String(who.id));
    expect(new URL(toList.href).searchParams.has('companyCode'), '고객사로 거르지 않는다').toBe(false);
    expect(text(box)).toContain('비우지 않으면 담당자로 남지만 문의를 볼 수 없습니다.');
    expect(text(box)).toContain(`${who.name} 님을 정지합니다.`);
    const check = await found(() => clearBox(box), `체크 상자 "${CLEAR}"`);
    expect(check).not.toBeChecked();

    await user.click(check);
    await user.click(go(box));
    await sent('POST', `${ACCOUNTS}/${String(who.id)}/suspend`);
    expect(suspendQuery(who.id).get('clearAssignments')).toBe('true');
    await closed();
    await said(`${who.name} 님을 정지했습니다`);
    await said('담당 4건을 비웠습니다');
  });

  test('S3-AC-85 C-10 정지(체크하지 않음): clearAssignments 없이 보내고 토스트에 비운 수가 없다', async () => {
    seed();
    s3.openAssignments = 4;
    const { user } = renderAt('/members');
    const box = await openRowAction(user, LEE.name, '정지');
    await found(() => clearBox(box), `체크 상자 "${CLEAR}"`);

    await user.click(go(box));
    await sent('POST', `${ACCOUNTS}/2/suspend`);
    expect(suspendQuery(2).has('clearAssignments')).toBe(false);
    await said('이상담 님을 정지했습니다');
    expectNotSaid(/담당 \d+건을 비웠습니다/);
  });

  test('S3-AC-85 C-10 고객 정지: 담당인 문의 수를 부르지 않고 체크 상자도 없다', async () => {
    seed();
    s3.openAssignments = 4;
    const { user } = renderAt('/members');
    // The agent's dialog first: this is what the customer's dialog must not do.
    const first = await openRowAction(user, LEE.name, '정지');
    await sent('GET', `${ACCOUNTS}/2/open-assignments`);
    await user.click(within(first).getByRole('button', { name: '취소' }));
    await closed();
    const asked = callsTo('GET', /\/open-assignments$/).length;

    const box = await openRowAction(user, KIM.name, '정지');
    await settle();

    expect(callsTo('GET', /\/open-assignments$/)).toHaveLength(asked);
    expect(clearBox(box)).toBeNull();
    expect(text(box)).not.toContain('담당인 열린 문의');
    await user.click(go(box));
    await sent('POST', `${ACCOUNTS}/11/suspend`);
    expect(suspendQuery(11).has('clearAssignments')).toBe(false);
  });

  test('S3-AC-86 C-10 필터를 주소에: 역할, 상태, 검색어를 바꾸면 주소의 쿼리에 실리고 그 값으로 목록을 부른다', async () => {
    seed();
    const { user } = renderAt('/members');
    await rowOf(KIM.name);

    await user.selectOptions(await select(/^역할/), '상담원');
    await eventually(() => {
      expect(address().get('role')).toBe('AGENT');
      expect(lastList().get('role')).toBe('AGENT');
    });
    await user.selectOptions(await select(/^상태/), '정지');
    await eventually(() => {
      expect(address().get('status')).toBe('SUSPENDED');
      expect(lastList().get('status')).toBe('SUSPENDED');
    });
    await user.type(await searchBox(), 'han{Enter}');
    await eventually(() => {
      expect([address().get('role'), address().get('status'), address().get('q')]).toEqual(['AGENT', 'SUSPENDED', 'han']);
      expect([lastList().get('role'), lastList().get('status'), lastList().get('q')]).toEqual(['AGENT', 'SUSPENDED', 'han']);
    });
    await rowOf(HAN.name);
  });

  test('S3-AC-86 C-10 주소로 바로 열기: 쿼리의 역할, 상태, 검색어로 필터를 그리고 그 값으로 부른다', async () => {
    seed();
    renderAt('/members?role=AGENT&status=SUSPENDED&q=han');

    await rowOf(HAN.name);
    expect([lastList().get('role'), lastList().get('status'), lastList().get('q')]).toEqual(['AGENT', 'SUSPENDED', 'han']);
    expect(chosen(await select(/^역할/))).toBe('상담원');
    expect(chosen(await select(/^상태/))).toBe('정지');
    expect(await searchBox()).toHaveValue('han');
    expect(callsTo('GET', ACCOUNTS).every((c) => new URL(c.url).searchParams.get('role') === 'AGENT'), '거르지 않은 요청이 없다').toBe(true);
  });

  test('C-10 axe (3번 묶음): 수와 체크 상자가 있는 정지 대화상자에 접근성 위반이 없다', async () => {
    seed();
    s3.openAssignments = 4;
    const { user } = renderAt('/members');
    const box = await openRowAction(user, LEE.name, '정지');
    await found(() => clearBox(box), `체크 상자 "${CLEAR}"`);

    await expectNoAxeViolations();
  });
});
