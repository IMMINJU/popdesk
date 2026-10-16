// 7b 묶음 screens.md 1절 C-5 통계(상담 쪽 모두, /stats): 옆 메뉴 자리("내 작업" 다음), 제목, 표의 칸과 캡션,
// 보관 배지, 고객사 링크, 0이 아닌 수의 링크(열림은 status 둘), 0은 링크 없음, 표 아래 안내, 비었을 때 관리자·상담원 문구,
// 읽기 실패와 "다시 불러오기", 그리고 다시 읽은 뒤 늦게 온 앞 답.
// 상태 낱말은 상담 쪽 낱말이다(screens.md 0절). 숫자 모양 123,456은 C-18의 수 칸과 같은 표기다(toLocaleString('ko')).
import { waitFor } from '@testing-library/react';
import { describe, expect, test } from 'vitest';
import {
  ADMIN,
  AGENT,
  callsTo,
  errors,
  eventually,
  expectNoAxeViolations,
  found,
  gate,
  hrefPath,
  menuLinks,
  pageText,
  renderAt,
  screen,
  sent,
  spokenText,
  state,
  text,
  within,
} from '../__tests__/console';
import { answered, route, settle } from '../__tests__/console1a';
import { listed, type Status } from '../__tests__/console3';
import {
  ACME_STAT,
  ARCHIVED,
  BETA_STAT,
  COLUMNS,
  EMPTY_ADMIN,
  EMPTY_AGENT,
  NOTICE,
  OLD_STAT,
  READ_FAILED,
  RETRY,
  STATS,
  URL_STATS,
  cellOf,
  headOf,
  linksIn,
  s7b,
  stat,
  statsMenuNow,
  statsResponse,
  statsRow,
  statsRowNow,
  statsRowsNow,
  statsTable,
  statsTableNow,
  target,
  type Column,
} from '../__tests__/console7b';

const ALL_STATUSES: Status[] = ['CLOSED', 'CONFIRMED', 'IN_PROGRESS', 'RECEIVED', 'RESOLVED'];
const NUMBER_COLUMNS = ['전체', '열림', '처리 중', '해결', '종료'] as const satisfies readonly Column[];

/** The statuses a count column filters by (screens.md 1절: 열림 is 접수 and 확인). */
const STATUSES_OF: Readonly<Record<Exclude<(typeof NUMBER_COLUMNS)[number], '전체'>, Status[]>> = {
  열림: ['CONFIRMED', 'RECEIVED'],
  '처리 중': ['IN_PROGRESS'],
  해결: ['RESOLVED'],
  종료: ['CLOSED'],
};

async function title(): Promise<HTMLElement> {
  return found(() => screen.queryByRole('heading', { level: 1, name: '통계' }), 'h1 "통계"');
}

describe('C-5 통계 자리 (7b 묶음)', () => {
  test.each([
    { who: '관리자', me: ADMIN },
    { who: '상담원', me: AGENT },
  ])('S7B-AC-20 C-5 옆 메뉴($who)의 "통계"는 "내 작업" 바로 다음이고, 누르면 /stats의 h1 "통계"와 표다', async ({ me }) => {
    state.me = me;
    s7b.stats = [ACME_STAT];
    const { user } = renderAt('/issues');
    const menu = await waitFor(
      () => {
        const a = statsMenuNow();
        expect(a, '메뉴 "통계"').not.toBeNull();
        return a as HTMLAnchorElement;
      },
      { timeout: 5000 },
    );
    const links = menuLinks();
    expect(links.indexOf('통계'), '"내 작업" 다음').toBe(links.indexOf('내 작업') + 1);
    expect(links.indexOf('내 작업')).toBeGreaterThanOrEqual(0);
    expect(hrefPath(menu)).toBe(URL_STATS);

    await user.click(menu);

    await eventually(() => {
      expect(window.location.pathname).toBe(URL_STATS);
    });
    await title();
    await statsTable(1);
    await sent('GET', STATS);
  });
});

