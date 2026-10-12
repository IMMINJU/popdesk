// 3번 묶음 screens.md 2절 C-3 문의 목록: 보기 고르기, 필터 줄, 담당자 칸, 늦게 온 답, "대리 등록" 버튼.
import { act } from '@testing-library/react';
import { HttpResponse } from 'msw/http';
import { describe, expect, test, vi } from 'vitest';
import {
  ACME,
  AGENT,
  AGENT_PERSON,
  BETA,
  OTHER_AGENT_PERSON,
  action,
  alertWith,
  atPath,
  callsTo,
  describedText,
  errors,
  eventually,
  expectNoAxeViolations,
  found,
  gate,
  heading1,
  on,
  paged,
  pageText,
  renderAt,
  screen,
  sent,
  state,
  summary,
  text,
  useFakeClock,
  within,
} from '../__tests__/console';
import { answered, settle } from '../__tests__/console1a';
import {
  ISSUES,
  OPEN_STATUSES,
  addressQuery,
  cellText,
  chosen,
  filters,
  filtersOf,
  issueRow,
  lastList,
  listed,
  optionLabels,
  optionWith,
  s3,
  select,
} from '../__tests__/console3';

const MINE = { assignee: 'me', status: [...OPEN_STATUSES] };
const UNASSIGNED = { assignee: 'none', status: [...OPEN_STATUSES] };
const URL_MINE = '/issues?assignee=me&status=RECEIVED&status=CONFIRMED&status=IN_PROGRESS&status=RESOLVED';
const URL_ACME = '/issues?companyCode=ACME';

type User = ReturnType<typeof renderAt>['user'];

const view = (name: '내 담당' | '담당 없음' | '전체') =>
  found(() => screen.queryAllByRole<HTMLAnchorElement>('link', { name }).find((a) => a.closest('nav, [role="navigation"]') !== null), `보기 "${name}"`);
const currentViews = () =>
  (['내 담당', '담당 없음', '전체'] as const).filter((name) =>
    screen.queryAllByRole('link', { name }).some((a) => a.closest('nav, [role="navigation"]') !== null && a.hasAttribute('aria-current') && a.getAttribute('aria-current') !== 'false'),
  );
const searchBox = () => found(() => screen.queryByRole<HTMLInputElement>('searchbox', { name: '문의 검색' }), '검색 칸 "문의 검색"');
const statusGroup = () => found(() => screen.queryByRole('group', { name: '상태' }), '묶음 "상태"');
const statusBox = async (word: string) => within(await statusGroup()).getByRole<HTMLInputElement>('checkbox', { name: word });
const clearFilters = () => [...screen.queryAllByRole('link', { name: '필터 지우기' }), ...screen.queryAllByRole('button', { name: '필터 지우기' })];
const rowsShown = () => screen.queryAllByRole('row').filter((r) => within(r).queryAllByRole('cell').length > 0);
const pageLink = (n: number) => screen.queryByRole('link', { name: new RegExp(`^${String(n)}(쪽)?$`) });
const inDocumentOrder = (a: Element, b: Element) => (a.compareDocumentPosition(b) & Node.DOCUMENT_POSITION_FOLLOWING ? -1 : 1);

/** A list of three pages with one row each, named after its page: ACME-1, ACME-2, ACME-3. */
function threePages() {
  on('get', ISSUES, ({ request }) => {
    const page = Number(new URL(request.url).searchParams.get('page') ?? '1');
    return HttpResponse.json(paged([summary({ key: `ACME-${String(page)}` })], { page, totalElements: 45, totalPages: 3 }));
  });
}

async function search(user: User, words: string) {
  await user.type(await searchBox(), `${words}{Enter}`);
}

