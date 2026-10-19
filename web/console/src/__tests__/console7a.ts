// More test support for slice 7a (design/archive/7a-trash/screens.md): the trash. The earlier support
// files stay as they are; this file adds deleting an issue and the trash endpoints to the fake
// server, in the shape of docs/integrate/openapi-console.json, and the helpers the slice 7a screen
// tests share.
//
// Every answer made here goes through `contract` of console6a.ts: a fixture that does not fit the
// contract file fails the test that used it.
import type { ConsoleComponents } from '@popdesk/api/console';
import { waitFor } from '@testing-library/react';
import { HttpResponse, http } from 'msw/http';
import { beforeEach, expect } from 'vitest';
import { ADMIN_PERSON, errors, found, issue, paged, screen, server, state, text, within, type Issue } from './console';
import { contract, rejectedWith } from './console6a';

type Schemas = ConsoleComponents['schemas'];
export type TrashedIssue = Schemas['TrashedIssue'];
export type PagedTrashedIssue = Schemas['PagedTrashedIssue'];
export type IssueHistoryActor = Schemas['IssueHistoryActor'];

export const URL_TRASH = '/trash';
export const TRASH = '/api/console/trash';

/** The path of one issue (deleting it: `deleteIssue`). */
export function issuePath(key = 'ACME-7'): string {
  return `/api/console/issues/${key}`;
}

/** The path of an issue in the trash (purging it: `purgeIssue`). */
export function trashPath(key: string): string {
  return `${TRASH}/${key}`;
}

/** The path that restores an issue from the trash (`restoreIssue`). */
export function restorePath(key: string): string {
  return `${trashPath(key)}/restore`;
}

/** The time `useFakeClock` of console.ts starts at: "now" of the tests that read "{n}일 뒤". */
export const NOW = '2026-10-08T03:20:00Z';

const DAY = 24 * 60 * 60 * 1000;

/** `NOW` moved by `days` (and `hours`). */
export function fromNow(days: number, hours = 0): string {
  return new Date(Date.parse(NOW) + days * DAY + hours * 60 * 60 * 1000).toISOString();
}

/** popdesk itself as the actor of a history entry (the contract: account 0, no name). */
export const SYSTEM_HISTORY_ACTOR: IssueHistoryActor = { accountId: 0, name: null, role: null, photoUpdatedAt: null };

// ---------------------------------------------------------------------------
// Fixtures

/** An issue in the trash: deleted by 최관리 a day ago, purged by popdesk 29 days from now. */
export function trashed(over: Partial<TrashedIssue> = {}): TrashedIssue {
  return {
    key: 'ACME-12',
    title: '결제 내역이 두 번 보입니다',
    companyCode: 'ACME',
    companyName: '에이크미',
    status: 'IN_PROGRESS',
    deletedAt: fromNow(-1),
    deletedBy: ADMIN_PERSON,
    purgeAt: fromNow(29),
    ...over,
  };
}

export const ACME_12 = trashed();
export const BETA_5 = trashed({
  key: 'BETA-5',
  title: '영수증 메일이 안 옵니다',
  companyCode: 'BETA',
  companyName: '베타상사',
  status: 'RESOLVED',
  deletedAt: fromNow(-2),
  purgeAt: fromNow(28),
});

/** `n` issues in the trash, the latest deleted first, as the contract orders them. */
export function manyTrashed(n: number): TrashedIssue[] {
  return Array.from({ length: n }, (_, i) =>
    trashed({ key: `ACME-${String(100 + n - i)}`, title: `지운 문의 ${String(i + 1).padStart(3, '0')}번`, deletedAt: fromNow(-1, -i), purgeAt: fromNow(29, -i) }),
  );
}

/** The issue `restoreIssue` answers for `t`: back as it was. */
export function restoredIssue(t: TrashedIssue): Issue {
  return issue({ key: t.key, title: t.title, status: t.status, companyCode: t.companyCode, companyName: t.companyName, version: 3 });
}

// ---------------------------------------------------------------------------
// Fake server

/** State the slice 7a handlers read. Tests change it before rendering. */
export const s7a: {
  /** The issues in the trash, in the order the contract lists them (the latest deleted first). */
  trash: TrashedIssue[];
  /** The keys `restoreIssue` refuses with `RESTORE_LINK_CONFLICT`, and the other keys it names. */
  conflicts: Record<string, string[]>;
} = { trash: [], conflicts: {} };