describe('C-5 통계 표 (7b 묶음)', () => {
  test('S7B-AC-20 C-5 캡션 "고객사별 문의 수, 3곳", 열 머리, 받은 차례의 줄, 고객사 칸(코드와 이름), 수 칸(오른쪽 맞춤), 보관 배지, 표 아래 안내', async () => {
    s7b.stats = [ACME_STAT, BETA_STAT, OLD_STAT];
    renderAt(URL_STATS);

    const table = await statsTable(3);
    const h1 = await title();
    expect(h1.compareDocumentPosition(table) & Node.DOCUMENT_POSITION_FOLLOWING, '제목 아래의 표').not.toBe(0);
    expect(within(table).getAllByRole('columnheader').map((h) => text(h))).toEqual([...COLUMNS]);

    const rows = statsRowsNow();
    expect(rows, '고객사마다 한 줄').toHaveLength(3);
    expect(rows.map((r) => spokenText(cellOf(r, '고객사'))), '받은 차례 그대로').toEqual([
      expect.stringContaining('ACME'),
      expect.stringContaining('BETA'),
      expect.stringContaining('OLD'),
    ]);
    const [acme, beta, old] = rows as [HTMLElement, HTMLElement, HTMLElement];
    expect(spokenText(cellOf(acme, '고객사'))).toContain('에이크미');
    expect(spokenText(cellOf(beta, '고객사'))).toContain('베타상사');
    expect(spokenText(cellOf(old, '고객사'))).toContain('옛상사');
    expect(spokenText(cellOf(old, '고객사')), '보관한 고객사의 배지').toContain(ARCHIVED);
    expect(spokenText(cellOf(acme, '고객사')), '보관하지 않은 고객사').not.toContain(ARCHIVED);
    expect(spokenText(cellOf(beta, '고객사')), '보관하지 않은 고객사').not.toContain(ARCHIVED);

    const counts = (row: HTMLElement) => NUMBER_COLUMNS.map((c) => text(cellOf(row, c)));
    expect(counts(acme)).toEqual(['12', '5', '3', '0', '4']);
    expect(counts(beta)).toEqual(['3', '2', '0', '1', '0']);
    expect(counts(old)).toEqual(['7', '0', '0', '0', '7']);
    for (const c of NUMBER_COLUMNS) {
      expect(headOf(c).getAttribute('data-align'), `"${c}" 머리의 맞춤`).toBe('end');
      expect(cellOf(acme, c).getAttribute('data-align'), `"${c}" 칸의 맞춤`).toBe('end');
    }

    const notice = await found(
      () => Array.from(document.querySelectorAll<HTMLElement>('main p, main [role="note"], main div')).find((el) => text(el) === NOTICE),
      `안내 "${NOTICE}"`,
    );
    expect(table.compareDocumentPosition(notice) & Node.DOCUMENT_POSITION_FOLLOWING, '표 아래의 안내').not.toBe(0);
    expect(pageText()).not.toContain(EMPTY_ADMIN);
    await expectNoAxeViolations();
  });

  test('S7B-AC-20 C-5 큰 수는 자리 쉼표로 보인다: 123456 → "123,456"', async () => {
    s7b.stats = [stat({ open: 123456, inProgress: 0, resolved: 0, closed: 0 })];
    renderAt(URL_STATS);

    const row = await statsRow('ACME');
    expect(text(cellOf(row, '전체'))).toBe('123,456');
    expect(text(cellOf(row, '열림'))).toBe('123,456');
  });
});

