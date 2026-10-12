// More test support for slice 1a (design/slices/1a-people/screens.md). `console.ts`
// stays as it is; this file adds the 1a endpoints to the fake server, in the shape
// of docs/integrate/openapi-console.json, and the helpers the 1a screen tests share.
import type { ConsoleComponents } from '@popdesk/api/console';
import { act } from '@testing-library/react';
import { http, HttpResponse } from 'msw/http';
import { afterEach, beforeEach, expect } from 'vitest';
import {
  INVITE_LINK,
  account,
  apiError,
  errors,
  eventually,
  found,
  member,
  openDialog,
  paged,
  readParts,
  screen,
  server,
  state,
  text,
  within,
  type Account,
  type Company,
  type InvitationResult,
  type Me,
} from './console';

type Schemas = ConsoleComponents['schemas'];
export type PasswordResetLink = Schemas['PasswordResetLink'];

/** When a photo was last changed: the `v` of its address. */
export const PHOTO_AT = '2026-10-09T01:02:03Z';
export const PHOTO_AT_NEW = '2026-10-10T04:05:06Z';

const RESET_TOKEN = 'rst-Qm27xVd9Ke1b';
/** A reset link as the server makes it: the token only in the fragment (design.md 5-1절). */
export const RESET_LINK = `http://console.localhost/reset-password#token=${RESET_TOKEN}`;

export function rejected(code: string, status = 400) {
  return apiError(status, { code, message: `${code} (server text).` });
}

export const csrfInvalid = () => rejected('CSRF_TOKEN_INVALID', 403);

function requireSession(): Response | undefined {
  return state.signedIn ? undefined : errors.unauthenticated();
}

function requireAdmin(): Response | undefined {
  return requireSession() ?? (state.me.role === 'ADMIN' ? undefined : errors.forbidden());
}

function changeAccount(id: string | readonly string[] | undefined, change: Partial<Account>): Response {
  const denied = requireAdmin();
  if (denied) return denied;
  const before = state.accounts.find((a) => String(a.id) === id);
  if (!before) return errors.notFound();
  const after = { ...before, ...change };
  state.accounts = state.accounts.map((a) => (a === before ? after : a));
  return HttpResponse.json(after);
}

function changeCompany(code: string | readonly string[] | undefined, change: Partial<Company>): Response {
  const denied = requireAdmin();
  if (denied) return denied;
  const before = state.companies.find((c) => c.code === code);
  if (!before) return errors.notFound();
  const after = { ...before, ...change };
  state.companies = state.companies.map((c) => (c === before ? after : c));
  return HttpResponse.json(after);
}

function setInvitation(id: string | readonly string[] | undefined, cancelled: boolean) {
  state.accounts = state.accounts.map((a) => (String(a.id) === id ? { ...a, status: cancelled ? 'SUSPENDED' : 'INVITED' } : a));
  state.invitations = state.invitations.map((i) => (String(i.accountId) === id ? { ...i, cancelled } : i));
}

