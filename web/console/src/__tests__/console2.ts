// More test support for slice 2 (design/slices/2-manager/screens.md). The earlier
// support files stay as they are; this file adds the manager mark and the membership
// requests to the fake server, in the shape of docs/integrate/openapi-console.json,
// and the helpers the slice 2 screen tests share.
import type { ConsoleComponents } from '@popdesk/api/console';
import { http, HttpResponse } from 'msw/http';
import { beforeEach, expect } from 'vitest';
import { ACME, T0, errors, eventually, liveRegions, matchText, member, paged, screen, server, state, text, within, type Company } from './console';
// The 1a handlers too: adding and removing a company's customers.
import { rejected } from './console1a';

type Schemas = ConsoleComponents['schemas'];
export type PendingMembership = Schemas['PendingMembership'];
export type Notification = Schemas['Notification'];

// ---------------------------------------------------------------------------
// Fixtures

/** A manager of ACME asked for an account that exists already. `name` and `email` are what the manager typed. */
export function request(over: Partial<PendingMembership> = {}): PendingMembership {
  return {
    id: 301,
    company: { code: ACME.code, name: ACME.name },
    requestedBy: { accountId: 11, name: '김고객', email: 'kim@acme.test' },
    name: '박입력',
    email: 'park@other.test',
    account: { accountId: 31, name: '박실제', role: 'CUSTOMER', status: 'ACTIVE', invitationCancelled: false },
    requestedAt: T0,
    ...over,
  };
}

/** What the admin's inbox gets for a request (S2-AC-39). No screen reads it yet. */
export function notification(of: PendingMembership): Notification {
  return { id: 9000 + of.id, kind: 'MEMBERSHIP_REQUESTED', companyCode: of.company.code, createdAt: of.requestedAt, readAt: null };
}

// ---------------------------------------------------------------------------
// Fake server

/** The open requests, and the IDs of those closed in this test. Tests change `requests` before rendering. */
export const pending: { requests: PendingMembership[]; closed: number[] } = { requests: [], closed: [] };

export const REQUESTS = '/api/console/membership-requests';

function requireSession(): Response | undefined {
  return state.signedIn ? undefined : errors.unauthenticated();
}

function requireAdmin(): Response | undefined {
  return requireSession() ?? (state.me.role === 'ADMIN' ? undefined : errors.forbidden());
}

/** The company with the number of its open requests, so the list and the request list agree. */
function counted(c: Company): Company {
  return { ...c, pendingMembershipRequests: pending.requests.filter((r) => r.company.code === c.code).length };
}

type PathValue = string | readonly string[] | undefined;

function setManager(code: PathValue, accountId: PathValue, manager: boolean): Response {
  const denied = requireAdmin();
  if (denied) return denied;
  const company = state.companies.find((c) => c.code === code);
  const before = state.customers.find((m) => String(m.accountId) === accountId);
  if (!company || !before) return errors.notFound();
  if (manager && company.status === 'ARCHIVED') return rejected('COMPANY_NOT_ACTIVE');
  const after = { ...before, manager };
  state.customers = state.customers.map((m) => (m === before ? after : m));
  return HttpResponse.json(after);
}

/** Closes a request: 409 for one closed already, 404 for one that never was. */
function close(id: PathValue, approve: boolean): Response {
  const denied = requireAdmin();
  if (denied) return denied;
  const found = pending.requests.find((r) => String(r.id) === id);
  if (!found) return pending.closed.some((n) => String(n) === id) ? rejected('REQUEST_CLOSED', 409) : errors.notFound();
  if (approve) {
    if (found.account.role !== 'CUSTOMER') return rejected('NOT_A_CUSTOMER');
    if (state.companies.find((c) => c.code === found.company.code)?.status === 'ARCHIVED') return rejected('COMPANY_NOT_ACTIVE');
    state.customers = [...state.customers, member({ accountId: found.account.accountId, name: found.account.name, email: found.email, status: found.account.status })];
  }
  pending.requests = pending.requests.filter((r) => r !== found);
  pending.closed = [...pending.closed, found.id];
  return new HttpResponse(null, { status: 200 });
}

/** What the slice 2 server does when nothing goes wrong. Tests replace one with `route` of console1a. */
const handlers2 = [
  http.get('*/api/console/companies', () => requireSession() ?? HttpResponse.json(paged(state.companies.map(counted)))),
  http.get('*/api/console/companies/:code', ({ params }) => {
    const denied = requireSession();
    if (denied) return denied;
    const found = state.companies.find((c) => c.code === params.code);
    return found ? HttpResponse.json(counted(found)) : errors.notFound();
  }),
  http.put('*/api/console/companies/:code/customers/:accountId/manager', ({ params }) => setManager(params.code, params.accountId, true)),
  http.delete('*/api/console/companies/:code/customers/:accountId/manager', ({ params }) => setManager(params.code, params.accountId, false)),
  http.get(`*${REQUESTS}`, ({ request }) => {
    const denied = requireAdmin();
    if (denied) return denied;
    const query = new URL(request.url).searchParams;
    const code = query.get('companyCode');
    const page = Number(query.get('page') ?? '1');
    const size = Number(query.get('size') ?? '20');
    const all = pending.requests.filter((r) => code === null || r.company.code === code);
    return HttpResponse.json(paged(all.slice((page - 1) * size, page * size), { page, size, totalElements: all.length }));
  }),
  http.post(`*${REQUESTS}/:id/approve`, ({ params }) => close(params.id, true)),
  http.post(`*${REQUESTS}/:id/reject`, ({ params }) => close(params.id, false)),
  http.get('*/api/console/notifications/unread-count', () => requireSession() ?? HttpResponse.json({ count: state.me.role === 'ADMIN' ? pending.requests.length : 0 })),
  http.get('*/api/console/notifications', () => requireSession() ?? HttpResponse.json(paged(state.me.role === 'ADMIN' ? pending.requests.map(notification) : []))),
];

beforeEach(() => {
  pending.requests = [];
  pending.closed = [];
  server.use(...handlers2);
});

// ---------------------------------------------------------------------------
// Finding things

/** The cell of `row` under the column head `column` of the table inside `scope`. */
export function cellUnder(scope: HTMLElement, row: HTMLElement, column: string): Element | null {
  const head = within(scope)
    .queryAllByRole('columnheader')
    .find((h) => text(h) === column);
  if (!head?.parentElement) return null;
  return row.children[Array.from(head.parentElement.children).indexOf(head)] ?? null;
}

function saidNow(wanted: string | RegExp): boolean {
  return liveRegions().some((el) => matchText(text(el), wanted));
}

/** Waits for a toast or a band that says `wanted`: any live region. */
export async function said(wanted: string | RegExp) {
  await eventually(() => {
    expect(saidNow(wanted), `토스트나 띠: ${String(wanted)}`).toBe(true);
  });
}

export function expectNotSaid(wanted: string | RegExp) {
  expect(saidNow(wanted), `토스트나 띠: ${String(wanted)}`).toBe(false);
}

/** The 소속 요청 part of C-9, or null when the screen does not draw it. */
export function requestsRegion(): HTMLElement | null {
  return screen.queryByRole('region', { name: /소속 요청/ });
}
