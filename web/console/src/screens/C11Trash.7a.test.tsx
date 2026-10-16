// 7a 묶음 screens.md 2절 C-11 휴지통(관리자, /trash): 옆 메뉴 자리, 제목과 안내, 표의 칸과 캡션, "{n}일 뒤"·"오늘",
// 복구(묻지 않음, 줄이 링크 알림으로 바뀜, 409의 상대 키), 영구 삭제(한 번 더 묻고 줄이 빠짐), 실패 문구, 404면 다시 읽기,
// 비었을 때, 쪽과 주소(S7A-AC-51), 그리고 쪽을 옮기거나 확인을 닫은 뒤 온 답(S7A-AC-53).
// "{n}일 뒤"는 남은 시간이 24시간 미만이면 "오늘", 아니면 남은 일수를 올림한다(2026-10-16 메인 결정). 경계는 시계를 멈추고 본다.
import { waitFor } from '@testing-library/react';
import { describe, expect, test, vi } from 'vitest';
import {
  AGENT,
  callsTo,
  errors,
  eventually,
  expectNoAxeViolations,
  found,
  gate,
  hrefPath,
  menuLinks,
  openDialog,
  pageText,
  renderAt,
  screen,
  sent,
  spokenText,
  state,
  text,
  within,
} from '../__tests__/console';
import { answered, closed, route, settle } from '../__tests__/console1a';
import { STATUS_WORDS } from '../__tests__/console3';
import {
  ACME_12,
  BETA_5,
  NOW,
  PURGE_QUESTION,
  PURGE_TEXT,
  TRASH,
  URL_TRASH,
  alertAfter,
  dialogButton,
  fromNow,
  linkConflict,
  manyTrashed,
  notInTrash,
  restoredResponse,
  purgeButton,
  purgeDialog,
  purgeDialogNow,
  restoreButton,
  restorePath,
  s7a,
  trashPath,
  trashResponse,
  trashRow,
  trashRowNow,
  trashRowsNow,
  trashTable,
  trashed,
  type TrashedIssue,
} from '../__tests__/console7a';

const NOTICE = '지운 문의는 30일 뒤 영구 삭제됩니다.';
const EMPTY = '휴지통이 비었습니다.';
const CHANGE_FAILED = '휴지통을 바꾸지 못했습니다. 다시 해 보세요.';
const CONFLICT = '연결 규칙에 걸려 복구하지 못했습니다:';
const RESTORED = '복구했습니다.';
const COLUMNS = ['문의', '고객사', '상태', '지운 시각', '지운 사람', '영구 삭제', '동작'] as const;

type User = ReturnType<typeof renderAt>['user'];

/** The cell of `row` under the column whose head is `column`. */
function cellOf(row: HTMLElement, column: (typeof COLUMNS)[number]): HTMLElement {
  const heads = screen.queryAllByRole('columnheader');
  const head = heads.find((h) => text(h) === column);
  expect(head ?? null, `열 머리 "${column}"`).not.toBeNull();
  const cell = row.children[Array.from(head?.parentElement?.children ?? []).indexOf(head as HTMLElement)];
  expect(cell ?? null, `"${column}" 칸`).not.toBeNull();
  return cell as HTMLElement;
}

/** Stops `Date` at `NOW` (timers stay real), so "{n}일 뒤" does not hang on how long the render took. */
function stopClock(): void {
  vi.useFakeTimers({ now: new Date(NOW), toFake: ['Date'] });
}

/** The `page` of the last `listTrash` request. */
function lastPage(): string | null {
  const last = callsTo('GET', TRASH).at(-1);
  return last ? new URL(last.url).searchParams.get('page') : null;
}

/** The `page` of the address. */
function addressPage(): string | null {
  return new URLSearchParams(window.location.search).get('page');
}

/** The link to page `n` of the page navigation. */
function pageLink(n: number): Promise<HTMLElement> {
  return found(() => screen.queryByRole('link', { name: new RegExp(`^${String(n)}(쪽)?$`) }), `${String(n)}쪽 링크`);
}

/** Opens the purge confirmation of the row of `key`. */
async function openPurge(user: User, key: string): Promise<HTMLElement> {
  await user.click(await purgeButton(key));
  return purgeDialog();
}

