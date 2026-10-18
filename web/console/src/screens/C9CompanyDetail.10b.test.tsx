// 10b 묶음 screens.md C-9 고객사 상세의 "내보내기" 구역(관리자): 자리(라벨 구역 뒤, 머리에 단추 하나), 읽는 중, 작업 없음,
// 목록(상태 배지와 톤, 요청한 사람, 요청 시각, 완료만 크기와 "내려받기", 실패 까닭 문장 넷), 읽기 실패와 다시 시도, 보관한 고객사
// (S10B-AC-24). 대화상자(제목, 본문, 선택 상자 둘과 늘 보이는 설명, 보낸 값, 보내는 중, 202 뒤 맨 위 진행 중 줄, 409·503·404·
// 그 밖의 문장, 닫은 뒤 온 답)(S10B-AC-25). 진행 중인 동안 5초마다 다시 읽기(가짜 시계), 끝·실패면 멈추고 숨긴 상태 줄이 한 번
// 읽힘, 탭이 가려지면 쉼, 화면을 떠나면 멈춤, 늦은 답 버림, 401이면 멈춤, 다시 읽기 실패(S10B-AC-26). 내려받기는 10a의 확인
// 도움(HEAD 뒤 내려받기)이고 404는 구역 안에 문장(S10B-AC-07의 화면). 옮겨 가는 것은 jsdom에서 보이지 않으므로 C-10의
// 정보 내보내기 테스트처럼 HEAD와 문장으로 본다.
import { HttpResponse, http } from 'msw/http';
import { describe, expect, test } from 'vitest';
import {
  ACME,
  BETA,
  atPath,
  callsTo,
  company,
  describedText,
  errors,
  eventually,
  expectNoAxeViolations,
  found,
  gate,
  isBlocked,
  on,
  openDialog,
  renderAt,
  screen,
  sent,
  server,
  state,
  text,
  useFakeClock,
  within,
} from '../__tests__/console';
import { answered, closed, settle } from '../__tests__/console1a';
import { badgesIn } from '../__tests__/console8a';
import { bodyOf, labelRegion } from '../__tests__/console6a';
import {
  ACME_EXPORTS,
  ASKING,
  BODY,
  CANCEL,
  COMPANY_NOT_FOUND,
  DOWNLOAD,
  EXPORT_BUTTON,
  FAILED,
  FAILURE_WORDS,
  FINISHED,
  INTERNAL,
  INTERNAL_HELP,
  LOAD_FAILED,
  MIXED,
  NONE_YET,
  NO_FILE,
  PRIVATE,
  PRIVATE_HELP,
  RELOAD_FAILED,
  RETRY,
  RUNNING_ELSEWHERE,
  SECTION,
  SERVER_FAILED,
  SIZE_TEXT,
  STATE_TONES,
  STATE_WORDS,
  URL_ACME,
  busyText,
  companyExport,
  dialogTitle,
  ended,
  exportButtonNow,
  exportDialog,
  exportId,
  exportLine,
  exportLinesNow,
  exportRegion,
  exportRegionNow,
  exportRunning,
  exportsOf,
  failed,
  filePath,
  goToCompany,
  listCalls,
  running,
  s10b,
  sendButtonNow,
  serverBusy,
  setHidden,
  spokenWith,
  tick,
  type CompanyExport,
  type CompanyExportFailure,
} from '../__tests__/console10b';

type User = ReturnType<typeof renderAt>['user'];

/** Waits generously for the 구역 and its first line: the first wait of a test (s4-t-common "웹 테스트 쓰는 법"). */
async function listShows(name: string): Promise<HTMLElement> {
  await found(exportRegionNow, `구역 "${SECTION}"`);
  let line: HTMLElement | undefined;
  await eventually(() => {
    line = exportLinesNow().find((l) => text(l).includes(name));
    expect(line, `내보내기 구역의 "${name}" 줄`).toBeDefined();
  });
  return line as HTMLElement;
}

/** The "내려받기" of `line`: a button or a link. */
const downloadIn = (line: HTMLElement) => within(line).queryByRole('button', { name: DOWNLOAD }) ?? within(line).queryByRole('link', { name: DOWNLOAD });