function resetS7a() {
  s7a.trash = [];
  s7a.conflicts = {};
}

type PathValue = string | readonly string[] | undefined;

function keyOf(key: PathValue): string {
  return String(key);
}

function requireAdmin(): Response | undefined {
  if (!state.signedIn) return errors.unauthenticated();
  return state.me.role === 'ADMIN' ? undefined : errors.forbidden();
}

/** Keys deleted in this test: from then on `getIssue` answers 404 for them (S7A-AC-03). */
const deleted = new Set<string>();

function inTrash(key: PathValue): TrashedIssue | undefined {
  return s7a.trash.find((t) => t.key === keyOf(key));
}

/** One page of the trash for the `page` and `size` of the request. */
export function trashPage(request: Request): PagedTrashedIssue {
  const query = new URL(request.url).searchParams;
  const page = Number(query.get('page') ?? '1');
  const size = Number(query.get('size') ?? '20');
  const items = s7a.trash.slice((page - 1) * size, page * size);
  return paged(items, { page, size, totalElements: s7a.trash.length });
}

/** The answer of `listTrash` for `request`, through the contract check. */
export function trashResponse(request: Request): Response {
  return HttpResponse.json(contract('listTrash', 200, trashPage(request)));
}

/** The 200 of `restoreIssue` for `t`, through the contract check. */
export function restoredResponse(t: TrashedIssue): Response {
  return HttpResponse.json(contract('restoreIssue', 200, restoredIssue(t)));
}

/** The 409 of `restoreIssue` naming `issueKeys`, through the contract check. */
export function linkConflict(issueKeys: string[]): Response {
  const body: Schemas['ApiError'] = { code: 'RESTORE_LINK_CONFLICT', message: 'RESTORE_LINK_CONFLICT (server text).', issueKeys };
  return HttpResponse.json(contract('restoreIssue', 409, body), { status: 409 });
}

/** The 404 of an issue not in the trash (someone did it first, S7A-AC-23), through the contract check. */
export function notInTrash(operationId: 'restoreIssue' | 'purgeIssue'): Response {
  return rejectedWith(operationId, 'NOT_FOUND', 404);
}

/** What the slice 7a server does when nothing goes wrong. Tests replace one with `route` of console1a. */
const handlers7a = [
  // 문의 읽기 again: a deleted issue is 404 to admins too (S7A-AC-03).
  http.get('*/api/console/issues/:key', ({ params }) => {
    if (!state.signedIn) return errors.unauthenticated();
    const k = keyOf(params.key);
    return k === state.issue.key && !deleted.has(k) ? HttpResponse.json(contract('getIssue', 200, state.issue)) : errors.notFound();
  }),
  http.delete('*/api/console/issues/:key', ({ params }) => {
    const denied = requireAdmin();
    if (denied) return denied;
    const k = keyOf(params.key);
    if (k !== state.issue.key || deleted.has(k)) return errors.notFound();
    deleted.add(k);
    state.issues = state.issues.filter((i) => i.key !== k);
    return new HttpResponse(null, { status: 204 });
  }),
  http.get('*/api/console/trash', ({ request }) => requireAdmin() ?? trashResponse(request)),
  http.post('*/api/console/trash/:key/restore', ({ params }) => {
    const denied = requireAdmin();
    if (denied) return denied;
    const t = inTrash(params.key);
    if (!t) return notInTrash('restoreIssue');
    const conflict = s7a.conflicts[t.key];
    if (conflict) return linkConflict(conflict);
    s7a.trash = s7a.trash.filter((x) => x !== t);
    return restoredResponse(t);
  }),
  http.delete('*/api/console/trash/:key', ({ params }) => {
    const denied = requireAdmin();
    if (denied) return denied;
    const t = inTrash(params.key);
    if (!t) return notInTrash('purgeIssue');
    s7a.trash = s7a.trash.filter((x) => x !== t);
    return new HttpResponse(null, { status: 204 });
  }),
];

beforeEach(() => {
  resetS7a();
  deleted.clear();
  server.use(...handlers7a);
});

// ---------------------------------------------------------------------------
// Finding things on C-11 휴지통 (screens.md 2절)

