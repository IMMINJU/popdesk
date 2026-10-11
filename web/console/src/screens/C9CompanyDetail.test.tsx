// screens.md 4절 C-9 고객사 상세(관리자). 배정 빼기는 이번 묶음에 없다(1번 묶음). 상담원이 보는 경우는 adminOnly.test.tsx.
import { HttpResponse } from 'msw/http';
import { describe, expect, test } from 'vitest';
import {
  ACME,
  account,
  button,
  callsTo,
  company,
  dialog,
  eventually,
  expectNoAxeViolations,
  found,
  gate,
  heading1,
  hrefPath,
  isBlocked,
  member,
  on,
  pageText,
  queryAction,
  renderAt,
  screen,
  sent,
  state,
  text,
  within,
} from '../__tests__/console';

const URL_ACME = '/companies/ACME';

type User = ReturnType<typeof renderAt>['user'];

const section = (name: RegExp) => found(() => screen.queryByRole('region', { name }), `section ${String(name)}`);
const customersSection = () => section(/소속 고객/);
const agentsSection = () => section(/배정 상담원/);
const inviteButton = () => found(() => queryAction('이 고객사로 고객 초대'), '"이 고객사로 고객 초대"');
const assignButton = () => button('상담원 배정');

/** Agents that can be offered, and two that must not be: suspended, already assigned, customer, admin. */
function seedAccounts() {
  state.accounts = [
    account(),
    account({ id: 2, email: 'lee@popdesk.test', name: '이상담', role: 'AGENT', status: 'ACTIVE' }),
    account({ id: 3, email: 'jung@popdesk.test', name: '정상담', role: 'AGENT', status: 'INVITED' }),
    account({ id: 4, email: 'han@popdesk.test', name: '한정지', role: 'AGENT', status: 'SUSPENDED' }),
    account({ id: 5, email: 'oh@popdesk.test', name: '오배정', role: 'AGENT', status: 'ACTIVE' }),
    account({ id: 11, email: 'kim@acme.test', name: '김고객', role: 'CUSTOMER', status: 'ACTIVE' }),
  ];
  state.agents = [member({ accountId: 5, email: 'oh@popdesk.test', name: '오배정', status: 'ACTIVE' })];
}

/** Picks an agent in the assign dialog, whether the list is radios, options, or one button per agent. */
async function pickAgent(user: User, box: HTMLElement, name: string) {
  const control = await found(
    () =>
      within(box).queryByRole('radio', { name: new RegExp(name) }) ??
      within(box).queryByRole('option', { name: new RegExp(name) }) ??
      within(box).queryByRole('checkbox', { name: new RegExp(name) }) ??
      within(box).queryByRole('button', { name: new RegExp(name) }),
    `배정 대화상자의 "${name}"`,
  );
  await user.click(control);
  const confirm = within(box).queryAllByRole('button').find((b) => /^배정/.test(text(b)) && b !== control);
  if (confirm) await user.click(confirm);
}

