// 2번 묶음 screens.md 2절 C-9 고객사 상세의 "소속 요청" 구역: 걸려 있는 요청을 승인·거절과 함께 보인다(S2-AC-48).
// 떠난 뒤 온 답은 버린다(S2-AC-49). 승인·거절이 끝나면 옆 메뉴의 합도 줄어든다(screens.md 5절).
import { describe, expect, test } from 'vitest';
import {
  ACME,
  BETA,
  atPath,
  callsTo,
  company,
  dialog,
  eventually,
  expectNoAxeViolations,
  found,
  gate,
  hrefPath,
  link,
  member,
  openDialog,
  renderAt,
  screen,
  sent,
  state,
  text,
  within,
} from '../__tests__/console';
import { answered, closed, confirmButton, region, rejected, route, rowOf, settle } from '../__tests__/console1a';
import { REQUESTS, cellUnder, expectNotSaid, pending, request, requestsRegion, said } from '../__tests__/console2';

const URL_ACME = '/companies/ACME';
const CUSTOMERS = '/api/console/companies/ACME/customers';
const MANAGER = `${CUSTOMERS}/11/manager`;
const COLUMNS = ['요청한 사람', '넣은 이름과 이메일', '계정', '요청한 시각', '동작'];
const STAFF_NOTE = '상담 쪽 계정은 고객사에 소속시킬 수 없습니다';
const CANCELLED_NOTE = '승인한 뒤 구성원 목록에서 초대를 다시 보내세요';
const REJECT_ASKS = ['이 요청을 거절할까요?', '요청한 담당자의 목록에서 이 사람이 사라집니다.'];

const REQ = request();
const OTHER = request({
  id: 302,
  name: '정입력',
  email: 'jung@other.test',
  account: { accountId: 32, name: '정실제', role: 'CUSTOMER', status: 'ACTIVE', invitationCancelled: false },
});

const KIM = member({ accountId: 11, email: 'kim@acme.test', name: '김고객' });

type User = ReturnType<typeof renderAt>['user'];

const requests = () => region(/소속 요청/);
const customers = () => region(/소속 고객/);
const buttonIn = (row: HTMLElement, name: string) => within(row).queryByRole('button', { name });

/** The row of one request, found by the name the manager typed. */
async function rowFor(typed: string): Promise<HTMLElement> {
  return rowOf(typed, await requests());
}

async function press(user: User, typed: string, name: string) {
  const row = await rowFor(typed);
  await user.click(await found(() => buttonIn(row, name), `${typed} 줄의 "${name}"`));
}

/** Presses 거절 of one request and confirms. */
async function reject(user: User, typed: string) {
  await press(user, typed, '거절');
  await user.click(confirmButton(await dialog()));
}

/** Presses 담당자로 지정 or 담당자 해제 in 김고객's row of the customer table. */
async function pressManager(user: User, name: string) {
  const row = await rowOf(KIM.name, await customers());
  await user.click(await found(() => buttonIn(row, name), `${KIM.name} 줄의 "${name}"`));
}

/** The request list of one company that C-9 reads; the side menu's sum has no `companyCode`. */
function listsOfCompany() {
  return callsTo('GET', REQUESTS).filter((c) => new URL(c.url).searchParams.has('companyCode'));
}