describe('C-11 휴지통 자리와 권한 (7a 묶음)', () => {
  test('S7A-AC-51 C-11 관리자 옆 메뉴의 "휴지통"은 "구성원"과 "감사 로그" 사이에 있고, 누르면 /trash의 h1 "휴지통"이다', async () => {
    s7a.trash = [ACME_12];
    const { user } = renderAt('/issues');
    const menu = await found(
      () => screen.queryAllByRole('link', { name: '휴지통' }).find((a) => a.closest('nav') !== null),
      '메뉴 "휴지통"',
    );
    const links = menuLinks();
    expect(links.indexOf('휴지통'), '"구성원" 뒤').toBe(links.indexOf('구성원') + 1);
    expect(links.indexOf('감사 로그'), '"감사 로그"는 "SLA 설정" 바로 뒤(8a screens.md 0절)').toBe(links.indexOf('SLA 설정') + 1);
    expect(hrefPath(menu)).toBe(URL_TRASH);

    await user.click(menu);

    await eventually(() => {
      expect(window.location.pathname).toBe(URL_TRASH);
    });
    await found(() => screen.queryByRole('heading', { level: 1, name: '휴지통' }), 'h1 "휴지통"');
    await trashTable(1);
  });

  test('S7A-AC-51 C-11 상담원: 메뉴에 "휴지통"이 없고, 주소로 오면 화면 전체가 "권한이 없습니다"이며 목록을 부르지 않는다', async () => {
    state.me = AGENT;
    s7a.trash = [ACME_12];
    renderAt(URL_TRASH);

    await waitFor(
      () => {
        expect(pageText()).toContain('권한이 없습니다');
      },
      { timeout: 5000 },
    );
    expect(menuLinks()).not.toContain('휴지통');
    expect(screen.queryAllByRole('table')).toHaveLength(0);
    expect(pageText()).not.toContain(ACME_12.title);
    expect(callsTo('GET', TRASH)).toHaveLength(0);
  });
});

