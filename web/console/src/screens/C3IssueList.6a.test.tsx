// 6a 묶음 screens.md 2절 C-3 문의 목록: "라벨", "마감일" 열, 고객사를 고른 뒤의 "라벨" 필터(label 여러 번),
// "정렬"의 "마감일순"(sort=dueDate), 주소에 남는 label과 sort와 그 경계(정렬은 필터가 아니다). 고객사를
// 바꾸거나 지운 뒤 온 옛 라벨 목록은 버린다(S6A-AC-82, S6A-AC-86).
import { HttpResponse } from 'msw/http';
import { describe, expect, test } from 'vitest';
import { ACME, BETA, callsTo, eventually, expectNoAxeViolations, found, gate, on, pageText, renderAt, screen, sent, state, summary, text, within } from '../__tests__/console';
import { answered, settle } from '../__tests__/console1a';
import { ISSUES, addressQuery, cellText, chosen, issueRow, lastList, optionLabels, select } from '../__tests__/console3';
import { ACME_LABELS, BETA_LABELS, BETA_ONLY, BILLING, MOBILE, URGENT, boxIn, byName, contract, labelParams, refOf, s6a } from '../__tests__/console6a';

const URL_ALL = '/issues';
const URL_ACME = '/issues?companyCode=ACME';
const THIS_YEAR = new Date().getFullYear();

/** The 라벨 filter: a group of boxes named 라벨, drawn like the 상태 filter (screens.md 2절). */
const labelGroup = () => screen.queryByRole('group', { name: '라벨' });

/** The 라벨 filter with its boxes, waited for. */
function labelFilter(): Promise<HTMLElement> {
  return found(() => {
    const group = labelGroup();
    return group && within(group).queryAllByRole('checkbox').length > 0 ? group : null;
  }, '"라벨" 필터의 상자들');
}

