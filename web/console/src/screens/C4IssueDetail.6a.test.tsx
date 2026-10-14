// 6a 묶음 screens.md 1절 C-4 문의 상세의 "라벨", "마감일" 칸과 두 고르기, 3절 이력의 문장.
// 라벨은 한 번에 하나씩 붙이고 떼며 답의 문의로 다시 그린다. "지남"은 서버의 overdue만 본다.
// 다른 문의로 바뀐 뒤 온 답은 버린다. 고르기가 닫힌 뒤 온 실패는 버리고 성공은 칸을 다시 그린다(S6A-AC-80, 81, 86).
import { HttpResponse } from 'msw/http';
import { describe, expect, test } from 'vitest';
import {
  ACME,
  AGENT,
  BETA,
  callsTo,
  errors,
  eventually,
  expectNoAxeViolations,
  found,
  gate,
  hrefPath,
  isBlocked,
  issue,
  on,
  renderAt,
  screen,
  sent,
  state,
  summary,
  text,
  within,
} from '../__tests__/console';
import { answered, settle } from '../__tests__/console1a';
import { history, issueIn, opener, s3 } from '../__tests__/console3';
import {
  ACME_LABELS,
  BETA_LABELS,
  BETA_ONLY,
  BILLING,
  DUE_DATE_7,
  MOBILE,
  URGENT,
  anyChangeButtonOf,
  bodyOf,
  boxIn,
  byName,
  changeButtonOf,
  contract,
  issueLabelPath,
  longDate,
  manyLabels,
  metaTerms,
  metaValue,
  metaValueNow,
  pastDate,
  refOf,
  rejectedWith,
  s6a,
} from '../__tests__/console6a';

const URL_7 = '/issues/ACME-7';
const LIMIT_TEXT = '라벨은 20개까지 붙입니다.';
const FAILED_TEXT = '라벨을 바꾸지 못했습니다. 다시 해 보세요.';
const EMPTY_TEXT = '이 고객사에 라벨이 없습니다.';
const PAST_TEXT = '오늘이나 그 뒤의 날짜를 고르세요.';

type User = ReturnType<typeof renderAt>['user'];

/** The opened label picker: the group named 라벨. */
const labelGroup = () => screen.queryByRole('group', { name: '라벨' });
const dueInput = () => screen.queryByLabelText<HTMLInputElement>('마감일', { selector: 'input' });

/** Waits for C-4 with its value list drawn. */
async function opened(): Promise<void> {
  await metaValue('담당자');
}

async function openLabels(user: User): Promise<HTMLElement> {
  await opened();
  await user.click(await found(() => changeButtonOf('라벨'), '"라벨" 칸의 "라벨 바꾸기"'));
  return found(labelGroup, '라벨 고르기(group "라벨")');
}

async function openDue(user: User): Promise<HTMLInputElement> {
  await opened();
  await user.click(await found(() => changeButtonOf('마감일'), '"마감일" 칸의 "마감일 바꾸기"'));
  return found(dueInput, '날짜 입력 "마감일"');
}

/**
 * Opens the picker of `term` unless it is open already: after moving to another issue the
 * screen may keep it open or close it.
 */
async function reopen(user: User, term: '라벨' | '마감일'): Promise<void> {
  const toggle = await found(() => changeButtonOf(term), `"${term}" 칸의 "${term} 바꾸기"`);
  if (toggle.getAttribute('aria-expanded') !== 'true') await user.click(toggle);
}

/** Goes to another issue as a person would: the menu "문의", then its link in the list. */
async function goToIssue(user: User, title: RegExp, key: string): Promise<void> {
  await user.click(await found(() => screen.queryAllByRole('link', { name: '문의' }).find((a) => a.closest('nav') !== null), '메뉴 "문의"'));
  await user.click(await found(() => screen.queryByRole('link', { name: title }), `${key} 링크`));
  await eventually(() => {
    expect(window.location.pathname).toBe(`/issues/${key}`);
  });
}