describe('C-5 통계 링크 (7b 묶음)', () => {
  test('S7B-AC-20 C-5 고객사 이름은 /issues?companyCode={코드}로 가는 링크 하나다(상태 없음)', async () => {
    s7b.stats = [ACME_STAT, OLD_STAT];
    renderAt(URL_STATS);

    for (const [code, name] of [
      ['ACME', '에이크미'],
      ['OLD', '옛상사'],
    ] as const) {
      const row = await statsRow(code);
      const links = linksIn(cellOf(row, '고객사'));
      expect(links, `${code} 고객사 칸의 링크`).toHaveLength(1);
      const a = links[0] as HTMLAnchorElement;
      expect(text(a)).toContain(name);
      expect(target(a)).toEqual({ path: '/issues', companyCode: code, status: [], others: [] });
    }
  });

  test.each([
    { column: '열림', statuses: STATUSES_OF.열림 },
    { column: '처리 중', statuses: STATUSES_OF['처리 중'] },
    { column: '종료', statuses: STATUSES_OF.종료 },
  ] as const)('S7B-AC-20 C-5 0이 아닌 수 "$column"은 그 고객사와 상태($statuses)로 거른 /issues 링크다', async ({ column, statuses }) => {
    s7b.stats = [ACME_STAT, BETA_STAT];
    renderAt(URL_STATS);

    const row = await statsRow('ACME');
    const links = linksIn(cellOf(row, column));
    expect(links, `ACME "${column}" 칸의 링크`).toHaveLength(1);
    const a = links[0] as HTMLAnchorElement;
    expect(text(a)).toBe(text(cellOf(row, column)));
    expect(target(a)).toEqual({ path: '/issues', companyCode: 'ACME', status: [...statuses].sort(), others: [] });
  });

  test('S7B-AC-20 C-5 0이 아닌 수 "해결"(BETA)도 링크이고, "전체"는 그 고객사의 모든 상태로 간다', async () => {
    s7b.stats = [ACME_STAT, BETA_STAT];
    renderAt(URL_STATS);

    const beta = await statsRow('BETA');
    const resolved = linksIn(cellOf(beta, '해결'));
    expect(resolved).toHaveLength(1);
    expect(target(resolved[0] as HTMLAnchorElement)).toEqual({ path: '/issues', companyCode: 'BETA', status: ['RESOLVED'], others: [] });

    const total = linksIn(cellOf(beta, '전체'));
    expect(total, 'BETA "전체" 칸의 링크').toHaveLength(1);
    const to = target(total[0] as HTMLAnchorElement);
    expect(to.path).toBe('/issues');
    expect(to.companyCode).toBe('BETA');
    expect(to.others).toEqual([]);
    // 상태로 거르지 않거나 다섯을 모두 고른다. 둘은 같은 목록이다.
    expect([[], ALL_STATUSES]).toContainEqual(to.status);
  });

  test('S7B-AC-20 C-5 0인 수는 링크가 없다(글자 "0"만)', async () => {
    s7b.stats = [BETA_STAT, OLD_STAT];
    renderAt(URL_STATS);

    const beta = await statsRow('BETA');
    for (const c of ['처리 중', '종료'] as const) {
      expect(text(cellOf(beta, c))).toBe('0');
      expect(linksIn(cellOf(beta, c)), `BETA "${c}"`).toHaveLength(0);
    }
    const old = await statsRow('OLD');
    for (const c of ['열림', '처리 중', '해결'] as const) {
      expect(text(cellOf(old, c))).toBe('0');
      expect(linksIn(cellOf(old, c)), `OLD "${c}"`).toHaveLength(0);
    }
    expect(linksIn(cellOf(old, '종료')), 'OLD "종료"(7)').toHaveLength(1);
  });

  test('S7B-AC-20 C-5 "열림" 수를 누르면 문의 목록(C-3)으로 가서 그 고객사와 접수·확인으로 거른 목록을 부른다', async () => {
    s7b.stats = [ACME_STAT];
    const { user } = renderAt(URL_STATS);
    const row = await statsRow('ACME');

    await user.click(linksIn(cellOf(row, '열림'))[0] as HTMLAnchorElement);

    await eventually(() => {
      expect(window.location.pathname).toBe('/issues');
    });
    await listed({ companyCode: 'ACME', status: STATUSES_OF.열림 });
  });
});

