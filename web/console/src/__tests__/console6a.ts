// More test support for slice 6a (design/slices/6a-labels/screens.md): labels and due dates.
// The earlier support files stay as they are; this file adds the label and due date endpoints
// to the fake server, in the shape of docs/integrate/openapi-console.json, and the helpers the
// slice 6a screen tests share.
//
// Every answer made here goes through `contract`: a fixture that does not fit the contract
// file fails the test that used it (the check of packages/api/src/testing).
import { HttpResponse, http } from 'msw/http';
import { afterEach, beforeEach, expect } from 'vitest';
import type { ConsoleComponents } from '@popdesk/api/console';
import { checkResponse } from '../../../packages/api/src/testing/contract';
import { apiError, callsTo, errors, found, screen, server, state, text, within, type Issue } from './console';

type Schemas = ConsoleComponents['schemas'];
export type Label = Schemas['Label'];
export type LabelRef = Schemas['LabelRef'];

export const ISSUE_7 = '/api/console/issues/ACME-7';
export const DUE_DATE_7 = `${ISSUE_7}/due-date`;
export const ACME_LABELS = '/api/console/companies/ACME/labels';
export const BETA_LABELS = '/api/console/companies/BETA/labels';

/** The path that puts label `id` on ACME-7 or takes it off. */
export function issueLabelPath(id: number, key = 'ACME-7'): string {
  return `/api/console/issues/${key}/labels/${String(id)}`;
}

/** The path of one label of a company. */
export function companyLabelPath(id: number, code = 'ACME'): string {
  return `/api/console/companies/${code}/labels/${String(id)}`;
}

// ---------------------------------------------------------------------------
// The contract

/** What the contract check found wrong in this test. A test with a line here fails when it ends. */
const contractProblems: string[] = [];

/** `value` after the contract check of the `status` answer of `operationId`. */
export function contract<T>(operationId: string, status: number, value: T): T {
  try {
    return checkResponse('console', operationId, status, value);
  } catch (error) {
    contractProblems.push(error instanceof Error ? error.message : String(error));
    throw error;
  }
}

// ---------------------------------------------------------------------------
// Fixtures

/** The eight swatches of C-9 (screens.md 4절), by the name a screen reader reads. */
export const SWATCHES = [
  ['회색', '#808080'],
  ['빨강', '#C0262D'],
  ['주황', '#B07A00'],
  ['노랑', '#F2C14E'],
  ['초록', '#1F7A3A'],
  ['청록', '#0F766E'],
  ['파랑', '#245FB0'],
  ['보라', '#6D28D9'],
] as const;

export function label(over: Partial<Label> = {}): Label {
  return { id: 61, name: '긴급', color: '#C0262D', issueCount: 0, ...over };
}

/** A label as an issue carries it. */
export function refOf(l: Label): LabelRef {
  return { id: l.id, name: l.name, color: l.color };
}

export const URGENT = label({ id: 61, name: '긴급', color: '#C0262D', issueCount: 3 });
export const BILLING = label({ id: 62, name: '결제', color: '#245FB0', issueCount: 1 });
export const MOBILE = label({ id: 63, name: '모바일', color: '#F2C14E', issueCount: 0 });
/** A label of the other company: never a choice for an ACME issue. */
export const BETA_ONLY = label({ id: 71, name: '베타전용', color: '#1F7A3A', issueCount: 2 });

/** `n` labels named 라벨01, 라벨02, … with IDs from `first`. */
export function manyLabels(n: number, first = 100): Label[] {
  return Array.from({ length: n }, (_, i) => label({ id: first + i, name: `라벨${String(i + 1).padStart(2, '0')}`, color: '#808080' }));
}

/** Name order whatever the case (S6A-AC-10, S6A-AC-50). */
export function byName<T extends { name: string }>(items: readonly T[]): T[] {
  return [...items].sort((a, b) => a.name.toLowerCase().localeCompare(b.name.toLowerCase()));
}

// ---------------------------------------------------------------------------
// Fake server

/** State the slice 6a handlers read. Tests change it before rendering. */
export const s6a: {
  /** The labels of each company, by code. */
  labels: Record<string, Label[]>;
  /** The ID the next created label gets. */
  nextId: number;
} = { labels: {}, nextId: 90 };

