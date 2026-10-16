// 8a 묶음 screens.md 2절 C-3 문의 목록: "SLA" 칸(맨 끝, 나쁜 쪽 목표의 남은 시간과 배지, 접근 이름에 목표 이름), 필터 "SLA"
// (담당자 다음, 전체·곧 넘김·넘김)와 주소의 sla, 정렬 "SLA 기한순"(sort=slaDue), 필터를 바꾼 뒤 늦게 온 앞 목록(S8A-AC-30~32).
// 남은 시간은 서버가 준 remainingMinutes 그대로다: 견본의 dueAt은 일부러 그 분과 맞지 않게 멀리 둔다(acceptance.md 5절 8).
import { describe, expect, test } from 'vitest';
import { callsTo, eventually, expectNoAxeViolations, gate, renderAt, screen, sent, state, text, within } from '../__tests__/console';
import { answered, route, settle } from '../__tests__/console1a';
import { ISSUES, addressQuery, chosen, issueRow, lastList, optionLabels, select } from '../__tests__/console3';
import { NO_GOAL, badgesIn, goal, listResponse, sla, speechOf, withSla, type IssueSla } from '../__tests__/console8a';

const URL_ALL = '/issues';
/** Three days from the fake now: far from every remainingMinutes below, so a screen that counts from dueAt shows something else. */
const FAR = '2026-10-11T03:15:00Z';

/** The "SLA" cell of `row`: the cell under the last column head "SLA". */
function slaCell(row: HTMLElement): HTMLElement {
  const heads = screen.queryAllByRole('columnheader');
  const head = heads.find((h) => text(h) === 'SLA');
  expect(head ?? null, '열 머리 "SLA"').not.toBeNull();
  const cell = row.children[Array.from(head?.parentElement?.children ?? []).indexOf(head as HTMLElement)];
  expect(cell ?? null, '"SLA" 칸').not.toBeNull();
  return cell as HTMLElement;
}

describe('C-3 문의 목록 SLA 칸 (8a 묶음)', () => {
  test('S8A-AC-30 C-3 열: "SLA"는 "올린 시각" 다음, 맨 끝이다', async () => {
    state.issues = [withSla('ACME-7', sla(goal('OK', 200, FAR), NO_GOAL))];
    renderAt(URL_ALL);
    await issueRow('ACME-7');

    const headers = screen.queryAllByRole('columnheader').map(text);
    expect(headers.at(-1), '맨 끝 열').toBe('SLA');
    expect(headers.at(-2), '"SLA" 앞 열').toContain('올린 시각');
    await expectNoAxeViolations();
  });

  test.each<{ what: string; value: IssueSla | null; shows: string; goalName: string | null; badge: { text: string; tone: string } | null }>([
    { what: 'SLA 대상 아님(null)', value: null, shows: '', goalName: null, badge: null },
    { what: '두 목표가 멈춤(지킴, 못 지킴)', value: sla(goal('MET', null), goal('MISSED', null)), shows: '', goalName: null, badge: null },
    { what: '목표 없음과 멈춤', value: sla(NO_GOAL, goal('MET', null)), shows: '', goalName: null, badge: null },
    { what: '괜찮음 하나(첫 응답)', value: sla(goal('OK', 200, FAR), NO_GOAL), shows: '3시간 20분 남음', goalName: '첫 응답', badge: null },
    {
      what: '곧 넘김이 괜찮음보다 나쁨(해결)',
      value: sla(goal('OK', 30, FAR), goal('WARNING', 50, FAR)),
      shows: '50분 남음',
      goalName: '해결',
      badge: { text: '곧 넘김', tone: 'neutral' },
    },
    {
      what: '넘김이 곧 넘김보다 나쁨(첫 응답)',
      value: sla(goal('BREACHED', -1560, FAR), goal('WARNING', 50, FAR)),
      shows: '1일 2시간 넘김',
      goalName: '첫 응답',
      badge: { text: '넘김', tone: 'warning' },
    },
    {
      what: '둘 다 괜찮음이면 기한이 이른 쪽(해결)',
      value: sla(goal('OK', 500, '2026-10-12T03:15:00Z'), goal('OK', 100, FAR)),
      shows: '1시간 40분 남음',
      goalName: '해결',
      badge: null,
    },
    {
      what: '둘 다 넘김이면 기한이 이른 쪽(해결)',
      value: sla(goal('BREACHED', -20, '2026-10-12T03:15:00Z'), goal('BREACHED', -1500, FAR)),
      shows: '1일 1시간 넘김',
      goalName: '해결',
      badge: { text: '넘김', tone: 'warning' },
    },
  ])('S8A-AC-30 C-3 SLA 칸($what)', async ({ value, shows, goalName, badge }) => {
    // 비교할 줄(ACME-8, 괜찮음)이 있어 열은 늘 있다.
    state.issues = [withSla('ACME-7', value), withSla('ACME-8', sla(goal('OK', 61, FAR), NO_GOAL))];
    renderAt(URL_ALL);

    const row = await issueRow('ACME-7');
    const other = await issueRow('ACME-8');
    await eventually(() => {
      expect(text(slaCell(other)), '비교 줄의 SLA 칸').toContain('1시간 1분 남음');
    });
    const cell = slaCell(row);
    if (shows === '') {
      expect(text(cell), '빈 칸').toBe('');
      return;
    }
    expect(text(cell)).toContain(shows);
    expect(badgesIn(cell), '배지').toEqual(badge ? [badge] : []);
    if (badge) {
      const words = text(cell);
      expect(words.indexOf(badge.text), '배지가 남은 시간 앞').toBeLessThan(words.indexOf(shows));
    }
    const speech = speechOf(cell);
    expect(speech, '접근 이름의 목표 이름').toContain(goalName as string);
    expect(speech).toContain(shows);
    expect(speech, '다른 목표 이름은 없다').not.toContain(goalName === '첫 응답' ? '해결' : '첫 응답');
  });
});

