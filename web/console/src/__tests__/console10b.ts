// More test support for slice 10b (design/slices/10b-privacy/screens.md): the 내보내기 구역 of C-9 and
// its dialog, the three inbox items of C-7, the "자동 정리" of C-11 and the audit words of C-12. The
// earlier support files stay as they are; this file adds the company export endpoints (list, create,
// `HEAD` and `GET` of the file) to the fake server, in the shape of docs/integrate/openapi-console.json,
// and the helpers the slice 10b screen tests share.
//
// Every JSON answer made here goes through `contract` of console6a.ts: a fixture that does not fit the
// contract file fails the test that used it.
import type { ConsoleComponents } from '@popdesk/api/console';
import { HttpResponse, http } from 'msw/http';
import { act } from '@testing-library/react';
import { afterEach, beforeEach, expect, vi } from 'vitest';
import { apiError, callsTo, errors, eventually, found, screen, server, state, text, within } from './console';
import { alertItem, type Notification } from './console4';
import { contract, rejectedWith } from './console6a';

type Schemas = ConsoleComponents['schemas'];
export type CompanyExport = Schemas['CompanyExport'];
export type CompanyExportState = CompanyExport['state'];
export type CompanyExportFailure = NonNullable<CompanyExport['failure']>;

export const URL_ACME = '/companies/ACME';
export const ACME_EXPORTS = '/api/console/companies/ACME/exports';
export const BETA_EXPORTS = '/api/console/companies/BETA/exports';

/** The path of an export's ZIP (`downloadCompanyExport`, `headCompanyExport`). */
export function filePath(id: string): string {
  return `/api/console/company-exports/${id}/file`;
}

// ---------------------------------------------------------------------------
// screens.md의 문구

export const SECTION = '내보내기';
export const EXPORT_BUTTON = '내보내기';
export const NONE_YET = '아직 내보낸 적이 없습니다.';
export const DOWNLOAD = '내려받기';
export const STATE_WORDS: Readonly<Record<CompanyExportState, string>> = { RUNNING: '진행 중', DONE: '완료', FAILED: '실패', EXPIRED: '기간 지남' };
/** The tone of each state's `Badge` (acceptance.md 5절 22). */
export const STATE_TONES: Readonly<Record<CompanyExportState, string>> = { RUNNING: 'accent', DONE: 'neutral', FAILED: 'warning', EXPIRED: 'neutral' };
export const FAILURE_WORDS: Readonly<Record<CompanyExportFailure, string>> = {
  INTERRUPTED: '앱이 다시 시작되어 멈췄습니다.',
  STORAGE: '첨부 저장소를 읽지 못했습니다.',
  DISK: '내보내기 파일을 쓸 공간이 없습니다.',
  UNEXPECTED: '알 수 없는 오류로 멈췄습니다.',
};
export const FINISHED = '내보내기가 끝났습니다';
export const FAILED = '내보내기가 실패했습니다';
export const RELOAD_FAILED = '목록을 새로 고치지 못했습니다.';
export const LOAD_FAILED = '내보내기 목록을 불러오지 못했습니다.';
export const RETRY = '다시 시도';
export const NO_FILE = '파일이 없습니다. 기간이 지났거나 지워졌습니다.';

export const BODY = '이 고객사의 공개 문의, 공개 댓글, 첨부 파일, 소속 고객(이메일과 이름)을 ZIP 파일 하나로 만듭니다. 다 되면 알림함으로 알립니다.';
export const PRIVATE = '비공개 문의도 넣기';
export const PRIVATE_HELP = '비공개 문의는 같은 고객사의 다른 고객도 보지 못하는 문의입니다.';
export const INTERNAL = '내부 댓글과 작업도 넣기';
export const INTERNAL_HELP = "상담 쪽 사람의 이름은 '지원팀'으로 적습니다. 내부 댓글 본문에 쓴 이름은 그대로 들어갑니다.";
export const CANCEL = '취소';
export const ASKING = '요청하는 중…';
export const RUNNING_ELSEWHERE = '다른 내보내기가 진행 중입니다. 끝난 뒤 다시 시도해 주세요.';
export const COMPANY_NOT_FOUND = '고객사를 찾을 수 없습니다.';
/** The common failure text of the screens (a lost connection, a 500). */
export const SERVER_FAILED = '서버에 연결하지 못했습니다. 잠시 뒤 다시 하세요.';

