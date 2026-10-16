// 8b 묶음 screens.md 2절 C-5 통계의 SLA: 정책이 켜져 있으면(항목의 sla가 null이 아님) 표 위 "SLA 기간" 고르기, 열 둘
// ("첫 응답 SLA", "해결 SLA"), 칸 두 줄(중앙값 slaLength, "지킴 n% (met/전체)" 내림), "없음", "중앙값 없음", 새 안내 줄,
// 주소의 ?days=(30이면 없음, 모르는 값은 30으로 고쳐 replace), 기간을 바꾸면 그 days로 다시 읽음, 읽는 동안 표를 감싼 틀의
// aria-busy, 늦게 온 앞 기간의 답을 버림, 다시 읽기 실패면 앞 수를 감추고 LoadFailed. 꺼져 있으면 7b 화면 그대로다.
// 표 캡션("고객사별 문의 수, n곳")은 그대로라 7b의 도움으로 표를 찾는다(screens.md 0절).
// 통계 요청의 days는 보낸 주소의 쿼리로 본다. 없으면 계약의 기본값 30으로 읽는다(30을 보내는지 빼는지는 고정하지 않는다).
import { waitFor } from '@testing-library/react';
import { describe, expect, test } from 'vitest';
import { calls, errors, eventually, expectNoAxeViolations, found, gate, pageText, renderAt, screen, sent, text, within } from '../__tests__/console';
import { answered, route, settle } from '../__tests__/console1a';
import { COLUMNS, NOTICE, READ_FAILED, STATS, URL_STATS, stat, statsRow, statsRowNow, statsTable, statsTableNow } from '../__tests__/console7b';
import {
  PERIOD,
  PERIODS,
  SLA_COLUMNS,
  SLA_NOTICE,
  addressDays,
  busyAroundTable,
  daysOf,
  daysSent,
  goal,
  linesOf,
  periodNow,
  s8b,
  slaCellOf,
  slaStat,
  statsFor,
} from '../__tests__/console8b';

/** 2일 23시간 = 4,260분. 지킴 249/250은 99.6%라 내리면 99%다. */
const ACME_30 = slaStat({ firstResponse: goal(4260, 249, 1), resolution: goal(95, 25, 0) });
/** 응답 없이 닫힌 것만 있어 중앙값만 없는 첫 응답(S8B-AC-24)과, 셀 것이 없는 해결("없음"). */
const BETA_30 = slaStat({ firstResponse: goal(null, 2, 1), resolution: goal(null, 0, 0) }, { companyCode: 'BETA', companyName: '베타상사', open: 2, inProgress: 0, resolved: 1, closed: 0 });
const ACME_7 = slaStat({ firstResponse: goal(480, 3, 1), resolution: goal(1500, 1, 1) });
const ACME_90 = slaStat({ firstResponse: goal(20, 9, 0), resolution: goal(2880, 7, 3) });

function title(): Promise<HTMLElement> {
  return found(() => screen.queryByRole('heading', { level: 1, name: '통계' }), 'h1 "통계"');
}

async function period(): Promise<HTMLSelectElement> {
  let el: HTMLSelectElement | null = null;
  await waitFor(
    () => {
      el = periodNow();
      expect(el, `고르기 "${PERIOD}"`).not.toBeNull();
    },
    { timeout: 5000 },
  );
  return el as unknown as HTMLSelectElement;
}

/** The text of the option chosen in `select`. */
function chosen(select: HTMLSelectElement): string {
  return text(select.selectedOptions[0]);
}

/** The lines of ACME's 첫 응답 SLA cell now, or [] while the row is not drawn. */
function acmeFirstResponse(): string[] {
  const row = statsRowNow('ACME');
  if (!row || !screen.queryAllByRole('columnheader').some((h) => text(h) === SLA_COLUMNS[0])) return [];
  return linesOf(slaCellOf(row, '첫 응답 SLA'));
}

async function showsFirstResponse(lines: string[]) {
  await waitFor(
    () => {
      expect(acmeFirstResponse()).toEqual(lines);
    },
    { timeout: 5000 },
  );
}

function notices(): HTMLElement[] {
  return Array.from(document.querySelectorAll<HTMLElement>('main p')).filter((p) => text(p) === NOTICE || text(p) === SLA_NOTICE);
}