describe('C-9 고객사 상세', () => {
  test('C-9 데이터 있음: 정보(이름, 코드, 회사명, 상태)와 "소속 고객"·"배정 상담원" 두 section(h2)', async () => {
    state.customers = [member(), member({ accountId: 12, email: 'park@acme.test', name: '박초대', status: 'INVITED' })];
    state.agents = [member({ accountId: 2, email: 'lee@popdesk.test', name: '이상담' })];
    renderAt(URL_ACME);

    await found(() => screen.queryByRole('heading', { level: 1, name: /에이크미/ }), '화면 제목에 고객사 이름');
    const main = text(screen.getByRole('main'));
    for (const part of ['ACME', '에이크미 주식회사', '사용 중']) expect(main).toContain(part);
    const customers = await customersSection();
    expect(within(customers).getByRole('heading', { level: 2 })).toBeInTheDocument();
    await eventually(() => {
      expect(text(customers)).toContain('김고객');
    });
    expect(text(customers)).toContain('kim@acme.test');
    expect(text(customers)).toContain('박초대');
    const agents = await agentsSection();
    expect(within(agents).getByRole('heading', { level: 2 })).toBeInTheDocument();
    expect(text(agents)).toContain('이상담');
    expect(text(agents)).toContain('lee@popdesk.test');
  });

  test('C-9 소속 고객: 수락 전 계정에 "수락 전" 표시', async () => {
    state.customers = [member(), member({ accountId: 12, email: 'park@acme.test', name: '박초대', status: 'INVITED' })];
    renderAt(URL_ACME);

    const customers = await customersSection();
    const pending = await found(() => within(customers).queryAllByRole('row').find((r) => text(r).includes('박초대')) ?? Array.from(customers.querySelectorAll('li')).find((li) => text(li).includes('박초대')), '박초대 줄');
    expect(text(pending)).toContain('수락 전');
    const active = within(customers).queryAllByRole('row').find((r) => text(r).includes('김고객')) ?? Array.from(customers.querySelectorAll('li')).find((li) => text(li).includes('김고객'));
    expect(text(active)).not.toContain('수락 전');
  });

  test('C-9 소속 고객 없음: "아직 고객이 없습니다."와 초대 버튼', async () => {
    renderAt(URL_ACME);

    const customers = await customersSection();
    await eventually(() => {
      expect(text(customers)).toContain('아직 고객이 없습니다.');
    });
    await inviteButton();
  });

  test('C-9 배정 상담원 없음: "배정된 상담원이 없습니다. 관리자만 이 고객사의 문의를 봅니다."', async () => {
    renderAt(URL_ACME);

    const agents = await agentsSection();
    await eventually(() => {
      expect(text(agents)).toContain('배정된 상담원이 없습니다. 관리자만 이 고객사의 문의를 봅니다.');
    });
    await assignButton();
  });

  test('C-9 상담원 배정: 대화상자에는 아직 배정되지 않은 ACTIVE·INVITED 상담원만 보인다', async () => {
    seedAccounts();
    const { user } = renderAt(URL_ACME);
    await user.click(await assignButton());

    const box = await dialog();
    await eventually(() => {
      expect(text(box)).toContain('이상담');
      expect(text(box)).toContain('정상담');
    });
    expect(text(box), '정지된 상담원').not.toContain('한정지');
    expect(text(box), '이미 배정된 상담원').not.toContain('오배정');
    expect(text(box), '고객').not.toContain('김고객');
    expect(text(box), '관리자').not.toContain('최관리');
  });

  test('C-9 상담원 배정: 고르면 PUT으로 배정하고 목록을 다시 불러와 포커스를 "배정 상담원" 제목으로', async () => {
    seedAccounts();
    const { user } = renderAt(URL_ACME);
    await agentsSection();
    await sent('GET', '/api/console/companies/ACME/agents');
    const loads = callsTo('GET', '/api/console/companies/ACME/agents').length;
    await user.click(await assignButton());
    await pickAgent(user, await dialog(), '정상담');

    await sent('PUT', '/api/console/companies/ACME/agents/3');
    await eventually(() => {
      expect(callsTo('GET', '/api/console/companies/ACME/agents').length).toBeGreaterThan(loads);
    });
    const agents = await agentsSection();
    await eventually(() => {
      expect(text(agents)).toContain('정상담');
      expect(document.activeElement).toBe(within(agents).getByRole('heading', { level: 2 }));
    });
  });

  test('C-9 같은 것을 다시 함: 200이면 성공과 같다(PROJ-4)', async () => {
    seedAccounts();
    on('put', '/api/console/companies/:code/agents/:accountId', () =>
      HttpResponse.json(member({ accountId: 2, email: 'lee@popdesk.test', name: '이상담' })),
    );
    const { user } = renderAt(URL_ACME);
    await user.click(await assignButton());
    await pickAgent(user, await dialog(), '이상담');

    await sent('PUT', '/api/console/companies/ACME/agents/2');
    await eventually(() => {
      expect(screen.queryAllByRole('alert').filter((el) => text(el) !== '')).toHaveLength(0);
    });
  });

  test('C-9 저장 중: 누른 버튼만 막힌다', async () => {
    seedAccounts();
    const release = gate();
    on('put', '/api/console/companies/:code/agents/:accountId', async () => {
      await release.opened;
      return HttpResponse.json(member({ accountId: 3, email: 'jung@popdesk.test', name: '정상담', status: 'INVITED' }));
    });
    const { user } = renderAt(URL_ACME);
    const invite = await inviteButton();
    await user.click(await assignButton());
    const box = await dialog();
    await pickAgent(user, box, '정상담');

    await sent('PUT', '/api/console/companies/ACME/agents/3');
    // 2절: 저장 중 버튼은 aria-disabled로 막고 disabled를 쓰지 않는다(포커스를 잃지 않게).
    await eventually(() => {
      const pressed = within(box)
        .queryAllByRole('button')
        .filter((b) => b.getAttribute('aria-disabled') === 'true');
      expect(pressed.length, '누른 버튼 막힘(aria-disabled)').toBeGreaterThan(0);
    });
    const disabled = within(box)
      .queryAllByRole('button')
      .filter((b) => (b as HTMLButtonElement).disabled);
    expect(disabled.length, '저장 중 disabled 버튼').toBe(0);
    expect(isBlocked(invite), '초대 버튼은 막히지 않는다').toBe(false);
    release.open();
  });

  test('C-9 "이 고객사로 고객 초대": C-10 초대를 고객 역할과 이 고객사를 고른 채로 연다', async () => {
    state.companies = [ACME, company({ code: 'BETA', name: '베타상사' })];
    const { user } = renderAt(URL_ACME);
    await user.click(await inviteButton());

    const box = await dialog();
    await eventually(() => {
      expect(within(box).getByRole('radio', { name: '고객' })).toBeChecked();
      expect(within(box).getByRole('checkbox', { name: /에이크미/ })).toBeChecked();
    });
    expect(within(box).getByRole('checkbox', { name: /베타상사/ })).not.toBeChecked();
  });

  test('C-9 ARCHIVED: 배정·초대 버튼을 숨긴다(PROJ-3)', async () => {
    state.companies = [company({ status: 'ARCHIVED' })];
    state.customers = [member()];
    renderAt(URL_ACME);

    const customers = await customersSection();
    await eventually(() => {
      expect(text(customers)).toContain('김고객');
    });
    expect(text(screen.getByRole('main'))).toContain('보관');
    expect(queryAction('이 고객사로 고객 초대')).toBeNull();
    expect(screen.queryByRole('button', { name: '상담원 배정' })).toBeNull();
  });

  test('C-9 404: 없는 고객사는 "찾을 수 없습니다"와 목록 링크', async () => {
    renderAt('/companies/NOPE');

    await eventually(() => {
      expect(pageText()).toContain('찾을 수 없습니다');
    });
    const toList = screen.queryAllByRole('link').filter((a) => hrefPath(a) === '/companies' && !a.closest('nav'));
    expect(toList.length, '고객사 목록으로 가는 링크').toBeGreaterThan(0);
  });

  test('C-9 접근성: 처음 포커스는 h1(tabindex=-1), 화면 제목을 document.title에', async () => {
    renderAt(URL_ACME);
    await customersSection();

    const h1 = await heading1();
    expect(h1).toHaveAttribute('tabindex', '-1');
    await eventually(() => {
      expect(document.activeElement).toBe(h1);
    });
    expect(document.title).toContain(text(h1));
  });

  test('C-9 axe: 두 목록과 열린 배정 대화상자에 접근성 위반이 없다', async () => {
    seedAccounts();
    state.customers = [member(), member({ accountId: 12, email: 'park@acme.test', name: '박초대', status: 'INVITED' })];
    const { user } = renderAt(URL_ACME);
    await customersSection();
    await user.click(await assignButton());
    await dialog();

    await expectNoAxeViolations();
  });
});