describe('C-3 문의 목록 SLA 필터와 정렬 (8a 묶음)', () => {
  test('S8A-AC-31 C-3 필터 "SLA": "담당자" 다음에 있고 선택지는 전체·곧 넘김·넘김이다. 고르면 요청과 주소에 sla=breached, sla=warning', async () => {
    const { user } = renderAt(URL_ALL);
    await issueRow('ACME-7');
    const filter = await select('SLA');
    const assignee = await select('담당자');
    const sort = await select('정렬');

    expect(optionLabels(filter)).toEqual(['전체', '곧 넘김', '넘김']);
    expect(chosen(filter)).toBe('전체');
    expect(assignee.compareDocumentPosition(filter) & Node.DOCUMENT_POSITION_FOLLOWING, '"담당자" 뒤').not.toBe(0);
    expect(filter.compareDocumentPosition(sort) & Node.DOCUMENT_POSITION_FOLLOWING, '"정렬" 앞').not.toBe(0);
    expect(lastList().get('sla'), '처음 요청').toBeNull();

    await user.selectOptions(filter, Array.from(filter.options).find((o) => text(o) === '넘김') as HTMLOptionElement);
    await eventually(() => {
      expect(lastList().get('sla')).toBe('breached');
    });
    expect(addressQuery().get('sla')).toBe('breached');

    await user.selectOptions(filter, Array.from(filter.options).find((o) => text(o) === '곧 넘김') as HTMLOptionElement);
    await eventually(() => {
      expect(lastList().get('sla')).toBe('warning');
    });
    expect(addressQuery().get('sla')).toBe('warning');

    await user.selectOptions(filter, Array.from(filter.options).find((o) => text(o) === '전체') as HTMLOptionElement);
    await eventually(() => {
      expect(lastList().has('sla'), '전체면 sla 없음').toBe(false);
    });
    expect(addressQuery().has('sla')).toBe(false);
  });

  test('S8A-AC-31 C-3 주소로 열기: ?sla=warning이면 첫 요청부터 sla=warning이고 "곧 넘김"을 고른 상태다', async () => {
    renderAt(`${URL_ALL}?sla=warning`);

    const first = new URL((await sent('GET', ISSUES))[0]?.url ?? 'http://x/').searchParams;
    expect(first.get('sla'), '첫 요청의 sla').toBe('warning');
    const filter = await select('SLA');
    await eventually(() => {
      expect(chosen(filter)).toBe('곧 넘김');
    });
  });

  test('S8A-AC-32 C-3 정렬: "SLA 기한순"이 있고 고르면 요청과 주소에 sort=slaDue', async () => {
    const { user } = renderAt(URL_ALL);
    await issueRow('ACME-7');
    const sort = await select('정렬');
    expect(optionLabels(sort)).toEqual(expect.arrayContaining(['올린 시각순', '마감일순', 'SLA 기한순']));

    await user.selectOptions(sort, Array.from(sort.options).find((o) => text(o) === 'SLA 기한순') as HTMLOptionElement);

    await eventually(() => {
      expect(lastList().get('sort')).toBe('slaDue');
    });
    expect(addressQuery().get('sort')).toBe('slaDue');
    expect(chosen(sort)).toBe('SLA 기한순');
  });

  test('S8A-AC-31 C-3 필터를 "넘김"으로 바꾼 뒤 늦게 온 앞 목록은 버린다: 거른 목록이 그대로다', async () => {
    const ok = withSla('ACME-7', sla(goal('OK', 200, FAR), NO_GOAL));
    const breached = withSla('ACME-9', sla(goal('BREACHED', -30, FAR), NO_GOAL));
    const release = gate();
    route('get', ISSUES, async ({ request }) => {
      if (new URL(request.url).searchParams.get('sla') === 'breached') return listResponse([breached]);
      await release.opened;
      return listResponse([ok, breached]);
    });
    const { user } = renderAt(URL_ALL);
    await sent('GET', ISSUES);
    const filter = await select('SLA');

    await user.selectOptions(filter, Array.from(filter.options).find((o) => text(o) === '넘김') as HTMLOptionElement);
    await issueRow('ACME-9');
    expect(callsTo('GET', ISSUES).some((c) => new URL(c.url).searchParams.get('sla') === 'breached')).toBe(true);

    const done = answered('GET', ISSUES);
    release.open();
    await done;
    await settle();

    const table = screen.getByRole('table', { name: /문의 목록/ });
    expect(within(table).queryAllByRole('row').some((r) => text(r).includes('ACME-7')), '늦게 온 앞 목록의 ACME-7').toBe(false);
    expect(within(table).queryAllByRole('row').some((r) => text(r).includes('ACME-9'))).toBe(true);
    expect(addressQuery().get('sla')).toBe('breached');
  });
});