describe('C-5 통계의 SLA 칸 (8b 묶음)', () => {
  test('S8B-AC-22 S8B-AC-26 C-5 정책이 켜져 있으면 표 위 "SLA 기간"(최근 30일)과 "종료" 다음 열 둘, 칸 두 줄(중앙값과 지킴 비율 내림), "없음", "중앙값 없음", 있는 안내 다음의 새 안내 줄이고 캡션은 그대로다', async () => {
    s8b.byDays['30'] = [ACME_30, BETA_30];
    renderAt(URL_STATS);

    const table = await statsTable(2);
    await title();
    const select = await period();
    expect(chosen(select), '처음 기간').toBe(PERIODS[30]);
    expect(Array.from(select.options).map((o) => text(o)), '고를 수 있는 기간').toEqual([PERIODS[7], PERIODS[30], PERIODS[90]]);
    expect(select.compareDocumentPosition(table) & Node.DOCUMENT_POSITION_FOLLOWING, '표 위의 고르기').not.toBe(0);
    expect(within(table).getAllByRole('columnheader').map((h) => text(h))).toEqual([...COLUMNS, ...SLA_COLUMNS]);

    const acme = await statsRow('ACME');
    expect(linesOf(slaCellOf(acme, '첫 응답 SLA')), 'ACME 첫 응답').toEqual(['중앙값 2일 23시간', '지킴 99% (249/250)']);
    expect(linesOf(slaCellOf(acme, '해결 SLA')), 'ACME 해결').toEqual(['중앙값 1시간 35분', '지킴 100% (25/25)']);
    const beta = await statsRow('BETA');
    expect(linesOf(slaCellOf(beta, '첫 응답 SLA')), '중앙값만 없음, 2/3은 66%').toEqual(['중앙값 없음', '지킴 66% (2/3)']);
    expect(linesOf(slaCellOf(beta, '해결 SLA')), '셀 것이 없음').toEqual(['없음']);
    expect(text(slaCellOf(acme, '종료')), '있는 수는 그대로').toBe('4');

    const shown = notices();
    expect(shown.map((p) => text(p)), '안내 줄 둘, 있는 줄이 먼저').toEqual([NOTICE, SLA_NOTICE]);
    expect(table.compareDocumentPosition(shown[0] as HTMLElement) & Node.DOCUMENT_POSITION_FOLLOWING, '표 아래의 안내').not.toBe(0);
    await expectNoAxeViolations();
  });

  test.each([
    { what: '주소에 기간 없음', path: URL_STATS },
    { what: '주소의 days=7을 무시', path: `${URL_STATS}?days=7` },
  ])('S8B-AC-26 C-5 정책이 꺼져 있으면($what) 7b 화면 그대로다: 열 여섯, 기간 고르기와 새 안내 줄이 없다', async ({ path }) => {
    s8b.byDays = { 7: [stat()], 30: [stat()], 90: [stat()] };
    renderAt(path);

    const table = await statsTable(1);
    await statsRow('ACME');
    expect(within(table).getAllByRole('columnheader').map((h) => text(h))).toEqual([...COLUMNS]);
    expect(periodNow(), `고르기 "${PERIOD}"`).toBeNull();
    expect(pageText()).not.toContain(PERIOD);
    expect(notices().map((p) => text(p))).toEqual([NOTICE]);
  });
});

describe('C-5 SLA 기간과 주소 (8b 묶음)', () => {
  test('S8B-AC-21 S8B-AC-26 C-5 기간을 "최근 7일"로 바꾸면 days=7로 다시 읽어 그 수를 보이고 주소에 ?days=7을 남긴다. 초점은 고르기에 남는다. "최근 30일"로 돌리면 주소에서 days가 빠진다', async () => {
    s8b.byDays = { 7: [ACME_7], 30: [ACME_30], 90: [ACME_90] };
    const { user } = renderAt(URL_STATS);
    await showsFirstResponse(['중앙값 2일 23시간', '지킴 99% (249/250)']);
    expect(new Set(daysSent(calls)), '처음 읽기').toEqual(new Set(['30']));
    const select = await period();

    await user.selectOptions(select, PERIODS[7]);

    await showsFirstResponse(['중앙값 8시간', '지킴 75% (3/4)']);
    expect(daysSent(calls).at(-1), '바꾼 기간으로 읽기').toBe('7');
    expect(addressDays()).toBe('7');
    expect(chosen(await period())).toBe(PERIODS[7]);
    expect(document.activeElement, '초점').toBe(periodNow());

    await user.selectOptions(await period(), PERIODS[30]);

    await showsFirstResponse(['중앙값 2일 23시간', '지킴 99% (249/250)']);
    expect(daysSent(calls).at(-1)).toBe('30');
    expect(addressDays(), '30이면 주소에 없다').toBeNull();
  });

  test('S8B-AC-26 C-5 주소의 ?days=90으로 열면 "최근 90일"이 골라져 있고 days=90으로 읽는다', async () => {
    s8b.byDays = { 7: [ACME_7], 30: [ACME_30], 90: [ACME_90] };
    renderAt(`${URL_STATS}?days=90`);

    await showsFirstResponse(['중앙값 20분', '지킴 100% (9/9)']);
    expect(chosen(await period())).toBe(PERIODS[90]);
    expect(new Set(daysSent(calls)), '30으로 먼저 읽지 않는다').toEqual(new Set(['90']));
  });

  test('S8B-AC-26 C-5 주소의 모르는 값 ?days=14는 30으로 읽고, 주소를 고쳐 쓴다(새 기록을 쌓지 않는다)', async () => {
    s8b.byDays = { 7: [ACME_7], 30: [ACME_30], 90: [ACME_90] };
    const before = window.history.length;
    renderAt(`${URL_STATS}?days=14`);

    await showsFirstResponse(['중앙값 2일 23시간', '지킴 99% (249/250)']);
    expect(chosen(await period())).toBe(PERIODS[30]);
    await eventually(() => {
      expect(addressDays(), '고친 주소').toBeNull();
    });
    expect(window.location.pathname).toBe(URL_STATS);
    expect(window.history.length, 'replace').toBe(before);
    expect(daysSent(calls).filter((d) => d !== '30'), '30 밖의 days로 읽기').toEqual([]);
  });
});

