// More test support for slice 2 (design/slices/2-manager/screens.md). `portal.ts` and
// `portal1a.ts` stay as they are; this file adds the company member endpoints to the
// fake server, in the shape of docs/integrate/openapi-portal.json, and the helpers the
// P-8 screen tests share.
import type { PortalComponents } from '@popdesk/api/portal';
import { http, HttpResponse } from 'msw/http';
import { beforeEach, expect } from 'vitest';
import { ME, button, errors, eventually, found, liveRegions, matchText, paged, screen, server, state, text, within, type Company } from './portal';
// The 1a handlers too: `/api/portal/me` answers `session.me`.
import { rejected, session } from './portal1a';

type Schemas = PortalComponents['schemas'];
export type Member = Schemas['Member'];

// ---------------------------------------------------------------------------
// Fixtures

/** Companies as the signed-in customer sees them: a manager of the first two, a plain member of the third. */
export const ACME_MANAGED: Company = { code: 'ACME', name: '에이크미', status: 'ACTIVE', manager: true };
export const BETA_MANAGED: Company = { code: 'BETA', name: '베타상사', status: 'ACTIVE', manager: true };
export const GAMMA_MEMBER: Company = { code: 'GAMMA', name: '감마물산', status: 'ACTIVE', manager: false };

export function person(over: Partial<Member> = {}): Member {
  return { id: crypto.randomUUID(), name: '최동료', email: 'choi@acme.test', status: 'ACTIVE', manager: false, ...over };
}

/** The signed-in manager's own row: the same email as `ME`. */
export const MY_ROW = person({ id: '0b6f3d52-7c1e-4a55-9f0e-5d2a4c1b7e03', name: ME.name, email: ME.email, manager: true });
export const OTHER_MANAGER = person({ id: '1c7a4e63-8d2f-4b66-8a1f-6e3b5d2c8f14', name: '한담당', email: 'han@acme.test', manager: true });
export const COLLEAGUE = person({ id: '2d8b5f74-9e3a-4c77-9b2a-7f4c6e3d9a25' });
export const SUSPENDED_ROW = person({ id: '3e9c6a85-af4b-4d88-8c3b-8a5d7f4e0b36', name: '오정지', email: 'oh@acme.test', status: 'SUSPENDED' });
export const INVITED_ROW = person({ id: '4fad7b96-b05c-4e99-9d4c-9b6e8a5f1c47', name: '박초대', email: 'park@acme.test', status: 'INVITED' });
export const BETA_ROW = person({ id: '5abe8ca7-c16d-4faa-8e5d-ac7f9b6a2d58', name: '임베타', email: 'lim@beta.test' });

/** The list endpoint of one company; a row's own endpoints hang under it. */
export function membersPath(code: string): string {
  return `/api/portal/companies/${code}/members`;
}

/** `count` more colleagues, in the order of their emails. */
export function colleagues(count: number): Member[] {
  return Array.from({ length: count }, (_, i) => {
    const n = String(i + 1).padStart(2, '0');
    return person({ name: `동료 ${n}`, email: `a${n}@acme.test` });
  });
}

// ---------------------------------------------------------------------------
// Fake server

/** The rows of each company, in the order the server gives them (email ascending). Tests change it before rendering. */
export const people: { rows: Record<string, Member[]> } = { rows: {} };

function resetPeople() {
  people.rows = {
    ACME: [COLLEAGUE, OTHER_MANAGER, MY_ROW, SUSPENDED_ROW, INVITED_ROW],
    BETA: [MY_ROW, BETA_ROW],
    GAMMA: [MY_ROW],
  };
}

type PathValue = string | readonly string[] | undefined;

/** 401 without a session, 404 for a company I am not in, 403 for one I do not manage (S2-AC-11). */
function denied(code: PathValue): Response | undefined {
  if (!state.signedIn) return errors.unauthenticated();
  const mine = session.me.companies.find((c) => c.code === code);
  if (!mine) return errors.notFound();
  return mine.manager ? undefined : errors.forbidden();
}

function rowsOf(code: PathValue): Member[] {
  return people.rows[String(code)] ?? [];
}

const noContent = () => new HttpResponse(null, { status: 204 });

/** What the slice 2 server does when nothing goes wrong. Tests replace one with `route` of portal1a. */
const handlers2 = [
  http.get('*/api/portal/companies/:code/members', ({ request, params }) => {
    const no = denied(params.code);
    if (no) return no;
    const query = new URL(request.url).searchParams;
    const page = Number(query.get('page') ?? '1');
    const size = Number(query.get('size') ?? '20');
    const all = rowsOf(params.code);
    return HttpResponse.json(paged(all.slice((page - 1) * size, page * size), { page, size, totalElements: all.length }));
  }),
  http.post('*/api/portal/companies/:code/members', async ({ request, params }) => {
    const no = denied(params.code);
    if (no) return no;
    const body = (await request.json()) as Partial<Schemas['InviteMemberRequest']>;
    const blank = (['email', 'name'] as const).filter((field) => (body[field] ?? '').trim() === '');
    if (blank.length > 0) return errors.invalid(blank.map((field) => ({ field, code: 'REQUIRED' })));
    const code = String(params.code);
    if (session.me.companies.find((c) => c.code === code)?.status === 'ARCHIVED') return rejected('COMPANY_NOT_ACTIVE');
    const all = rowsOf(code);
    if (all.some((m) => m.email === body.email)) return rejected('ALREADY_LISTED', 409);
    // A new account and an account that exists already look the same: an INVITED row (S2-AC-16).
    const row = person({ name: body.name ?? '', email: body.email ?? '', status: 'INVITED' });
    people.rows = { ...people.rows, [code]: [...all, row].sort((a, b) => (a.email < b.email ? -1 : 1)) };
    return noContent();
  }),
  http.delete('*/api/portal/companies/:code/members/:id', ({ params }) => {
    const no = denied(params.code);
    if (no) return no;
    const all = rowsOf(params.code);
    const row = all.find((m) => m.id === params.id);
    if (!row) return errors.notFound();
    if (row.email === session.me.email) return rejected('OWN_ACCOUNT');
    if (row.manager) return rejected('MEMBER_IS_MANAGER');
    people.rows = { ...people.rows, [String(params.code)]: all.filter((m) => m !== row) };
    return noContent();
  }),
  http.post('*/api/portal/companies/:code/members/:id/resend', ({ params }) => {
    const no = denied(params.code);
    if (no) return no;
    const row = rowsOf(params.code).find((m) => m.id === params.id);
    if (!row) return errors.notFound();
    return row.status === 'INVITED' ? noContent() : rejected('INVITATION_NOT_RESENDABLE');
  }),
];

