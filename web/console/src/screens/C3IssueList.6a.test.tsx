// 6a 묶음 screens.md 2절 C-3 문의 목록: "라벨", "마감일" 열, 고객사를 고른 뒤의 "라벨" 필터(label 여러 번),
// "정렬"의 "마감일순"(sort=dueDate), 주소에 남는 label과 sort. 고객사를 바꾼 뒤 온 옛 라벨 목록은 버린다
// (S6A-AC-82, S6A-AC-86).
import { HttpResponse } from 'msw/http';
import { describe, expect, test } from 'vitest';
import { ACME, BETA, eventually, expectNoAxeViolations, found, gate, on, renderAt, screen, sent, state, summary, text, within } from '../__tests__/console';
import { answered, settle } from '../__tests__/console1a';
import { ISSUES, addressQuery, cellText, chosen, issueRow, lastList, optionLabels, select } from '../__tests__/console3';
import { ACME_LABELS, BETA_LABELS, BETA_ONLY, BILLING, MOBILE, URGENT, boxIn, contract, labelParams, refOf, s6a } from '../__tests__/console6a';

type User = ReturnType<typeof renderAt>['user'];

const URL_ALL = '/issues';
const URL_ACME = '/issues?companyCode=ACME';
const THIS_YEAR = new Date().getFullYear();

const labelGroup = () => screen.queryByRole('group', { name: '라벨' });
/** A folded control that opens the 라벨 filter, if it is drawn folded. */
const labelOpener = () =>
  screen.queryAllByRole('button').find((b) => b.getAttribute('aria-expanded') === 'false' && /라벨/.test(b.getAttribute('aria-label') ?? text(b))) ?? null;

/** The boxes of the 라벨 filter, opened if it is folded. */
async function labelFilter(user: User): Promise<HTMLElement> {
  await eventually(() => {
    expect(labelGroup() ?? labelOpener(), '"라벨" 필터').not.toBeNull();
  });
  const opener = labelGroup() ? null : labelOpener();
  if (opener) await user.click(opener);
  return found(labelGroup, '"라벨" 필터의 상자들');
}

function filterBox(group: HTMLElement, name: string): HTMLInputElement {
  const one = boxIn(group, name);
  expect(one, `라벨 필터의 "${name}"`).not.toBeNull();
  return one as HTMLInputElement;
}

const sorted = (values: readonly string[]) => [...values].sort();