/** What the 1a server does when nothing goes wrong. Tests replace one with `route`. */
const handlers1a = [
  http.post('*/api/public/password-reset/request', () => new HttpResponse(null, { status: 202 })),
  http.post('*/api/public/password-reset/confirm', () => new HttpResponse(null, { status: 204 })),

  http.patch('*/api/console/me', async ({ request }) => {
    const denied = requireSession();
    if (denied) return denied;
    const body = (await request.json()) as Schemas['UpdateMeRequest'];
    state.me = { ...state.me, ...body };
    return HttpResponse.json(state.me);
  }),
  http.put('*/api/console/me/password', () => requireSession() ?? new HttpResponse(null, { status: 204 })),
  http.post('*/api/console/me/photo', async ({ request }) => {
    const denied = requireSession();
    if (denied) return denied;
    await readParts(request);
    state.me = { ...state.me, photoUpdatedAt: PHOTO_AT_NEW };
    return HttpResponse.json(state.me, { status: 201 });
  }),
  http.delete('*/api/console/me/photo', () => {
    const denied = requireSession();
    if (denied) return denied;
    state.me = { ...state.me, photoUpdatedAt: null };
    return new HttpResponse(null, { status: 204 });
  }),

  // The account list with the 1a filters: role, status and a part of the name or email (S1A-AC-29).
  http.get('*/api/console/accounts', ({ request }) => {
    const denied = requireAdmin();
    if (denied) return denied;
    const query = new URL(request.url).searchParams;
    const role = query.get('role');
    const status = query.get('status');
    const q = query.get('q')?.toLowerCase();
    const items = state.accounts.filter(
      (a) =>
        (!role || a.role === role) &&
        (!status || a.status === status) &&
        (!q || a.name.toLowerCase().includes(q) || a.email.toLowerCase().includes(q)),
    );
    return HttpResponse.json(paged(items));
  }),
  http.patch('*/api/console/accounts/:accountId', async ({ request, params }) => {
    const body = (await request.json()) as Schemas['UpdateAccountRequest'];
    return changeAccount(params.accountId, body.name === undefined ? {} : { name: body.name });
  }),
  http.put('*/api/console/accounts/:accountId/role', async ({ request, params }) => {
    const body = (await request.json()) as Schemas['ChangeRoleRequest'];
    return changeAccount(params.accountId, { role: body.role });
  }),
  http.post('*/api/console/accounts/:accountId/suspend', ({ params }) => changeAccount(params.accountId, { status: 'SUSPENDED' })),
  http.post('*/api/console/accounts/:accountId/reactivate', ({ params }) => changeAccount(params.accountId, { status: 'ACTIVE' })),
  http.post('*/api/console/accounts/:accountId/password-reset', () => {
    const denied = requireAdmin();
    if (denied) return denied;
    const result: PasswordResetLink = { resetLink: state.status.mail.enabled ? null : RESET_LINK };
    return HttpResponse.json(result, { headers: { 'Cache-Control': 'no-store' } });
  }),
  http.post('*/api/console/invitations/:accountId/resend', ({ params }) => {
    const denied = requireAdmin();
    if (denied) return denied;
    setInvitation(params.accountId, false);
    const result: InvitationResult = { accountId: Number(params.accountId), inviteLink: state.status.mail.enabled ? null : INVITE_LINK };
    return HttpResponse.json(result, { headers: { 'Cache-Control': 'no-store' } });
  }),
  http.post('*/api/console/invitations/:accountId/cancel', ({ params }) => {
    const denied = requireAdmin();
    if (denied) return denied;
    setInvitation(params.accountId, true);
    return new HttpResponse(null, { status: 204 });
  }),

  http.patch('*/api/console/companies/:code', async ({ request, params }) => {
    const body = (await request.json()) as Schemas['UpdateCompanyRequest'];
    return changeCompany(params.code, {
      ...(body.name === undefined ? {} : { name: body.name }),
      ...(body.legalName === undefined ? {} : { legalName: body.legalName }),
    });
  }),
  http.put('*/api/console/companies/:code/status', async ({ request, params }) => {
    const body = (await request.json()) as Schemas['ChangeCompanyStatusRequest'];
    return changeCompany(params.code, { status: body.status });
  }),
  http.put('*/api/console/companies/:code/customers/:accountId', ({ params }) => {
    const denied = requireAdmin();
    if (denied) return denied;
    const id = Number(params.accountId);
    const known = state.accounts.find((a) => a.id === id) ?? account({ id });
    const added = member({ accountId: id, name: known.name, email: known.email, status: known.status, photoUpdatedAt: known.photoUpdatedAt });
    if (!state.customers.some((m) => m.accountId === id)) state.customers = [...state.customers, added];
    return HttpResponse.json(added);
  }),
  http.delete('*/api/console/companies/:code/customers/:accountId', ({ params }) => {
    const denied = requireAdmin();
    if (denied) return denied;
    state.customers = state.customers.filter((m) => String(m.accountId) !== params.accountId);
    return new HttpResponse(null, { status: 200 });
  }),
  http.delete('*/api/console/companies/:code/agents/:accountId', ({ params }) => {
    const denied = requireAdmin();
    if (denied) return denied;
    state.agents = state.agents.filter((m) => String(m.accountId) !== params.accountId);
    return new HttpResponse(null, { status: 200 });
  }),
  // Slice 3: the 배정 빼기 and 정지 dialogs ask for the open issues the account is the assignee of. None here.
  http.get('*/api/console/companies/:code/agents/:accountId/open-assignments', () => requireAdmin() ?? HttpResponse.json({ issues: 0 })),
  http.get('*/api/console/accounts/:accountId/open-assignments', () => requireAdmin() ?? HttpResponse.json({ issues: 0 })),
];

beforeEach(() => {
  server.use(...handlers1a);
});

afterEach(() => {
  // The CSRF cookie a test set (`setCsrfCookie`) must not reach the next test.
  document.cookie = 'XSRF-TOKEN=; expires=Thu, 01 Jan 1970 00:00:00 GMT; path=/';
});

/** Replaces one endpoint for this test. Like `on` of console.ts, with PATCH. */
export function route(
  method: 'get' | 'post' | 'put' | 'patch' | 'delete',
  path: string,
  resolver: Parameters<typeof http.get>[1],
) {
  server.use(http[method](`*${path}`, resolver));
}

/** Signs in as `me` with more fields set, e.g. a photo. */
export function signInAs(me: Me, over: Partial<Me> = {}) {
  state.me = { ...me, ...over };
}

/** What `Set-Cookie: XSRF-TOKEN=…` from the server does in a browser. */
export function setCsrfCookie(token: string) {
  document.cookie = `XSRF-TOKEN=${token}; path=/`;
}

// ---------------------------------------------------------------------------
// Late answers (S1A-AC-58)