describe('C-11 휴지통 표 (7a 묶음)', () => {
  test('S7A-AC-51 C-11 제목 아래 안내 한 줄, 캡션 "휴지통, 2건", 열 머리, 줄의 칸(키와 제목·링크 없음, 고객사, 상태, 지운 시각, 지운 사람, 영구 삭제, 동작)', async () => {
    stopClock();
    // 28일 18시간 남음: 올림이면 "29일 뒤", 내림이면 "28일 뒤". 일수 경계에서 멀다.
    s7a.trash = [trashed({ purgeAt: fromNow(29, -6) }), BETA_5];
    renderAt(URL_TRASH);

    const table = await trashTable(2);
    const title = await found(() => screen.queryByRole('heading', { level: 1, name: '휴지통' }), 'h1 "휴지통"');
    const notice = await found(
      () => Array.from(document.querySelectorAll<HTMLElement>('main p, main [role="note"], main div')).find((el) => text(el) === NOTICE),
      `안내 "${NOTICE}"`,
    );
    expect(title.compareDocumentPosition(notice) & Node.DOCUMENT_POSITION_FOLLOWING, '제목 아래').not.toBe(0);
    expect(table.querySelector('caption')).not.toBeNull();
    expect(within(table).getAllByRole('columnheader').map((h) => text(h))).toEqual([...COLUMNS]);

    const rows = trashRowsNow();
    expect(rows.map((r) => text(cellOf(r, '문의'))), '지운 시각이 늦은 순(받은 차례)').toEqual([
      expect.stringContaining('ACME-12'),
      expect.stringContaining('BETA-5'),
    ]);
    const [first, second] = rows as [HTMLElement, HTMLElement];
    expect(text(cellOf(first, '문의'))).toContain(ACME_12.title);
    expect(within(cellOf(first, '문의')).queryAllByRole('link'), '문의 칸의 링크').toHaveLength(0);
    expect(spokenText(cellOf(first, '고객사'))).toContain('에이크미');
    expect(spokenText(cellOf(second, '고객사'))).toContain('베타상사');
    expect(spokenText(cellOf(first, '상태'))).toContain(STATUS_WORDS.IN_PROGRESS);
    expect(spokenText(cellOf(second, '상태'))).toContain(STATUS_WORDS.RESOLVED);
    const deletedAt = cellOf(first, '지운 시각').querySelector('time');
    expect(deletedAt, '지운 시각의 <time>').not.toBeNull();
    expect(Date.parse(deletedAt?.getAttribute('datetime') ?? '')).toBe(Date.parse(ACME_12.deletedAt));
    expect(spokenText(cellOf(first, '지운 사람'))).toContain('최관리');
    expect(spokenText(cellOf(first, '영구 삭제'))).toContain('29일 뒤');
    expect(within(cellOf(first, '동작')).getByRole('button', { name: 'ACME-12 복구' })).toBeTruthy();
    expect(within(cellOf(first, '동작')).getByRole('button', { name: 'ACME-12 영구 삭제' })).toBeTruthy();
    await expectNoAxeViolations();
  });

  test.each([
    { what: '12일 6시간 남음', purgeAt: fromNow(12, 6), says: '13일 뒤' },
    { what: '방금 지움(30일 남음)', purgeAt: fromNow(30), says: '30일 뒤' },
    { what: '24시간 1분 남음', purgeAt: fromNow(1, 1 / 60), says: '2일 뒤' },
    { what: '정확히 24시간 남음', purgeAt: fromNow(1), says: '1일 뒤' },
    { what: '23시간 59분 남음', purgeAt: fromNow(1, -1 / 60), says: '오늘' },
    { what: '3시간 남음', purgeAt: fromNow(0, 3), says: '오늘' },
    { what: '이미 지남', purgeAt: fromNow(0, -2), says: '오늘' },
  ])('S7A-AC-51 C-11 영구 삭제 칸($what): "$says"', async ({ purgeAt, says }) => {
    stopClock();
    s7a.trash = [trashed({ purgeAt })];
    renderAt(URL_TRASH);

    const row = await trashRow('ACME-12');
    await eventually(() => {
      expect(spokenText(cellOf(row, '영구 삭제'))).toBe(says);
    });
  });

  test('S7A-AC-51 C-11 비었을 때: "휴지통이 비었습니다."이고 복구·영구 삭제 단추가 없다', async () => {
    s7a.trash = [];
    renderAt(URL_TRASH);

    await waitFor(
      () => {
        expect(pageText()).toContain(EMPTY);
      },
      { timeout: 5000 },
    );
    await sent('GET', TRASH);
    expect(screen.queryAllByRole('button', { name: / (복구|영구 삭제)$/ })).toHaveLength(0);
  });

  test('S7A-AC-51 C-11 쪽: ?page=2로 오면 2쪽을 부르고, 쪽 넘기기로 1쪽에 가면 주소와 요청이 따라간다', async () => {
    s7a.trash = manyTrashed(25);
    const { user } = renderAt(`${URL_TRASH}?page=2`);

    await trashTable(25);
    await eventually(() => {
      expect(lastPage()).toBe('2');
      expect(trashRowsNow()).toHaveLength(5);
    });
    expect(pageText()).toContain('지운 문의 021번');

    await user.click(await pageLink(1));

    await eventually(() => {
      expect(lastPage()).toBe('1');
      expect(addressPage() ?? '1').toBe('1');
      expect(trashRowsNow()).toHaveLength(20);
    });
    expect(pageText()).toContain('지운 문의 001번');
    expect(pageText()).not.toContain('지운 문의 021번');
    expect(window.location.pathname).toBe(URL_TRASH);

    await user.click(await pageLink(2));
    await eventually(() => {
      expect(addressPage()).toBe('2');
      expect(lastPage()).toBe('2');
    });
  });
});

