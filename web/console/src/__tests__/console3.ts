// More test support for slice 3 (design/slices/3-issues/screens.md). The earlier
// support files stay as they are; this file adds the issue endpoints of slice 3 to
// the fake server, in the shape of docs/integrate/openapi-console.json, and the
// helpers the slice 3 screen tests share.
import type { ConsoleComponents } from '@popdesk/api/console';
import { http, HttpResponse } from 'msw/http';
import { beforeEach, expect } from 'vitest';
import {
  ADMIN_PERSON,
  AGENT_PERSON,
  OTHER_AGENT_PERSON,
  T0,
  callsTo,
  errors,
  eventually,
  found,
  issue,
  paged,
  screen,
  server,
  spokenText,
  state,
  text,
  within,
  type Issue,
  type Person,
} from './console';
// The 1a handlers too: this file answers two of their endpoints again (배정 빼기, 정지).
import { rejected } from './console1a';

type Schemas = ConsoleComponents['schemas'];
export type IssueHistoryEntry = Schemas['IssueHistoryEntry'];
export type CompanyReporter = Schemas['CompanyReporter'];
export type CreateIssueRequest = Schemas['CreateIssueRequest'];
export type UpdateIssueRequest = Schemas['UpdateIssueRequest'];
export type SuspendedAccount = Schemas['SuspendedAccount'];
export type Status = Issue['status'];

export const ISSUES = '/api/console/issues';

// ---------------------------------------------------------------------------
// Fixtures

/** The four statuses of the 내 담당 and 담당 없음 views: all but 종료. */
export const OPEN_STATUSES: readonly Status[] = ['RECEIVED', 'CONFIRMED', 'IN_PROGRESS', 'RESOLVED'];

export const STATUS_WORDS: Readonly<Record<Status, string>> = {
  RECEIVED: '접수',
  CONFIRMED: '확인',
  IN_PROGRESS: '처리 중',
  RESOLVED: '해결',
  CLOSED: '종료',
};

/** What the server allows from each status (rules.md 2절). The screen must not hold this table; the fake server does. */
const NEXT: Readonly<Record<Status, Status[]>> = {
  RECEIVED: ['CONFIRMED', 'IN_PROGRESS', 'CLOSED'],
  CONFIRMED: ['IN_PROGRESS', 'CLOSED'],
  IN_PROGRESS: ['RESOLVED'],
  RESOLVED: ['IN_PROGRESS', 'CLOSED'],
  CLOSED: [],
};

/** An issue in `status` with the transitions the server gives for it. A closed one can be reopened unless told otherwise. */
export function issueIn(status: Status, over: Partial<Issue> = {}): Issue {
  return issue({ status, nextStatuses: NEXT[status], reopenable: status === 'RESOLVED' || status === 'CLOSED', ...over });
}

export const KIM: CompanyReporter = { accountId: 11, name: '김고객', email: 'kim@acme.test', photoUpdatedAt: null };
export const CHOI: CompanyReporter = { accountId: 12, name: '최동료', email: 'choi@acme.test', photoUpdatedAt: null };
export const BAE: CompanyReporter = { accountId: 41, name: '배베타', email: 'bae@beta.test', photoUpdatedAt: null };

/** One line of an issue's history: 이상담 moved the status from 접수 to 처리 중. */
export function history(over: Partial<IssueHistoryEntry> = {}): IssueHistoryEntry {
  return {
    id: 1,
    kind: 'CHANGED',
    field: 'STATUS',
    oldValue: 'RECEIVED',
    newValue: 'IN_PROGRESS',
    oldPerson: null,
    newPerson: null,
    actor: AGENT_PERSON,
    createdAt: T0,
    ...over,
  };
}

// ---------------------------------------------------------------------------
// Fake server

/** State the slice 3 handlers read. Tests change it before rendering. */
export const s3: {
  /** Who can be the assignee, for every company. */
  candidates: Person[];
  /** The `ACTIVE` customers of each company, by code. */
  reporters: Record<string, CompanyReporter[]>;
  history: IssueHistoryEntry[];
  /** Open issues the asked account is the assignee of. */
  openAssignments: number;
  /** Issues the clearing request says it cleared; null means the same as `openAssignments`. */
  cleared: number | null;
} = { candidates: [], reporters: {}, history: [], openAssignments: 0, cleared: null };

function resetS3() {
  s3.candidates = [ADMIN_PERSON, AGENT_PERSON, OTHER_AGENT_PERSON];
  s3.reporters = { ACME: [KIM, CHOI], BETA: [BAE] };
  s3.history = [];
  s3.openAssignments = 0;
  s3.cleared = null;
}

type PathValue = string | readonly string[] | undefined;

function requireSession(): Response | undefined {
  return state.signedIn ? undefined : errors.unauthenticated();
}

function requireAdmin(): Response | undefined {
  return requireSession() ?? (state.me.role === 'ADMIN' ? undefined : errors.forbidden());
}

function unknownIssue(key: PathValue): Response | undefined {
  return requireSession() ?? (key === state.issue.key ? undefined : errors.notFound());
}

