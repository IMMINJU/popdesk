// 3번 묶음 screens.md 1절 C-19 대리 등록 (/issues/new). 상담 쪽이 고객의 이름으로 문의를 올린다.
import { HttpResponse } from 'msw/http';
import { describe, expect, test } from 'vitest';
import {
  ACME,
  ADMIN,
  AGENT,
  BETA,
  alertWith,
  atPath,
  button,
  callsTo,
  company,
  dialog,
  draftKept,
  errors,
  eventually,
  expectFieldError,
  expectNoAxeViolations,
  field,
  found,
  gate,
  hrefPath,
  isBlocked,
  on,
  paged,
  pageText,
  renderAt,
  screen,
  sent,
  state,
  text,
  within,
} from '../__tests__/console';
import { answered, rejected, settle } from '../__tests__/console1a';
import { said } from '../__tests__/console2';
import { BAE, CHOI, ISSUES, KIM, chosen, issueIn, optionLabels, s3, select, type CreateIssueRequest } from '../__tests__/console3';

const URL_NEW = '/issues/new';
const REPORTERS_ACME = '/api/console/companies/ACME/reporters';
const REPORTERS_BETA = '/api/console/companies/BETA/reporters';
const ON_BEHALF = '김고객 님(kim@acme.test)의 이름으로 올립니다. 이 고객에게 메일로 알립니다.';
const NOT_A_REPORTER = '이 고객은 지금 이 고객사에 문의를 올릴 수 없습니다. 다시 골라 주세요.';

type User = ReturnType<typeof renderAt>['user'];

const companySelect = () => select(/^고객사/);
/** The box that finds a customer: the one search box of the form. */
const customerSearch = () => screen.queryByRole<HTMLInputElement>('searchbox');
/** A customer in the found list, by the text of its line. */
const customer = (line: string) =>
  screen.queryAllByRole<HTMLInputElement>('radio').find((r) => r.labels?.[0] && text(r.labels[0]).includes(line)) ??
  screen.queryAllByRole('option').find((o) => text(o).includes(line)) ??
  null;
const customerLine = (who: { name: string; email: string }) => `${who.name} (${who.email})`;
const pickCustomer = async (user: User, who: { name: string; email: string }) => {
  await user.click(await found(() => customer(customerLine(who)), `고객 "${customerLine(who)}"`));
};
const channelGroup = () => found(() => screen.queryByRole('radiogroup', { name: '받은 경로' }), '묶음 "받은 경로"');
const priorityGroup = () => found(() => screen.queryByRole('radiogroup', { name: '우선순위' }), '묶음 "우선순위"');
const titleBox = () => field('제목', 'input');
const contentBox = () => field<HTMLTextAreaElement>('내용', 'textarea');
const submit = () => button('등록');
/** Waits until 등록 can be pressed again after a failed send. */
const ready = () =>
  eventually(() => {
    const again = screen.queryByRole('button', { name: '등록' });
    expect(again !== null && !isBlocked(again), '"등록"을 다시 누를 수 있다').toBe(true);
  });
const keysSent = () => callsTo('POST', ISSUES).map((c) => c.headers.get('Idempotency-Key'));

/** Picks ACME and 김고객 and writes a title: the least a form needs. */
async function fill(user: User, title = '전화로 받은 문의') {
  await user.selectOptions(await companySelect(), ACME.name);
  await pickCustomer(user, KIM);
  await user.type(await titleBox(), title);
}