/** Resolves when the fake server has answered `method path`. */
export function answered(method: string, path: string): Promise<void> {
  return new Promise((resolve) => {
    const listener = ({ request }: { request: Request }) => {
      if (request.method === method && new URL(request.url).pathname === path) {
        server.events.removeListener('response:mocked', listener);
        resolve();
      }
    };
    server.events.on('response:mocked', listener);
  });
}

/**
 * Lets an answer already released reach the screen code: two more full round
 * trips, inside `act` so React draws what the answer changed before the test looks.
 */
export async function settle() {
  await act(async () => {
    for (let i = 0; i < 2; i += 1) {
      await fetch(new URL('/api/public/csrf', window.location.href));
    }
  });
}

/** Every place a link could still sit: input values and the page markup. */
export function leftOnPage(secret: string): boolean {
  const values = Array.from(document.querySelectorAll<HTMLInputElement | HTMLTextAreaElement>('input, textarea')).map((el) => el.value);
  return values.some((v) => v.includes(secret)) || document.body.innerHTML.includes(secret);
}

// ---------------------------------------------------------------------------
// Photos (S1A-AC-60). The screen makes the address: /api/console/photos/{accountId}?v=<photoUpdatedAt>.

export interface PhotoRequest {
  path: string;
  v: string | null;
}

/** The profile photos drawn inside `root`, as the path and the `v` query of each. */
export function photosIn(root: Element): PhotoRequest[] {
  return Array.from(root.querySelectorAll('img'))
    .map((img) => new URL(img.getAttribute('src') ?? '', window.location.href))
    .filter((url) => url.pathname.startsWith('/api/console/photos/'))
    .map((url) => ({ path: url.pathname, v: url.searchParams.get('v') }));
}

export function photoOf(accountId: number, updatedAt: string): PhotoRequest {
  return { path: `/api/console/photos/${String(accountId)}`, v: updatedAt };
}

// ---------------------------------------------------------------------------
// Finding things

export async function region(name: string | RegExp): Promise<HTMLElement> {
  return found(() => screen.queryByRole('region', { name }), `구역 "${String(name)}"`);
}

/** The table row that holds `name` in one of its cells. */
export async function rowOf(name: string, root?: HTMLElement): Promise<HTMLElement> {
  const scope = root ? within(root) : screen;
  return found(
    () => scope.queryAllByRole('row').find((r) => within(r).queryAllByRole('cell').some((c) => text(c).includes(name))),
    `${name} 줄`,
  );
}

type User = { click: (el: Element) => Promise<void> };

/**
 * The names of a row's actions (screens.md "줄 메뉴"). They may sit in the row as
 * buttons or behind one menu button (`aria-haspopup`); a menu is opened to read
 * its items and left open.
 */
export async function rowActions(user: User, row: HTMLElement): Promise<string[]> {
  const buttons = within(row).queryAllByRole('button');
  const trigger = buttons.find((b) => b.hasAttribute('aria-haspopup'));
  const inRow = buttons.filter((b) => b !== trigger).map((b) => text(b));
  if (!trigger) return inRow.sort();
  await user.click(trigger);
  const items = await found(() => screen.queryByRole('menu'), '줄 메뉴').then((menu) => within(menu).queryAllByRole('menuitem'));
  return [...inRow, ...items.map((i) => text(i))].sort();
}

/** One action of a row, by name: a button in the row, or an item of the row's menu. */
export async function rowAction(user: User, row: HTMLElement, name: string): Promise<HTMLElement> {
  const direct = within(row).queryByRole('button', { name });
  if (direct) return direct;
  const trigger = within(row)
    .queryAllByRole('button')
    .find((b) => b.hasAttribute('aria-haspopup'));
  expect(trigger ?? null, `줄의 "${name}" 버튼 또는 줄 메뉴`).not.toBeNull();
  if (trigger && trigger.getAttribute('aria-expanded') !== 'true') await user.click(trigger);
  return found(() => screen.queryByRole('menuitem', { name }), `줄 메뉴의 "${name}"`);
}

/** Opens a row action and returns the dialog it shows. */
export async function openRowAction(user: User, rowName: string, action: string): Promise<HTMLElement> {
  await user.click(await rowAction(user, await rowOf(rowName), action));
  return found(() => screen.queryByRole('dialog') ?? screen.queryByRole('alertdialog'), `"${action}" 대화상자`);
}

/** The button of a dialog that goes on: not 취소, not 닫기. */
export function confirmButton(box: HTMLElement): HTMLElement {
  const go = within(box)
    .queryAllByRole('button')
    .find((b) => !/^(취소|닫기)$/.test(b.getAttribute('aria-label') ?? text(b)));
  expect(go ?? null, '대화상자의 확인 버튼').not.toBeNull();
  return go as HTMLElement;
}

/** Waits until no dialog is open. */
export async function closed() {
  await eventually(() => {
    expect(openDialog(), '대화상자가 닫힘').toBeNull();
  });
}