/** The 503 text with the seconds of `Retry-After`, or "잠시 뒤" without the header. */
export function busyText(seconds: number | null): string {
  return `서버가 잠시 내보내기를 받을 수 없습니다. ${seconds === null ? '잠시 뒤' : `${String(seconds)}초 뒤`} 다시 시도해 주세요.`;
}

/** The title of the dialog for a company named `name`. */
export const dialogTitle = (name: string) => `${name} 내보내기`;

// C-7 (screens.md "C-7 알림 문장")
export const readyText = (code: string) => `고객사 ${code} 내보내기가 끝났습니다`;
export const failedText = (code: string) => `고객사 ${code} 내보내기가 실패했습니다`;
export const armedText = (days: number, count: number) =>
  `문의 자동 정리가 켜졌습니다. 내일부터 종료된 지 ${String(days)}일이 지난 문의 ${String(count)}건을 휴지통으로 옮깁니다`;

// C-11, C-12
export const BY_CLEANUP = '자동 정리';
export const AUDIT_WORDS = { COMPANY_EXPORTED: '고객사 내보내기', COMPANY_EXPORT_DOWNLOADED: '고객사 내보내기 받기' } as const;
export const CHANGE_WORDS = { includeInternal: '내부 넣기', includePrivate: '비공개 넣기', exportId: '작업' } as const;

// ---------------------------------------------------------------------------
// Fixtures

/** A UUID whose last part is `n`. */
export const exportId = (n: number) => `0b7e2d4c-5a61-4f3e-9c2b-${String(n).padStart(12, '0')}`;

/** A `DONE` export of 최관리: 9.5 MB, asked at 03:00 and done at 03:10 UTC on 2026-10-08. */
export function companyExport(over: Partial<CompanyExport> = {}): CompanyExport {
  return {
    id: exportId(1),
    state: 'DONE',
    failure: null,
    includeInternal: false,
    includePrivate: false,
    requestedBy: { accountId: 1, name: '최관리' },
    requestedAt: '2026-10-08T03:00:00Z',
    finishedAt: '2026-10-08T03:10:00Z',
    size: 9_961_472,
    ...over,
  };
}

/** What 9_961_472 bytes read as (`formatBytes`). */
export const SIZE_TEXT = '9.5 MB';

export function running(over: Partial<CompanyExport> = {}): CompanyExport {
  return companyExport({ state: 'RUNNING', finishedAt: null, size: null, ...over });
}

export function failed(failure: CompanyExportFailure, over: Partial<CompanyExport> = {}): CompanyExport {
  return companyExport({ state: 'FAILED', failure, size: null, ...over });
}

export function expired(over: Partial<CompanyExport> = {}): CompanyExport {
  return companyExport({ state: 'EXPIRED', size: null, ...over });
}

/** `e` after it ended: `DONE` with a size, or `FAILED` with `failure`. */
export function ended(e: CompanyExport, failure: CompanyExportFailure | null = null): CompanyExport {
  return failure === null
    ? { ...e, state: 'DONE', failure: null, finishedAt: '2026-10-08T03:25:00Z', size: 9_961_472 }
    : { ...e, state: 'FAILED', failure, finishedAt: '2026-10-08T03:25:00Z', size: null };
}

/** One export in each state, each asked by another admin, the latest first. */
export const MIXED: CompanyExport[] = [
  running({ id: exportId(4), requestedBy: { accountId: 1, name: '최관리' }, requestedAt: '2026-10-08T03:18:00Z' }),
  companyExport({ id: exportId(3), requestedBy: { accountId: 5, name: '박관리' }, requestedAt: '2026-10-08T02:00:00Z' }),
  failed('DISK', { id: exportId(2), requestedBy: { accountId: 6, name: '한관리' }, requestedAt: '2026-10-07T09:00:00Z' }),
  expired({ id: exportId(1), requestedBy: { accountId: 7, name: '정관리' }, requestedAt: '2026-10-05T09:00:00Z' }),
];

/** The C-7 items of slice 10b: about no issue; the two export items carry the company. */
export function exportItem(kind: 'COMPANY_EXPORT_READY' | 'COMPANY_EXPORT_FAILED', over: Partial<Notification> = {}): Notification {
  return alertItem(kind, { companyCode: 'ACME', ...over });
}