describe('C-3 문의 목록 라벨과 마감일 (6a 묶음)', () => {
  test('S6A-AC-82 C-3 열: "담당자" 다음에 "라벨", "마감일"이 있고 그 뒤에 "올린 시각"이다', async () => {
    renderAt(URL_ALL);
    await issueRow('ACME-7');

    const headers = screen.queryAllByRole('columnheader').map(text);
    const at = headers.findIndex((h) => h.includes('담당자'));
    expect(headers.slice(at + 1, at + 3), '"담당자" 뒤의 열').toEqual(['라벨', '마감일']);
    expect(headers[at + 3]).toContain('올린 시각');
  });

  test('S6A-AC-82 C-3 라벨 칸: 칩 셋까지 보이고 넘으면 "+n"이다. 가린 이름은 title과 화면 읽기 글자에 있다', async () => {
    const four = [refOf(BILLING), refOf(URGENT), refOf(MOBILE), { id: 64, name: '보안', color: '#1F7A3A' }];
    state.issues = [summary({ key: 'ACME-7', labels: four }), summary({ key: 'ACME-8', labels: [refOf(URGENT)] })];
    renderAt(URL_ALL);

    const row = await issueRow('ACME-7');
    await eventually(() => {
      expect(cellText(row, '라벨')).toContain('+1');
    });
    const cell = cellText(row, '라벨');
    for (const name of ['결제', '긴급', '모바일']) expect(cell).toContain(name);
    expect(cell, '가린 이름의 화면 읽기 글자').toContain('보안');
    const plus = within(row).queryAllByTitle(/보안/);
    expect(plus, '"+1"의 title').toHaveLength(1);
    expect(text(plus[0])).toContain('+1');

    const one = await issueRow('ACME-8');
    expect(cellText(one, '라벨')).toContain('긴급');
    expect(cellText(one, '라벨')).not.toMatch(/\+\d/);
  });

  test.each([
    { what: '올해', dueDate: `${String(THIS_YEAR)}-07-15`, overdue: false, shows: '7월 15일', not: [`${String(THIS_YEAR)}년`, '지남'] },
    { what: '올해가 아님', dueDate: '2099-10-20', overdue: false, shows: '2099년 10월 20일', not: ['지남'] },
    { what: '지남(overdue)', dueDate: '2020-03-04', overdue: true, shows: '2020년 3월 4일', not: [] },
  ])('S6A-AC-82 C-3 마감일 칸($what): "$shows"', async ({ dueDate, overdue, shows, not }) => {
    state.issues = [summary({ dueDate, overdue })];
    renderAt(URL_ALL);

    const row = await issueRow('ACME-7');
    await eventually(() => {
      expect(cellText(row, '마감일')).toContain(shows);
    });
    for (const part of not) expect(cellText(row, '마감일')).not.toContain(part);
    if (overdue) expect(cellText(row, '마감일')).toContain('지남');
  });

  test('S6A-AC-81 C-3 "지남"은 서버의 overdue만 본다: 지난 날짜라도 overdue가 false면 없다', async () => {
    state.issues = [summary({ key: 'ACME-7', dueDate: '2020-03-04', overdue: false }), summary({ key: 'ACME-8', dueDate: '2099-03-04', overdue: true })];
    renderAt(URL_ALL);

    const late = await issueRow('ACME-8');
    await eventually(() => {
      expect(cellText(late, '마감일')).toContain('지남');
    });
    expect(cellText(await issueRow('ACME-7'), '마감일')).not.toContain('지남');
  });

  test('S6A-AC-82 C-3 라벨 필터: 고객사를 고르기 전에는 없고, 고르면 그 고객사의 라벨로 생긴다. 고른 라벨마다 요청에 label(정수)이 한 번씩 가고 주소에 남는다', async () => {
    state.companies = [ACME, BETA];
    const { user } = renderAt(URL_ALL);
    await issueRow('ACME-7');
    await select('고객사');
    expect(labelGroup(), '고객사를 고르기 전의 라벨 필터').toBeNull();
    expect(labelOpener(), '고객사를 고르기 전의 라벨 필터').toBeNull();

    await user.selectOptions(await select('고객사'), 'ACME');
    await sent('GET', ACME_LABELS);
    let group = await labelFilter(user);
    expect(boxIn(group, BETA_ONLY.name), '다른 고객사의 라벨').toBeNull();
    await user.click(filterBox(group, '긴급'));
    await eventually(() => {
      expect(labelParams()).toEqual(['61']);
    });
    group = await labelFilter(user);
    await user.click(filterBox(group, '모바일'));

    await eventually(() => {
      expect(sorted(labelParams()), '목록 요청의 label').toEqual(['61', '63']);
    });
    expect(sorted(addressQuery().getAll('label')), '주소의 label').toEqual(['61', '63']);
    expect(lastList().get('companyCode')).toBe('ACME');
  });

  test.each([
    { what: '다른 고객사로 바꾸면', pick: 'BETA', code: 'BETA' },
    { what: '고객사를 지우면', pick: '', code: null },
  ])('S6A-AC-82 C-3 라벨 필터 지우기($what): 주소와 요청의 label이 없어진다', async ({ pick, code }) => {
    state.companies = [ACME, BETA];
    const { user } = renderAt(`${URL_ACME}&label=61`);
    await eventually(() => {
      expect(labelParams()).toEqual(['61']);
    });

    await user.selectOptions(await select('고객사'), pick);

    await eventually(() => {
      expect(lastList().get('companyCode')).toBe(code);
      expect(labelParams(), '요청의 label').toEqual([]);
    });
    expect(addressQuery().getAll('label'), '주소의 label').toEqual([]);
  });

  test('S6A-AC-82 C-3 정렬: "정렬"에 "올린 시각순"(기본)과 "마감일순"이 있고, "마감일순"이 요청과 주소에 sort=dueDate를 남긴다', async () => {
    const { user } = renderAt(URL_ALL);
    await issueRow('ACME-7');
    const sort = await select('정렬');
    expect(optionLabels(sort)).toEqual(expect.arrayContaining(['올린 시각순', '마감일순']));
    expect(chosen(sort)).toBe('올린 시각순');

    const option = Array.from(sort.options).find((o) => text(o) === '마감일순');
    expect(option, '"마감일순"').toBeDefined();
    await user.selectOptions(sort, option as HTMLOptionElement);

    await eventually(() => {
      expect(lastList().get('sort')).toBe('dueDate');
    });
    expect(addressQuery().get('sort')).toBe('dueDate');
  });

  test('S6A-AC-82 C-3 주소로 열기: label과 sort가 든 주소로 열면 첫 요청부터 그대로이고 고른 상태로 보인다', async () => {
    const { user } = renderAt(`${URL_ACME}&label=61&label=63&sort=dueDate`);

    const first = new URL((await sent('GET', ISSUES))[0]?.url ?? 'http://x/').searchParams;
    expect(sorted(first.getAll('label')), '첫 요청의 label').toEqual(['61', '63']);
    expect(first.get('sort')).toBe('dueDate');
    expect(first.get('companyCode')).toBe('ACME');
    const sort = await select('정렬');
    await eventually(() => {
      expect(chosen(sort)).toBe('마감일순');
    });
    const group = await labelFilter(user);
    await eventually(() => {
      expect(filterBox(group, '긴급')).toBeChecked();
    });
    expect(filterBox(group, '모바일')).toBeChecked();
    expect(filterBox(group, '결제')).not.toBeChecked();
    await expectNoAxeViolations();
  });

  test('S6A-AC-86 C-3 고객사를 바꾼 뒤 온 옛 라벨 목록은 버린다: 라벨 필터에 새 고객사의 라벨만 있다', async () => {
    state.companies = [ACME, BETA];
    const release = gate();
    on('get', ACME_LABELS, async () => {
      await release.opened;
      return HttpResponse.json(contract('listCompanyLabels', 200, { items: s6a.labels['ACME'] ?? [] }));
    });
    const { user } = renderAt(URL_ACME);
    await sent('GET', ACME_LABELS);

    await user.selectOptions(await select('고객사'), 'BETA');
    await sent('GET', BETA_LABELS);
    const group = await labelFilter(user);
    await found(() => boxIn(group, BETA_ONLY.name), `라벨 필터의 "${BETA_ONLY.name}"`);
    const done = answered('GET', ACME_LABELS);
    release.open();
    await done;
    await settle();

    const now = await labelFilter(user);
    expect(boxIn(now, BETA_ONLY.name), '새 고객사의 라벨').not.toBeNull();
    expect(boxIn(now, '긴급'), '옛 고객사의 라벨').toBeNull();
    expect(boxIn(now, '결제'), '옛 고객사의 라벨').toBeNull();
  });
});
