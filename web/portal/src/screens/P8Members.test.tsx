// 2번 묶음 screens.md 1절 P-8 우리 회사 사람: 포털 메뉴(S2-AC-45), 고객사 고르기, 표(S2-AC-08, 10), 줄 메뉴(S2-AC-47).
// 동작과 초대 대화상자는 P8Members.actions.test.tsx, 늦게 온 답은 P8Members.late.test.tsx에 있다.
import { describe, expect, test } from 'vitest';
import { callsTo, dialog, eventually, expectNoAxeViolations, heading1, hrefPath, link, pageText, renderAt, screen, sent, text } from '../__tests__/portal';
import {
  ACME_MANAGED,
  BETA_MANAGED,
  BETA_ROW,
  COLLEAGUE,
  COLUMNS,
  GAMMA_MEMBER,
  INVITED_ROW,
  MY_ROW,
  OTHER_MANAGER,
  SUSPENDED_ROW,
  belongTo,
  cellOf,
  choose,
  colleagues,
  companyInAddress,
  companySelect,
  forbiddenScreen,
  main,
  membersPath,
  offered,
  people,
  rowActions,
  rowOf,
} from '../__tests__/portal2';

const URL_PEOPLE = '/members';
const inviteButtons = () => screen.queryAllByRole('button', { name: /^초대$/ });

describe('P-8 우리 회사 사람: 포털 메뉴', () => {
  test('S2-AC-45 포털 메뉴: 담당자인 고객사가 하나라도 있으면 메뉴에 "문의"와 "우리 회사 사람"이 있다', async () => {
    belongTo(GAMMA_MEMBER, ACME_MANAGED);
    renderAt('/issues');

    const peopleLink = await link('우리 회사 사람');
    expect(hrefPath(peopleLink)).toBe(URL_PEOPLE);
    expect(peopleLink.closest('nav'), '메뉴 안').not.toBeNull();
    const issues = await link('문의');
    expect(hrefPath(issues)).toBe('/issues');
    expect(issues.closest('nav')).toBe(peopleLink.closest('nav'));
  });

  test('S2-AC-45 포털 메뉴: 담당자인 고객사가 없으면 길이 없고, 주소로 오면 403 상태', async () => {
    belongTo({ ...ACME_MANAGED, manager: false });
    renderAt(URL_PEOPLE);

    await forbiddenScreen();
    expect(screen.queryByRole('link', { name: '우리 회사 사람' })).toBeNull();
    expect(screen.queryByRole('link', { name: '문의' }), '항목이 하나뿐인 메뉴는 그리지 않는다').toBeNull();
    expect(pageText()).not.toContain(COLLEAGUE.email);
  });
});

describe('P-8 우리 회사 사람: 고객사 고르기', () => {
  test('P-8 고객사 고르기(담당자인 고객사가 하나): 고르기 없이 제목 옆에 고객사 이름', async () => {
    belongTo(ACME_MANAGED, GAMMA_MEMBER);
    renderAt(URL_PEOPLE);

    await rowOf(COLLEAGUE.email);
    expect(text(await heading1())).toContain('우리 회사 사람');
    expect(screen.queryAllByRole('combobox'), '고객사 고르기').toHaveLength(0);
    expect(text(main())).toContain(ACME_MANAGED.name);
    expect(text(main())).not.toContain(GAMMA_MEMBER.name);
  });

  test('P-8 고객사 고르기(둘 이상): Select에 내가 담당자인 고객사만 나오고, 고른 고객사가 주소의 쿼리에 실린다', async () => {
    belongTo(ACME_MANAGED, BETA_MANAGED, GAMMA_MEMBER);
    const { user } = renderAt(`${URL_PEOPLE}?company=BETA`);

    // The address says which company: BETA is not the first one.
    await rowOf(BETA_ROW.email);
    const select = await companySelect();
    const choices = offered(select);
    expect(choices).toHaveLength(2);
    expect(choices.filter((c) => c.includes(ACME_MANAGED.name))).toHaveLength(1);
    expect(choices.filter((c) => c.includes(BETA_MANAGED.name))).toHaveLength(1);
    expect(text(select.selectedOptions[0])).toContain(BETA_MANAGED.name);

    await choose(user, ACME_MANAGED);

    await eventually(() => {
      expect(companyInAddress()).toBe('ACME');
    });
    await rowOf(COLLEAGUE.email);
    expect(pageText()).not.toContain(BETA_ROW.email);
    await sent('GET', membersPath('ACME'));
  });

  test.each([
    ['소속이지만 담당자가 아닌 곳이면 403 상태', 'GAMMA', '권한이 없습니다'],
    ['소속이 아닌 곳이면 404 상태', 'NOPE', '찾을 수 없습니다'],
  ])('S2-AC-45 P-8 쿼리의 고객사가 %s', async (_, code, wanted) => {
    belongTo(ACME_MANAGED, GAMMA_MEMBER);
    renderAt(`${URL_PEOPLE}?company=${code}`);

    await eventually(() => {
      expect(pageText()).toContain(wanted);
    });
    expect(screen.queryAllByRole('table'), '표').toHaveLength(0);
    expect(inviteButtons(), '"초대" 버튼').toHaveLength(0);
    expect(pageText()).not.toContain(COLLEAGUE.email);
  });

  test.each([
    ['멈춘 고객사: 띠 "이 고객사는 멈춰 있습니다", "초대" 버튼은 있다', 'SUSPENDED', '이 고객사는 멈춰 있습니다', 1],
    ['보관된 고객사: 띠 "이 고객사는 보관되어 초대할 수 없습니다", "초대" 버튼이 없다', 'ARCHIVED', '이 고객사는 보관되어 초대할 수 없습니다', 0],
  ] as const)('P-8 %s', async (_, status, band, invites) => {
    belongTo({ ...ACME_MANAGED, status });
    renderAt(URL_PEOPLE);

    await eventually(() => {
      expect(pageText()).toContain(band);
    });
    await rowOf(COLLEAGUE.email);
    expect(inviteButtons(), '"초대" 버튼').toHaveLength(invites);
  });
});

