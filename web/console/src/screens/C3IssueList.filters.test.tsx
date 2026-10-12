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
import { answered, rowAction, settle } from '../__tests__/console1a';
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
/** An address with one filter, so the screen does not move to 내 담당. */
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

async function search(user: User, words: string) {
  await user.type(await searchBox(), `${words}{Enter}`);
}

describe('C-3 문의 목록 (3번 묶음)', () => {
  test('S3-AC-77 C-3 처음: 쿼리 없이 /issues로 오면 주소를 "내 담당"으로 바꾸고(replace) 첫 요청부터 그 필터로 부른다', async () => {
    const before = window.history.length;
    renderAt('/issues');

    await listed(MINE);
    await issueRow('ACME-7');
    expect(currentViews()).toEqual(['내 담당']);
    expect(callsTo('GET', ISSUES).map((c) => new URL(c.url).searchParams.get('assignee')), '거르지 않은 요청이 없다').not.toContain(null);
    expect(window.history.length, '뒤로 가기에 빈 주소를 남기지 않는다').toBe(before);
  });

  test('S3-AC-77 C-3 보기 고르기: nav 안의 링크 "내 담당", "담당 없음", "전체". 누르면 그 필터로 바뀌고 aria-current가 옮겨 간다. "전체"는 "내 담당"으로 돌아가지 않는다', async () => {
    const { user } = renderAt('/issues');
    await listed(MINE);

    await user.click(await view('담당 없음'));
    await listed(UNASSIGNED);
    expect(currentViews()).toEqual(['담당 없음']);

    await user.click(await view('전체'));
    await listed({});
    await settle();
    expect(currentViews()).toEqual(['전체']);
    expect(filtersOf(lastList()), '"전체"에 머문다').toEqual(filters());

    await user.click(await view('내 담당'));
    await listed(MINE);
    expect(currentViews()).toEqual(['내 담당']);
  });

  test('S3-AC-77 C-3 보기와 필터: 필터를 손으로 바꿔 세 보기 어느 것과도 다르면 아무 보기도 골라지지 않는다', async () => {
    const { user } = renderAt('/issues');
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

    await user.selectOptions(await select(/^고객사/), BETA.name);
    await eventually(() => {
      expect(addressQuery().get('companyCode')).toBe('BETA');
      expect(lastList().get('companyCode')).toBe('BETA');
    });
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

  test('S3-AC-76 C-3 올린 사람으로 거르기: 줄의 올린 사람 메뉴 "이 사람이 올린 문의만"을 누르면 reporter를 싣고 필터 줄에 "올린 사람: 김고객" 조각이 생긴다', async () => {
    const { user } = renderAt(URL_ACME);
    const row = await issueRow('ACME-7');
    expect(pageText()).not.toContain('올린 사람:');

    await user.click(await rowAction(user, row, '이 사람이 올린 문의만'));

    await listed({ companyCode: 'ACME', reporter: '11' });
    await eventually(() => {
      expect(pageText()).toContain('올린 사람: 김고객');
    });
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
    const main = screen.getByRole('main');
    const inEmpty = clearFilters().filter((el) => main.contains(el));
    expect(inEmpty.length, '"필터 지우기"').toBeGreaterThan(0);

    await user.click(inEmpty.at(-1) as HTMLElement);
    await listed({});
    await eventually(() => {
      expect(pageText()).toContain('아직 문의가 없습니다.');
    });
  });

  test('S3-AC-77 C-3 "내 담당"이 빔: "담당인 열린 문의가 없습니다"와 "담당 없음 보기" 링크', async () => {
    state.me = AGENT;
    state.issues = [];
    const { user } = renderAt('/issues');
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
    act(() => {
      vi.advanceTimersByTime(3100);
    });

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
    await listed(MINE);

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