describe('C-11 복구 (7a 묶음)', () => {
  test('S7A-AC-51 C-11 "복구"는 묻지 않고 보낸다. 줄이 "복구했습니다. ACME-12 열기"가 되고 링크는 /issues/ACME-12다', async () => {
    s7a.trash = [ACME_12, BETA_5];
    const { user } = renderAt(URL_TRASH);
    await trashTable(2);

    await user.click(await restoreButton('ACME-12'));

    await sent('POST', restorePath('ACME-12'));
    expect(openDialog(), '복구는 묻지 않는다').toBeNull();
    const row = await found(() => trashRowsNow().find((r) => text(r).includes(RESTORED)), `"${RESTORED}" 줄`);
    expect(text(row)).toContain('ACME-12');
    const open = await found(() => within(row).queryByRole('link', { name: /ACME-12 열기/ }), '링크 "ACME-12 열기"');
    expect(hrefPath(open)).toBe('/issues/ACME-12');
    expect(within(row).queryByRole('button', { name: 'ACME-12 복구' }), '복구한 줄의 "복구"').toBeNull();
    expect(trashRowNow('BETA-5'), '다른 줄').not.toBeNull();
    await expectNoAxeViolations();
  });

  test('S7A-AC-51 C-11 복구한 줄은 쪽을 다시 읽을 때 빠진다', async () => {
    s7a.trash = manyTrashed(21);
    const first = s7a.trash[0]?.key ?? '';
    const { user } = renderAt(URL_TRASH);
    await trashTable(21);
    await user.click(await restoreButton(first));
    await found(() => trashRowsNow().find((r) => text(r).includes(RESTORED)), `"${RESTORED}" 줄`);

    await user.click(await pageLink(2));
    await eventually(() => {
      expect(lastPage()).toBe('2');
    });
    await user.click(await pageLink(1));

    await eventually(() => {
      expect(lastPage()).toBe('1');
      expect(trashRowNow('지운 문의 002번')).not.toBeNull();
    });
    expect(trashRowNow(first), '다시 읽은 쪽의 복구한 문의').toBeNull();
    expect(pageText()).not.toContain(RESTORED);
  });

  test('S7A-AC-51 C-11 복구 409 RESTORE_LINK_CONFLICT: 줄 아래 role="alert"에 "연결 규칙에 걸려 복구하지 못했습니다: "와 상대 키들이고 줄은 남는다', async () => {
    s7a.trash = [ACME_12, BETA_5];
    s7a.conflicts = { 'ACME-12': ['ACME-40', 'BETA-77'] };
    const { user } = renderAt(URL_TRASH);
    await trashTable(2);

    await user.click(await restoreButton('ACME-12'));

    const row = await trashRow('ACME-12');
    const alert = await found(() => alertAfter(row, CONFLICT), `줄 아래의 "${CONFLICT}"`);
    expect(text(alert)).toContain('ACME-40');
    expect(text(alert)).toContain('BETA-77');
    expect(text(alert)).not.toContain(CHANGE_FAILED);
    expect(pageText()).not.toContain(RESTORED);
    expect(within(row).queryByRole('button', { name: 'ACME-12 복구' }), '남은 줄의 "복구"').not.toBeNull();
    await expectNoAxeViolations();
  });

  test('S7A-AC-51 C-11 복구 실패(500): 줄 아래 role="alert"에 "휴지통을 바꾸지 못했습니다. 다시 해 보세요."이고 줄은 남는다', async () => {
    s7a.trash = [ACME_12, BETA_5];
    route('post', restorePath('ACME-12'), () => errors.internal());
    const { user } = renderAt(URL_TRASH);
    await trashTable(2);

    await user.click(await restoreButton('ACME-12'));

    const row = await trashRow('ACME-12');
    await found(() => alertAfter(row, CHANGE_FAILED), `줄 아래의 "${CHANGE_FAILED}"`);
    expect(pageText()).not.toContain(RESTORED);
  });

  test('S7A-AC-51 C-11 복구 404(누군가 먼저 했다): 쪽을 다시 읽어 그 줄이 빠지고 오류 문구는 없다', async () => {
    s7a.trash = [ACME_12, BETA_5];
    route('post', restorePath('ACME-12'), () => {
      s7a.trash = s7a.trash.filter((t) => t.key !== 'ACME-12');
      return notInTrash('restoreIssue');
    });
    const { user } = renderAt(URL_TRASH);
    await trashTable(2);
    const reads = callsTo('GET', TRASH).length;

    await user.click(await restoreButton('ACME-12'));

    await eventually(() => {
      expect(callsTo('GET', TRASH).length, '404 뒤 다시 읽기').toBeGreaterThan(reads);
      expect(trashRowNow('ACME-12')).toBeNull();
    });
    expect(trashRowNow('BETA-5')).not.toBeNull();
    expect(pageText()).not.toContain(CHANGE_FAILED);
    expect(pageText()).not.toContain(RESTORED);
  });
});