describe('C-5 기간을 바꾸는 동안과 실패 (8b 묶음)', () => {
  test('S8B-AC-26 C-5 기간을 바꾸고 답을 기다리는 동안 표를 감싼 틀이 aria-busy="true"이고, 답이 오면 풀리고 새 수다', async () => {
    s8b.byDays = { 7: [ACME_7], 30: [ACME_30], 90: [ACME_90] };
    const release = gate();
    route('get', STATS, async ({ request }) => {
      if (daysOf(request.url) === '7') await release.opened;
      return statsFor(request);
    });
    const { user } = renderAt(URL_STATS);
    await showsFirstResponse(['중앙값 2일 23시간', '지킴 99% (249/250)']);
    expect(busyAroundTable(), '처음 읽은 뒤').toBeNull();

    await user.selectOptions(await period(), PERIODS[7]);

    await eventually(() => {
      expect(daysSent(calls).at(-1)).toBe('7');
    });
    await eventually(() => {
      expect(busyAroundTable(), '읽는 동안').not.toBeNull();
    });
    const done = answered('GET', STATS);
    release.open();
    await done;

    await showsFirstResponse(['중앙값 8시간', '지킴 75% (3/4)']);
    await eventually(() => {
      expect(busyAroundTable(), '읽은 뒤').toBeNull();
    });
  });

  test('S8B-AC-26 C-5 늦게 온 앞 기간의 답은 버린다: 7일을 기다리는 동안 90일로 바꾸면, 뒤에 온 7일 답이 90일 수를 덮지 않는다', async () => {
    s8b.byDays = { 7: [ACME_7], 30: [ACME_30], 90: [ACME_90] };
    const release = gate();
    route('get', STATS, async ({ request }) => {
      if (daysOf(request.url) === '7') await release.opened;
      return statsFor(request);
    });
    const { user } = renderAt(URL_STATS);
    await showsFirstResponse(['중앙값 2일 23시간', '지킴 99% (249/250)']);

    await user.selectOptions(await period(), PERIODS[7]);
    await eventually(() => {
      expect(daysSent(calls).at(-1)).toBe('7');
    });
    await user.selectOptions(await period(), PERIODS[90]);
    await showsFirstResponse(['중앙값 20분', '지킴 100% (9/9)']);

    const late = answered('GET', STATS);
    release.open();
    await late;
    await settle();

    expect(acmeFirstResponse(), '90일 수 그대로').toEqual(['중앙값 20분', '지킴 100% (9/9)']);
    expect(pageText()).not.toContain('지킴 75% (3/4)');
    expect(chosen(await period())).toBe(PERIODS[90]);
    expect(addressDays()).toBe('90');
  });

  test('S8B-AC-26 C-5 기간을 바꿔 다시 읽기가 실패하면(500) 앞 기간의 수를 두지 않고 표를 감춘 채 "통계를 읽지 못했습니다. 다시 해 보세요."다', async () => {
    s8b.byDays = { 7: [ACME_7], 30: [ACME_30], 90: [ACME_90] };
    route('get', STATS, ({ request }) => (daysOf(request.url) === '7' ? errors.internal() : statsFor(request)));
    const { user } = renderAt(URL_STATS);
    await showsFirstResponse(['중앙값 2일 23시간', '지킴 99% (249/250)']);

    await user.selectOptions(await period(), PERIODS[7]);

    await waitFor(
      () => {
        expect(screen.queryAllByRole('alert').some((a) => text(a).includes(READ_FAILED)), `role="alert" "${READ_FAILED}"`).toBe(true);
      },
      { timeout: 5000 },
    );
    await sent('GET', STATS, 2);
    expect(daysSent(calls).at(-1)).toBe('7');
    expect(statsTableNow(), '앞 기간의 표').toBeNull();
    expect(statsRowNow('ACME')).toBeNull();
    expect(pageText(), '앞 기간의 수').not.toContain('2일 23시간');
    expect(pageText()).not.toContain('249/250');
  });
});