beforeEach(() => {
  resetPeople();
  server.use(...handlers2);
});

/** Signs in as `ME` belonging to these companies; `manager` of each says where P-8 opens. */
export function belongTo(...companies: Company[]) {
  session.me = { ...ME, companies };
  state.companies = companies;
}

// ---------------------------------------------------------------------------
// Finding things

export const COLUMNS = ['이름', '이메일', '상태', '담당자', '동작'] as const;
export type Column = (typeof COLUMNS)[number];

export function main(): HTMLElement {
  return screen.getByRole('main');
}

/** The table row that holds `wanted` (an email or a name) in one of its cells. */
export async function rowOf(wanted: string): Promise<HTMLElement> {
  return found(
    () => screen.queryAllByRole('row').find((r) => within(r).queryAllByRole('cell').some((c) => text(c).includes(wanted))),
    `${wanted} 줄`,
  );
}

/** The cell of `row` under the column head `column`. */
export function cellOf(row: HTMLElement, column: Column): Element | null {
  const head = screen.queryAllByRole('columnheader').find((h) => text(h) === column);
  if (!head?.parentElement) return null;
  return row.children[Array.from(head.parentElement.children).indexOf(head)] ?? null;
}

/** The company choice of P-8, drawn only for a manager of two or more companies. */
export async function companySelect(): Promise<HTMLSelectElement> {
  return found(() => within(main()).queryByRole<HTMLSelectElement>('combobox'), '고객사 고르기');
}

/** The companies the choice offers, as the text of each option. */
export function offered(select: HTMLSelectElement): string[] {
  return Array.from(select.options)
    .filter((o) => o.value !== '')
    .map((o) => text(o));
}

type User = { click: (el: Element) => Promise<void>; selectOptions: (el: Element, values: HTMLElement) => Promise<void> };

export async function choose(user: User, company: Company) {
  const select = await companySelect();
  const option = Array.from(select.options).find((o) => text(o).includes(company.name));
  expect(option ?? null, `고르기의 "${company.name}"`).not.toBeNull();
  if (option) await user.selectOptions(select, option);
}

/** The company in the address: `/members?company=ACME`. */
export function companyInAddress(): string | null {
  return new URLSearchParams(window.location.search).get('company');
}

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

export function openDialog(): HTMLElement | null {
  return screen.queryByRole('dialog') ?? screen.queryByRole('alertdialog');
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

/** Cancels a dialog, also while it is sending. A dialog that asks about the typed values first is told to drop them. */
export async function cancel(user: User, box: HTMLElement) {
  await user.click(within(box).getByRole('button', { name: '취소' }));
  const drop = screen.queryByRole('button', { name: '버리고 닫기' });
  if (drop) await user.click(drop);
  await closed();
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

type Typist = User & { type: (el: Element, text: string) => Promise<void> };

/** Opens the invitation dialog of P-8 for the company shown and returns it with its two fields. */
export async function openInvite(user: User, companyName: string) {
  await user.click(await button(/^초대$/));
  const box = await found(() => screen.queryByRole('dialog', { name: `${companyName}에 초대` }), `대화상자 "${companyName}에 초대"`);
  return { box, email: await emailIn(box), name: await nameIn(box) };
}

export const emailIn = (box: HTMLElement) => found(() => within(box).queryAllByLabelText<HTMLInputElement>(/^이메일/, { selector: 'input' })[0], '대화상자의 이메일 칸');
export const nameIn = (box: HTMLElement) => found(() => within(box).queryAllByLabelText<HTMLInputElement>(/^이름/, { selector: 'input' })[0], '대화상자의 이름 칸');

/** Types an email and a name into an open invitation dialog. */
export async function fillInvite(user: Typist, d: Awaited<ReturnType<typeof openInvite>>, email = 'new@acme.test', name = '신입') {
  await user.type(d.email, email);
  await user.type(d.name, name);
}

/** The whole screen in a state named by its `h1` (screens.md 4절): "권한이 없습니다" for 403, "찾을 수 없습니다" for 404. */
export async function screenState(title: string): Promise<HTMLElement> {
  return found(() => screen.queryByRole('heading', { level: 1, name: title }), `화면 제목 h1 "${title}"`);
}

/** The whole screen in the 403 state (S2-AC-45): the `h1`, no table and nothing to invite with. */
export async function forbiddenScreen() {
  await screenState('권한이 없습니다');
  expect(screen.queryAllByRole('table'), '표').toHaveLength(0);
  expect(screen.queryByRole('button', { name: /^초대$/ }), '"초대" 버튼').toBeNull();
}