describe('C-5 비었을 때와 실패 (7b 묶음)', () => {
  test.each([
    { who: '관리자', me: ADMIN, says: EMPTY_ADMIN, not: EMPTY_AGENT },
    { who: '상담원', me: AGENT, says: EMPTY_AGENT, not: EMPTY_ADMIN },
  ])('S7B-AC-21 C-5 고객사가 없으면($who) "$says"이고 줄이 없다', async ({ me, says, not }) => {
    state.me = me;
    s7b.stats = [];
    renderAt(URL_STATS);

    await waitFor(
      () => {
        expect(pageText()).toContain(says);
      },
      { timeout: 5000 },
    );
    await sent('GET', STATS);
    expect(pageText()).not.toContain(not);
    expect(statsRowsNow().filter((r) => linksIn(r).length > 0), '링크가 있는 줄').toHaveLength(0);
    expect(screen.queryAllByRole('alert').filter((a) => text(a).includes(READ_FAILED))).toHaveLength(0);
    await expectNoAxeViolations();
  });

  test('S7B-AC-20 C-5 읽기 실패(500): role="alert" "통계를 읽지 못했습니다. 다시 해 보세요."와 "다시 불러오기". 누르면 다시 불러 표가 나온다', async () => {
    s7b.stats = [ACME_STAT, BETA_STAT];
    let fail = true;
    route('get', STATS, () => (fail ? errors.internal() : statsResponse()));
    const { user } = renderAt(URL_STATS);

    await waitFor(
      () => {
        expect(screen.queryAllByRole('alert').some((a) => text(a).includes(READ_FAILED)), `role="alert" "${READ_FAILED}"`).toBe(true);
      },
      { timeout: 5000 },
    );
    const retry = await found(() => screen.queryByRole('button', { name: RETRY }), `"${RETRY}"`);
    expect(pageText(), '실패를 빈 목록으로 보이지 않는다').not.toContain(EMPTY_ADMIN);
    expect(statsRowNow('ACME')).toBeNull();
    await expectNoAxeViolations();
    const before = callsTo('GET', STATS).length;
    fail = false;

    await user.click(retry);

    await eventually(() => {
      expect(callsTo('GET', STATS).length, '다시 읽기').toBeGreaterThan(before);
    });
    await statsTable(2);
    await statsRow('BETA');
    expect(screen.queryAllByRole('alert').filter((a) => text(a).includes(READ_FAILED)), '다시 읽은 뒤의 실패 띠').toHaveLength(0);
  });

  test('S7B-AC-20 C-5 다시 읽은 뒤 늦게 온 앞 답은 버린다: 떠났다 돌아와 받은 2곳을 늦게 온 1곳이 덮지 않는다', async () => {
    s7b.stats = [ACME_STAT, BETA_STAT];
    const release = gate();
    let first = true;
    route('get', STATS, async () => {
      if (first) {
        first = false;
        await release.opened;
        return statsResponse([ACME_STAT]);
      }
      return statsResponse();
    });
    const { user } = renderAt(URL_STATS);
    await title();
    await sent('GET', STATS);

    await user.click(await found(() => screen.queryAllByRole('link', { name: '내 작업' }).find((a) => a.closest('nav') !== null), '메뉴 "내 작업"'));
    await eventually(() => {
      expect(window.location.pathname).not.toBe(URL_STATS);
    });
    await user.click(await found(statsMenuNow, '메뉴 "통계"'));
    await statsTable(2);
    await statsRow('BETA');

    const done = answered('GET', STATS);
    release.open();
    await done;
    await settle();

    expect(statsTableNow(2), '다시 읽은 표').not.toBeNull();
    expect(statsTableNow(1), '늦게 온 앞 답의 표').toBeNull();
    expect(statsRowNow('BETA')).not.toBeNull();
  });
});
