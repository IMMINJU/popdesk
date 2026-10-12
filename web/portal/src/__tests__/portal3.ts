// More test support for slice 3 (design/slices/3-issues/screens.md). The earlier
// support files stay as they are; this file adds the issue endpoints of slice 3 to
// the fake server, in the shape of docs/integrate/openapi-portal.json, and the
// helpers the slice 3 screen tests share.
import type { PortalComponents } from '@popdesk/api/portal';
import { http, HttpResponse } from 'msw/http';
import { beforeEach, expect, onTestFinished } from 'vitest';
import { callsTo, errors, eventually, found, issue, screen, server, state, text, type Issue } from './portal';
import { rejected } from './portal1a';

type Schemas = PortalComponents['schemas'];
export type UpdateIssueRequest = Schemas['UpdateIssueRequest'];
export type Status = Issue['status'];

export const ISSUES = '/api/portal/issues';

// ---------------------------------------------------------------------------
// Fixtures

/** The customer's words for the five statuses (UX-3). */
export const STATUS_WORDS: Readonly<Record<Status, string>> = {
  RECEIVED: '접수됨',
  CONFIRMED: '확인됨',
  IN_PROGRESS: '처리 중',
  RESOLVED: '해결됨',
  CLOSED: '종료됨',
};

export const RESOLVED_AT = '2026-10-08T04:00:00Z';
export const CLOSED_AT = '2026-10-08T05:00:00Z';

/**
 * An issue in `status` as the server gives it: the times that go with the status, and
 * `reopenable` for a resolved or closed one unless told otherwise. `reopenable` says
 * nothing about who asks (the contract), so a colleague's issue has it too.
 */
export function issueIn(status: Status, over: Partial<Issue> = {}): Issue {
  return issue({
    status,
    resolvedAt: status === 'RESOLVED' ? RESOLVED_AT : null,
    closedAt: status === 'CLOSED' ? CLOSED_AT : null,
    reopenable: status === 'RESOLVED' || status === 'CLOSED',
    ...over,
  });
}

// ---------------------------------------------------------------------------
// Fake server

type PathValue = string | readonly string[] | undefined;

function unknownIssue(key: PathValue): Response | undefined {
  if (!state.signedIn) return errors.unauthenticated();
  return key === state.issue.key ? undefined : errors.notFound();
}

/** Changes the one issue of the fake server the way a write does: a new version. */
function change(over: Partial<Issue>): Response {
  state.issue = { ...state.issue, ...over, version: state.issue.version + 1, updatedAt: '2026-10-08T06:00:00Z' };
  return HttpResponse.json(state.issue);
}

/** What the slice 3 server does when nothing goes wrong. Tests replace one with `route` of portal1a. */
const handlers3 = [
  http.patch(`*${ISSUES}/:key`, async ({ request, params }) => {
    const denied = unknownIssue(params.key);
    if (denied) return denied;
    const { version, ...fields } = (await request.json()) as UpdateIssueRequest;
    if (state.issue.status === 'CLOSED') return rejected('ISSUE_CLOSED');
    if (version !== state.issue.version) return rejected('VERSION_CONFLICT', 409);
    return change(fields);
  }),
  http.post(`*${ISSUES}/:key/close`, ({ params }) => {
    const denied = unknownIssue(params.key);
    if (denied) return denied;
    if (state.issue.status === 'CLOSED') return rejected('ISSUE_CLOSED');
    return change({ status: 'CLOSED', closedAt: CLOSED_AT, reopenable: true });
  }),
  http.post(`*${ISSUES}/:key/reopen`, ({ params }) => {
    const denied = unknownIssue(params.key);
    if (denied) return denied;
    if (!state.issue.reopenable) return rejected('NOT_REOPENABLE');
    return change({ status: 'RECEIVED', resolvedAt: null, closedAt: null, reopenable: false });
  }),
];

beforeEach(() => {
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
    mine: query.get('mine'),
  };
}

export type Filters = ReturnType<typeof filtersOf>;

export function filters(over: Partial<Filters> = {}): Filters {
  return { q: null, status: [], companyCode: null, mine: null, ...over };
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

/** The value (`dd`) of the line named `term` in the value list at the head of P-5; null when there is no such line. */
export function metaOf(term: string): Element | null {
  return screen.queryAllByRole('term').find((t) => text(t) === term)?.nextElementSibling ?? null;
}

/**
 * A window 360px wide for this test: media queries on the width answer for it
 * (`portal/src/__tests__/network.ts` answers as a PC-width window). The way
 * `AppShell.narrow.test.tsx` of the ui package does it.
 */
export function narrowWindow(width = 360) {
  const before = Object.getOwnPropertyDescriptor(window, 'matchMedia');
  const matches = (media: string) => {
    const max = /max-width:\s*([\d.]+)px/.exec(media);
    const min = /min-width:\s*([\d.]+)px/.exec(media);
    return (!max || width <= Number(max[1])) && (!min || width >= Number(min[1]));
  };
  Object.defineProperty(window, 'matchMedia', {
    configurable: true,
    value: (media: string) => ({
      media,
      matches: matches(media),
      onchange: null,
      addEventListener: () => undefined,
      removeEventListener: () => undefined,
      addListener: () => undefined,
      removeListener: () => undefined,
      dispatchEvent: () => false,
    }),
  });
  onTestFinished(() => {
    if (before) Object.defineProperty(window, 'matchMedia', before);
    else Reflect.deleteProperty(window, 'matchMedia');
  });
}