describe('C-19 대리 등록', () => {
  test('S3-AC-84 C-19 처음: 고객사는 ACTIVE인 것만 고르고, 고르기 전에는 고객 칸이 닫혀 있다. 받은 경로는 "전화"가 골라져 있고 첨부는 없다', async () => {
    state.companies = [ACME, BETA, company({ code: 'STOP', name: '멈춘상사', status: 'SUSPENDED' }), company({ code: 'OLD', name: '옛상사', status: 'ARCHIVED' })];
    renderAt(URL_NEW);

    const labels = optionLabels(await companySelect());
    expect(labels).toEqual(expect.arrayContaining([ACME.name, BETA.name]));
    expect(labels.some((label) => /멈춘상사|옛상사/.test(label)), 'ACTIVE가 아닌 고객사').toBe(false);
    const closed = customerSearch();
    expect(closed === null || closed.disabled, '고객 칸이 닫혀 있다').toBe(true);
    expect(customer(KIM.name), '고객사를 고르기 전의 고객').toBeNull();
    expect(callsTo('GET', /\/reporters$/), '고객사를 고르기 전').toHaveLength(0);

    const channel = await channelGroup();
    expect(within(channel).getByRole('radio', { name: '전화' })).toBeChecked();
    expect(within(channel).getByRole('radio', { name: '그 밖' })).not.toBeChecked();
    expect(within(channel).getAllByRole('radio')).toHaveLength(2);
    expect(pageText()).toContain('첨부는 등록한 뒤 문의에서 올립니다.');
    expect(document.querySelector('input[type="file"]'), '첨부 칸').toBeNull();
    expect(pageText()).not.toContain('의 이름으로 올립니다');
  });

  test('S3-AC-84 C-19 고객사가 하나뿐: 처음부터 골라져 있고 고객 칸이 열려 그 고객사의 고객이 나온다', async () => {
    renderAt(URL_NEW);

    const only = await companySelect();
    await eventually(() => {
      expect(chosen(only)).toBe(ACME.name);
    });
    await sent('GET', REPORTERS_ACME);
    await found(() => customer(customerLine(KIM)), `"${customerLine(KIM)}"`);
    expect(await found(customerSearch, '고객 찾기 칸')).toBeEnabled();
  });

  test('S3-AC-84 C-19 고객 고르기: 고객사를 고르면 그 고객사의 고객 20명을 부르고 줄은 "이름 (이메일)"이다. 글자를 치면 q로 다시 부른다. 칸은 고객사, 고객, 받은 경로, 제목, 내용, 우선순위, 비공개 순서다', async () => {
    state.companies = [ACME, BETA];
    const { user } = renderAt(URL_NEW);
    await user.selectOptions(await companySelect(), ACME.name);

    const [first] = await sent('GET', REPORTERS_ACME);
    const firstQuery = new URL(first?.url ?? '').searchParams;
    expect(firstQuery.get('q') ?? '').toBe('');
    expect(firstQuery.get('size')).toBe('20');
    await found(() => customer(customerLine(KIM)), `"${customerLine(KIM)}"`);
    await found(() => customer(customerLine(CHOI)), `"${customerLine(CHOI)}"`);

    const box = await found(customerSearch, '고객 찾기 칸');
    expect(box).toBeEnabled();
    await user.type(box, 'choi');
    await eventually(() => {
      expect(new URL(callsTo('GET', REPORTERS_ACME).at(-1)?.url ?? '').searchParams.get('q')).toBe('choi');
      expect(customer(KIM.name), '검색에 안 맞는 고객').toBeNull();
    });
    await found(() => customer(customerLine(CHOI)), `"${customerLine(CHOI)}"`);

    const inOrder = [
      await companySelect(),
      box,
      await channelGroup(),
      await titleBox(),
      await contentBox(),
      await priorityGroup(),
      await found(() => screen.queryByRole('checkbox', { name: '비공개' }), '"비공개"'),
    ];
    for (const [i, el] of inOrder.entries()) {
      const next = inOrder[i + 1];
      if (next) expect(el.compareDocumentPosition(next) & Node.DOCUMENT_POSITION_FOLLOWING, `${String(i + 1)}번째 칸 다음`).toBeTruthy();
    }
  });

  test('S3-AC-84 C-19 고객사를 바꾸면: 고른 고객이 비워지고 보내기 전의 한 줄도 사라진다. 새 고객사의 고객이 나온다', async () => {
    state.companies = [ACME, BETA];
    const { user } = renderAt(URL_NEW);
    await user.selectOptions(await companySelect(), ACME.name);
    await found(() => customer(customerLine(KIM)), '김고객');
    expect(pageText(), '고객을 고르기 전').not.toContain('의 이름으로 올립니다');

    await pickCustomer(user, KIM);
    await eventually(() => {
      expect(pageText()).toContain(ON_BEHALF);
    });

    await user.selectOptions(await companySelect(), BETA.name);
    await found(() => customer(customerLine(BAE)), '배베타');
    expect(customer(KIM.name), '앞 고객사의 고객').toBeNull();
    expect(pageText()).not.toContain('의 이름으로 올립니다');
    expect(screen.queryAllByRole<HTMLInputElement>('radio').filter((r) => r.checked && r.labels?.[0] && text(r.labels[0]).includes('@')), '골라진 고객').toHaveLength(0);
  });

  test('S3-AC-84 C-19 등록: 고른 값을 JSON으로 보내고 Idempotency-Key를 붙인다. 보내는 동안 버튼을 막고, 성공하면 만든 문의의 상세로 가며 토스트 "문의를 올렸습니다"', async () => {
    const release = gate();
    on('post', ISSUES, async () => {
      await release.opened;
      state.issue = issueIn('RECEIVED', { key: 'ACME-8', title: '전화로 받은 문의', channel: 'OTHER' });
      return HttpResponse.json(state.issue, { status: 201 });
    });
    const { user } = renderAt(URL_NEW);
    await fill(user);
    await user.click(within(await channelGroup()).getByRole('radio', { name: '그 밖' }));
    await user.type(await contentBox(), '고객이 전화로 알려 줌');
    await user.click(within(await priorityGroup()).getByRole('radio', { name: '긴급' }));
    await user.click(screen.getByRole('checkbox', { name: '비공개' }));
    expect(pageText()).toContain(ON_BEHALF);
    expect(callsTo('POST', ISSUES), '누르기 전').toHaveLength(0);

    await user.click(await submit());
    const [post] = await sent('POST', ISSUES);
    const body: CreateIssueRequest = {
      companyCode: 'ACME',
      reporterId: KIM.accountId,
      channel: 'OTHER',
      title: '전화로 받은 문의',
      content: '고객이 전화로 알려 줌',
      priority: 'URGENT',
      private: true,
    };
    expect(await post?.body).toEqual(body);
    expect(post?.headers.get('Idempotency-Key') ?? '', 'Idempotency-Key 머리글').toMatch(/^[\x21-\x7e]{1,100}$/);
    await eventually(() => {
      expect(isBlocked(screen.getByRole('button', { name: /등록|보내는 중/ })), '보내는 동안 "등록"').toBe(true);
    });
    await user.click(screen.getByRole('button', { name: /등록|보내는 중/ }));
    expect(callsTo('POST', ISSUES), '두 번 누름').toHaveLength(1);

    release.open();
    await atPath('/issues/ACME-8');
    await said('문의를 올렸습니다');
  });

  test('S3-AC-84 C-19 키: 5xx 뒤 그대로 다시 보내면 같은 키, 칸을 고친 뒤 보내면 다른 키, 폼을 새로 열면 또 다른 키', async () => {
    on('post', ISSUES, () => errors.internal());
    const { user, unmount } = renderAt(URL_NEW);
    await fill(user);

    await user.click(await submit());
    await sent('POST', ISSUES, 1);
    await alertWith(/./);
    await ready();
    await user.click(await submit());
    await sent('POST', ISSUES, 2);
    await ready();

    await user.type(await titleBox(), ' (고침)');
    await user.click(await submit());
    const posts = await sent('POST', ISSUES, 3);
    expect((await posts[2]?.body) as CreateIssueRequest).toMatchObject({ title: '전화로 받은 문의 (고침)' });

    unmount();
    const again = renderAt(URL_NEW);
    await fill(again.user);
    await again.user.click(await submit());
    await sent('POST', ISSUES, 4);

    const [first, same, edited, reopened] = keysSent();
    expect(first ?? '', '첫 키').not.toBe('');
    expect(same, '그대로 다시 보낸 키').toBe(first);
    expect(edited ?? '', '고친 뒤의 키').not.toBe('');
    expect(edited, '고친 뒤의 키').not.toBe(first);
    expect(reopened ?? '', '새로 연 폼의 키').not.toBe('');
    expect([first, edited], '새로 연 폼의 키').not.toContain(reopened);
  });

  test('C-19 409 IDEMPOTENCY_KEY_REUSED: 새 키로 한 번 다시 보낸다', async () => {
    let reused = true;
    on('post', ISSUES, () => {
      if (reused) {
        reused = false;
        return rejected('IDEMPOTENCY_KEY_REUSED', 409);
      }
      state.issue = issueIn('RECEIVED', { key: 'ACME-8' });
      return HttpResponse.json(state.issue, { status: 201 });
    });
    const { user } = renderAt(URL_NEW);
    await fill(user);
    await user.click(await submit());

    await atPath('/issues/ACME-8');
    const [first, second] = keysSent();
    expect(callsTo('POST', ISSUES)).toHaveLength(2);
    expect(second ?? '', '다시 보낸 키').not.toBe('');
    expect(second, '다시 보낸 키').not.toBe(first);
  });

  test('C-19 400 칸 오류: 틀린 칸 아래에 알리고, 고객 칸의 UNKNOWN_VALUE는 정해진 문구와 함께 고객 목록을 다시 부른다. 쓴 글은 남는다', async () => {
    on('post', ISSUES, () =>
      errors.invalid([
        { field: 'title', code: 'TOO_LONG' },
        { field: 'reporterId', code: 'UNKNOWN_VALUE' },
      ]),
    );
    const { user } = renderAt(URL_NEW);
    await fill(user, '긴 제목');
    const asked = callsTo('GET', REPORTERS_ACME).length;
    await user.click(await submit());

    await expectFieldError(await titleBox());
    await eventually(() => {
      expect(pageText()).toContain(NOT_A_REPORTER);
      expect(callsTo('GET', REPORTERS_ACME).length, '고객 목록을 다시 부름').toBeGreaterThan(asked);
    });
    expect(await titleBox()).toHaveValue('긴 제목');
    expect(window.location.pathname).toBe(URL_NEW);
  });

  test.each([
    { what: '400 COMPANY_NOT_ACTIVE', answer: () => rejected('COMPANY_NOT_ACTIVE'), says: '멈췄거나 보관된 고객사입니다.', again: null },
    { what: '404', answer: () => errors.notFound(), says: '이 고객사를 찾을 수 없습니다', again: '/api/console/companies' },
  ])('C-19 $what: 오류 띠 "$says". 쓴 글은 남는다', async ({ answer, says, again }) => {
    on('post', ISSUES, answer);
    const { user } = renderAt(URL_NEW);
    await fill(user, '보내지 못한 제목');
    const asked = again === null ? 0 : callsTo('GET', again).length;
    await user.click(await submit());

    await alertWith(says);
    expect(draftKept('보내지 못한 제목')).toBe(true);
    expect(window.location.pathname).toBe(URL_NEW);
    if (again !== null) {
      await eventually(() => {
        expect(callsTo('GET', again).length, '고객사 목록을 다시 부름').toBeGreaterThan(asked);
      });
    }
  });

  test.each([
    { who: '상담원', me: AGENT, companies: [], says: '배정된 고객사가 없습니다. 관리자에게 배정을 요청하세요.' },
    { who: '관리자', me: ADMIN, companies: [company({ code: 'OLD', name: '옛상사', status: 'ARCHIVED' })], says: '사용 중인 고객사가 없습니다' },
  ])('C-19 고를 고객사가 없음($who): 폼 대신 "$says"', async ({ me, companies, says }) => {
    state.me = me;
    state.companies = companies;
    renderAt(URL_NEW);

    await eventually(() => {
      expect(pageText()).toContain(says);
    });
    expect(screen.queryByRole('button', { name: '등록' }), '폼').toBeNull();
    expect(screen.queryAllByLabelText('제목', { selector: 'input' }), '폼').toHaveLength(0);
  });

  test.each([
    { who: '관리자', me: ADMIN, linked: true },
    { who: '상담원', me: AGENT, linked: false },
  ])('C-19 고객사에 올릴 수 있는 고객이 없음($who): 고객 칸 아래에 알리고, 관리자에게만 그 고객사 상세로 가는 링크', async ({ me, linked }) => {
    state.me = me;
    s3.reporters.ACME = [];
    const { user } = renderAt(URL_NEW);
    await user.selectOptions(await companySelect(), ACME.name);

    await eventually(() => {
      expect(pageText()).toContain('이 고객사에는 문의를 올릴 수 있는 고객이 없습니다.');
    });
    const toCompany = screen.queryAllByRole('link').filter((a) => hrefPath(a) === '/companies/ACME');
    expect(toCompany.length > 0, '고객사 상세 링크').toBe(linked);
  });

  test('C-19 떠나기: 쓰던 글이 있을 때 메뉴로 옮기면 묻고, 그 자리에 머문다', async () => {
    const { user } = renderAt(URL_NEW);
    await user.type(await titleBox(), '쓰다 만 제목');
    await user.click(await found(() => screen.queryByRole('link', { name: '고객사' }), '메뉴 "고객사"'));

    await dialog();
    expect(window.location.pathname).toBe(URL_NEW);
    expect(await titleBox()).toHaveValue('쓰다 만 제목');
  });

  test('S3-AC-87 C-19 늦게 온 답: 고객사를 바꾼 뒤에 온 앞 고객사의 고객 목록은 버린다', async () => {
    state.companies = [ACME, BETA];
    const release = gate();
    on('get', REPORTERS_BETA, async () => {
      await release.opened;
      return HttpResponse.json(paged([BAE]));
    });
    const { user } = renderAt(URL_NEW);
    await user.selectOptions(await companySelect(), BETA.name);
    await sent('GET', REPORTERS_BETA);

    await user.selectOptions(await companySelect(), ACME.name);
    await found(() => customer(customerLine(KIM)), '김고객');
    const done = answered('GET', REPORTERS_BETA);
    release.open();
    await done;
    await settle();

    expect(customer(BAE.name), '앞 고객사의 고객').toBeNull();
    expect(customer(customerLine(KIM)), '지금 고객사의 고객').not.toBeNull();
    expect(chosen(await companySelect())).toBe(ACME.name);
  });

  test('C-19 axe: 고객을 고른 폼에 접근성 위반이 없다', async () => {
    state.companies = [ACME, BETA];
    const { user } = renderAt(URL_NEW);
    await fill(user);
    await eventually(() => {
      expect(pageText()).toContain(ON_BEHALF);
    });

    await expectNoAxeViolations();
  });
});