describe('C-3 문의 목록 (3번 묶음)', () => {
  test('S3-AC-77 C-3 처음: 콘솔의 /로 오면 "내 담당" 주소가 되고 첫 요청부터 그 필터로 부른다. 옆 메뉴의 "문의"도 그 주소다', async () => {
    renderAt('/');

    await listed(MINE);
    await issueRow('ACME-7');
    expect(window.location.pathname).toBe('/issues');
    expect(currentViews()).toEqual(['내 담당']);
    expect(callsTo('GET', ISSUES).map((c) => new URL(c.url).searchParams.get('assignee')), '거르지 않은 요청이 없다').not.toContain(null);
    const menu = await found(() => screen.queryByRole<HTMLAnchorElement>('link', { name: '문의' }), '메뉴 "문의"');
    expect(new URL(menu.href).pathname).toBe('/issues');
    expect(filtersOf(new URL(menu.href).searchParams), '메뉴 "문의"의 주소').toEqual(filters({ ...MINE, status: [...MINE.status].sort() }));
  });

  test('S3-AC-77 C-3 쿼리 없는 /issues: "전체"다. 다른 보기로 바꿔 열지 않는다', async () => {
    renderAt('/issues');

    await issueRow('ACME-7');
    await view('전체');
    await settle();
    expect(window.location.search).toBe('');
    expect(filtersOf(lastList())).toEqual(filters());
    expect(currentViews()).toEqual(['전체']);
  });

  test('S3-AC-77 C-3 보기 고르기: nav 안의 링크 "내 담당", "담당 없음", "전체". 누르면 그 필터로 바뀌고 aria-current가 옮겨 간다. "전체"의 주소에는 쿼리가 없다', async () => {
    const { user } = renderAt(URL_MINE);
    await listed(MINE);
    expect(currentViews()).toEqual(['내 담당']);

    await user.click(await view('담당 없음'));
    await listed(UNASSIGNED);
    expect(currentViews()).toEqual(['담당 없음']);

    await user.click(await view('전체'));
    await listed({});
    expect(window.location.search).toBe('');
    expect(currentViews()).toEqual(['전체']);

    await user.click(await view('내 담당'));
    await listed(MINE);
    expect(currentViews()).toEqual(['내 담당']);
  });

  test('S3-AC-77 C-3 보기와 필터: 필터를 손으로 바꿔 세 보기 어느 것과도 다르면 아무 보기도 골라지지 않는다', async () => {
    const { user } = renderAt(URL_MINE);
    await listed(MINE);
    for (const word of ['접수', '확인', '처리 중', '해결']) expect(await statusBox(word), `"${word}"`).toBeChecked();
    expect(await statusBox('종료')).not.toBeChecked();

    await user.click(await statusBox('해결'));

    await listed({ assignee: 'me', status: ['RECEIVED', 'CONFIRMED', 'IN_PROGRESS'] });
    expect(currentViews()).toEqual([]);
  });

  test('S3-AC-76 C-3 필터 줄: 검색, 상태, 고객사, 담당자를 바꿀 때마다 주소의 쿼리와 목록 요청의 쿼리가 같은 값을 싣는다', async () => {
    state.companies = [ACME, BETA];
    const { user } = renderAt(URL_ACME);
    await listed({ companyCode: 'ACME' });
    expect(describedText(await searchBox())).toContain('제목과 내용에서 찾습니다');

    await search(user, '결제');
    await listed({ companyCode: 'ACME', q: '결제' });

    await user.click(await statusBox('접수'));
    await user.click(await statusBox('종료'));
    await listed({ companyCode: 'ACME', q: '결제', status: ['RECEIVED', 'CLOSED'] });

    const assignee = await select(/^담당자/);
    expect(optionLabels(assignee).slice(0, 4)).toEqual(['전체', '나', '담당 없음', '담당자가 볼 수 없음']);
    await user.selectOptions(assignee, '담당자가 볼 수 없음');
    await listed({ companyCode: 'ACME', q: '결제', status: ['RECEIVED', 'CLOSED'], assignee: 'unavailable' });
    await user.selectOptions(assignee, '담당 없음');
    await listed({ companyCode: 'ACME', q: '결제', status: ['RECEIVED', 'CLOSED'], assignee: 'none' });
    await user.selectOptions(assignee, '나');
    await listed({ companyCode: 'ACME', q: '결제', status: ['RECEIVED', 'CLOSED'], assignee: 'me' });

    // The other filters stay when the company changes ("나" is not a person of the company).
    await user.selectOptions(await select(/^고객사/), BETA.name);
    await listed({ companyCode: 'BETA', q: '결제', status: ['RECEIVED', 'CLOSED'], assignee: 'me' });
  });

  test('S3-AC-76 C-3 담당자 필터의 사람: 고객사를 고르기 전에는 사람이 없고, 고르면 그 고객사의 후보가 선택지로 나온다. 고르면 assignee에 계정 ID를 싣는다', async () => {
    state.companies = [ACME, BETA];
    const { user } = renderAt('/issues?status=RECEIVED');
    await listed({ status: ['RECEIVED'] });
    expect(optionLabels(await select(/^담당자/))).toEqual(['전체', '나', '담당 없음', '담당자가 볼 수 없음']);
    expect(callsTo('GET', /\/assignees$/), '고객사를 고르기 전').toHaveLength(0);

    await user.selectOptions(await select(/^고객사/), BETA.name);
    await sent('GET', '/api/console/companies/BETA/assignees');
    const assignee = await select(/^담당자/);
    await user.selectOptions(assignee, await optionWith(assignee, OTHER_AGENT_PERSON.name));

    await listed({ status: ['RECEIVED'], companyCode: 'BETA', assignee: String(OTHER_AGENT_PERSON.accountId) });
  });

  test('S3-AC-76 C-3 필터를 바꾸면 1쪽으로: 3쪽에서 상태를 고르면 주소와 요청의 쪽이 1이다', async () => {
    on('get', ISSUES, ({ request }) => {
      const page = Number(new URL(request.url).searchParams.get('page') ?? '1');
      return HttpResponse.json(paged([summary({ key: `ACME-${String(page)}` })], { page, totalElements: 45, totalPages: 3 }));
    });
    const { user } = renderAt(`${URL_ACME}&page=3`);
    await issueRow('ACME-3');
    expect(lastList().get('page')).toBe('3');

    await user.click(await statusBox('접수'));

    await listed({ companyCode: 'ACME', status: ['RECEIVED'] });
    await issueRow('ACME-1');
  });

  test('S3-AC-76 C-3 필터를 건 채 2쪽으로: 주소와 목록 요청이 필터와 쪽을 함께 싣는다', async () => {
    threePages();
    const { user } = renderAt(`${URL_ACME}&status=RECEIVED`);
    await issueRow('ACME-1');

    await user.click(await found(() => pageLink(2), '2쪽 링크'));

    await issueRow('ACME-2');
    const wanted = filters({ companyCode: 'ACME', status: ['RECEIVED'] });
    expect(filtersOf(addressQuery()), '주소의 쿼리').toEqual(wanted);
    expect(addressQuery().get('page'), '주소의 쪽').toBe('2');
    expect(filtersOf(lastList()), '목록 요청의 쿼리').toEqual(wanted);
    expect(lastList().get('page'), '요청의 쪽').toBe('2');
    expect(await statusBox('접수')).toBeChecked();
  });

  test('S3-AC-76 C-3 뒤로 가기: 필터를 건 채 2쪽으로 갔다가 뒤로 가면 같은 필터의 1쪽이 다시 나온다', async () => {
    threePages();
    const { user } = renderAt(`${URL_ACME}&status=RECEIVED`);
    await issueRow('ACME-1');
    await user.click(await found(() => pageLink(2), '2쪽 링크'));
    await issueRow('ACME-2');

    act(() => {
      window.history.back();
    });

    await issueRow('ACME-1');
    await eventually(() => {
      expect(rowsShown().map((r) => cellText(r, '키'))).toEqual(['ACME-1']);
    });
    expect(filtersOf(addressQuery()), '주소의 쿼리').toEqual(filters({ companyCode: 'ACME', status: ['RECEIVED'] }));
    expect(Number(addressQuery().get('page') ?? '1'), '주소의 쪽').toBe(1);
    expect(await statusBox('접수')).toBeChecked();
    expect(chosen(await select(/^고객사/))).toBe(ACME.name);
  });

  test('S3-AC-76 C-3 주소로 바로 열기: 쿼리의 값으로 필터를 그리고 그 값으로 부른다', async () => {
    state.companies = [ACME, BETA];
    state.issues = [summary({ key: 'BETA-4', companyCode: BETA.code, companyName: BETA.name })];
    renderAt('/issues?q=%ED%99%98%EB%B6%88&status=RECEIVED&status=CLOSED&companyCode=BETA&assignee=3&reporter=11');

    await issueRow('BETA-4');
    expect(filtersOf(lastList())).toEqual({ q: '환불', status: ['CLOSED', 'RECEIVED'], companyCode: 'BETA', assignee: '3', reporter: '11' });
    expect(await searchBox()).toHaveValue('환불');
    for (const [word, on_] of [['접수', true], ['확인', false], ['처리 중', false], ['해결', false], ['종료', true]] as const) {
      expect((await statusBox(word)).checked, `상태 "${word}"`).toBe(on_);
    }
    expect(chosen(await select(/^고객사/))).toBe(BETA.name);
    const assignee = await select(/^담당자/);
    await eventually(() => {
      expect(chosen(assignee)).toContain(OTHER_AGENT_PERSON.name);
    });
    // The name comes with the rows: 김고객 is the reporter of BETA-4.
    await eventually(() => {
      expect(pageText()).toContain('올린 사람: 김고객');
    });
    expect(currentViews()).toEqual([]);
  });

  test('S3-AC-76 C-3 주소의 틀린 값: 모르는 상태, 꼴이 틀린 담당자와 올린 사람은 그 값만 버리고 나머지로 부른다', async () => {
    renderAt('/issues?status=BOGUS&status=RECEIVED&assignee=somebody&reporter=kim&q=%EA%B2%B0%EC%A0%9C');

    await issueRow('ACME-7');
    expect(filtersOf(lastList())).toEqual(filters({ q: '결제', status: ['RECEIVED'] }));
    expect((await statusBox('접수')).checked).toBe(true);
    expect(chosen(await select(/^담당자/))).toBe('전체');
    expect(pageText()).not.toContain('올린 사람:');
  });

  test.each([
    { what: '목록의 줄에 그 사람이 있음', issues: [summary({ key: 'ACME-1', assignee: OTHER_AGENT_PERSON })], label: OTHER_AGENT_PERSON.name },
    { what: '줄이 없어 이름을 모름', issues: [], label: '다른 사람' },
  ])('S3-AC-76 C-3 후보에 없는 담당자($what): 고객사를 고르지 않고 assignee=계정 ID로 열면 담당자 고르기에 "$label"이 골라져 있고 요청의 assignee는 그 ID 그대로다', async ({ issues, label }) => {
    state.companies = [ACME, BETA];
    state.issues = issues;
    renderAt('/issues?assignee=3');

    await listed({ assignee: '3' });
    if (issues.length > 0) await issueRow('ACME-1');
    const assignee = await select(/^담당자/);
    await eventually(() => {
      expect(chosen(assignee)).toBe(label);
    });
    await settle();
    expect(chosen(assignee), '답이 다 온 뒤에도').toBe(label);
    expect(optionLabels(assignee).filter((l) => l === label), `선택지 "${label}"`).toHaveLength(1);
    expect(filtersOf(lastList()).assignee).toBe('3');
    expect(filtersOf(addressQuery()).assignee).toBe('3');
  });

  test('S3-AC-76 C-3 이름을 모르는 올린 사람: reporter=계정 ID로 열었는데 결과가 비면 조각은 "올린 사람: 한 명"이다', async () => {
    state.issues = [];
    renderAt('/issues?reporter=11');

    await listed({ reporter: '11' });
    await eventually(() => {
      expect(pageText()).toContain('올린 사람: 한 명');
    });
    await found(() => screen.queryByRole('button', { name: '올린 사람 필터 지우기' }), '"올린 사람 필터 지우기"');
  });

  test('S3-AC-76 C-3 필터 지우기: 필터가 걸려 있을 때만 있고, 누르면 "전체" 보기가 되고 검색 칸이 비워진다', async () => {
    const { user } = renderAt('/issues?q=%EA%B2%B0%EC%A0%9C&status=RECEIVED');
    await listed({ q: '결제', status: ['RECEIVED'] });
    expect(clearFilters()).toHaveLength(1);

    await user.click(clearFilters()[0] as HTMLElement);

    await listed({});
    expect(currentViews()).toEqual(['전체']);
    expect(await searchBox()).toHaveValue('');
    expect((await statusBox('접수')).checked).toBe(false);
    expect(clearFilters(), '"전체"에는 없다').toHaveLength(0);
  });

  test('S3-AC-76 C-3 올린 사람 조각: reporter가 걸려 있으면 필터 줄에 "올린 사람: 김고객"과 "올린 사람 필터 지우기"가 있다. 누르면 그 필터만 지운다. 줄에는 메뉴가 없다', async () => {
    const { user } = renderAt(`${URL_ACME}&reporter=11`);
    const row = await issueRow('ACME-7');
    await eventually(() => {
      expect(pageText()).toContain('올린 사람: 김고객');
    });
    expect(within(row).queryAllByRole('button'), '줄 안의 버튼').toHaveLength(0);

    await user.click(await found(() => screen.queryByRole('button', { name: '올린 사람 필터 지우기' }), '"올린 사람 필터 지우기"'));

    await listed({ companyCode: 'ACME' });
    expect(pageText()).not.toContain('올린 사람:');
    expect(screen.queryByRole('button', { name: '올린 사람 필터 지우기' })).toBeNull();
  });

  test('S3-AC-76 C-3 고객사를 바꾸면: 사람으로 건 담당자 필터는 비우고 "나"는 남긴다', async () => {
    state.companies = [ACME, BETA];
    const { user } = renderAt('/issues?companyCode=BETA&assignee=3');
    await listed({ companyCode: 'BETA', assignee: '3' });

    await user.selectOptions(await select(/^고객사/), ACME.name);
    await listed({ companyCode: 'ACME' });
    expect(chosen(await select(/^담당자/))).toBe('전체');

    await user.selectOptions(await select(/^담당자/), '나');
    await listed({ companyCode: 'ACME', assignee: 'me' });
    await user.selectOptions(await select(/^고객사/), BETA.name);
    await listed({ companyCode: 'BETA', assignee: 'me' });
  });

  test('S3-AC-78 C-3 표: "올린 사람" 다음에 담당자 칸. 이름, "담당 없음", 볼 수 없으면 이름 뒤에 글자 "볼 수 없음". 대리 등록한 문의의 제목 칸에 "대리 등록"', async () => {
    state.issues = [
      summary({ key: 'ACME-1', assignee: AGENT_PERSON }),
      summary({ key: 'ACME-2' }),
      summary({ key: 'ACME-3', assignee: OTHER_AGENT_PERSON, assigneeUnavailable: true, filedOnBehalf: true }),
    ];
    renderAt(URL_ACME);
    const [mine, none, gone] = [await issueRow('ACME-1'), await issueRow('ACME-2'), await issueRow('ACME-3')];

    const heads = screen.getAllByRole('columnheader').map(text);
    expect(heads.indexOf('담당자'), '담당자 칸의 자리').toBe(heads.indexOf('올린 사람') + 1);
    expect(cellText(mine, '담당자')).toBe('이상담');
    expect(cellText(none, '담당자')).toBe('담당 없음');
    expect(cellText(gone, '담당자')).toMatch(/^정상담\s*볼 수 없음$/);
    expect(cellText(gone, '제목')).toContain('대리 등록');
    expect(cellText(mine, '제목')).not.toContain('대리 등록');
  });

  test('S3-AC-76 C-3 거른 결과가 없음: "조건에 맞는 문의가 없습니다"와 "필터 지우기". 필터가 없을 때의 빈 문구는 쓰지 않는다', async () => {
    state.issues = [];
    const { user } = renderAt(URL_ACME);

    await eventually(() => {
      expect(pageText()).toContain('조건에 맞는 문의가 없습니다');
    });
    expect(pageText()).not.toContain('아직 문의가 없습니다.');
    // Two of them: the one of the filter row, and after it the one of the empty state.
    const main = screen.getByRole('main');
    const inMain = clearFilters()
      .filter((el) => main.contains(el))
      .sort(inDocumentOrder);
    expect(inMain, '필터 줄과 빈 상태의 "필터 지우기"').toHaveLength(2);
    const search = await searchBox();
    expect((inMain[1] as HTMLElement).compareDocumentPosition(search) & Node.DOCUMENT_POSITION_PRECEDING, '빈 상태의 것은 검색 칸 뒤에 있다').toBeTruthy();

    await user.click(inMain[1] as HTMLElement);
    await listed({});
    await eventually(() => {
      expect(pageText()).toContain('아직 문의가 없습니다.');
    });
  });

  test('S3-AC-77 C-3 "내 담당"이 빔: "담당인 열린 문의가 없습니다"와 "담당 없음 보기" 링크', async () => {
    state.me = AGENT;
    state.issues = [];
    const { user } = renderAt(URL_MINE);
    await listed(MINE);

    await eventually(() => {
      expect(pageText()).toContain('담당인 열린 문의가 없습니다');
    });
    expect(pageText()).not.toContain('조건에 맞는 문의가 없습니다');
    await user.click(await found(() => screen.queryByRole('link', { name: '담당 없음 보기' }), '링크 "담당 없음 보기"'));

    await listed(UNASSIGNED);
    expect(currentViews()).toEqual(['담당 없음']);
  });

  test('C-3 검색 429: 공통 띠를 보이고, 초가 지나면 마지막 검색어로 다시 부른다', async () => {
    useFakeClock();
    let limited = true;
    on('get', ISSUES, ({ request }) => {
      const q = new URL(request.url).searchParams.get('q');
      if (q === '결제' && limited) return errors.rateLimited(3);
      return HttpResponse.json(paged(q === '결제' ? [summary({ key: 'ACME-21', title: '결제 오류' })] : state.issues));
    });
    const { user } = renderAt(URL_ACME, { fakeTimers: true });
    await issueRow('ACME-7');

    await search(user, '결제');
    await alertWith('요청이 많습니다. 3초 뒤 다시 하세요.');
    const asked = callsTo('GET', ISSUES).filter((c) => new URL(c.url).searchParams.get('q') === '결제').length;

    limited = false;
    // The band can be drawn before the screen starts to count (the count starts in an effect after it),
    // so the clock moves a second at a time and the screen follows, until it asks again.
    const askedAgain = () => callsTo('GET', ISSUES).filter((c) => new URL(c.url).searchParams.get('q') === '결제').length > asked;
    for (let second = 0; second < 6 && !askedAgain(); second += 1) {
      await act(async () => {
        await vi.advanceTimersByTimeAsync(1000);
      });
    }

    await issueRow('ACME-21');
    expect(callsTo('GET', ISSUES).filter((c) => new URL(c.url).searchParams.get('q') === '결제')).toHaveLength(asked + 1);
    expect(await searchBox()).toHaveValue('결제');
  });

  test('S3-AC-87 C-3 늦게 온 답(검색): 검색어를 빨리 둘 바꾸면, 먼저 보낸 것이 늦게 와도 마지막 검색어의 결과만 보인다', async () => {
    const release = gate();
    on('get', ISSUES, async ({ request }) => {
      const q = new URL(request.url).searchParams.get('q');
      if (q === '결') {
        await release.opened;
        return HttpResponse.json(paged([summary({ key: 'ACME-31', title: '먼저 보낸 검색의 결과' })]));
      }
      return HttpResponse.json(paged(q === '결제' ? [summary({ key: 'ACME-32', title: '마지막 검색의 결과' })] : state.issues));
    });
    const { user } = renderAt(URL_ACME);
    await issueRow('ACME-7');

    await search(user, '결');
    await eventually(() => {
      expect(lastList().get('q')).toBe('결');
    });
    await search(user, '제');
    await issueRow('ACME-32');

    const done = answered('GET', ISSUES);
    release.open();
    await done;
    await settle();

    expect(rowsShown().map((r) => cellText(r, '키'))).toEqual(['ACME-32']);
    expect(addressQuery().get('q')).toBe('결제');
  });

  test('S3-AC-87 C-3 늦게 온 답(담당자 후보): 고객사를 바꾼 뒤에 온 앞 고객사의 후보는 담당자 선택지에 넣지 않는다', async () => {
    state.companies = [ACME, BETA];
    const release = gate();
    on('get', '/api/console/companies/BETA/assignees', async () => {
      await release.opened;
      return HttpResponse.json({ items: [OTHER_AGENT_PERSON] });
    });
    s3.candidates = [AGENT_PERSON];
    const { user } = renderAt('/issues?companyCode=BETA');
    await sent('GET', '/api/console/companies/BETA/assignees');

    await user.selectOptions(await select(/^고객사/), ACME.name);
    const assignee = await select(/^담당자/);
    await optionWith(assignee, AGENT_PERSON.name);

    const done = answered('GET', '/api/console/companies/BETA/assignees');
    release.open();
    await done;
    await settle();

    expect(optionLabels(assignee).some((label) => label.includes(OTHER_AGENT_PERSON.name)), 'BETA의 후보').toBe(false);
    expect(optionLabels(assignee).some((label) => label.includes(AGENT_PERSON.name)), 'ACME의 후보').toBe(true);
  });

  test('S3-AC-87 C-3 떠난 뒤 온 답: 걸러 부른 목록의 401이 C-19로 옮긴 뒤에 와도 주소를 /login으로 바꾸지 않는다', async () => {
    const release = gate();
    on('get', ISSUES, async ({ request }) => {
      if (new URL(request.url).searchParams.get('q') !== '결제') return HttpResponse.json(paged(state.issues));
      await release.opened;
      return errors.unauthenticated();
    });
    const { user } = renderAt(URL_ACME);
    await issueRow('ACME-7');
    await search(user, '결제');
    await eventually(() => {
      expect(lastList().get('q')).toBe('결제');
    });

    await user.click(await action('대리 등록'));
    await atPath('/issues/new');
    const done = answered('GET', ISSUES);
    release.open();
    await done;
    await settle();

    expect(window.location.pathname).toBe('/issues/new');
  });

  test('C-3 "대리 등록" 버튼: 머리에 있고 C-19로 간다. 고객사로 걸러져 있었으면 C-19에 그 고객사가 골라져 있다', async () => {
    state.companies = [ACME, BETA];
    const { user } = renderAt('/issues?companyCode=BETA');
    await listed({ companyCode: 'BETA' });
    const toCreate = await action('대리 등록');
    expect(toCreate.closest('table'), '표 밖').toBeNull();

    await user.click(toCreate);

    await atPath('/issues/new');
    expect(text(await heading1())).toBe('대리 등록');
    const company = await select(/^고객사/);
    await eventually(() => {
      expect(chosen(company)).toBe(BETA.name);
    });
  });

  test('C-3 "대리 등록" 버튼: 고를 고객사가 없는 상담원에게도 보인다', async () => {
    state.me = AGENT;
    state.companies = [];
    state.issues = [];
    renderAt('/issues');

    expect((await action('대리 등록')).closest('table'), '표 밖').toBeNull();
  });

  test('C-3 axe (3번 묶음): 보기 고르기, 필터 줄, 담당자 칸과 배지가 있는 표에 접근성 위반이 없다', async () => {
    state.companies = [ACME, BETA];
    state.issues = [
      summary({ key: 'ACME-1', assignee: AGENT_PERSON, filedOnBehalf: true }),
      summary({ key: 'ACME-3', assignee: OTHER_AGENT_PERSON, assigneeUnavailable: true }),
    ];
    renderAt('/issues?companyCode=ACME&status=RECEIVED&q=%EA%B2%B0%EC%A0%9C&reporter=11');
    await issueRow('ACME-3');
    await view('전체');
    await statusGroup();
    await searchBox();

    await expectNoAxeViolations();
  });
});