async function openExport(user: User): Promise<HTMLElement> {
  await user.click(await found(exportButtonNow, `구역 머리의 "${EXPORT_BUTTON}"`));
  return exportDialog();
}

const sendOf = (box: HTMLElement) => found(() => sendButtonNow(box), `대화상자의 "${EXPORT_BUTTON}"`);

/** The badges of `line`: the state word and its tone. */
function stateOf(line: HTMLElement): { text: string; tone: string | null }[] {
  return badgesIn(line);
}

describe('C-9 내보내기 구역 (10b 묶음)', () => {
  test('S10B-AC-24 C-9 자리: "내보내기" 구역은 "라벨" 구역 뒤 맨 끝이고, 머리에 "내보내기" 단추가 하나 있으며 그 고객사의 작업을 부른다', async () => {
    s10b.exports.ACME = [companyExport()];
    renderAt(URL_ACME);

    await listShows('최관리');
    const exports = await exportRegion();
    const labels = await found(labelRegion, '구역 "라벨"');
    expect(labels.compareDocumentPosition(exports) & Node.DOCUMENT_POSITION_FOLLOWING, '"라벨" 뒤').toBeTruthy();
    const main = screen.getByRole('main');
    expect(within(main).getAllByRole('region').at(-1), '본문의 맨 끝 구역').toBe(exports);
    const buttons = within(exports).getAllByRole('button', { name: EXPORT_BUTTON });
    expect(buttons, '"내보내기" 단추').toHaveLength(1);
    const [first] = exportLinesNow();
    expect((buttons[0] as HTMLElement).compareDocumentPosition(first as HTMLElement) & Node.DOCUMENT_POSITION_FOLLOWING, '단추는 목록 앞(구역 머리)').toBeTruthy();
    expect(callsTo('GET', ACME_EXPORTS)).toHaveLength(1);
  });

  test('S10B-AC-24 C-9 읽는 중: 구역 제목 "내보내기"는 바로 있고, 답이 오기 전에는 줄도 "아직 내보낸 적이 없습니다."도 없다', async () => {
    const release = gate();
    on('get', ACME_EXPORTS, async () => {
      await release.opened;
      return exportsOf('ACME');
    });
    renderAt(URL_ACME);

    const region = await exportRegion();
    await sent('GET', ACME_EXPORTS);
    expect(within(region).queryAllByRole('heading').map(text), '구역 제목').toContain(SECTION);
    expect(text(region)).not.toContain(NONE_YET);
    expect(exportLinesNow(), '읽는 동안의 줄').toEqual([]);

    const done = answered('GET', ACME_EXPORTS);
    release.open();
    await done;
    await found(() => within(region).queryByText(NONE_YET), `"${NONE_YET}"`);
  });

  test('S10B-AC-24 C-9 작업 없음: "아직 내보낸 적이 없습니다."와 "내보내기" 단추', async () => {
    renderAt(URL_ACME);

    const region = await exportRegion();
    await found(() => within(region).queryByText(NONE_YET), `"${NONE_YET}"`);
    expect(exportButtonNow(), `"${EXPORT_BUTTON}"`).not.toBeNull();
  });

  test('S10B-AC-24 C-9 목록: 받은 차례대로 줄마다 상태 배지(톤), 요청한 사람, 요청 시각, 완료만 크기와 "내려받기", 실패는 까닭 문장. axe 위반이 없다', async () => {
    s10b.exports.ACME = MIXED;
    renderAt(URL_ACME);

    await listShows('정관리');
    const lines = exportLinesNow();
    expect(lines.map((l) => ['최관리', '박관리', '한관리', '정관리'].find((n) => text(l).includes(n))), '받은 차례(최신순)').toEqual(['최관리', '박관리', '한관리', '정관리']);
    const [runningLine, doneLine, failedLine, expiredLine] = lines as [HTMLElement, HTMLElement, HTMLElement, HTMLElement];
    const shown: [HTMLElement, CompanyExport][] = [
      [runningLine, MIXED[0] as CompanyExport],
      [doneLine, MIXED[1] as CompanyExport],
      [failedLine, MIXED[2] as CompanyExport],
      [expiredLine, MIXED[3] as CompanyExport],
    ];
    for (const [line, e] of shown) {
      expect(stateOf(line), `${e.requestedBy.name} 줄의 배지`).toEqual([{ text: STATE_WORDS[e.state], tone: STATE_TONES[e.state] }]);
      const times = Array.from(line.querySelectorAll('time')).map((t) => Date.parse(t.getAttribute('datetime') ?? ''));
      expect(times, `${e.requestedBy.name} 줄의 요청 시각 <time>`).toContain(Date.parse(e.requestedAt));
      expect(downloadIn(line) !== null, `${e.requestedBy.name} 줄의 "${DOWNLOAD}"`).toBe(e.state === 'DONE');
      expect(text(line).includes(SIZE_TEXT), `${e.requestedBy.name} 줄의 크기`).toBe(e.state === 'DONE');
    }
    expect(text(failedLine)).toContain(FAILURE_WORDS.DISK);
    expect(text(doneLine)).not.toContain(FAILURE_WORDS.DISK);
    await expectNoAxeViolations();
  });

  test.each(Object.entries(FAILURE_WORDS) as [CompanyExportFailure, string][])('S10B-AC-24 C-9 실패 까닭 %s: 줄 끝에 "%s"이고 "내려받기"는 없다', async (failure, says) => {
    s10b.exports.ACME = [failed(failure)];
    renderAt(URL_ACME);

    const line = await listShows('최관리');
    await eventually(() => {
      expect(text(line)).toContain(says);
    });
    expect(stateOf(line)).toEqual([{ text: STATE_WORDS.FAILED, tone: STATE_TONES.FAILED }]);
    expect(downloadIn(line)).toBeNull();
  });

  test('S10B-AC-24 C-9 보관한 고객사: 구역과 "내보내기" 단추, 작업 목록이 그대로 보인다', async () => {
    state.companies = [company({ status: 'ARCHIVED' })];
    s10b.exports.ACME = [companyExport()];
    renderAt(URL_ACME);

    const line = await listShows('최관리');
    expect(exportButtonNow(), `보관한 고객사의 "${EXPORT_BUTTON}"`).not.toBeNull();
    expect(downloadIn(line)).not.toBeNull();
  });

  test('S10B-AC-24 C-9 읽기 실패: 구역 안에 "내보내기 목록을 불러오지 못했습니다."와 "다시 시도". 누르면 다시 읽어 목록을 보인다', async () => {
    let first = true;
    on('get', ACME_EXPORTS, () => {
      if (!first) return exportsOf('ACME');
      first = false;
      return errors.internal();
    });
    s10b.exports.ACME = [companyExport()];
    const { user } = renderAt(URL_ACME);

    const region = await exportRegion();
    await found(() => within(region).queryByText(LOAD_FAILED, { exact: false }), `구역 안의 "${LOAD_FAILED}"`);
    await user.click(await found(() => within(region).queryByRole('button', { name: RETRY }), `구역 안의 "${RETRY}"`));

    await sent('GET', ACME_EXPORTS, 2);
    await exportLine('최관리');
    expect(text(region)).not.toContain(LOAD_FAILED);
  });
});