describe('P-8 우리 회사 사람: 표', () => {
  test('S2-AC-08 S2-AC-10 P-8 표: 칸 다섯, 내 행의 "(나)", 상태 배지의 말, "담당자" 배지. 사진과 시각은 없다', async () => {
    belongTo(ACME_MANAGED);
    renderAt(URL_PEOPLE);

    const mine = await rowOf(MY_ROW.email);
    expect(screen.getAllByRole('columnheader').map((h) => text(h))).toEqual([...COLUMNS]);
    const rows = { colleague: await rowOf(COLLEAGUE.email), manager: await rowOf(OTHER_MANAGER.email), suspended: await rowOf(SUSPENDED_ROW.email), invited: await rowOf(INVITED_ROW.email) };

    expect(text(cellOf(mine, '이름'))).toContain(MY_ROW.name);
    expect(text(cellOf(mine, '이름'))).toContain('(나)');
    expect(text(cellOf(rows.manager, '이름'))).toContain(OTHER_MANAGER.name);
    expect(text(cellOf(rows.manager, '이름'))).not.toContain('(나)');
    expect(text(cellOf(rows.colleague, '이메일'))).toBe(COLLEAGUE.email);
    expect([rows.invited, rows.colleague, rows.suspended].map((r) => text(cellOf(r, '상태')))).toEqual(['수락 전', '사용 중', '정지']);
    expect([mine, rows.manager, rows.colleague, rows.invited].map((r) => text(cellOf(r, '담당자')))).toEqual(['담당자', '담당자', '', '']);

    const table = screen.getByRole('table');
    expect(table.querySelectorAll('img'), '사진').toHaveLength(0);
    expect(table.querySelectorAll('time'), '시각').toHaveLength(0);
  });

  test('P-8 표 없는 쪽: "이 쪽에는 항목이 없습니다"와 "1쪽으로"', async () => {
    belongTo(ACME_MANAGED);
    renderAt(`${URL_PEOPLE}?page=9`);

    await eventually(() => {
      expect(pageText()).toContain('이 쪽에는 항목이 없습니다');
    });
    expect(hrefPath(await link('1쪽으로'))).toBe(URL_PEOPLE);
    expect(new URL(callsTo('GET', membersPath('ACME')).at(-1)?.url ?? 'http://x/').searchParams.get('page')).toBe('9');
  });

  test('P-8 표 고객사를 바꿈: 1쪽으로 돌아가고 그 고객사의 표를 다시 불러온다', async () => {
    belongTo(ACME_MANAGED, BETA_MANAGED);
    const many = colleagues(25);
    people.rows = { ...people.rows, ACME: many };
    const { user } = renderAt(`${URL_PEOPLE}?company=ACME&page=2`);
    await rowOf(many[20]?.email ?? '');

    await choose(user, BETA_MANAGED);

    await rowOf(BETA_ROW.email);
    const query = new URL(callsTo('GET', membersPath('BETA')).at(-1)?.url ?? 'http://x/').searchParams;
    expect(query.get('page') ?? '1').toBe('1');
    expect(new URLSearchParams(window.location.search).get('page') ?? '1').toBe('1');
    expect(companyInAddress()).toBe('BETA');
  });
});

describe('P-8 우리 회사 사람: 줄 메뉴', () => {
  test.each([
    ['내 행에는 메뉴가 없다', MY_ROW, []],
    ['다른 담당자의 행에는 메뉴가 없다', OTHER_MANAGER, []],
    ['수락 전 행에는 "초대 다시 보내기"와 "초대 취소"', INVITED_ROW, ['초대 다시 보내기', '초대 취소']],
    ['사용 중인 행에는 "소속에서 빼기"', COLLEAGUE, ['소속에서 빼기']],
    ['정지된 행에는 "소속에서 빼기"', SUSPENDED_ROW, ['소속에서 빼기']],
  ])('S2-AC-47 P-8 줄 메뉴: %s', async (_, row, wanted) => {
    belongTo(ACME_MANAGED);
    const { user } = renderAt(URL_PEOPLE);

    expect(await rowActions(user, await rowOf(row.email))).toEqual([...wanted].sort());
  });

  test('P-8 axe: 표와 열린 초대 대화상자에 접근성 위반이 없다', async () => {
    belongTo(ACME_MANAGED, BETA_MANAGED);
    const { user } = renderAt(URL_PEOPLE);
    await rowOf(COLLEAGUE.email);
    await user.click(inviteButtons()[0] ?? main());
    expect(text(await dialog())).toContain('초대 메일이 가거나');

    await expectNoAxeViolations();
  });
});
