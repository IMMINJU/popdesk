// 3번 묶음 screens.md 2절 P-3 문의 목록: 필터 줄, 좁은 화면, 줄의 배지와 "(지원팀이 대신 올림)", 늦게 온 답.
import { HttpResponse } from 'msw/http';
import { describe, expect, test } from 'vitest';
import {
  ACME,
  BETA,
  COLLEAGUE,
  action,
  atPath,
  errors,
  eventually,
  expectNoAxeViolations,
  found,
  gate,
  hrefPath,
  link,
  on,
  paged,
  pageText,
  renderAt,
  screen,
  state,
  summary,
  text,
  within,
} from '../__tests__/portal';
import { answered, settle } from '../__tests__/portal1a';
import { belongTo } from '../__tests__/portal2';
import { ISSUES, STATUS_WORDS, addressQuery, chosen, filters, filtersOf, lastList, listed, narrowWindow, select } from '../__tests__/portal3';

type User = ReturnType<typeof renderAt>['user'];

const SUPPORT_FILED = '(지원팀이 대신 올림)';
const NEEDS_CHECK = '확인이 필요합니다';
const NO_MATCH = '조건에 맞는 문의가 없습니다';
const NO_ISSUES = '아직 올린 문의가 없습니다.';

const searchBox = () => found(() => screen.queryByRole<HTMLInputElement>('searchbox', { name: '문의 검색' }), '검색 칸 "문의 검색"');
const statusGroup = () => found(() => screen.queryByRole('group', { name: '상태' }), '묶음 "상태"');
const statusBox = async (word: string) => within(await statusGroup()).getByRole<HTMLInputElement>('checkbox', { name: word });
const mineBox = () => found(() => screen.queryByRole<HTMLInputElement>('checkbox', { name: '내가 올린 문의만' }), '체크 "내가 올린 문의만"');
const companySelects = () => screen.queryAllByLabelText(/^고객사/, { selector: 'select' });
const clearFilters = () => [...screen.queryAllByRole('link', { name: '필터 지우기' }), ...screen.queryAllByRole('button', { name: '필터 지우기' })];
const rowKeys = () =>
  screen
    .queryAllByRole<HTMLAnchorElement>('link')
    .map((a) => /^\/issues\/([A-Z0-9]+-\d+)$/.exec(hrefPath(a))?.[1])
    .filter((key) => key !== undefined);

async function search(user: User, words: string) {
  await user.type(await searchBox(), `${words}{Enter}`);
}