describe('C-9 내보내기 대화상자 (10b 묶음)', () => {
  test('S10B-AC-25 C-9 대화상자: 제목 "{고객사 이름} 내보내기", 본문, 선택 상자 둘(기본 끔, 설명이 늘 보임), "내보내기"와 "취소". axe 위반이 없다', async () => {
    const { user } = renderAt(URL_ACME);
    const region = await exportRegion();
    await found(() => within(region).queryByText(NONE_YET), `"${NONE_YET}"`);
    const box = await openExport(user);

    expect(box).toHaveAccessibleName(dialogTitle(ACME.name));
    expect(text(box)).toContain(BODY);
    for (const [name, help] of [
      [PRIVATE, PRIVATE_HELP],
      [INTERNAL, INTERNAL_HELP],
    ] as const) {
      const choice = within(box).getByRole('checkbox', { name });
      expect(choice, name).not.toBeChecked();
      expect(describedText(choice), `"${name}"의 설명`).toContain(help);
      expect(text(box), `"${name}"의 설명이 보인다`).toContain(help);
    }
    expect(await sendOf(box)).toBeTruthy();
    expect(within(box).getByRole('button', { name: CANCEL })).toBeTruthy();
    await expectNoAxeViolations();
  });

  test.each([
    { what: '둘 다 끔', check: [] as string[], body: { includeInternal: false, includePrivate: false } },
    { what: '비공개만', check: [PRIVATE], body: { includeInternal: false, includePrivate: true } },
    { what: '내부만', check: [INTERNAL], body: { includeInternal: true, includePrivate: false } },
    { what: '둘 다', check: [PRIVATE, INTERNAL], body: { includeInternal: true, includePrivate: true } },
  ])('S10B-AC-25 C-9 대화상자($what): 고른 두 값을 보내고, 202면 닫고 목록 맨 위에 진행 중 줄이 있다', async ({ check, body }) => {
    s10b.exports.ACME = [companyExport({ requestedBy: { accountId: 5, name: '박관리' } })];
    const { user } = renderAt(URL_ACME);
    await listShows('박관리');
    const box = await openExport(user);

    for (const name of check) await user.click(within(box).getByRole('checkbox', { name }));
    await user.click(await sendOf(box));

    await sent('POST', ACME_EXPORTS);
    expect(await bodyOf('POST', ACME_EXPORTS)).toEqual(body);
    await closed();
    await eventually(() => {
      const [top] = exportLinesNow();
      expect(top && text(top), '맨 위 줄').toContain('최관리');
      expect(top && stateOf(top), '맨 위 줄의 배지').toEqual([{ text: STATE_WORDS.RUNNING, tone: STATE_TONES.RUNNING }]);
    });
    expect(exportLinesNow()).toHaveLength(2);
  });

  test('S10B-AC-25 C-9 대화상자 보내는 중: 단추가 "요청하는 중…"이고 막혀 있다', async () => {
    const release = gate();
    server.use(
      http.post(`*${ACME_EXPORTS}`, async () => {
        await release.opened;
      }),
    );
    const { user } = renderAt(URL_ACME);
    const box = await openExport(user);
    await user.click(await sendOf(box));
    await sent('POST', ACME_EXPORTS);

    await eventually(() => {
      const go = sendButtonNow(box);
      expect(go && text(go)).toBe(ASKING);
      expect(go && isBlocked(go), '보내는 중의 단추').toBe(true);
    });
    release.open();
    await closed();
  });

  test.each([
    { what: '409 EXPORT_RUNNING', answer: () => exportRunning(), says: RUNNING_ELSEWHERE },
    { what: '503(Retry-After 7)', answer: () => serverBusy(7), says: busyText(7) },
    { what: '503(Retry-After 없음)', answer: () => serverBusy(null), says: busyText(null) },
    { what: '404', answer: () => errors.notFound(), says: COMPANY_NOT_FOUND },
    { what: '500', answer: () => errors.internal(), says: SERVER_FAILED },
    { what: '연결 실패', answer: () => HttpResponse.error(), says: SERVER_FAILED },
  ])('S10B-AC-25 C-9 대화상자 $what: 대화상자 안에 "$says"이고 열린 채이며 목록은 그대로다', async ({ answer, says }) => {
    s10b.exports.ACME = [companyExport()];
    on('post', ACME_EXPORTS, () => answer());
    const { user } = renderAt(URL_ACME);
    await listShows('최관리');
    const box = await openExport(user);
    await user.click(await sendOf(box));

    await sent('POST', ACME_EXPORTS);
    await eventually(() => {
      expect(text(box)).toContain(says);
    });
    expect(openDialog(), '열린 대화상자').toBe(box);
    expect(exportLinesNow(), '목록').toHaveLength(1);
    expect(isBlocked(await sendOf(box)), '다시 누를 수 있는 단추').toBe(false);
  });

  test('S10B-AC-25 C-9 대화상자 409: 다른 고객사의 내보내기가 진행 중이면 서버가 거절하고 "다른 내보내기가 진행 중입니다."를 대화상자에 보인다', async () => {
    state.companies = [ACME, BETA];
    s10b.exports.BETA = [running({ id: exportId(9), requestedBy: { accountId: 5, name: '박관리' } })];
    const { user } = renderAt(URL_ACME);
    const box = await openExport(user);
    await user.click(await sendOf(box));

    await sent('POST', ACME_EXPORTS);
    await eventually(() => {
      expect(text(box)).toContain('다른 내보내기가 진행 중입니다.');
    });
  });

  test.each([
    { what: '202', answer: (): Response | undefined => undefined, lines: 2 },
    { what: '409', answer: (): Response | undefined => exportRunning(), lines: 1 },
    { what: '500', answer: (): Response | undefined => errors.internal(), lines: 1 },
  ])('S10B-AC-25 C-9 대화상자를 닫은 뒤 온 답($what): 목록만 다시 읽고, 대화상자도 오류 문장도 없다', async ({ answer, lines }) => {
    s10b.exports.ACME = [companyExport()];
    const release = gate();
    // A handler that returns nothing lets the request go on to the fake server, which makes the export.
    server.use(
      http.post(`*${ACME_EXPORTS}`, async () => {
        await release.opened;
        return answer();
      }),
    );
    const { user } = renderAt(URL_ACME);
    await listShows('최관리');
    const box = await openExport(user);
    await user.click(await sendOf(box));
    await sent('POST', ACME_EXPORTS);

    await user.click(within(box).getByRole('button', { name: CANCEL }));
    await closed();
    const before = listCalls();
    const done = answered('POST', ACME_EXPORTS);
    release.open();
    await done;

    await eventually(() => {
      expect(listCalls(), '닫은 뒤 온 답 뒤의 목록 요청').toBeGreaterThan(before);
    });
    await eventually(() => {
      expect(exportLinesNow()).toHaveLength(lines);
    });
    await settle();
    expect(openDialog(), '대화상자').toBeNull();
    for (const part of [RUNNING_ELSEWHERE, SERVER_FAILED]) expect(text(document.body), part).not.toContain(part);
  });
});