function resetS6a() {
  s6a.labels = { ACME: [URGENT, BILLING, MOBILE], BETA: [BETA_ONLY] };
  s6a.nextId = 90;
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

/** A 400 with `code`, in the contract's error shape. */
export function rejectedWith(operationId: string, code: string, status = 400, fields?: Schemas['FieldError'][]): Response {
  const body: Schemas['ApiError'] = fields ? { code, message: `${code} (server text).`, fields } : { code, message: `${code} (server text).` };
  return apiError(status, contract(operationId, status, body));
}

/** The issue after a change: `version` one up (design.md 0절 5). */
function changed(over: Partial<Issue>): Issue {
  state.issue = { ...state.issue, ...over, version: state.issue.version + 1 };
  return state.issue;
}

/** The answer to a label being put on or taken off: the issue as it is now. */
export function issueAnswer(operationId: string): Response {
  return HttpResponse.json(contract(operationId, 200, state.issue));
}

async function jsonOf(request: Request): Promise<Record<string, unknown>> {
  const raw = await request.text();
  return raw === '' ? {} : (JSON.parse(raw) as Record<string, unknown>);
}

/** What the slice 6a server does when nothing goes wrong. Tests replace one with `on` of console.ts. */
const handlers6a = [
  // The issue itself again, through the contract check.
  http.get('*/api/console/issues/:key', ({ params }) => unknownIssue(params.key) ?? HttpResponse.json(contract('getIssue', 200, state.issue))),
  http.get('*/api/console/companies/:code/labels', ({ params }) => {
    const denied = unknownCompany(params.code);
    if (denied) return denied;
    return HttpResponse.json(contract('listCompanyLabels', 200, { items: byName(s6a.labels[String(params.code)] ?? []) }));
  }),
  http.post('*/api/console/companies/:code/labels', async ({ request, params }) => {
    const denied = unknownCompany(params.code) ?? requireAdmin();
    if (denied) return denied;
    const body = await jsonOf(request);
    const created = label({
      id: s6a.nextId++,
      name: typeof body['name'] === 'string' ? body['name'].trim() : '',
      color: typeof body['color'] === 'string' ? body['color'].toUpperCase() : '#808080',
      issueCount: 0,
    });
    const code = String(params.code);
    s6a.labels = { ...s6a.labels, [code]: [...(s6a.labels[code] ?? []), created] };
    return HttpResponse.json(contract('createLabel', 201, created), { status: 201 });
  }),
  http.patch('*/api/console/companies/:code/labels/:id', async ({ request, params }) => {
    const denied = unknownCompany(params.code) ?? requireAdmin();
    if (denied) return denied;
    const code = String(params.code);
    const id = Number(params.id);
    const old = (s6a.labels[code] ?? []).find((l) => l.id === id);
    if (!old) return errors.notFound();
    const body = await jsonOf(request);
    const saved: Label = {
      ...old,
      ...(typeof body['name'] === 'string' ? { name: body['name'].trim() } : {}),
      ...(typeof body['color'] === 'string' ? { color: body['color'].toUpperCase() } : {}),
    };
    s6a.labels = { ...s6a.labels, [code]: (s6a.labels[code] ?? []).map((l) => (l.id === id ? saved : l)) };
    return HttpResponse.json(contract('updateLabel', 200, saved));
  }),
  http.delete('*/api/console/companies/:code/labels/:id', ({ params }) => {
    const denied = unknownCompany(params.code) ?? requireAdmin();
    if (denied) return denied;
    const code = String(params.code);
    const id = Number(params.id);
    if (!(s6a.labels[code] ?? []).some((l) => l.id === id)) return errors.notFound();
    s6a.labels = { ...s6a.labels, [code]: (s6a.labels[code] ?? []).filter((l) => l.id !== id) };
    return new HttpResponse(null, { status: 200 });
  }),
  http.put('*/api/console/issues/:key/labels/:id', ({ params }) => {
    const denied = unknownIssue(params.key);
    if (denied) return denied;
    if (state.issue.status === 'CLOSED') return rejectedWith('addIssueLabel', 'ISSUE_CLOSED');
    const id = Number(params.id);
    const known = (s6a.labels[state.issue.companyCode] ?? []).find((l) => l.id === id);
    if (!known) return rejectedWith('addIssueLabel', 'INVALID_INPUT', 400, [{ field: 'id', code: 'UNKNOWN_VALUE' }]);
    if (!state.issue.labels.some((l) => l.id === id)) changed({ labels: byName([...state.issue.labels, refOf(known)]) });
    return issueAnswer('addIssueLabel');
  }),
  http.delete('*/api/console/issues/:key/labels/:id', ({ params }) => {
    const denied = unknownIssue(params.key);
    if (denied) return denied;
    const id = Number(params.id);
    if (state.issue.labels.some((l) => l.id === id)) changed({ labels: state.issue.labels.filter((l) => l.id !== id) });
    return issueAnswer('removeIssueLabel');
  }),
  http.put('*/api/console/issues/:key/due-date', async ({ request, params }) => {
    const denied = unknownIssue(params.key);
    if (denied) return denied;
    const sent = (await jsonOf(request))['dueDate'];
    const dueDate = typeof sent === 'string' ? sent : '';
    if (state.issue.dueDate !== dueDate) changed({ dueDate, overdue: false });
    return HttpResponse.json(contract('setIssueDueDate', 200, state.issue));
  }),
  http.delete('*/api/console/issues/:key/due-date', ({ params }) => {
    const denied = unknownIssue(params.key);
    if (denied) return denied;
    if (state.issue.dueDate !== null) changed({ dueDate: null, overdue: false });
    return HttpResponse.json(contract('clearIssueDueDate', 200, state.issue));
  }),
];

beforeEach(() => {
  resetS6a();
  contractProblems.length = 0;
  server.use(...handlers6a);
});

afterEach(() => {
  expect(contractProblems, '계약과 어긋난 가짜 응답').toEqual([]);
});

/** The 400 the server gives a due date before today in the installation time zone (S6A-AC-31). */
export function pastDate(): Response {
  return rejectedWith('setIssueDueDate', 'INVALID_INPUT', 400, [{ field: 'dueDate', code: 'OUT_OF_RANGE' }]);
}

// ---------------------------------------------------------------------------
// Requests

/** The `label` values of the last issue list request, as sent. */
export function labelParams(): string[] {
  const last = callsTo('GET', '/api/console/issues').at(-1);
  return last ? new URL(last.url).searchParams.getAll('label') : [];
}

/** The body of a request, parsed as JSON. */
export async function bodyOf(method: string, path: string, index = -1): Promise<Record<string, unknown>> {
  const call = callsTo(method, path).at(index);
  expect(call, `${method} ${path} 요청`).toBeDefined();
  return ((await call?.body) ?? {}) as Record<string, unknown>;
}

// ---------------------------------------------------------------------------
// Finding things

/** The value (`dd`) of the line named `term` in the value list (`dl`) at the head of C-4, waited for. */
export function metaValue(term: string): Promise<HTMLElement> {
  return found(() => {
    const dt = screen.queryAllByRole('term').find((t) => text(t) === term);
    return dt?.nextElementSibling instanceof HTMLElement ? dt.nextElementSibling : null;
  }, `C-4 머리의 칸 "${term}"`);
}

/** The `dd` of `term`, or null now. */
export function metaValueNow(term: string): HTMLElement | null {
  const dt = screen.queryAllByRole('term').find((t) => text(t) === term);
  return dt?.nextElementSibling instanceof HTMLElement ? dt.nextElementSibling : null;
}

/**
 * The "바꾸기" button inside the `dd` of `term`, or null. Its accessible name is "라벨 바꾸기" or
 * "마감일 바꾸기" (screens.md 1절): the locked slice 3 tests want one button named "바꾸기" or
 * "담당자 바꾸기" on the page, so the 담당자 one keeps either name.
 */
export function changeButtonOf(term: string): HTMLElement | null {
  const dd = metaValueNow(term);
  const name = term === '담당자' ? /^(담당자 )?바꾸기$/ : `${term} 바꾸기`;
  return dd ? within(dd).queryByRole('button', { name }) : null;
}

/** Every button of the `dd` of `term` whose name holds "바꾸기", whatever the rest of the name. */
export function anyChangeButtonOf(term: string): HTMLElement[] {
  const dd = metaValueNow(term);
  return dd ? within(dd).queryAllByRole('button', { name: /바꾸기/ }) : [];
}

/** The terms of the C-4 value list, in order. */
export function metaTerms(): string[] {
  return screen.queryAllByRole('term').map((t) => text(t));
}

/** A checkbox named `name` inside `root`. */
export function boxIn(root: HTMLElement, name: string): HTMLInputElement | null {
  return within(root).queryByRole<HTMLInputElement>('checkbox', { name });
}

/** The label region of C-9 (screens.md 4절), or null. */
export function labelRegion(): HTMLElement | null {
  return screen.queryByRole('region', { name: /^라벨/ });
}

/** The line of the C-9 label list that holds `name`: a table row or a list item. */
export function labelLine(name: string): HTMLElement | null {
  const region = labelRegion();
  if (!region) return null;
  const lines = [...within(region).queryAllByRole('row'), ...within(region).queryAllByRole('listitem')];
  return lines.find((l) => within(l).queryAllByText(name, { exact: true }).length > 0 || text(l).startsWith(name)) ?? null;
}

/** The label names of the C-9 list, in the order shown. */
export function labelLineNames(names: readonly string[]): string[] {
  const region = labelRegion();
  if (!region) return [];
  const all = text(region);
  return names
    .map((n) => ({ n, at: all.indexOf(n) }))
    .filter((x) => x.at >= 0)
    .sort((a, b) => a.at - b.at)
    .map((x) => x.n);
}

/** `#RRGGBB` as jsdom writes a colour set in `style`: `rgb(r, g, b)`. */
export function rgbText(hex: string): string {
  const [r, g, b] = [1, 3, 5].map((i) => parseInt(hex.slice(i, i + 2), 16));
  return `rgb(${String(r)}, ${String(g)}, ${String(b)})`;
}

/**
 * The chip of `name` inside `root`: an element whose whole text is the name and that, or an
 * element above it inside `root`, carries the background colour in `style` (the chip colour
 * is set on the chip itself, LabelChip.test.tsx). Returns the element with the colour; null when there is none.
 */
export function chipIn(root: HTMLElement, name: string): HTMLElement | null {
  for (const el of within(root).queryAllByText(name, { exact: true })) {
    for (let at: HTMLElement | null = el; at && at !== root.parentElement; at = at.parentElement) {
      if (at.style.backgroundColor !== '') return at;
    }
  }
  return null;
}

/** A due date the browser shows in C-4: `2026년 10월 20일`. */
export function longDate(iso: string): string {
  const [y, m, d] = iso.split('-').map(Number);
  return `${String(y)}년 ${String(m)}월 ${String(d)}일`;
}