describe('C-11 영구 삭제 (7a 묶음)', () => {
  test('S7A-AC-51 C-11 "영구 삭제": 한 번 더 묻는다(글과 "영구 삭제"·"그만두기"). "그만두기"는 보내지 않고, "영구 삭제"는 DELETE 뒤 줄이 빠진다', async () => {
    s7a.trash = [ACME_12, BETA_5];
    const { user } = renderAt(URL_TRASH);
    await trashTable(2);

    const first = await openPurge(user, 'ACME-12');
    expect(text(first)).toContain(PURGE_QUESTION);
    expect(text(first)).toContain(PURGE_TEXT);
    expect(
      within(first)
        .queryAllByRole('button')
        .map((b) => text(b))
        .filter((t) => t === '영구 삭제' || t === '그만두기')
        .sort(),
    ).toEqual(['그만두기', '영구 삭제']);
    await expectNoAxeViolations();
    await user.click(await dialogButton(first, '그만두기'));
    await closed();
    await settle();
    expect(callsTo('DELETE', trashPath('ACME-12')), '그만두기의 요청').toHaveLength(0);
    expect(trashRowNow('ACME-12')).not.toBeNull();

    const second = await openPurge(user, 'ACME-12');
    await user.click(await dialogButton(second, '영구 삭제'));

    await sent('DELETE', trashPath('ACME-12'));
    await eventually(() => {
      expect(trashRowNow('ACME-12'), '영구 삭제한 줄').toBeNull();
    });
    expect(purgeDialogNow()).toBeNull();
    expect(trashRowNow('BETA-5')).not.toBeNull();
    expect(callsTo('DELETE', trashPath('ACME-12'))).toHaveLength(1);
  });

  test('S7A-AC-51 C-11 영구 삭제 실패(500): 확인 대화상자가 열린 채 그 안에 role="alert" "휴지통을 바꾸지 못했습니다. 다시 해 보세요."이고 줄은 남는다', async () => {
    s7a.trash = [ACME_12, BETA_5];
    route('delete', trashPath('ACME-12'), () => errors.internal());
    const { user } = renderAt(URL_TRASH);
    await trashTable(2);

    const box = await openPurge(user, 'ACME-12');
    await user.click(await dialogButton(box, '영구 삭제'));

    await found(() => within(box).queryAllByRole('alert').find((a) => text(a).includes(CHANGE_FAILED)), `대화상자 안의 "${CHANGE_FAILED}"`);
    expect(purgeDialogNow(), '실패한 뒤의 확인 대화상자').not.toBeNull();
    expect(trashRowNow('ACME-12'), '실패한 줄').not.toBeNull();
  });

  test('S7A-AC-51 C-11 영구 삭제 404(누군가 먼저 했다): 확인 대화상자를 닫고 쪽을 다시 읽어 그 줄이 빠지며 오류 문구는 없다', async () => {
    s7a.trash = [ACME_12, BETA_5];
    route('delete', trashPath('ACME-12'), () => {
      s7a.trash = s7a.trash.filter((t) => t.key !== 'ACME-12');
      return notInTrash('purgeIssue');
    });
    const { user } = renderAt(URL_TRASH);
    await trashTable(2);
    const reads = callsTo('GET', TRASH).length;

    const box = await openPurge(user, 'ACME-12');
    await user.click(await dialogButton(box, '영구 삭제'));

    await eventually(() => {
      expect(callsTo('GET', TRASH).length, '404 뒤 다시 읽기').toBeGreaterThan(reads);
      expect(trashRowNow('ACME-12')).toBeNull();
      expect(purgeDialogNow(), '404 뒤의 확인 대화상자').toBeNull();
    });
    expect(trashRowNow('BETA-5')).not.toBeNull();
    expect(pageText()).not.toContain(CHANGE_FAILED);
  });
});