describe('C-9 내보내기 목록 다시 읽기 (10b 묶음)', () => {
  test('S10B-AC-26 C-9 진행 중: 5초마다 다시 읽고, 끝나면 "내려받기"와 크기를 보이며 숨긴 상태 줄이 "내보내기가 끝났습니다"를 한 번 읽힌다. 그 뒤로는 읽지 않는다', async () => {
    useFakeClock();
    const mine = running({ id: exportId(4) });
    const older = companyExport({ id: exportId(3), requestedBy: { accountId: 5, name: '박관리' } });
    s10b.exports.ACME = [mine, older];
    renderAt(URL_ACME, { fakeTimers: true });
    await listShows('최관리');
    await listShows('박관리');
    await settle();
    expect(listCalls(), '처음 읽기').toBe(1);
    expect(spokenWith(FINISHED), '처음부터 끝난 작업은 읽히지 않는다').toEqual([]);

    tick(2000);
    await settle();
    expect(listCalls(), '2초 뒤').toBe(1);
    s10b.exports.ACME = [ended(mine), older];
    tick(3000);

    await eventually(() => {
      expect(listCalls(), '5초 뒤').toBe(2);
    });
    const line = await exportLine('최관리');
    await eventually(() => {
      expect(stateOf(line)).toEqual([{ text: STATE_WORDS.DONE, tone: STATE_TONES.DONE }]);
      expect(downloadIn(line)).not.toBeNull();
      expect(text(line)).toContain(SIZE_TEXT);
    });
    await eventually(() => {
      expect(spokenWith(FINISHED)).toHaveLength(1);
    });
    expect(screen.queryAllByRole('status').filter((s) => text(s).includes(FINISHED)), 'role="status"인 줄').toHaveLength(1);

    tick(5000);
    tick(5000);
    await settle();
    expect(listCalls(), '끝난 뒤').toBe(2);
    expect(spokenWith(FINISHED), '한 번만').toHaveLength(1);
  });

  test('S10B-AC-26 C-9 진행 중이던 작업이 실패: 까닭 문장을 보이고 숨긴 상태 줄이 "내보내기가 실패했습니다"를 읽힌다. 그 뒤로는 읽지 않는다', async () => {
    useFakeClock();
    const mine = running({ id: exportId(4) });
    s10b.exports.ACME = [mine];
    renderAt(URL_ACME, { fakeTimers: true });
    await listShows('최관리');
    expect(spokenWith(FAILED)).toEqual([]);

    s10b.exports.ACME = [ended(mine, 'STORAGE')];
    tick(5000);

    const line = await exportLine('최관리');
    await eventually(() => {
      expect(text(line)).toContain(FAILURE_WORDS.STORAGE);
      expect(stateOf(line)).toEqual([{ text: STATE_WORDS.FAILED, tone: STATE_TONES.FAILED }]);
    });
    await eventually(() => {
      expect(spokenWith(FAILED)).toHaveLength(1);
    });
    tick(5000);
    tick(5000);
    await settle();
    expect(listCalls(), '실패한 뒤').toBe(2);
  });

  test('S10B-AC-26 C-9 진행 중인 작업이 없으면 다시 읽지 않는다. 대화상자로 하나를 시작하면 그때부터 5초마다 읽는다', async () => {
    useFakeClock();
    s10b.exports.ACME = [companyExport({ requestedBy: { accountId: 5, name: '박관리' } })];
    const { user } = renderAt(URL_ACME, { fakeTimers: true });
    await listShows('박관리');

    tick(5000);
    tick(5000);
    tick(5000);
    await settle();
    expect(listCalls(), '15초 동안').toBe(1);

    const box = await openExport(user);
    await user.click(await sendOf(box));
    await closed();
    await exportLine('최관리');
    const before = listCalls();
    tick(5000);
    await eventually(() => {
      expect(listCalls(), '진행 중 줄이 생긴 뒤 5초').toBeGreaterThan(before);
    });
  });

  test('S10B-AC-26 C-9 탭이 가려지면 다시 읽지 않고, 다시 보이면 다시 읽는다', async () => {
    useFakeClock();
    s10b.exports.ACME = [running()];
    renderAt(URL_ACME, { fakeTimers: true });
    await listShows('최관리');

    setHidden(true);
    tick(5000);
    tick(5000);
    await settle();
    expect(listCalls(), '가려진 동안').toBe(1);

    setHidden(false);
    tick(5000);
    await eventually(() => {
      expect(listCalls(), '다시 보인 뒤').toBeGreaterThan(1);
    });
  });

  test('S10B-AC-26 C-9 화면을 떠나면 다시 읽지 않는다', async () => {
    useFakeClock();
    s10b.exports.ACME = [running()];
    const { user } = renderAt(URL_ACME, { fakeTimers: true });
    await listShows('최관리');

    await user.click(await found(() => screen.queryAllByRole('link', { name: '문의' }).find((a) => a.closest('nav') !== null), '메뉴 "문의"'));
    await atPath('/issues');
    tick(5000);
    tick(5000);
    await settle();

    expect(listCalls(), '떠난 뒤').toBe(1);
  });

  test('S10B-AC-26 C-9 늦은 답은 버린다: 다시 읽는 중에 다른 고객사로 가면, 늦게 온 앞 고객사의 목록이 새 고객사의 구역에 보이지 않는다', async () => {
    useFakeClock();
    state.companies = [ACME, BETA];
    s10b.exports.ACME = [running({ requestedBy: { accountId: 1, name: '최관리' } })];
    s10b.exports.BETA = [companyExport({ id: exportId(7), requestedBy: { accountId: 5, name: '박관리' } })];
    const release = gate();
    let asked = 0;
    on('get', ACME_EXPORTS, async () => {
      asked += 1;
      if (asked > 1) await release.opened;
      return exportsOf('ACME');
    });
    const { user } = renderAt(URL_ACME, { fakeTimers: true });
    await listShows('최관리');
    tick(5000);
    await sent('GET', ACME_EXPORTS, 2);

    await goToCompany(user, /베타상사/, 'BETA');
    await listShows('박관리');
    const done = answered('GET', ACME_EXPORTS);
    release.open();
    await done;
    await settle();

    expect(window.location.pathname).toBe('/companies/BETA');
    expect(exportLinesNow().map(text).join(' '), 'BETA 구역의 ACME 줄').not.toContain('최관리');
    expect(exportLinesNow()).toHaveLength(1);
  });

  test('S10B-AC-26 C-9 다시 읽기가 401이면 멈춘다', async () => {
    useFakeClock();
    s10b.exports.ACME = [running()];
    let asked = 0;
    on('get', ACME_EXPORTS, () => {
      asked += 1;
      return asked > 1 ? errors.unauthenticated() : exportsOf('ACME');
    });
    renderAt(URL_ACME, { fakeTimers: true });
    await listShows('최관리');

    tick(5000);
    await eventually(() => {
      expect(listCalls()).toBe(2);
    });
    tick(5000);
    tick(5000);
    await settle();
    expect(listCalls(), '401 뒤').toBe(2);
  });

  test('S10B-AC-26 C-9 다시 읽기 실패: 목록은 그대로 두고 그 아래에 "목록을 새로 고치지 못했습니다."이며 다음 회차에 다시 읽는다', async () => {
    useFakeClock();
    const mine = running();
    s10b.exports.ACME = [mine];
    let asked = 0;
    on('get', ACME_EXPORTS, () => {
      asked += 1;
      return asked === 2 ? errors.internal() : exportsOf('ACME');
    });
    renderAt(URL_ACME, { fakeTimers: true });
    const line = await listShows('최관리');

    tick(5000);
    const notice = await found(
      () => Array.from(document.querySelectorAll<HTMLElement>('main *')).find((el) => el.children.length === 0 && text(el).includes(RELOAD_FAILED)),
      `"${RELOAD_FAILED}"`,
    );
    expect(exportLinesNow(), '그대로인 목록').toHaveLength(1);
    expect(stateOf(exportLinesNow()[0] as HTMLElement)).toEqual([{ text: STATE_WORDS.RUNNING, tone: STATE_TONES.RUNNING }]);
    expect(line.compareDocumentPosition(notice) & Node.DOCUMENT_POSITION_FOLLOWING, '목록 아래').toBeTruthy();
    expect(text(document.body)).not.toContain(LOAD_FAILED);

    s10b.exports.ACME = [ended(mine)];
    tick(5000);
    await eventually(() => {
      expect(listCalls(), '다음 회차').toBe(3);
    });
    await eventually(() => {
      expect(stateOf(exportLinesNow()[0] as HTMLElement)).toEqual([{ text: STATE_WORDS.DONE, tone: STATE_TONES.DONE }]);
    });
  });
});