function unknownCompany(code: PathValue): Response | undefined {
  return requireSession() ?? (state.companies.some((c) => c.code === code) ? undefined : errors.notFound());
}

/** Changes the one issue of the fake server the way a write does: a new version. */
function change(over: Partial<Issue>): Response {
  state.issue = { ...state.issue, ...over, version: state.issue.version + 1, updatedAt: '2026-10-08T05:00:00Z' };
  return HttpResponse.json(state.issue);
}

function clearedNow(): number {
  return s3.cleared ?? s3.openAssignments;
}

/** What the slice 3 server does when nothing goes wrong. Tests replace one with `route` of console1a. */
const handlers3 = [
  http.post(`*${ISSUES}`, async ({ request }) => {
    const denied = requireSession();
    if (denied) return denied;
    const body = (await request.json()) as CreateIssueRequest;
    const company = state.companies.find((c) => c.code === body.companyCode);
    if (!company) return errors.notFound();
    if (company.status !== 'ACTIVE') return rejected('COMPANY_NOT_ACTIVE');
    const reporter = (s3.reporters[company.code] ?? []).find((r) => r.accountId === body.reporterId);
    if (!reporter) return errors.invalid([{ field: 'reporterId', code: 'UNKNOWN_VALUE' }]);
    state.issue = issueIn('RECEIVED', {
      key: `${company.code}-8`,
      title: body.title,
      content: body.content ?? '',
      priority: body.priority ?? 'NORMAL',
      private: body.private ?? false,
      channel: body.channel,
      companyCode: company.code,
      companyName: company.name,
      reporter: { accountId: reporter.accountId, name: reporter.name, role: 'CUSTOMER', photoUpdatedAt: reporter.photoUpdatedAt },
      createdBy: { accountId: state.me.id, name: state.me.name, role: state.me.role, photoUpdatedAt: state.me.photoUpdatedAt },
    });
    return HttpResponse.json(state.issue, { status: 201 });
  }),
  http.patch(`*${ISSUES}/:key`, async ({ request, params }) => {
    const denied = unknownIssue(params.key);
    if (denied) return denied;
    const { version, ...fields } = (await request.json()) as UpdateIssueRequest;
    if (state.issue.status === 'CLOSED') return errors.issueClosed();
    if (version !== state.issue.version) return rejected('VERSION_CONFLICT', 409);
    return change(fields);
  }),
  http.put(`*${ISSUES}/:key/status`, async ({ request, params }) => {
    const denied = unknownIssue(params.key);
    if (denied) return denied;
    const { status } = (await request.json()) as Schemas['ChangeIssueStatusRequest'];
    if (!state.issue.nextStatuses.includes(status)) return rejected('INVALID_TRANSITION');
    return change({ status, nextStatuses: NEXT[status], reopenable: status === 'RESOLVED' || status === 'CLOSED' });
  }),
  http.post(`*${ISSUES}/:key/reopen`, ({ params }) => {
    const denied = unknownIssue(params.key);
    if (denied) return denied;
    if (!state.issue.reopenable) return rejected('NOT_REOPENABLE');
    return change({ status: 'RECEIVED', nextStatuses: NEXT.RECEIVED, reopenable: false });
  }),
  http.put(`*${ISSUES}/:key/assignee`, async ({ request, params }) => {
    const denied = unknownIssue(params.key);
    if (denied) return denied;
    const { accountId } = (await request.json()) as Schemas['SetAssigneeRequest'];
    const person = s3.candidates.find((p) => p.accountId === accountId);
    if (!person) return errors.invalid([{ field: 'accountId', code: 'UNKNOWN_VALUE' }]);
    return change({ assignee: person, assigneeUnavailable: false });
  }),
  http.delete(`*${ISSUES}/:key/assignee`, ({ params }) => unknownIssue(params.key) ?? change({ assignee: null, assigneeUnavailable: false })),
  http.get(`*${ISSUES}/:key/history`, ({ request, params }) => {
    const denied = unknownIssue(params.key);
    if (denied) return denied;
    const query = new URL(request.url).searchParams;
    const page = Number(query.get('page') ?? '1');
    const size = Number(query.get('size') ?? '20');
    return HttpResponse.json(paged(s3.history.slice((page - 1) * size, page * size), { page, size, totalElements: s3.history.length }));
  }),

  http.get('*/api/console/companies/:code/assignees', ({ params }) => unknownCompany(params.code) ?? HttpResponse.json({ items: s3.candidates })),
  http.get('*/api/console/companies/:code/reporters', ({ request, params }) => {
    const denied = unknownCompany(params.code);
    if (denied) return denied;
    const q = new URL(request.url).searchParams.get('q')?.toLowerCase();
    const all = s3.reporters[String(params.code)] ?? [];
    return HttpResponse.json(paged(all.filter((r) => !q || r.name.toLowerCase().includes(q) || r.email.toLowerCase().includes(q))));
  }),
  http.get(
    '*/api/console/companies/:code/agents/:accountId/open-assignments',
    ({ params }) => requireAdmin() ?? unknownCompany(params.code) ?? HttpResponse.json({ issues: s3.openAssignments }),
  ),
  http.get('*/api/console/accounts/:accountId/open-assignments', () => requireAdmin() ?? HttpResponse.json({ issues: s3.openAssignments })),

  // 배정 빼기: a body only when the request asked to clear (the contract of slice 3).
  http.delete('*/api/console/companies/:code/agents/:accountId', ({ request, params }) => {
    const denied = requireAdmin();
    if (denied) return denied;
    state.agents = state.agents.filter((m) => String(m.accountId) !== params.accountId);
    const clear = new URL(request.url).searchParams.get('clearAssignments') === 'true';
    return clear ? HttpResponse.json({ issues: clearedNow() }) : new HttpResponse(null, { status: 200 });
  }),
  http.post('*/api/console/accounts/:accountId/suspend', ({ request, params }) => {
    const denied = requireAdmin();
    if (denied) return denied;
    const before = state.accounts.find((a) => String(a.id) === params.accountId);
    if (!before) return errors.notFound();
    const after = { ...before, status: 'SUSPENDED' as const };
    state.accounts = state.accounts.map((a) => (a === before ? after : a));
    const clear = new URL(request.url).searchParams.get('clearAssignments') === 'true';
    const body: SuspendedAccount = { ...after, cleared: { issues: clear ? clearedNow() : 0 } };
    return HttpResponse.json(body);
  }),
];