export function armedItem(retentionDays: number, retentionCount: number, over: Partial<Notification> = {}): Notification {
  return alertItem('ISSUE_RETENTION_ARMED', { retentionDays, retentionCount, ...over });
}

// ---------------------------------------------------------------------------
// Fake server

/** State the slice 10b handlers read. Tests change it before rendering. */
export const s10b: {
  /** The exports of each company, by code, the latest first. */
  exports: Record<string, CompanyExport[]>;
  /** The ID the next export gets. */
  nextId: number;
} = { exports: {}, nextId: 50 };

type PathValue = string | readonly string[] | undefined;

function requireAdmin(): Response | undefined {
  if (!state.signedIn) return errors.unauthenticated();
  return state.me.role === 'ADMIN' ? undefined : errors.forbidden();
}

const notFound = () => errors.notFound();
const knownCompany = (code: PathValue) => state.companies.some((c) => c.code === code);
const knownExport = (id: PathValue) => Object.values(s10b.exports).flat().find((e) => e.id === id);

/** The 200 of `listCompanyExports` for `code`, through the contract check. */
export function exportsOf(code: string): Response {
  return HttpResponse.json(contract('listCompanyExports', 200, s10b.exports[code] ?? []));
}

/** What the slice 10b server does when nothing goes wrong. Tests replace one with `on` of console.ts. */
const handlers10b = [
  http.get('*/api/console/companies/:code/exports', ({ params }) => {
    const denied = requireAdmin();
    if (denied) return denied;
    return knownCompany(params.code) ? exportsOf(String(params.code)) : notFound();
  }),
  http.post('*/api/console/companies/:code/exports', async ({ request, params }) => {
    const denied = requireAdmin();
    if (denied) return denied;
    if (!knownCompany(params.code)) return notFound();
    const body = (await request.json()) as { includeInternal?: unknown; includePrivate?: unknown };
    if (typeof body.includeInternal !== 'boolean' || typeof body.includePrivate !== 'boolean') {
      return rejectedWith('createCompanyExport', 'INVALID_INPUT', 400, [{ field: 'includeInternal', code: 'REQUIRED' }]);
    }
    // One export at a time in the whole installation (acceptance.md 5절 3).
    if (Object.values(s10b.exports).flat().some((e) => e.state === 'RUNNING')) return exportRunning();
    s10b.nextId += 1;
    const created = running({
      id: exportId(s10b.nextId),
      includeInternal: body.includeInternal,
      includePrivate: body.includePrivate,
      requestedBy: { accountId: state.me.id, name: state.me.name },
      requestedAt: '2026-10-08T03:20:00Z',
    });
    const code = String(params.code);
    s10b.exports = { ...s10b.exports, [code]: [created, ...(s10b.exports[code] ?? [])] };
    return HttpResponse.json(contract('createCompanyExport', 202, created), { status: 202 });
  }),
  http.head('*/api/console/company-exports/:id/file', ({ params }) => {
    const denied = requireAdmin();
    if (denied) return new HttpResponse(null, { status: denied.status });
    return new HttpResponse(null, { status: knownExport(params.id)?.state === 'DONE' ? 200 : 404 });
  }),
  http.get('*/api/console/company-exports/:id/file', ({ params }) => {
    const denied = requireAdmin();
    if (denied) return denied;
    if (knownExport(params.id)?.state !== 'DONE') return notFound();
    return new HttpResponse(new Uint8Array([0x50, 0x4b, 0x05, 0x06]), {
      status: 200,
      headers: { 'Content-Type': 'application/zip', 'Content-Disposition': 'attachment; filename="popdesk-ACME-2026-10-08.zip"', 'Cache-Control': 'no-store' },
    });
  }),
];

beforeEach(() => {
  s10b.exports = { ACME: [], BETA: [] };
  s10b.nextId = 50;
  server.use(...handlers10b);
});

/** The 409 of `createCompanyExport`: another export is running. */
export function exportRunning(): Response {
  return rejectedWith('createCompanyExport', 'EXPORT_RUNNING', 409);
}

/** The 503 of `createCompanyExport` with `Retry-After` (or without it). */
export function serverBusy(seconds: number | null): Response {
  const body = contract('createCompanyExport', 503, { code: 'SERVER_BUSY', message: 'SERVER_BUSY (server text).' });
  return apiError(503, body, seconds === null ? {} : { 'Retry-After': String(seconds) });
}