describe('C-11 늦은 답 (7a 묶음)', () => {
  test('S7A-AC-53 C-11 쪽을 옮긴 뒤 온 목록: 늦게 온 2쪽의 답이 그 사이에 돌아온 1쪽을 덮지 않는다', async () => {
    s7a.trash = manyTrashed(25);
    const release = gate();
    route('get', TRASH, async ({ request }) => {
      if (new URL(request.url).searchParams.get('page') === '2') await release.opened;
      return trashResponse(request);
    });
    const { user } = renderAt(URL_TRASH);
    await trashTable(25);

    await user.click(await pageLink(2));
    await eventually(() => {
      expect(lastPage()).toBe('2');
    });
    await user.click(await pageLink(1));
    await eventually(() => {
      expect(addressPage() ?? '1').toBe('1');
      expect(callsTo('GET', TRASH).filter((c) => new URL(c.url).searchParams.get('page') !== '2').length).toBeGreaterThanOrEqual(2);
    });
    await found(() => trashRowNow('지운 문의 001번'), '1쪽의 첫 줄');
    const done = answered('GET', TRASH);
    release.open();
    await done;
    await settle();

    expect(trashRowNow('지운 문의 001번'), '1쪽의 줄').not.toBeNull();
    expect(trashRowNow('지운 문의 021번'), '늦게 온 2쪽의 줄').toBeNull();
    expect(addressPage() ?? '1').toBe('1');
  });

  test.each([
    { what: '복구 성공', action: 'restore', ok: true, answer: (t: TrashedIssue) => restoredResponse(t) },
    { what: '복구 409', action: 'restore', ok: false, answer: () => linkConflict(['ACME-40']) },
    { what: '복구 실패', action: 'restore', ok: false, answer: () => errors.internal() },
    { what: '영구 삭제 성공', action: 'purge', ok: true, answer: () => new Response(null, { status: 204 }) },
    { what: '영구 삭제 실패', action: 'purge', ok: false, answer: () => errors.internal() },
  ] as const)('S7A-AC-53 C-11 쪽을 옮긴 뒤 온 $what 답은 버린다: 2쪽이 그대로다', async ({ action, ok, answer }) => {
    s7a.trash = manyTrashed(25);
    const target = s7a.trash[0] as TrashedIssue;
    const key = target.key;
    const path = action === 'restore' ? restorePath(key) : trashPath(key);
    const release = gate();
    route(action === 'restore' ? 'post' : 'delete', path, async () => {
      await release.opened;
      if (ok) s7a.trash = s7a.trash.filter((t) => t.key !== key);
      return answer(target);
    });
    const { user } = renderAt(URL_TRASH);
    await trashTable(25);
    if (action === 'restore') {
      await user.click(await restoreButton(key));
      await sent('POST', path);
    } else {
      const box = await openPurge(user, key);
      await user.click(await dialogButton(box, '영구 삭제'));
      await sent('DELETE', path);
      // The confirmation is modal: it is closed before the page moves.
      await user.keyboard('{Escape}');
      await closed();
    }

    await user.click(await pageLink(2));
    await found(() => trashRowNow('지운 문의 021번'), '2쪽의 줄');
    const done = answered(action === 'restore' ? 'POST' : 'DELETE', path);
    release.open();
    await done;
    await settle();

    expect(addressPage()).toBe('2');
    expect(trashRowNow('지운 문의 021번'), '2쪽의 줄').not.toBeNull();
    expect(trashRowsNow()).toHaveLength(5);
    expect(pageText(), '옮긴 뒤 온 성공').not.toContain(RESTORED);
    expect(pageText(), '옮긴 뒤 온 실패').not.toContain(CHANGE_FAILED);
    expect(pageText(), '옮긴 뒤 온 409').not.toContain(CONFLICT);
  });

  test.each([
    { what: '성공', ok: true },
    { what: '실패', ok: false },
  ])('S7A-AC-53 C-11 영구 삭제 확인을 닫은 뒤 온 답($what): 성공은 줄을 빼고 실패는 버린다(다시 열어도 오류가 없다)', async ({ ok }) => {
    s7a.trash = [ACME_12, BETA_5];
    const release = gate();
    route('delete', trashPath('ACME-12'), async () => {
      await release.opened;
      if (!ok) return errors.internal();
      s7a.trash = s7a.trash.filter((t) => t.key !== 'ACME-12');
      return new Response(null, { status: 204 });
    });
    const { user } = renderAt(URL_TRASH);
    await trashTable(2);
    const box = await openPurge(user, 'ACME-12');
    await user.click(await dialogButton(box, '영구 삭제'));
    await sent('DELETE', trashPath('ACME-12'));

    await user.keyboard('{Escape}');
    await closed();
    const done = answered('DELETE', trashPath('ACME-12'));
    release.open();
    await done;
    await settle();

    expect(openDialog(), '늦은 답이 대화상자를 다시 열지 않는다').toBeNull();
    expect(trashRowNow('BETA-5')).not.toBeNull();
    if (ok) {
      await eventually(() => {
        expect(trashRowNow('ACME-12'), '닫힌 뒤 온 성공').toBeNull();
      });
    } else {
      expect(pageText(), '닫힌 뒤 온 실패').not.toContain(CHANGE_FAILED);
      expect(trashRowNow('ACME-12'), '실패한 줄').not.toBeNull();
      const again = await openPurge(user, 'ACME-12');
      expect(within(again).queryAllByRole('alert'), '앞 요청의 오류').toHaveLength(0);
    }
  });
});
