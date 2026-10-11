// 1a screens.md 2절 C-9 고객사 상세: 고치기, 상태 버튼과 띠, 코드를 넣어야 눌리는 보관 확인,
// 소속 더하기·빼기, 배정 빼기(S1A-AC-43~56), 닫히거나 대상이 바뀐 뒤 온 답 버리기(S1A-AC-58).
import { describe, expect, test } from 'vitest';
import {
  ACME,
  BETA,
  account,
  atPath,
  button,
  callsTo,
  company,
  dialog,
  errors,
  eventually,
  expectNoAxeViolations,
  found,
  gate,
  isBlocked,
  link,
  member,
  openDialog,
  pageText,
  queryAction,
  renderAt,
  screen,
  sent,
  state,
  text,
  within,
} from '../__tests__/console';
import { answered, region, rejected, route, rowOf, settle } from '../__tests__/console1a';

const URL_ACME = '/companies/ACME';
const COMPANY = '/api/console/companies/ACME';
const SUSPENDED_BAND = '중지된 고객사입니다. 새 문의를 받지 않습니다.';
const ARCHIVED_BAND = '보관된 고객사입니다. 되돌릴 수 없습니다.';

const KIM = member({ accountId: 11, email: 'kim@acme.test', name: '김고객' });
const CHOI = member({ accountId: 12, email: 'choi@acme.test', name: '최동료' });
const LEE = member({ accountId: 2, email: 'lee@popdesk.test', name: '이상담' });
const JUNG = member({ accountId: 3, email: 'jung@popdesk.test', name: '정상담' });

type User = ReturnType<typeof renderAt>['user'];

const customers = () => region(/소속 고객/);
const agents = () => region(/배정 상담원/);
const title = (name: string) => found(() => screen.queryByRole('heading', { level: 1, name: new RegExp(name) }), `화면 제목 ${name}`);
const input = (label: RegExp) => found(() => screen.queryAllByLabelText<HTMLInputElement>(label, { selector: 'input' })[0], `칸 ${String(label)}`);

function seedMembers() {
  state.customers = [KIM, CHOI];
  state.agents = [LEE, JUNG];
}

/** The button that goes on: not 취소, not 닫기. */
function confirmButton(box: HTMLElement): HTMLElement {
  const go = within(box)
    .queryAllByRole('button')
    .find((b) => !/^(취소|닫기)$/.test(b.getAttribute('aria-label') ?? text(b)));
  expect(go ?? null, '대화상자의 확인 버튼').not.toBeNull();
  return go as HTMLElement;
}

async function closed() {
  await eventually(() => {
    expect(openDialog(), '대화상자가 닫힘').toBeNull();
  });
}

async function openEdit(user: User) {
  await title(ACME.name);
  await user.click(await found(() => queryAction('고치기'), '"고치기"'));
  return { name: await input(/^이름/), legalName: await input(/^회사명/), save: await button('저장') };
}

/** Opens 빼기 of one row in a member list and returns the confirm dialog. */
async function openRemove(user: User, list: Promise<HTMLElement>, name: string): Promise<HTMLElement> {
  const row = await rowOf(name, await list);
  await user.click(await found(() => within(row).queryByRole('button', { name: /빼기/ }), `${name} 줄의 "빼기"`));
  return dialog();
}