beforeEach(() => {
  resetS3();
  server.use(...handlers3);
});

// ---------------------------------------------------------------------------
// Requests

/** The query of the last issue list request so far; empty when there was none. */
export function lastList(): URLSearchParams {
  return new URL(callsTo('GET', ISSUES).at(-1)?.url ?? 'http://x/').searchParams;
}

/** The query of the address bar. */
export function addressQuery(): URLSearchParams {
  return new URLSearchParams(window.location.search);
}

/** The filters a query carries, without the page: `status` sorted. */
export function filtersOf(query: URLSearchParams) {
  return {
    q: query.get('q'),
    status: query.getAll('status').sort(),
    companyCode: query.get('companyCode'),
    assignee: query.get('assignee'),
    reporter: query.get('reporter'),
  };
}

export type Filters = ReturnType<typeof filtersOf>;

export function filters(over: Partial<Filters> = {}): Filters {
  return { q: null, status: [], companyCode: null, assignee: null, reporter: null, ...over };
}

/** Waits until the address and the last list request both carry exactly these filters, on the first page. */
export async function listed(wanted: Partial<Filters>) {
  const all = filters({ ...wanted, status: [...(wanted.status ?? [])].sort() });
  await eventually(() => {
    expect(filtersOf(addressQuery()), '주소의 쿼리').toEqual(all);
    expect(filtersOf(lastList()), '목록 요청의 쿼리').toEqual(all);
    expect(Number(addressQuery().get('page') ?? '1'), '주소의 쪽').toBe(1);
    expect(Number(lastList().get('page') ?? '1'), '요청의 쪽').toBe(1);
  });
}

// ---------------------------------------------------------------------------
// Finding things

export function select(label: string | RegExp): Promise<HTMLSelectElement> {
  return found(() => screen.queryAllByLabelText<HTMLSelectElement>(label, { selector: 'select' })[0], `고르기 "${String(label)}"`);
}

/** The label of the choice a select shows now. */
export function chosen(el: HTMLSelectElement): string {
  return text(el.selectedOptions[0]);
}

export function optionLabels(el: HTMLSelectElement): string[] {
  return Array.from(el.options).map((o) => text(o));
}

/** The option of a select whose label holds `part`, e.g. a person's name. */
export function optionWith(el: HTMLSelectElement, part: string): Promise<HTMLOptionElement> {
  return found(() => Array.from(el.options).find((o) => text(o).includes(part)), `선택지 "${part}"`);
}

/** The value (`dd`) of the line named `term` in the value list at the head of C-4; null when there is no such line. */
export function metaOf(term: string): Element | null {
  return screen.queryAllByRole('term').find((t) => text(t) === term)?.nextElementSibling ?? null;
}

/** What a screen reader reads in the cell of `row` under the column whose head holds `column`: no initials of a photo. */
export function cellText(row: HTMLElement, column: string): string {
  const head = screen.queryAllByRole('columnheader').find((h) => text(h).includes(column));
  expect(head ?? null, `열 머리 "${column}"`).not.toBeNull();
  const cell = row.children[Array.from(head?.parentElement?.children ?? []).indexOf(head as HTMLElement)];
  return cell ? spokenText(cell) : '';
}

/** The table row of the issue `key`. */
export function issueRow(key: string): Promise<HTMLElement> {
  return found(
    () => screen.queryAllByRole('row').find((r) => within(r).queryAllByRole('cell').some((c) => text(c) === key)),
    `${key} 줄`,
  );
}

/** A thing that opens a folded part: a button, or the `summary` of a `details`. */
export function opener(name: RegExp): Promise<HTMLElement> {
  return found(
    () => screen.queryByRole('button', { name }) ?? Array.from(document.querySelectorAll<HTMLElement>('summary')).find((s) => name.test(text(s))),
    `펼치는 것 ${String(name)}`,
  );
}