describe('C-9 고객사 상세: 소속 요청 구역 (2번 묶음)', () => {
  test('S2-AC-48 C-9 소속 요청 구역: 칸 다섯. 요청한 사람, 담당자가 넣은 이름과 이메일, 계정(이름·역할·상태, 구성원 목록으로 가는 링크), 요청한 시각, "승인"·"거절"', async () => {
    pending.requests = [REQ, request({ id: 399, company: { code: BETA.code, name: BETA.name }, name: '남의요청' })];
    state.companies = [ACME, BETA];
    renderAt(URL_ACME);

    const box = await requests();
    const row = await rowFor(REQ.name);
    expect(within(box).getAllByRole('columnheader').map((h) => text(h))).toEqual(COLUMNS);
    const cell = (column: string) => cellUnder(box, row, column);
    for (const part of [REQ.requestedBy.name, REQ.requestedBy.email]) expect(text(cell('요청한 사람'))).toContain(part);
    for (const part of [REQ.name, REQ.email]) expect(text(cell('넣은 이름과 이메일'))).toContain(part);
    for (const part of [REQ.account.name, '고객', '사용 중']) expect(text(cell('계정'))).toContain(part);
    const toAccount = cell('계정')?.querySelector('a');
    expect(toAccount ? hrefPath(toAccount) : null, '계정 칸의 링크').toBe('/members');
    expect(cell('요청한 시각')?.querySelector('time') ?? null, '요청한 시각').not.toBeNull();
    expect(buttonIn(row, '승인')).not.toBeNull();
    expect(buttonIn(row, '거절')).not.toBeNull();
    expect(text(box), '다른 고객사의 요청').not.toContain('남의요청');
  });

  test('S2-AC-48 C-9 승인: 확인 없이 바로. 행이 사라지고 소속 고객 표를 다시 불러와 그 계정이 생긴다. 토스트 "소속시켰습니다". 요청이 없으면 구역도 없다', async () => {
    pending.requests = [REQ];
    const { user } = renderAt(URL_ACME);
    await rowFor(REQ.name);
    const loads = callsTo('GET', CUSTOMERS).length;

    await press(user, REQ.name, '승인');

    await sent('POST', `${REQUESTS}/${String(REQ.id)}/approve`);
    expect(openDialog(), '확인 대화상자').toBeNull();
    await said('소속시켰습니다');
    await rowOf(REQ.account.name, await customers());
    expect(callsTo('GET', CUSTOMERS).length, '소속 고객 표를 다시 불러옴').toBeGreaterThan(loads);
    await eventually(() => {
      expect(requestsRegion(), '요청이 없으면 구역을 그리지 않는다').toBeNull();
    });
  });

  test('S2-AC-48 C-9 거절: 확인 대화상자의 문구, 확인하면 행이 사라지고 토스트 "요청을 거절했습니다"', async () => {
    pending.requests = [REQ, OTHER];
    const reject = `${REQUESTS}/${String(REQ.id)}/reject`;
    const { user } = renderAt(URL_ACME);
    await press(user, REQ.name, '거절');

    const box = await dialog();
    for (const ask of REJECT_ASKS) expect(text(box)).toContain(ask);
    expect(callsTo('POST', reject), '확인 전').toHaveLength(0);
    await user.click(confirmButton(box));

    await sent('POST', reject);
    await said('요청을 거절했습니다');
    await closed();
    const left = await requests();
    await eventually(() => {
      expect(text(left)).not.toContain(REQ.name);
    });
    expect(text(left)).toContain(OTHER.name);
    expect(callsTo('POST', `${REQUESTS}/${String(REQ.id)}/approve`)).toHaveLength(0);
  });

  test.each([
    { what: '계정이 상담원', role: 'AGENT', companyStatus: 'ACTIVE', note: STAFF_NOTE },
    { what: '계정이 관리자', role: 'ADMIN', companyStatus: 'ACTIVE', note: STAFF_NOTE },
    { what: '보관된 고객사', role: 'CUSTOMER', companyStatus: 'ARCHIVED', note: null },
  ] as const)('S2-AC-48 C-9 소속 요청 $what: "승인" 버튼이 없고 "거절"은 있다', async ({ role, companyStatus, note }) => {
    state.companies = [company({ status: companyStatus })];
    pending.requests = [request({ account: { ...REQ.account, role } })];
    renderAt(URL_ACME);

    const row = await rowFor(REQ.name);
    expect(buttonIn(row, '거절')).not.toBeNull();
    expect(buttonIn(row, '승인')).toBeNull();
    expect(text(row).includes(STAFF_NOTE), `"${STAFF_NOTE}"`).toBe(note !== null);
  });

  test.each([
    ['수락 전이고 초대가 취소된 계정(상태 SUSPENDED)', 'SUSPENDED', true, true, null],
    ['그냥 정지된 계정: 계정 칸의 배지 "정지"', 'SUSPENDED', false, false, '정지'],
    ['수락 전이고 초대가 살아 있는 계정: 계정 칸의 배지 "수락 전"', 'INVITED', false, false, '수락 전'],
  ] as const)('S2-AC-48 C-9 소속 요청 %s: "승인"은 되고 안내 한 줄은 초대가 취소됐을 때만', async (_, status, invitationCancelled, noted, badge) => {
    pending.requests = [request({ account: { ...REQ.account, status, invitationCancelled } }), OTHER];
    renderAt(URL_ACME);

    const row = await rowFor(REQ.name);
    expect(buttonIn(row, '승인')).not.toBeNull();
    expect(text(cellUnder(await requests(), row, '계정'))).toContain(badge ?? REQ.account.name);
    expect(text(row).includes(CANCELLED_NOTE), `"${CANCELLED_NOTE}"`).toBe(noted);
    expect(text(await rowFor(OTHER.name))).not.toContain(CANCELLED_NOTE);
  });

  test('S2-AC-48 C-9 승인이 409 REQUEST_CLOSED: 토스트 "이미 처리된 요청입니다"와 함께 다시 불러온다', async () => {
    pending.requests = [REQ, OTHER];
    route('post', `${REQUESTS}/:id/approve`, () => {
      // Another admin closed it first.
      pending.requests = [OTHER];
      return rejected('REQUEST_CLOSED', 409);
    });
    const { user } = renderAt(URL_ACME);
    await press(user, REQ.name, '승인');

    await said('이미 처리된 요청입니다');
    expectNotSaid('소속시켰습니다');
    const left = await requests();
    await eventually(() => {
      expect(text(left)).not.toContain(REQ.name);
    });
    expect(text(left)).toContain(OTHER.name);
  });

  test.each([
    { what: '승인', go: (user: User) => press(user, REQ.name, '승인') },
    { what: '거절', go: (user: User) => reject(user, REQ.name) },
  ])('S2-AC-48 C-9 옆 메뉴의 합($what): 끝나면 다시 불러와 "고객사 소속 요청 3건"이 "…2건"이 된다', async ({ go }) => {
    state.companies = [ACME, BETA];
    pending.requests = [REQ, OTHER, request({ id: 399, company: { code: BETA.code, name: BETA.name } })];
    const { user } = renderAt(URL_ACME);
    await link('고객사 소속 요청 3건');

    await go(user);

    await link('고객사 소속 요청 2건');
    expect(screen.queryByRole('link', { name: '고객사 소속 요청 3건' }), '옛 숫자').toBeNull();
  });

  test('S2-AC-48 C-9 걸린 요청 수가 0인 고객사: 그 고객사의 소속 요청 목록을 부르지 않는다', async () => {
    state.customers = [KIM];
    renderAt(URL_ACME);
    await rowOf(KIM.name, await customers());

    // The side menu's sum went out and came back: the screen had its time to ask.
    await sent('GET', REQUESTS);
    await settle();
    expect(listsOfCompany(), 'companyCode가 있는 소속 요청 목록 요청').toHaveLength(0);
    expect(requestsRegion()).toBeNull();
  });

  test.each([
    { what: '승인', method: 'post', path: `${REQUESTS}/${String(REQ.id)}/approve`, mine: KIM, toast: '소속시켰습니다', go: (user: User) => press(user, REQ.name, '승인') },
    { what: '거절', method: 'post', path: `${REQUESTS}/${String(REQ.id)}/reject`, mine: KIM, toast: '요청을 거절했습니다', go: (user: User) => reject(user, REQ.name) },
    { what: '담당자로 지정', method: 'put', path: MANAGER, mine: KIM, toast: '담당자로 지정했습니다', go: (user: User) => pressManager(user, '담당자로 지정') },
    {
      what: '담당자 해제',
      method: 'delete',
      path: MANAGER,
      mine: member({ ...KIM, manager: true }),
      toast: '담당자에서 해제했습니다',
      go: (user: User) => pressManager(user, '담당자 해제'),
    },
  ] as const)('S2-AC-49 C-9 떠난 뒤 온 답($what): ACME에서 보내는 중에 BETA로 가면, 뒤에 온 답의 토스트를 BETA 화면에 보이지 않는다', async ({ method, path, mine, toast, go }) => {
    state.companies = [ACME, BETA];
    state.customers = [mine];
    pending.requests = [REQ];
    const release = gate();
    route(method, path, async () => {
      await release.opened;
      return method === 'post' ? new Response(null, { status: 200 }) : Response.json({ ...mine, manager: method === 'put' });
    });
    const { user } = renderAt(URL_ACME);
    await go(user);
    await sent(method.toUpperCase(), path);
    // 거절 leaves its confirm dialog open while it sends.
    const asking = openDialog();
    if (asking) await user.click(within(asking).getByRole('button', { name: '취소' }));
    await closed();
    await user.click(await link(/^고객사/));
    await user.click(await link(BETA.name));
    await atPath('/companies/BETA');
    await found(() => screen.queryByRole('heading', { level: 1, name: new RegExp(BETA.name) }), `화면 제목 ${BETA.name}`);

    const done = answered(method.toUpperCase(), path);
    release.open();
    await done;
    await settle();

    expectNotSaid(toast);
    expect(window.location.pathname).toBe('/companies/BETA');
    expect(requestsRegion(), 'BETA에는 요청이 없다').toBeNull();
  });

  test('C-9 axe (2번 묶음): 소속 요청 구역과 열린 거절 확인 대화상자에 접근성 위반이 없다', async () => {
    state.customers = [member({ manager: true })];
    pending.requests = [REQ, request({ id: 303, name: '이입력', account: { ...REQ.account, accountId: 33, role: 'AGENT' } })];
    const { user } = renderAt(URL_ACME);
    await press(user, REQ.name, '거절');
    expect(text(await dialog())).toContain('이 요청을 거절할까요?');

    await expectNoAxeViolations();
  });
});