// ---------------------------------------------------------------------------
// Finding things in C-9 (screens.md "C-9 내보내기 구역")

/** The 내보내기 구역: the region named by its title "내보내기", or null now. */
export function exportRegionNow(): HTMLElement | null {
  return screen.queryByRole('region', { name: /^내보내기/ });
}

export function exportRegion(): Promise<HTMLElement> {
  return found(exportRegionNow, `구역 "${SECTION}"`);
}

/** The lines of the list in the 구역: table rows with cells, or list items. Loading rows hold no requester. */
export function exportLinesNow(): HTMLElement[] {
  const region = exportRegionNow();
  if (!region) return [];
  const rows = within(region)
    .queryAllByRole('row')
    .filter((r) => within(r).queryAllByRole('cell').length > 0 && text(r) !== '');
  return rows.length > 0 ? rows : within(region).queryAllByRole('listitem');
}

/** The line asked by `name`, waited for. */
export function exportLine(name: string): Promise<HTMLElement> {
  return found(() => exportLinesNow().find((l) => text(l).includes(name)), `내보내기 구역의 "${name}" 줄`);
}

/** The "내보내기" button of the 구역's head, or null now. */
export function exportButtonNow(): HTMLElement | null {
  const region = exportRegionNow();
  if (!region) return null;
  return within(region).queryAllByRole('button', { name: EXPORT_BUTTON }).find((b) => b.closest('[role="dialog"], dialog') === null) ?? null;
}

/** The 내보내기 dialog: the open dialog named "{name} 내보내기". */
export function exportDialog(name = '에이크미'): Promise<HTMLElement> {
  return found(() => screen.queryByRole('dialog', { name: dialogTitle(name) }), `대화상자 "${dialogTitle(name)}"`);
}

/** The button of the dialog that sends: "내보내기", or "요청하는 중…" while it asks. */
export function sendButtonNow(box: HTMLElement): HTMLElement | null {
  return within(box)
    .queryAllByRole('button')
    .find((b) => [EXPORT_BUTTON, ASKING].includes(text(b))) ?? null;
}

/** Live regions (`role="status"`, `role="alert"`, `aria-live`) that hold `part`. */
export function spokenWith(part: string): Element[] {
  return Array.from(document.querySelectorAll('[role="status"], [role="alert"], [aria-live]'))
    .filter((el) => el.getAttribute('aria-live') !== 'off')
    .filter((el) => text(el).includes(part))
    // A live region inside another one is the same announcement.
    .filter((el, _, all) => !all.some((o) => o !== el && o.contains(el)));
}

/** How many times the list of `path` was asked for. */
export function listCalls(path = ACME_EXPORTS): number {
  return callsTo('GET', path).length;
}

/** Goes to another company as a person would: the menu "고객사", then its link in the list. */
export async function goToCompany(user: { click: (el: Element) => Promise<void> }, name: RegExp, code: string): Promise<void> {
  await user.click(await found(() => screen.queryAllByRole('link', { name: '고객사' }).find((a) => a.closest('nav') !== null), '메뉴 "고객사"'));
  await user.click(await found(() => screen.queryByRole('link', { name }), `${code} 링크`));
  await eventually(() => {
    expect(window.location.pathname).toBe(`/companies/${code}`);
  });
}

// ---------------------------------------------------------------------------
// The clock and the tab (the shape of app/layout.inbox.test.tsx)

/** Moves the fake clock of `useFakeClock` on by `ms`. */
export function tick(ms: number): void {
  act(() => {
    vi.advanceTimersByTime(ms);
  });
}

/** What a browser does when the tab is hidden or shown again. */
export function setHidden(hidden: boolean): void {
  Object.defineProperty(document, 'hidden', { configurable: true, get: () => hidden });
  Object.defineProperty(document, 'visibilityState', { configurable: true, get: () => (hidden ? 'hidden' : 'visible') });
  act(() => {
    document.dispatchEvent(new Event('visibilitychange'));
  });
}

afterEach(() => {
  // Back to jsdom's own `hidden` and `visibilityState` (on the prototype).
  Reflect.deleteProperty(document, 'hidden');
  Reflect.deleteProperty(document, 'visibilityState');
});