function box(name: string): HTMLInputElement {
  const group = labelGroup();
  expect(group, '라벨 고르기').not.toBeNull();
  const one = boxIn(group as HTMLElement, name);
  expect(one, `상자 "${name}"`).not.toBeNull();
  return one as HTMLInputElement;
}

/**
 * Names of the chips in the 라벨 칸, in the order shown. The picker may open inside the 칸: its
 * boxes, buttons and lines are left out, so only the chips count.
 */
function labelNamesShown(names: readonly string[]): string[] {
  const dd = metaValueNow('라벨');
  if (!dd) return [];
  const copy = dd.cloneNode(true) as HTMLElement;
  copy.querySelectorAll('fieldset, [role="group"], [role="dialog"], [role="alert"], [role="status"], button, label, input, [id$="-list"]').forEach((el) => {
    el.remove();
  });
  const all = text(copy);
  return names.filter((n) => all.includes(n)).sort((a, b) => all.indexOf(a) - all.indexOf(b));
}

describe('C-4 문의 상세 라벨과 마감일 (6a 묶음)', () => {
  test('S6A-AC-80 C-4 칸: "담당자" 다음에 "라벨", "마감일"이 있다. 라벨은 칩으로 이름순, 마감일은 "2026년 10월 20일" 꼴이다', async () => {
    state.issue = issueIn('IN_PROGRESS', { labels: [refOf(BILLING), refOf(URGENT)], dueDate: '2026-10-20' });
    renderAt(URL_7);
    await opened();

    await eventually(() => {
      expect(metaTerms(), 'C-4 머리의 칸').toContain('라벨');
    });
    const order = metaTerms();
    const at = order.indexOf('담당자');
    expect(order.slice(at, at + 4)).toEqual(['담당자', '라벨', '마감일', '고객사']);
    expect(labelNamesShown(['결제', '긴급'])).toEqual(['결제', '긴급']);
    expect(text(await metaValue('마감일'))).toContain('2026년 10월 20일');
    expect(text(await metaValue('마감일')), '지나지 않은 마감일').not.toContain('지남');
  });

  test('S6A-AC-80 C-4 빈 칸: 라벨이 없고 마감일이 없으면 두 칸 모두 "없음"이다', async () => {
    state.issue = issueIn('IN_PROGRESS');
    renderAt(URL_7);

    expect(text(await metaValue('라벨'))).toContain('없음');
    expect(text(await metaValue('마감일'))).toContain('없음');
  });

  test.each([
    { what: 'overdue가 true', dueDate: '2026-10-01', overdue: true, says: true },
    { what: 'overdue가 false면 날짜가 지났어도', dueDate: '2020-01-01', overdue: false, says: false },
    { what: 'overdue가 true면 먼 날짜여도', dueDate: '2099-12-31', overdue: true, says: true },
  ])('S6A-AC-81 C-4 "지남"($what): 서버가 준 overdue만 보고 마감일 칸에 글자로 보인다', async ({ dueDate, overdue, says }) => {
    state.issue = issueIn('IN_PROGRESS', { dueDate, overdue });
    renderAt(URL_7);

    const dd = await metaValue('마감일');
    await eventually(() => {
      expect(text(dd)).toContain(longDate(dueDate));
    });
    if (says) expect(text(dd)).toContain('지남');
    else expect(text(dd)).not.toContain('지남');
  });

  test('S6A-AC-80 C-4 CLOSED: "라벨", "마감일"에 "바꾸기"가 없고 담당자 "바꾸기"는 있다. 열린 문의에는 셋 다 있다', async () => {
    state.issue = issueIn('CLOSED', { labels: [refOf(URGENT)], dueDate: '2026-10-20' });
    const { unmount } = renderAt(URL_7);
    await opened();
    await metaValue('라벨');
    expect(changeButtonOf('담당자'), '담당자 "바꾸기"').not.toBeNull();
    expect(anyChangeButtonOf('라벨'), 'CLOSED의 라벨 "바꾸기"(이름이 무엇이든)').toEqual([]);
    expect(anyChangeButtonOf('마감일'), 'CLOSED의 마감일 "바꾸기"(이름이 무엇이든)').toEqual([]);
    unmount();

    state.issue = issueIn('RESOLVED', { labels: [refOf(URGENT)], dueDate: '2026-10-20' });
    renderAt(URL_7);
    await found(() => changeButtonOf('라벨'), '열린 문의의 라벨 "라벨 바꾸기"');
    expect(changeButtonOf('마감일'), '열린 문의의 마감일 "마감일 바꾸기"').not.toBeNull();
    expect(changeButtonOf('담당자')).not.toBeNull();
    // The words on the buttons are "바꾸기"; the accessible names hold them (screens.md 1절).
    expect(text(changeButtonOf('라벨'))).toContain('바꾸기');
    expect(text(changeButtonOf('마감일'))).toContain('바꾸기');
    // The locked slice 3 tests find the 담당자 button as the one button named "바꾸기" or "담당자 바꾸기".
    expect(screen.queryAllByRole('button', { name: /^(담당자 )?바꾸기$/ }), '이름이 "바꾸기"나 "담당자 바꾸기"인 단추').toHaveLength(1);
  });

  test('S6A-AC-80 C-4 라벨 고르기: 그 고객사의 라벨만 체크 목록에 있고 붙은 것이 체크되어 있다', async () => {
    state.issue = issueIn('IN_PROGRESS', { labels: [refOf(URGENT)] });
    const { user } = renderAt(URL_7);

    const group = await openLabels(user);

    expect(within(group).queryAllByRole('checkbox')).toHaveLength(3);
    expect(boxIn(group, BETA_ONLY.name), '다른 고객사의 라벨').toBeNull();
    expect(box('긴급')).toBeChecked();
    expect(box('결제')).not.toBeChecked();
    expect(box('모바일')).not.toBeChecked();
    await sent('GET', ACME_LABELS);
    await expectNoAxeViolations();
  });

  test('S6A-AC-80 C-4 붙이기·떼기: 체크는 PUT, 풀기는 DELETE가 라벨 ID(정수) 경로로 하나씩 나가고 답의 문의로 칸을 다시 그린다', async () => {
    state.issue = issueIn('IN_PROGRESS', { labels: [refOf(URGENT)] });
    const { user } = renderAt(URL_7);
    await openLabels(user);

    await user.click(box('결제'));
    await sent('PUT', issueLabelPath(BILLING.id));
    await eventually(() => {
      expect(labelNamesShown(['결제', '긴급'])).toEqual(['결제', '긴급']);
    });
    expect(box('결제')).toBeChecked();

    await user.click(box('긴급'));
    await sent('DELETE', issueLabelPath(URGENT.id));
    await eventually(() => {
      expect(labelNamesShown(['결제', '긴급'])).toEqual(['결제']);
    });
    expect(box('긴급')).not.toBeChecked();

    const labelCalls = callsTo('PUT', /\/labels\//).concat(callsTo('DELETE', /\/labels\//));
    expect(labelCalls.map((c) => `${c.method} ${c.path}`).sort()).toEqual(
      [`PUT ${issueLabelPath(62)}`, `DELETE ${issueLabelPath(61)}`].sort(),
    );
  });

  test('S6A-AC-80 C-4 요청 중: 답을 기다리는 줄은 막히고 답이 오면 풀린다', async () => {
    state.issue = issueIn('IN_PROGRESS');
    const release = gate();
    on('put', issueLabelPath(BILLING.id), async () => {
      await release.opened;
      state.issue = { ...state.issue, labels: [refOf(BILLING)], version: state.issue.version + 1 };
      return HttpResponse.json(contract('addIssueLabel', 200, state.issue));
    });
    const { user } = renderAt(URL_7);
    await openLabels(user);

    await user.click(box('결제'));
    await sent('PUT', issueLabelPath(BILLING.id));
    await eventually(() => {
      expect(isBlocked(box('결제')), '요청 중인 줄').toBe(true);
    });
    expect(isBlocked(box('모바일')), '다른 줄').toBe(false);

    const done = answered('PUT', issueLabelPath(BILLING.id));
    release.open();
    await done;
    await eventually(() => {
      expect(isBlocked(box('결제'))).toBe(false);
    });
    expect(box('결제')).toBeChecked();
  });

  test.each([
    { what: '500', answer: () => errors.internal() },
    { what: '400 ISSUE_LABEL_LIMIT_EXCEEDED', answer: () => rejectedWith('addIssueLabel', 'ISSUE_LABEL_LIMIT_EXCEEDED') },
  ])('S6A-AC-80 C-4 붙이기 실패($what): 그 줄이 되돌고 목록 아래에 오류 줄이 보인다. 칸은 그대로다', async ({ answer }) => {
    state.issue = issueIn('IN_PROGRESS');
    on('put', issueLabelPath(BILLING.id), () => answer());
    const { user } = renderAt(URL_7);
    const group = await openLabels(user);

    await user.click(box('결제'));
    await sent('PUT', issueLabelPath(BILLING.id));

    await found(() => within(group).queryByText(FAILED_TEXT), `오류 줄 "${FAILED_TEXT}"`);
    expect(box('결제'), '되돌린 줄').not.toBeChecked();
    expect(labelNamesShown(['결제'])).toEqual([]);
  });

  test('S6A-AC-80 C-4 한도: 20개가 붙은 문의는 체크하지 않은 줄이 막히고 "라벨은 20개까지 붙입니다."가 보인다', async () => {
    const all = manyLabels(25);
    s6a.labels = { ...s6a.labels, ACME: all };
    state.issue = issueIn('IN_PROGRESS', { labels: all.slice(0, 20).map(refOf) });
    const { user } = renderAt(URL_7);
    const group = await openLabels(user);

    await found(() => within(group).queryByText(LIMIT_TEXT), `"${LIMIT_TEXT}"`);
    expect(isBlocked(box('라벨21')), '체크하지 않은 줄').toBe(true);
    expect(isBlocked(box('라벨01')), '체크한 줄').toBe(false);
  });

  test.each([
    { who: '관리자', me: () => state.me, link: true },
    { who: '상담원', me: () => ({ ...AGENT, companies: [{ code: 'ACME', name: '에이크미' }] }), link: false },
  ])('S6A-AC-80 C-4 라벨 없는 고객사($who): "이 고객사에 라벨이 없습니다." 관리자에게만 C-9로 가는 "고객사 설정에서 만들기"', async ({ me, link }) => {
    state.me = me();
    s6a.labels = { ...s6a.labels, ACME: [] };
    state.issue = issueIn('IN_PROGRESS');
    const { user } = renderAt(URL_7);
    await opened();
    await user.click(await found(() => changeButtonOf('라벨'), '"라벨" 칸의 "라벨 바꾸기"'));

    await found(() => screen.queryByText(EMPTY_TEXT), `"${EMPTY_TEXT}"`);
    const make = screen.queryByRole('link', { name: '고객사 설정에서 만들기' });
    if (link) {
      expect(make, '관리자의 링크').not.toBeNull();
      expect(hrefPath(make as HTMLElement)).toBe('/companies/ACME');
    } else {
      expect(make, '상담원에게는 링크가 없다').toBeNull();
    }
  });

  test('S6A-AC-80 C-4 마감일 정하기: 날짜 입력에 min이 있고, "정하기"가 PUT due-date에 고른 날짜를 보내고 답의 날짜로 칸을 다시 그린다', async () => {
    state.issue = issueIn('IN_PROGRESS');
    const { user } = renderAt(URL_7);
    const input = await openDue(user);

    expect(input.type).toBe('date');
    expect(input.getAttribute('min') ?? '', '오늘 힌트 min').toMatch(/^\d{4}-\d{2}-\d{2}$/);
    expect(screen.queryByRole('button', { name: '지우기' }), '마감일이 없을 때 "지우기"').toBeNull();
    await user.clear(input);
    await user.type(input, '2099-12-01');
    await user.click(await found(() => screen.queryByRole('button', { name: '정하기' }), '"정하기"'));

    await sent('PUT', DUE_DATE_7);
    expect(await bodyOf('PUT', DUE_DATE_7)).toEqual({ dueDate: '2099-12-01' });
    await eventually(() => {
      expect(text(metaValueNow('마감일'))).toContain('2099년 12월 1일');
    });
  });

  test('S6A-AC-80 C-4 마감일 지우기: 마감일이 있으면 "지우기"가 DELETE due-date를 보내고 칸이 "없음"이 된다', async () => {
    state.issue = issueIn('IN_PROGRESS', { dueDate: '2099-10-20' });
    const { user } = renderAt(URL_7);
    await openDue(user);

    await user.click(await found(() => screen.queryByRole('button', { name: '지우기' }), '"지우기"'));

    await sent('DELETE', DUE_DATE_7);
    await eventually(() => {
      expect(text(metaValueNow('마감일'))).toContain('없음');
    });
    expect(text(metaValueNow('마감일'))).not.toContain('2099');
  });

  // The screen does not hold back a date before min: min is a hint and the server decides
  // (screens.md 1절 마감일 고르기), so a past date is sent and the 400 shown.
  test('S6A-AC-80 C-4 지난 날짜(화면은 막지 않고 보낸다): 서버의 400(OUT_OF_RANGE)은 날짜 입력 아래 "오늘이나 그 뒤의 날짜를 고르세요."다. 칸은 그대로다', async () => {
    state.issue = issueIn('IN_PROGRESS');
    on('put', DUE_DATE_7, () => pastDate());
    const { user } = renderAt(URL_7);
    const input = await openDue(user);

    await user.type(input, '2026-01-02');
    await user.click(await found(() => screen.queryByRole('button', { name: '정하기' }), '"정하기"'));

    await sent('PUT', DUE_DATE_7);
    await found(() => screen.queryByText(PAST_TEXT), `"${PAST_TEXT}"`);
    expect(text(metaValueNow('마감일'))).toContain('없음');
    await expectNoAxeViolations();
  });

  test('S6A-AC-86 C-4 라벨 고르기를 닫은 뒤 온 실패는 버린다: 다시 열면 오류 줄이 없다', async () => {
    state.issue = issueIn('IN_PROGRESS');
    const release = gate();
    on('put', issueLabelPath(BILLING.id), async () => {
      await release.opened;
      return errors.internal();
    });
    const { user } = renderAt(URL_7);
    await openLabels(user);
    await user.click(box('결제'));
    await sent('PUT', issueLabelPath(BILLING.id));

    await user.keyboard('{Escape}');
    await eventually(() => {
      expect(labelGroup(), '닫힌 고르기').toBeNull();
    });
    const done = answered('PUT', issueLabelPath(BILLING.id));
    release.open();
    await done;
    await settle();

    expect(screen.queryByText(FAILED_TEXT), '닫힌 뒤 온 실패').toBeNull();
    await user.click(await found(() => changeButtonOf('라벨'), '"라벨" 칸의 "라벨 바꾸기"'));
    await found(labelGroup, '다시 연 라벨 고르기');
    expect(screen.queryByText(FAILED_TEXT), '다시 연 고르기의 오류 줄').toBeNull();
    expect(isBlocked(box('결제')), '다시 연 고르기의 줄').toBe(false);
  });

  test('S6A-AC-86 C-4 마감일 고르기를 닫은 뒤 온 400은 버린다: 다시 열면 오류가 없다', async () => {
    state.issue = issueIn('IN_PROGRESS');
    const release = gate();
    on('put', DUE_DATE_7, async () => {
      await release.opened;
      return pastDate();
    });
    const { user } = renderAt(URL_7);
    const input = await openDue(user);
    await user.type(input, '2026-01-02');
    await user.click(await found(() => screen.queryByRole('button', { name: '정하기' }), '"정하기"'));
    await sent('PUT', DUE_DATE_7);

    await user.keyboard('{Escape}');
    await eventually(() => {
      expect(dueInput(), '닫힌 고르기').toBeNull();
    });
    const done = answered('PUT', DUE_DATE_7);
    release.open();
    await done;
    await settle();

    expect(screen.queryByText(PAST_TEXT), '닫힌 뒤 온 400').toBeNull();
    await openDue(user);
    expect(screen.queryByText(PAST_TEXT), '다시 연 고르기의 오류').toBeNull();
  });

  test('S6A-AC-86 C-4 다른 문의로 옮긴 뒤 온 라벨 답은 버린다: 새 문의의 칸이 그 답으로 바뀌지 않는다', async () => {
    state.issue = issueIn('IN_PROGRESS');
    state.issues = [summary({ key: 'ACME-8', title: '다른 문의입니다' })];
    const release = gate();
    on('put', issueLabelPath(BILLING.id), async () => {
      await release.opened;
      const answer = issueIn('IN_PROGRESS', { labels: [refOf(BILLING)], version: 2 });
      return HttpResponse.json(contract('addIssueLabel', 200, answer));
    });
    const { user } = renderAt(URL_7);
    await openLabels(user);
    await user.click(box('결제'));
    await sent('PUT', issueLabelPath(BILLING.id));

    state.issue = issue({ key: 'ACME-8', title: '다른 문의입니다', status: 'IN_PROGRESS', labels: [refOf(MOBILE)] });
    await goToIssue(user, /다른 문의입니다/, 'ACME-8');
    await eventually(() => {
      expect(labelNamesShown(['모바일', '결제'])).toEqual(['모바일']);
    });
    const done = answered('PUT', issueLabelPath(BILLING.id));
    release.open();
    await done;
    await settle();

    expect(labelNamesShown(['모바일', '결제']), 'ACME-8의 라벨 칸').toEqual(['모바일']);
    expect(screen.queryAllByRole('heading', { level: 1 }).map(text).join(' ')).toContain('다른 문의입니다');
  });

  test('S6A-AC-86 C-4 라벨 고르기를 닫은 뒤 온 성공은 칸을 다시 그린다: 답의 문의(서버의 라벨)로 칸이 바뀐다', async () => {
    state.issue = issueIn('IN_PROGRESS');
    const release = gate();
    on('put', issueLabelPath(BILLING.id), async () => {
      await release.opened;
      // Someone else put 모바일 on meanwhile: the answer, not the box pressed, is what the 칸 shows.
      state.issue = { ...state.issue, labels: [refOf(BILLING), refOf(MOBILE)], version: state.issue.version + 1 };
      return HttpResponse.json(contract('addIssueLabel', 200, state.issue));
    });
    const { user } = renderAt(URL_7);
    await openLabels(user);
    await user.click(box('결제'));
    await sent('PUT', issueLabelPath(BILLING.id));

    await user.keyboard('{Escape}');
    await eventually(() => {
      expect(labelGroup(), '닫힌 고르기').toBeNull();
    });
    const done = answered('PUT', issueLabelPath(BILLING.id));
    release.open();
    await done;
    await settle();

    await eventually(() => {
      expect(labelNamesShown(['결제', '모바일']), '닫힌 뒤 온 성공으로 다시 그린 칸').toEqual(['결제', '모바일']);
    });
    expect(screen.queryByText(FAILED_TEXT)).toBeNull();
  });

  test('S6A-AC-86 C-4 마감일 고르기를 닫은 뒤 온 성공은 칸을 다시 그린다: 답의 날짜가 칸에 보인다', async () => {
    state.issue = issueIn('IN_PROGRESS');
    const release = gate();
    on('put', DUE_DATE_7, async () => {
      await release.opened;
      state.issue = { ...state.issue, dueDate: '2099-12-01', version: state.issue.version + 1 };
      return HttpResponse.json(contract('setIssueDueDate', 200, state.issue));
    });
    const { user } = renderAt(URL_7);
    const input = await openDue(user);
    await user.type(input, '2099-12-01');
    await user.click(await found(() => screen.queryByRole('button', { name: '정하기' }), '"정하기"'));
    await sent('PUT', DUE_DATE_7);

    await user.keyboard('{Escape}');
    await eventually(() => {
      expect(dueInput(), '닫힌 고르기').toBeNull();
    });
    const done = answered('PUT', DUE_DATE_7);
    release.open();
    await done;
    await settle();

    await eventually(() => {
      expect(text(metaValueNow('마감일')), '닫힌 뒤 온 성공으로 다시 그린 칸').toContain('2099년 12월 1일');
    });
  });

  test('S6A-AC-86 C-4 다른 문의로 옮긴 뒤 온 라벨 실패는 버린다: 새 문의에 오류 줄이 없고 줄이 막히지 않는다', async () => {
    state.issue = issueIn('IN_PROGRESS');
    state.issues = [summary({ key: 'ACME-8', title: '다른 문의입니다' })];
    const release = gate();
    on('put', issueLabelPath(BILLING.id), async () => {
      await release.opened;
      return errors.internal();
    });
    const { user } = renderAt(URL_7);
    await openLabels(user);
    await user.click(box('결제'));
    await sent('PUT', issueLabelPath(BILLING.id));

    state.issue = issue({ key: 'ACME-8', title: '다른 문의입니다', status: 'IN_PROGRESS' });
    await goToIssue(user, /다른 문의입니다/, 'ACME-8');
    await metaValue('라벨');
    const done = answered('PUT', issueLabelPath(BILLING.id));
    release.open();
    await done;
    await settle();

    expect(screen.queryByText(FAILED_TEXT), '옮긴 뒤 온 실패').toBeNull();
    await reopen(user, '라벨');
    await found(labelGroup, 'ACME-8의 라벨 고르기');
    await eventually(() => {
      expect(box('결제'), 'ACME-8의 "결제"').not.toBeChecked();
    });
    expect(screen.queryByText(FAILED_TEXT), 'ACME-8 고르기의 오류 줄').toBeNull();
    expect(isBlocked(box('결제')), 'ACME-8 고르기의 줄').toBe(false);
  });

  test.each([
    { what: '성공', answer: () => HttpResponse.json(contract('setIssueDueDate', 200, issueIn('IN_PROGRESS', { dueDate: '2099-12-01', version: 2 }))) },
    { what: '400', answer: () => pastDate() },
  ])('S6A-AC-86 C-4 다른 문의로 옮긴 뒤 온 마감일 답($what)은 버린다: 새 문의의 칸과 고르기가 그대로다', async ({ answer }) => {
    state.issue = issueIn('IN_PROGRESS');
    state.issues = [summary({ key: 'ACME-8', title: '다른 문의입니다' })];
    const release = gate();
    on('put', DUE_DATE_7, async () => {
      await release.opened;
      return answer();
    });
    const { user } = renderAt(URL_7);
    const input = await openDue(user);
    await user.type(input, '2099-12-01');
    await user.click(await found(() => screen.queryByRole('button', { name: '정하기' }), '"정하기"'));
    await sent('PUT', DUE_DATE_7);

    state.issue = issue({ key: 'ACME-8', title: '다른 문의입니다', status: 'IN_PROGRESS', dueDate: '2099-05-05' });
    await goToIssue(user, /다른 문의입니다/, 'ACME-8');
    await eventually(() => {
      expect(text(metaValueNow('마감일'))).toContain('2099년 5월 5일');
    });
    const done = answered('PUT', DUE_DATE_7);
    release.open();
    await done;
    await settle();

    expect(text(metaValueNow('마감일')), 'ACME-8의 마감일 칸').toContain('2099년 5월 5일');
    expect(text(metaValueNow('마감일')), 'ACME-7의 답').not.toContain('2099년 12월 1일');
    expect(screen.queryByText(PAST_TEXT), '옮긴 뒤 온 400').toBeNull();
    await reopen(user, '마감일');
    const now = await found(dueInput, 'ACME-8의 날짜 입력');
    expect(now.value, 'ACME-8의 날짜 입력').toBe('2099-05-05');
    expect(screen.queryByText(PAST_TEXT), 'ACME-8 고르기의 오류').toBeNull();
  });

  test('S6A-AC-86 C-4 고르기가 연 라벨 목록이 다른 문의로 옮긴 뒤 오면 버린다: 새 문의 고객사의 라벨만 있다', async () => {
    state.companies = [ACME, BETA];
    state.issue = issueIn('IN_PROGRESS');
    state.issues = [summary({ key: 'BETA-3', title: '베타의 문의입니다', companyCode: BETA.code, companyName: BETA.name })];
    const release = gate();
    on('get', ACME_LABELS, async () => {
      await release.opened;
      return HttpResponse.json(contract('listCompanyLabels', 200, { items: byName(s6a.labels['ACME'] ?? []) }));
    });
    const { user } = renderAt(URL_7);
    await opened();
    await user.click(await found(() => changeButtonOf('라벨'), '"라벨" 칸의 "라벨 바꾸기"'));
    await sent('GET', ACME_LABELS);

    state.issue = issue({ key: 'BETA-3', title: '베타의 문의입니다', status: 'IN_PROGRESS', companyCode: BETA.code, companyName: BETA.name });
    await goToIssue(user, /베타의 문의입니다/, 'BETA-3');
    await metaValue('라벨');
    await reopen(user, '라벨');
    await sent('GET', BETA_LABELS);
    const group = await found(labelGroup, 'BETA-3의 라벨 고르기');
    await found(() => boxIn(group, BETA_ONLY.name), `상자 "${BETA_ONLY.name}"`);
    const done = answered('GET', ACME_LABELS);
    release.open();
    await done;
    await settle();

    const now = await found(labelGroup, 'BETA-3의 라벨 고르기');
    expect(boxIn(now, BETA_ONLY.name), '새 고객사의 라벨').not.toBeNull();
    for (const old of [URGENT, BILLING, MOBILE]) expect(boxIn(now, old.name), `옛 고객사의 라벨 "${old.name}"`).toBeNull();
  });

  test('S6A-AC-80 C-4 떼기 실패: 그 줄이 체크로 되돌고 목록 아래에 오류 줄이 보인다. 칸은 그대로다', async () => {
    state.issue = issueIn('IN_PROGRESS', { labels: [refOf(URGENT)] });
    on('delete', issueLabelPath(URGENT.id), () => errors.internal());
    const { user } = renderAt(URL_7);
    const group = await openLabels(user);

    await user.click(box('긴급'));
    await sent('DELETE', issueLabelPath(URGENT.id));

    await found(() => within(group).queryByText(FAILED_TEXT), `오류 줄 "${FAILED_TEXT}"`);
    expect(box('긴급'), '되돌린 줄').toBeChecked();
    expect(labelNamesShown(['긴급'])).toEqual(['긴급']);
  });

  test.each([
    { what: '라벨 붙임', entry: { field: 'LABEL', oldValue: null, newValue: '긴급' }, says: '라벨을 붙임: 긴급' },
    { what: '라벨 뗌', entry: { field: 'LABEL', oldValue: '결제', newValue: null }, says: '라벨을 뗌: 결제' },
    { what: '마감일 정함', entry: { field: 'DUE_DATE', oldValue: null, newValue: '2026-10-20' }, says: '마감일을 2026년 10월 20일로 정했습니다' },
    {
      what: '마감일 바꿈',
      entry: { field: 'DUE_DATE', oldValue: '2026-10-20', newValue: '2026-11-03' },
      says: '마감일을 2026년 10월 20일에서 2026년 11월 3일로 바꿨습니다',
    },
    { what: '마감일 지움', entry: { field: 'DUE_DATE', oldValue: '2026-10-20', newValue: null }, says: '마감일을 지웠습니다' },
  ])('S6A-AC-80 C-4 이력($what): "$says"', async ({ entry, says }) => {
    state.issue = issueIn('IN_PROGRESS');
    s3.history = [history({ id: 5, ...entry })];
    const { user } = renderAt(URL_7);
    await user.click(await opener(/^이력/));

    const line = await found(
      () => screen.queryAllByRole('listitem').find((li) => /라벨을|마감일을/.test(text(li))),
      '이력의 라벨·마감일 줄',
    );
    expect(text(line)).toContain('이상담');
    expect(text(line)).toContain(says);
    expect(text(line), '날짜를 YYYY-MM-DD 그대로 쓰지 않는다').not.toMatch(/\d{4}-\d{2}-\d{2}/);
  });
});