describe('C-9 고객사 상세 (1a)', () => {
  test('S1A-AC-43 C-9 고치기: 이름과 회사명 칸, 코드는 읽기만이고 "코드는 바꿀 수 없습니다". 저장하면 코드 없이 보내고 화면이 새 이름을 보인다', async () => {
    const { user } = renderAt(URL_ACME);
    const edit = await openEdit(user);
    expect(edit.name).toHaveValue(ACME.name);
    expect(edit.legalName).toHaveValue(ACME.legalName);
    expect(pageText()).toContain('코드는 바꿀 수 없습니다');
    const codeInputs = Array.from(document.querySelectorAll('input')).filter((i) => i.value === ACME.code);
    expect(codeInputs.filter((i) => !i.readOnly && !i.disabled), '고칠 수 있는 코드 칸').toHaveLength(0);

    await user.clear(edit.name);
    await user.type(edit.name, '에이크미 코리아');
    await user.clear(edit.legalName);
    await user.type(edit.legalName, '에이크미 코리아 주식회사');
    await user.click(edit.save);

    const [patch] = await sent('PATCH', COMPANY);
    expect(await patch?.body).toEqual({ name: '에이크미 코리아', legalName: '에이크미 코리아 주식회사' });
    await title('에이크미 코리아');
  });

  test('S1A-AC-44 C-9 상태 ACTIVE: "중지"·"보관" 버튼. 중지하면 띠 "중지된 고객사입니다. 새 문의를 받지 않습니다."와 "다시 사용"·"보관"', async () => {
    const { user } = renderAt(URL_ACME);
    await title(ACME.name);
    await button('보관');
    expect(pageText()).not.toContain(SUSPENDED_BAND);
    await user.click(await button('중지'));

    const [put] = await sent('PUT', `${COMPANY}/status`);
    expect(await put?.body).toEqual({ status: 'SUSPENDED' });
    await eventually(() => {
      expect(pageText()).toContain(SUSPENDED_BAND);
    });
    await button('다시 사용');
    await button('보관');
    expect(screen.queryByRole('button', { name: '중지' })).toBeNull();
  });

  test('S1A-AC-44 C-9 상태 SUSPENDED: "다시 사용"을 누르면 ACTIVE로 바꾸고 띠가 사라진다', async () => {
    state.companies = [company({ status: 'SUSPENDED' })];
    const { user } = renderAt(URL_ACME);
    await user.click(await button('다시 사용'));

    const [put] = await sent('PUT', `${COMPANY}/status`);
    expect(await put?.body).toEqual({ status: 'ACTIVE' });
    await button('중지');
    expect(pageText()).not.toContain(SUSPENDED_BAND);
  });

  test('S1A-AC-48 C-9 상태 ARCHIVED: 띠 "보관된 고객사입니다. 되돌릴 수 없습니다." 고치기와 소속·배정 더하기는 없고 빼기는 남는다', async () => {
    state.companies = [company({ status: 'ARCHIVED' })];
    seedMembers();
    renderAt(URL_ACME);

    await eventually(() => {
      expect(pageText()).toContain(ARCHIVED_BAND);
    });
    expect(queryAction('고치기')).toBeNull();
    expect(screen.queryAllByRole('button', { name: /더하기|초대|상담원 배정/ }), '더하기 버튼').toHaveLength(0);
    expect(within(await customers()).queryAllByRole('button', { name: /빼기/ }), '소속 빼기').toHaveLength(2);
    expect(within(await agents()).queryAllByRole('button', { name: /빼기/ }), '배정 빼기').toHaveLength(2);
  });

  test('S1A-AC-44 C-9 보관 확인: 대화상자의 문구, 고객사 코드를 입력해야 확인 버튼이 눌린다. 확인하면 ARCHIVED로 바꾸고 보관 띠를 보인다', async () => {
    const { user } = renderAt(URL_ACME);
    await user.click(await button('보관'));
    const box = await dialog();
    expect(text(box)).toContain('보관하면 되돌릴 수 없습니다. 새 문의, 소속·배정 더하기, 이름 고치기가 막힙니다.');
    const code = await found(() => within(box).queryByRole<HTMLInputElement>('textbox'), '코드 입력 칸');
    const confirm = confirmButton(box);
    expect(isBlocked(confirm), '코드를 넣기 전').toBe(true);
    await user.click(confirm);

    await user.type(code, 'ACM');
    expect(isBlocked(confirm), '코드가 다를 때').toBe(true);
    await user.click(confirm);
    expect(callsTo('PUT', `${COMPANY}/status`), '코드가 맞기 전').toHaveLength(0);

    await user.type(code, 'E');
    await eventually(() => {
      expect(isBlocked(confirm), '코드가 맞을 때').toBe(false);
    });
    await user.click(confirm);

    const [put] = await sent('PUT', `${COMPANY}/status`);
    expect(await put?.body).toEqual({ status: 'ARCHIVED' });
    await eventually(() => {
      expect(pageText()).toContain(ARCHIVED_BAND);
    });
    await closed();
  });

  test('S1A-AC-50 C-9 소속 더하기: 고객 계정을 이름·이메일로 찾아 고른다. 이미 소속인 사람은 "소속됨"으로 나오고 고를 수 없다', async () => {
    state.customers = [KIM];
    state.accounts = [
      account(),
      account({ id: 11, email: 'kim@acme.test', name: '김고객', role: 'CUSTOMER' }),
      account({ id: 13, email: 'park@acme.test', name: '박신규', role: 'CUSTOMER' }),
      account({ id: 2, email: 'lee@acme.test', name: '이상담', role: 'AGENT' }),
    ];
    const { user } = renderAt(URL_ACME);
    const list = await customers();
    await user.click(await found(() => within(list).queryByRole('button', { name: /더하기/ }), '소속 고객의 "더하기"'));
    const box = await dialog();
    const candidate = (name: string) =>
      within(box).queryByRole('radio', { name: new RegExp(name) }) ??
      within(box).queryByRole('option', { name: new RegExp(name) }) ??
      within(box).queryByRole('checkbox', { name: new RegExp(name) }) ??
      within(box).queryByRole('button', { name: new RegExp(name) });
    await user.type(await found(() => within(box).queryByRole('searchbox') ?? within(box).queryByRole('textbox'), '찾기 칸'), 'acme');

    const park = await found(() => candidate('박신규'), '찾은 고객 "박신규"');
    const query = new URL(callsTo('GET', '/api/console/accounts').at(-1)?.url ?? 'http://x/').searchParams;
    expect(query.get('role')).toBe('CUSTOMER');
    expect(query.get('q')).toBe('acme');
    expect(text(box)).toContain('소속됨');
    const kim = candidate('김고객');
    expect(kim === null || isBlocked(kim), '이미 소속인 사람은 고를 수 없다').toBe(true);
    expect(candidate('이상담'), '고객이 아닌 계정').toBeNull();

    await user.click(park);
    const add = within(box)
      .queryAllByRole('button')
      .find((b) => /더하기/.test(text(b)) && b !== park);
    if (add) await user.click(add);

    await sent('PUT', `${COMPANY}/customers/13`);
    await rowOf('박신규', list);
    expect(callsTo('PUT', `${COMPANY}/customers/11`)).toHaveLength(0);
  });

  test.each([
    {
      what: '소속 빼기',
      list: customers,
      target: KIM,
      path: `${COMPANY}/customers/11`,
      message: '김고객 님은 이 고객사의 문의를 더 보지 못합니다. 자기가 올린 문의는 계속 봅니다.',
    },
    { what: '배정 빼기', list: agents, target: LEE, path: `${COMPANY}/agents/2`, message: '이상담 님은 이 고객사의 문의를 더 보지 못합니다.' },
  ])('S1A-AC-52 S1A-AC-55 C-9 $what: 확인 대화상자의 문구, 확인하면 빼고 줄이 사라진다', async ({ what, list, target, path, message }) => {
    seedMembers();
    const { user } = renderAt(URL_ACME);
    const box = await openRemove(user, list(), target.name);
    expect(text(box)).toContain(message);
    if (what === '배정 빼기') expect(text(box), '담당 수와 고객용 안내는 없다').not.toContain('자기가 올린 문의');
    expect(callsTo('DELETE', path), '확인 전').toHaveLength(0);
    await user.click(confirmButton(box));

    await sent('DELETE', path);
    const members = await list();
    await eventually(() => {
      expect(text(members)).not.toContain(target.name);
    });
    await closed();
  });

  test('S1A-AC-48 C-9 400 보관된 고객사: 다른 탭에서 보관된 뒤 고치면 오류 띠를 보이고 화면을 다시 불러온다', async () => {
    route('patch', COMPANY, () => {
      state.companies = [company({ status: 'ARCHIVED' })];
      return rejected('COMPANY_ARCHIVED');
    });
    const { user } = renderAt(URL_ACME);
    const edit = await openEdit(user);
    const loads = callsTo('GET', COMPANY).length;
    await user.type(edit.name, ' 2');
    await user.click(edit.save);

    await found(() => screen.queryAllByRole('alert').find((el) => text(el) !== ''), '오류 띠');
    await eventually(() => {
      expect(callsTo('GET', COMPANY).length, '다시 불러옴').toBeGreaterThan(loads);
      expect(pageText()).toContain(ARCHIVED_BAND);
    });
  });

  test.each([
    { what: '소속 빼기', list: customers, a: KIM, b: CHOI, path: `${COMPANY}/customers/11`, pathB: `${COMPANY}/customers/12` },
    { what: '배정 빼기', list: agents, a: LEE, b: JUNG, path: `${COMPANY}/agents/2`, pathB: `${COMPANY}/agents/3` },
  ])(
    'S1A-AC-58 C-9 닫힌 뒤 온 답($what): A를 빼는 중에 취소한 뒤 온 실패는 버린다. B로 열면 오류 없이 B를 묻는다',
    async ({ list, a, b, path, pathB }) => {
      seedMembers();
      const release = gate();
      route('delete', path, async () => {
        await release.opened;
        return errors.internal();
      });
      const { user } = renderAt(URL_ACME);
      const first = await openRemove(user, list(), a.name);
      await user.click(confirmButton(first));
      await sent('DELETE', path);
      await user.click(within(first).getByRole('button', { name: '취소' }));
      await closed();

      const done = answered('DELETE', path);
      release.open();
      await done;
      await settle();
      expect(openDialog(), '늦은 답이 대화상자를 다시 열지 않는다').toBeNull();

      const second = await openRemove(user, list(), b.name);
      expect(within(second).queryAllByRole('alert'), 'A의 오류').toHaveLength(0);
      expect(text(second)).toContain(`${b.name} 님은`);
      expect(text(second)).not.toContain(a.name);
      expect(isBlocked(confirmButton(second)), 'B의 확인 버튼').toBe(false);
      expect(callsTo('DELETE', pathB), 'B는 확인 전').toHaveLength(0);
    },
  );

  test('S1A-AC-58 C-9 대상이 바뀐 뒤 온 답: ACME를 중지하는 중에 BETA로 가면, 뒤에 온 ACME의 답을 BETA 화면에 보이지 않는다', async () => {
    state.companies = [ACME, BETA];
    const release = gate();
    route('put', `${COMPANY}/status`, async () => {
      await release.opened;
      return Response.json(company({ status: 'SUSPENDED' }));
    });
    const { user } = renderAt(URL_ACME);
    await user.click(await button('중지'));
    await sent('PUT', `${COMPANY}/status`);
    await user.click(await link('고객사'));
    await user.click(await link(BETA.name));
    await atPath('/companies/BETA');
    await title(BETA.name);

    const done = answered('PUT', `${COMPANY}/status`);
    release.open();
    await done;
    await settle();

    expect(pageText()).not.toContain(SUSPENDED_BAND);
    expect(window.location.pathname).toBe('/companies/BETA');
    await title(BETA.name);
    await button('중지');
  });

  test('C-9 axe (1a): 빼기가 있는 두 목록과 열린 보관 확인 대화상자에 접근성 위반이 없다', async () => {
    seedMembers();
    const { user } = renderAt(URL_ACME);
    await user.click(await button('보관'));
    const box = await dialog();
    expect(text(box)).toContain('보관하면 되돌릴 수 없습니다.');

    await expectNoAxeViolations();
  });
});