describe('P-3 문의 목록 (3번 묶음)', () => {
  test('S3-AC-76 P-3 쿼리 없이 오면: 필터 없는 전체다. 보기 고르기와 "필터 지우기"가 없고, 소속이 하나면 고객사 필터도 없다. 상태의 말은 고객의 말이다', async () => {
    renderAt('/issues');

    await searchBox();
    for (const word of Object.values(STATUS_WORDS)) expect(await statusBox(word), `상태 "${word}"`).not.toBeChecked();
    expect(await mineBox()).not.toBeChecked();
    await link(/ACME-7/);
    expect(window.location.search).toBe('');
    expect(filtersOf(lastList())).toEqual(filters());
    expect(companySelects(), '소속이 하나일 때 고객사 필터').toHaveLength(0);
    expect(clearFilters(), '"필터 지우기"').toHaveLength(0);
    for (const view of ['내 담당', '담당 없음', '전체']) expect(screen.queryByRole('link', { name: view }), `보기 "${view}"`).toBeNull();
  });

  test('S3-AC-76 P-3 필터 줄: 검색, 상태, 고객사, "내가 올린 문의만"을 바꿀 때마다 주소의 쿼리와 목록 요청의 쿼리가 같은 값을 싣는다', async () => {
    belongTo(ACME, BETA);
    const { user } = renderAt('/issues');
    await link(/ACME-7/);

    await search(user, '결제');
    await listed({ q: '결제' });

    await user.click(await statusBox('접수됨'));
    await user.click(await statusBox('종료됨'));
    await listed({ q: '결제', status: ['RECEIVED', 'CLOSED'] });

    await user.selectOptions(await select(/^고객사/), BETA.name);
    await listed({ q: '결제', status: ['RECEIVED', 'CLOSED'], companyCode: 'BETA' });

    await user.click(await mineBox());
    await listed({ q: '결제', status: ['RECEIVED', 'CLOSED'], companyCode: 'BETA', mine: 'true' });

    await user.click(await mineBox());
    await user.click(await statusBox('접수됨'));
    await listed({ q: '결제', status: ['CLOSED'], companyCode: 'BETA' });
  });

  test('S3-AC-76 P-3 필터를 바꾸면 1쪽으로: 3쪽에서 상태를 고르면 주소와 요청의 쪽이 1이다', async () => {
    on('get', ISSUES, ({ request }) => {
      const page = Number(new URL(request.url).searchParams.get('page') ?? '1');
      return HttpResponse.json(paged([summary({ key: `ACME-${String(page)}` })], { page, totalElements: 45, totalPages: 3 }));
    });
    const { user } = renderAt('/issues?mine=true&page=3');
    await link(/ACME-3\b/);
    expect(lastList().get('page')).toBe('3');

    await user.click(await statusBox('해결됨'));

    await listed({ mine: 'true', status: ['RESOLVED'] });
    await link(/ACME-1\b/);
  });

  test('S3-AC-76 P-3 주소로 바로 열기: 쿼리의 값으로 필터를 그리고 그 값으로 부른다', async () => {
    belongTo(ACME, BETA);
    state.issues = [summary({ key: 'BETA-4', companyCode: BETA.code, companyName: BETA.name })];
    renderAt('/issues?q=%ED%99%98%EB%B6%88&status=RESOLVED&status=CLOSED&companyCode=BETA&mine=true');

    await link(/BETA-4/);
    expect(filtersOf(lastList())).toEqual({ q: '환불', status: ['CLOSED', 'RESOLVED'], companyCode: 'BETA', mine: 'true' });
    expect(await searchBox()).toHaveValue('환불');
    for (const [word, checked] of [['접수됨', false], ['확인됨', false], ['처리 중', false], ['해결됨', true], ['종료됨', true]] as const) {
      expect((await statusBox(word)).checked, `상태 "${word}"`).toBe(checked);
    }
    expect(chosen(await select(/^고객사/))).toBe(BETA.name);
    expect(await mineBox()).toBeChecked();
  });

  test('S3-AC-76 P-3 필터 지우기: 필터가 걸려 있을 때만 있고, 누르면 쿼리 없는 전체가 되고 검색 칸과 체크가 비워진다', async () => {
    const { user } = renderAt('/issues?q=%EA%B2%B0%EC%A0%9C&status=RECEIVED&mine=true');
    await listed({ q: '결제', status: ['RECEIVED'], mine: 'true' });
    expect(clearFilters()).toHaveLength(1);

    await user.click(clearFilters()[0] as HTMLElement);

    await listed({});
    expect(window.location.search).toBe('');
    expect(await searchBox()).toHaveValue('');
    expect(await statusBox('접수됨')).not.toBeChecked();
    expect(await mineBox()).not.toBeChecked();
    expect(clearFilters(), '필터가 없으면 없다').toHaveLength(0);
  });

  test('S3-AC-76 P-3 거른 결과가 없음: "조건에 맞는 문의가 없습니다"와 "필터 지우기". 필터가 없을 때의 빈 문구는 쓰지 않는다', async () => {
    state.issues = [];
    const { user } = renderAt('/issues?status=CLOSED');

    await eventually(() => {
      expect(pageText()).toContain(NO_MATCH);
    });
    expect(pageText()).not.toContain(NO_ISSUES);
    const main = screen.getByRole('main');
    const inMain = clearFilters().filter((el) => main.contains(el));
    expect(inMain.length, '"필터 지우기"').toBeGreaterThan(0);

    await user.click(inMain.at(-1) as HTMLElement);
    await listed({});
    await eventually(() => {
      expect(pageText()).toContain(NO_ISSUES);
    });
    expect(pageText()).not.toContain(NO_MATCH);
  });

  test('P-3 좁은 화면(640px 아래): 검색만 보이고 "필터" 버튼으로 상태와 "내가 올린 문의만"이 펼쳐진다', async () => {
    narrowWindow();
    const { user } = renderAt('/issues');

    await searchBox();
    const toggle = await found(() => screen.queryByRole('button', { name: '필터' }), '버튼 "필터"');
    expect(toggle).toHaveAttribute('aria-expanded', 'false');
    expect(screen.queryByRole('group', { name: '상태' }), '접힌 상태 필터').toBeNull();
    expect(screen.queryByRole('checkbox', { name: '내가 올린 문의만' }), '접힌 "내가 올린 문의만"').toBeNull();

    await user.click(toggle);

    expect(toggle).toHaveAttribute('aria-expanded', 'true');
    await statusBox('접수됨');
    await mineBox();
  });

  test('S3-AC-83 P-3 줄: 해결된 내 문의에만 "확인이 필요합니다", 대리 등록한 문의의 올린 사람은 "나 (지원팀이 대신 올림)"이나 "이름 (지원팀이 대신 올림)"', async () => {
    state.issues = [
      summary({ key: 'ACME-1', status: 'RESOLVED' }),
      summary({ key: 'ACME-2', status: 'RESOLVED', reporter: COLLEAGUE }),
      summary({ key: 'ACME-3', filedBySupport: true }),
      summary({ key: 'ACME-4', filedBySupport: true, reporter: COLLEAGUE }),
    ];
    renderAt('/issues');

    const [mine, theirs, filedForMe, filedForThem] = [await link(/ACME-1\b/), await link(/ACME-2\b/), await link(/ACME-3\b/), await link(/ACME-4\b/)];
    await eventually(() => {
      expect(text(mine), '해결된 내 문의').toContain(NEEDS_CHECK);
    });
    expect(text(theirs), '해결된 동료의 문의').not.toContain(NEEDS_CHECK);
    expect(text(filedForMe), '해결되지 않은 내 문의').not.toContain(NEEDS_CHECK);
    expect(text(filedForMe)).toContain(`나 ${SUPPORT_FILED}`);
    expect(text(filedForThem)).toContain(`박동료 ${SUPPORT_FILED}`);
    expect(text(mine), '고객이 스스로 올린 문의').not.toContain(SUPPORT_FILED);
  });

  test('S3-AC-87 P-3 늦게 온 답(검색): 검색어를 빨리 둘 바꾸면, 먼저 보낸 것이 늦게 와도 마지막 검색어의 결과만 보인다', async () => {
    const release = gate();
    on('get', ISSUES, async ({ request }) => {
      const q = new URL(request.url).searchParams.get('q');
      if (q === '결') {
        await release.opened;
        return HttpResponse.json(paged([summary({ key: 'ACME-31', title: '먼저 보낸 검색의 결과' })]));
      }
      return HttpResponse.json(paged(q === '결제' ? [summary({ key: 'ACME-32', title: '마지막 검색의 결과' })] : state.issues));
    });
    const { user } = renderAt('/issues');
    await link(/ACME-7/);

    await search(user, '결');
    await eventually(() => {
      expect(lastList().get('q')).toBe('결');
    });
    await search(user, '제');
    await link(/ACME-32/);

    const done = answered('GET', ISSUES);
    release.open();
    await done;
    await settle();

    expect(rowKeys()).toEqual(['ACME-32']);
    expect(addressQuery().get('q')).toBe('결제');
  });

  test('S3-AC-87 P-3 떠난 뒤 온 답: 걸러 부른 목록의 401이 P-4로 옮긴 뒤에 와도 주소를 /login으로 바꾸지 않는다', async () => {
    const release = gate();
    on('get', ISSUES, async ({ request }) => {
      if (new URL(request.url).searchParams.get('q') !== '결제') return HttpResponse.json(paged(state.issues));
      await release.opened;
      return errors.unauthenticated();
    });
    const { user } = renderAt('/issues');
    await link(/ACME-7/);
    await search(user, '결제');
    await eventually(() => {
      expect(lastList().get('q')).toBe('결제');
    });

    await user.click(await action('문의 올리기'));
    await atPath('/issues/new');
    const done = answered('GET', ISSUES);
    release.open();
    await done;
    await settle();

    expect(window.location.pathname).toBe('/issues/new');
  });

  test('P-3 axe (3번 묶음): 필터 줄과 배지가 있는 목록에 접근성 위반이 없다', async () => {
    belongTo(ACME, BETA);
    state.issues = [summary({ key: 'ACME-1', status: 'RESOLVED' }), summary({ key: 'ACME-4', filedBySupport: true, reporter: COLLEAGUE })];
    renderAt('/issues?companyCode=ACME&status=RESOLVED&q=%EA%B2%B0%EC%A0%9C&mine=true');
    await link(/ACME-4/);
    await statusGroup();
    await searchBox();
    await select(/^고객사/);

    await expectNoAxeViolations();
  });
});