describe('C-9 내보내기 내려받기 (10b 묶음)', () => {
  test('S10B-AC-07 C-9 "내려받기": 그 작업의 파일 주소로 HEAD를 한 번 묻고, 200이면 구역에 문장 없이 내려받는다', async () => {
    s10b.exports.ACME = MIXED;
    const { user } = renderAt(URL_ACME);
    const line = await listShows('박관리');

    await user.click(await found(() => downloadIn(line), `"${DOWNLOAD}"`));

    await sent('HEAD', filePath(exportId(3)));
    await settle();
    expect(callsTo('HEAD', filePath(exportId(3)))).toHaveLength(1);
    expect(text(await exportRegion())).not.toContain(NO_FILE);
    expect(screen.queryAllByRole('alert').map(text).filter((t) => t !== ''), '실패 띠').toEqual([]);
  });

  test('S10B-AC-07 C-9 "내려받기" 404: 구역 안에 "파일이 없습니다. 기간이 지났거나 지워졌습니다."이고 내려받지 않는다', async () => {
    s10b.exports.ACME = MIXED;
    // A `HEAD` answer has no body (the contract of headCompanyExport).
    on('head', filePath(exportId(3)), () => new HttpResponse(null, { status: 404 }));
    const { user } = renderAt(URL_ACME);
    const line = await listShows('박관리');

    await user.click(await found(() => downloadIn(line), `"${DOWNLOAD}"`));

    const region = await exportRegion();
    await found(() => within(region).queryByText(NO_FILE, { exact: false }), `구역 안의 "${NO_FILE}"`);
    expect(callsTo('HEAD', filePath(exportId(3)))).toHaveLength(1);
    expect(callsTo('GET', filePath(exportId(3))), '내려받기 요청').toHaveLength(0);
  });
});