/** The table of C-11 by its caption "휴지통, n건", or null now. */
export function trashTableNow(count?: number): HTMLElement | null {
  return screen.queryByRole('table', { name: count === undefined ? /^휴지통, \d+건$/ : `휴지통, ${String(count)}건` });
}

/** The table of C-11, waited for generously: the first wait of a test may meet a busy machine. */
export async function trashTable(count?: number): Promise<HTMLElement> {
  let el: HTMLElement | null = null;
  await waitFor(
    () => {
      el = trashTableNow(count);
      expect(el, `표 "휴지통, ${count === undefined ? 'n' : String(count)}건"`).not.toBeNull();
    },
    { timeout: 5000 },
  );
  return el as unknown as HTMLElement;
}

/** The body rows of the C-11 table now (rows with cells). */
export function trashRowsNow(): HTMLElement[] {
  const table = trashTableNow();
  return table ? within(table).queryAllByRole('row').filter((r) => within(r).queryAllByRole('cell').length > 0) : [];
}

/** The body row of the C-11 table that holds `key`, or null now. */
export function trashRowNow(key: string): HTMLElement | null {
  return trashRowsNow().find((r) => text(r).includes(key)) ?? null;
}

export function trashRow(key: string): Promise<HTMLElement> {
  return found(() => trashRowNow(key), `휴지통의 ${key} 줄`);
}

/** The "복구" of the row of `key`: its accessible name is "{키} 복구". */
export function restoreButton(key: string): Promise<HTMLElement> {
  return found(() => screen.queryByRole('button', { name: `${key} 복구` }), `"${key} 복구"`);
}

/** The "영구 삭제" of the row of `key`: its accessible name is "{키} 영구 삭제". */
export function purgeButton(key: string): Promise<HTMLElement> {
  return found(() => screen.queryByRole('button', { name: `${key} 영구 삭제` }), `"${key} 영구 삭제"`);
}

export const PURGE_QUESTION = '이 문의를 영구 삭제할까요?';
export const PURGE_TEXT = '내용, 댓글, 첨부, 작업, 연결이 지워지고 되돌릴 수 없습니다.';

/** The purge confirmation, or null now. */
export function purgeDialogNow(): HTMLElement | null {
  return (
    [...screen.queryAllByRole('dialog'), ...screen.queryAllByRole('alertdialog')].find((d) => text(d).includes(PURGE_QUESTION)) ?? null
  );
}

export function purgeDialog(): Promise<HTMLElement> {
  return found(purgeDialogNow, `대화상자 "${PURGE_QUESTION}"`);
}

/** A button of a dialog by its exact name: "영구 삭제", "지우기", "그만두기". */
export function dialogButton(box: HTMLElement, name: string): Promise<HTMLElement> {
  return found(() => within(box).queryByRole('button', { name }), `대화상자의 "${name}"`);
}

/** The `role="alert"` holding `part` that comes after `row` in the page: "줄 아래". */
export function alertAfter(row: HTMLElement, part: string): HTMLElement | null {
  return (
    screen
      .queryAllByRole('alert')
      .find((a) => text(a).includes(part) && (row.compareDocumentPosition(a) & Node.DOCUMENT_POSITION_FOLLOWING) !== 0) ?? null
  );
}

// ---------------------------------------------------------------------------
// C-4 문의 지우기 (screens.md 1절)

export const DELETE_QUESTION = '이 문의를 지울까요?';
export const DELETE_TEXT = '휴지통으로 옮깁니다. 30일 안에는 휴지통에서 복구할 수 있고, 그 뒤에는 영구 삭제됩니다. 고객과 상담원에게 더는 보이지 않습니다.';

/** The "문의 지우기" button of the C-4 head, or null now. */
export function deleteIssueButtonNow(): HTMLElement | null {
  return screen.queryByRole('button', { name: '문의 지우기' });
}

/** The delete confirmation, by its title "이 문의를 지울까요?", or null now. */
export function deleteDialogNow(): HTMLElement | null {
  return screen.queryByRole('dialog', { name: DELETE_QUESTION }) ?? screen.queryByRole('alertdialog', { name: DELETE_QUESTION });
}

export function deleteDialog(): Promise<HTMLElement> {
  return found(deleteDialogNow, `대화상자 "${DELETE_QUESTION}"`);
}