/** The "필터 지우기" links and buttons on the page. */
const clearFilters = () => [...screen.queryAllByRole('link', { name: '필터 지우기' }), ...screen.queryAllByRole('button', { name: '필터 지우기' })];

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
    // The "+n" is the element with a title whose text starts with "+": a cut chip may carry its full name as a title too.
    const plus = Array.from(row.querySelectorAll<HTMLElement>('[title]')).filter((el) => text(el).startsWith('+'));
    expect(plus, '"+1"').toHaveLength(1);
    expect(text(plus[0])).toContain('+1');
    expect(plus[0]?.getAttribute('title'), '"+1"의 title').toContain('보안');

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

    await user.selectOptions(await select('고객사'), 'ACME');
    await sent('GET', ACME_LABELS);
    let group = await labelFilter();
    expect(boxIn(group, BETA_ONLY.name), '다른 고객사의 라벨').toBeNull();
    await user.click(filterBox(group, '긴급'));
    await eventually(() => {
      expect(labelParams()).toEqual(['61']);
    });
    group = await labelFilter();
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
    renderAt(`${URL_ACME}&label=61&label=63&sort=dueDate`);

    const first = new URL((await sent('GET', ISSUES))[0]?.url ?? 'http://x/').searchParams;
    expect(sorted(first.getAll('label')), '첫 요청의 label').toEqual(['61', '63']);
    expect(first.get('sort')).toBe('dueDate');
    expect(first.get('companyCode')).toBe('ACME');
    const sort = await select('정렬');
    await eventually(() => {
      expect(chosen(sort)).toBe('마감일순');
    });
    const group = await labelFilter();
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
      return HttpResponse.json(contract('listCompanyLabels', 200, { items: byName(s6a.labels['ACME'] ?? []) }));
    });
    const { user } = renderAt(URL_ACME);
    await sent('GET', ACME_LABELS);

    await user.selectOptions(await select('고객사'), 'BETA');
    await sent('GET', BETA_LABELS);
    const group = await labelFilter();
    await found(() => boxIn(group, BETA_ONLY.name), `라벨 필터의 "${BETA_ONLY.name}"`);
    const done = answered('GET', ACME_LABELS);
    release.open();
    await done;
    await settle();

    const now = await labelFilter();
    expect(boxIn(now, BETA_ONLY.name), '새 고객사의 라벨').not.toBeNull();
    expect(boxIn(now, '긴급'), '옛 고객사의 라벨').toBeNull();
    expect(boxIn(now, '결제'), '옛 고객사의 라벨').toBeNull();
  });

  test('S6A-AC-86 C-3 고객사를 지운 뒤 온 옛 라벨 목록은 버린다: 라벨 필터가 생기지 않고, 다시 고른 고객사의 라벨만 있다', async () => {
    state.companies = [ACME, BETA];
    const release = gate();
    on('get', ACME_LABELS, async () => {
      await release.opened;
      return HttpResponse.json(contract('listCompanyLabels', 200, { items: byName(s6a.labels['ACME'] ?? []) }));
    });
    const { user } = renderAt(URL_ACME);
    await sent('GET', ACME_LABELS);

    await user.selectOptions(await select('고객사'), '');
    await eventually(() => {
      expect(lastList().get('companyCode')).toBeNull();
    });
    const done = answered('GET', ACME_LABELS);
    release.open();
    await done;
    await settle();

    expect(labelGroup(), '고객사를 지운 뒤의 라벨 필터').toBeNull();
    await user.selectOptions(await select('고객사'), 'BETA');
    await sent('GET', BETA_LABELS);
    const group = await labelFilter();
    await found(() => boxIn(group, BETA_ONLY.name), `라벨 필터의 "${BETA_ONLY.name}"`);
    for (const old of [URGENT, BILLING, MOBILE]) expect(boxIn(group, old.name), `옛 고객사의 라벨 "${old.name}"`).toBeNull();
  });

  test.each([
    { what: 'companyCode 없는 label', path: '/issues?label=61' },
    { what: '모르는 sort', path: '/issues?sort=priority' },
    { what: 'companyCode 없는 label과 모르는 sort', path: '/issues?label=61&label=63&sort=bogus' },
  ])('S6A-AC-82 C-3 주소의 경계($what): 버리고 기본값으로 연다. 요청에 label이 없고 "정렬"은 "올린 시각순"이다', async ({ path }) => {
    renderAt(path);

    const first = new URL((await sent('GET', ISSUES))[0]?.url ?? 'http://x/').searchParams;
    expect(first.getAll('label'), '첫 요청의 label').toEqual([]);
    expect([null, 'createdAt'], '첫 요청의 sort').toContain(first.get('sort'));
    const sort = await select('정렬');
    expect(chosen(sort)).toBe('올린 시각순');
    await issueRow('ACME-7');
    expect(lastList().getAll('label'), '마지막 요청의 label').toEqual([]);
    expect([null, 'createdAt'], '마지막 요청의 sort').toContain(lastList().get('sort'));
    expect(labelGroup(), '고객사 없이 보이는 라벨 필터').toBeNull();
    expect(clearFilters(), '버린 값으로 생긴 "필터 지우기"').toEqual([]);
  });

  test('S6A-AC-82 C-3 정렬은 필터가 아니다: 정렬만 바꾼 빈 목록에는 "조건에 맞는 문의가 없습니다"와 "필터 지우기"가 없다', async () => {
    state.issues = [];
    renderAt('/issues?sort=dueDate');

    const sort = await select('정렬');
    await eventually(() => {
      expect(chosen(sort)).toBe('마감일순');
      expect(lastList().get('sort')).toBe('dueDate');
    });
    await settle();
    expect(pageText()).not.toContain('조건에 맞는 문의가 없습니다');
    expect(clearFilters(), '"필터 지우기"').toEqual([]);
  });

  test('S6A-AC-82 C-3 "필터 지우기"는 정렬을 그대로 둔다: 요청과 주소에 sort=dueDate가 남고 "정렬"은 "마감일순"이다', async () => {
    const { user } = renderAt('/issues?status=RECEIVED&sort=dueDate');
    await eventually(() => {
      expect(lastList().get('sort')).toBe('dueDate');
    });
    const before = callsTo('GET', ISSUES).length;

    await user.click(await found(() => clearFilters()[0], '"필터 지우기"'));

    await eventually(() => {
      expect(callsTo('GET', ISSUES).length, '지운 뒤의 목록 요청').toBeGreaterThan(before);
      expect(lastList().getAll('status'), '지운 뒤 요청의 status').toEqual([]);
      expect(lastList().get('sort'), '지운 뒤 요청의 sort').toBe('dueDate');
    });
    expect(addressQuery().get('sort'), '주소의 sort').toBe('dueDate');
    expect(chosen(await select('정렬'))).toBe('마감일순');
  });
});
