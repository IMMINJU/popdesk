// Test support for the console screen tests (design/slices/0-first/screens.md 2·4절).
//
// - The API is answered at the network level by MSW, in the shape of
//   docs/integrate/openapi-console.json. Bodies are typed with the generated types.
// - Screens are found by role and accessible name, and by the Korean text in screens.md.
// - Every check goes through `expect`, so a missing screen fails as an assertion.
// - The portal has its own copy of these helpers (web/portal/src/__tests__); the
//   two apps do not import each other's test code.
import type { ConsoleComponents } from '@popdesk/api/console';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import axe from 'axe-core';
import { createElement } from 'react';
import { http, HttpResponse } from 'msw/http';
import { afterEach, beforeEach, expect, vi } from 'vitest';
// Before the app: the fake network must be in place when src/api.ts creates its client.
import { server } from './network';
import { App } from '../App';

type Schemas = ConsoleComponents['schemas'];
export type Issue = Schemas['Issue'];
export type IssueSummary = Schemas['IssueSummary'];
export type Comment = Schemas['Comment'];
export type Attachment = Schemas['Attachment'];
export type Company = Schemas['Company'];
export type CompanyMember = Schemas['CompanyMember'];
export type Account = Schemas['Account'];
export type PendingInvitation = Schemas['PendingInvitation'];
export type InvitationResult = Schemas['InvitationResult'];
export type SystemStatus = Schemas['SystemStatus'];
export type ChannelStatus = Schemas['ChannelStatus'];
export type FailedDelivery = Schemas['FailedDelivery'];
export type Person = Schemas['Person'];
export type Me = Schemas['Me'];
export type ApiError = Schemas['ApiError'];
export type FieldError = Schemas['FieldError'];

// ---------------------------------------------------------------------------
// Fixtures

export const T0 = '2026-10-08T03:15:00Z';

export const ADMIN: Me = { id: 1, email: 'admin@popdesk.test', name: '최관리', role: 'ADMIN', locale: 'ko', photoUpdatedAt: null, companies: [] };
export const AGENT: Me = { id: 2, email: 'lee@popdesk.test', name: '이상담', role: 'AGENT', locale: 'ko', photoUpdatedAt: null, companies: [] };
export const ADMIN_PERSON: Person = { accountId: 1, name: '최관리', role: 'ADMIN', photoUpdatedAt: null };
export const AGENT_PERSON: Person = { accountId: 2, name: '이상담', role: 'AGENT', photoUpdatedAt: null };
export const OTHER_AGENT_PERSON: Person = { accountId: 3, name: '정상담', role: 'AGENT', photoUpdatedAt: null };
export const CUSTOMER_PERSON: Person = { accountId: 11, name: '김고객', role: 'CUSTOMER', photoUpdatedAt: null };

export function company(over: Partial<Company> = {}): Company {
  return { code: 'ACME', name: '에이크미', legalName: '에이크미 주식회사', status: 'ACTIVE', createdAt: T0, ...over };
}

export const ACME = company();
export const BETA = company({ code: 'BETA', name: '베타상사', legalName: '베타상사 유한회사' });

export function issue(over: Partial<Issue> = {}): Issue {
  return {
    key: 'ACME-7',
    title: '로그인이 안 됩니다',
    content: '어제부터 **로그인**이 안 됩니다.',
    status: 'RECEIVED',
    priority: 'NORMAL',
    private: false,
    reporter: CUSTOMER_PERSON,
    companyCode: ACME.code,
    companyName: ACME.name,
    channel: 'PORTAL',
    firstResponseAt: null,
    createdAt: T0,
    updatedAt: T0,
    version: 1,
    ...over,
  };
}

export function summary(over: Partial<IssueSummary> = {}): IssueSummary {
  const { key, title, status, priority, private: isPrivate, reporter, companyCode, companyName, createdAt } = issue();
  return { key, title, status, priority, private: isPrivate, reporter, companyCode, companyName, createdAt, ...over };
}

export function comment(over: Partial<Comment> = {}): Comment {
  return { id: 101, author: CUSTOMER_PERSON, content: '확인 부탁드립니다.', createdAt: T0, internal: false, ...over };
}

export function attachment(over: Partial<Attachment> = {}): Attachment {
  return {
    id: '7f1c2a9e-0c4b-4d8e-9a51-3b2f6c1d0e01',
    commentId: null,
    contentType: 'text/plain',
    createdAt: T0,
    deleted: false,
    deletedAt: null,
    deletedBy: null,
    fileName: 'log.txt',
    internal: false,
    size: 2048,
    uploadedBy: CUSTOMER_PERSON,
    ...over,
  };
}

export function account(over: Partial<Account> = {}): Account {
  return { id: 1, email: ADMIN.email, name: ADMIN.name, role: 'ADMIN', status: 'ACTIVE', createdAt: T0, photoUpdatedAt: null, ...over };
}

export function member(over: Partial<CompanyMember> = {}): CompanyMember {
  return { accountId: 11, email: 'kim@acme.test', name: '김고객', status: 'ACTIVE', photoUpdatedAt: null, ...over };
}

export function invitation(over: Partial<PendingInvitation> = {}): PendingInvitation {
  return {
    accountId: 21,
    email: 'park@acme.test',
    name: '박초대',
    role: 'CUSTOMER',
    companies: [{ code: ACME.code, name: ACME.name }],
    invitedAt: T0,
    expiresAt: '2026-10-15T03:15:00Z',
    expired: false,
    cancelled: false,
    ...over,
  };
}

export function channel(over: Partial<ChannelStatus> = {}): ChannelStatus {
  return { enabled: true, failed: 0, queued: 0, lastSuccessAt: T0, ...over };
}

export function failedDelivery(over: Partial<FailedDelivery> = {}): FailedDelivery {
  return {
    id: 501,
    channel: 'MAIL',
    createdAt: T0,
    lastErrorKind: 'CONNECTION_REFUSED',
    recipientAccountId: 11,
    ...over,
  };
}

/** An invitation link as the server makes it: the token only in the fragment. */
export const INVITE_LINK = 'http://portal.localhost/invite#token=inv-Zq81mWc4Lp0a';

export function paged<T>(items: T[], over: { page?: number; size?: number; totalElements?: number; totalPages?: number } = {}) {
  const size = over.size ?? 20;
  const totalElements = over.totalElements ?? items.length;
  return {
    items,
    page: over.page ?? 1,
    size,
    totalElements,
    totalPages: over.totalPages ?? Math.ceil(totalElements / size),
  };
}

export function apiError(status: number, body: ApiError, headers: Record<string, string> = {}) {
  return HttpResponse.json(body, { status, headers });
}

export const errors = {
  unauthenticated: () => apiError(401, { code: 'UNAUTHENTICATED', message: 'Sign in first.' }),
  signInFailed: () => apiError(401, { code: 'SIGN_IN_FAILED', message: 'Sign-in failed.' }),
  forbidden: () => apiError(403, { code: 'FORBIDDEN', message: 'Not allowed.' }),
  notFound: () => apiError(404, { code: 'NOT_FOUND', message: 'Not found.' }),
  rateLimited: (seconds: number) =>
    apiError(429, { code: 'RATE_LIMITED', message: 'Too many requests.' }, { 'Retry-After': String(seconds) }),
  invalid: (fields: FieldError[], message = 'Some fields are invalid.') =>
    apiError(400, { code: 'INVALID_INPUT', message, fields }),
  internal: () => apiError(500, { code: 'INTERNAL_ERROR', message: 'Unexpected error.' }),
  companyCodeTaken: () => apiError(409, { code: 'COMPANY_CODE_TAKEN', message: 'Code taken.' }),
  emailTaken: () => apiError(409, { code: 'EMAIL_TAKEN', message: 'Email taken.' }),
  issueClosed: () => apiError(400, { code: 'ISSUE_CLOSED', message: 'This issue is closed.' }),
  payloadTooLarge: () => apiError(413, { code: 'PAYLOAD_TOO_LARGE', message: 'Too large.' }),
};

/** A file whose `size` says what the test needs without allocating it. Its bytes are its own name. */
export function file(name: string, size = 1024, type = 'text/plain'): File {
  const f = new File([name], name, { type });
  Object.defineProperty(f, 'size', { value: size });
  return f;
}

export const MB = 1024 * 1024;

// ---------------------------------------------------------------------------
// Fake server

export interface Call {
  method: string;
  path: string;
  url: string;
  headers: Headers;
  /** Parsed body: JSON, multipart parts, text, or undefined. */
  body: Promise<unknown>;
}

/** Every request the screens sent in this test, in order. */
export const calls: Call[] = [];

/** State the default handlers read. Tests change it before rendering. */
export const state: {
  signedIn: boolean;
  me: Me;
  companies: Company[];
  issues: IssueSummary[];
  issue: Issue;
  comments: Comment[];
  attachments: Attachment[];
  accounts: Account[];
  invitations: PendingInvitation[];
  customers: CompanyMember[];
  agents: CompanyMember[];
  status: SystemStatus;
  failed: FailedDelivery[];
} = {
  signedIn: true,
  me: ADMIN,
  companies: [ACME],
  issues: [summary()],
  issue: issue(),
  comments: [],
  attachments: [],
  accounts: [account()],
  invitations: [],
  customers: [],
  agents: [],
  status: { mail: channel(), webhook: channel() },
  failed: [],
};

function resetState() {
  state.signedIn = true;
  state.me = ADMIN;
  state.companies = [ACME];
  state.issues = [summary()];
  state.issue = issue();
  state.comments = [];
  state.attachments = [];
  state.accounts = [account()];
  state.invitations = [];
  state.customers = [];
  state.agents = [];
  state.status = { mail: channel(), webhook: channel() };
  state.failed = [];
}

/** SMTP is configured when the mail channel is enabled (the admin settings summary, screens.md 7절). */
export function setSmtp(configured: boolean) {
  state.status = { ...state.status, mail: channel({ enabled: configured, lastSuccessAt: configured ? T0 : null }) };
}

/** One part of a multipart body. */
export interface Part {
  name: string;
  filename: string | null;
  type: string | null;
  text: string;
}

/** Reads a multipart body by hand (see the portal copy for why). */
export async function readParts(request: Request): Promise<Part[]> {
  const header = request.headers.get('content-type') ?? '';
  const match = /boundary=(?:"([^"]+)"|([^;\s]+))/.exec(header);
  const boundary = match?.[1] ?? match?.[2];
  if (!boundary) return [];
  const raw = await request.text();
  return raw
    .split(`--${boundary}`)
    .slice(1, -1)
    .map((chunk) => {
      const body = chunk.replace(/^\r\n/, '').replace(/\r\n$/, '');
      const gap = body.indexOf('\r\n\r\n');
      const head = gap < 0 ? body : body.slice(0, gap);
      const disposition = /content-disposition:[^\r\n]*/i.exec(head)?.[0] ?? '';
      return {
        name: /;\s*name="([^"]*)"/.exec(disposition)?.[1] ?? '',
        filename: /;\s*filename="([^"]*)"/.exec(disposition)?.[1] ?? null,
        type: /content-type:\s*([^\r\n]+)/i.exec(head)?.[1] ?? null,
        text: gap < 0 ? '' : body.slice(gap + 4),
      };
    });
}

/** The name of an uploaded file. The test files hold their own name as content, in case a name is lost on the way. */
export function partFileName(part: Part | undefined): string {
  if (!part) return '';
  return part.filename && part.filename !== 'blob' ? part.filename : part.text;
}

async function readBody(request: Request): Promise<unknown> {
  const type = request.headers.get('content-type') ?? '';
  if (request.method === 'GET' || request.method === 'HEAD') return undefined;
  if (type.includes('application/json')) return (await request.json()) as unknown;
  if (type.includes('multipart/form-data')) return await readParts(request);
  const text = await request.text();
  return text === '' ? undefined : text;
}

function requireSession(): Response | undefined {
  return state.signedIn ? undefined : errors.unauthenticated();
}

/** 401 without a session, 403 for an agent (admin-only endpoints, S0-AC-30·81). */
function requireAdmin(): Response | undefined {
  return requireSession() ?? (state.me.role === 'ADMIN' ? undefined : errors.forbidden());
}

function unknownIssue(key: string | readonly string[] | undefined): Response | undefined {
  return key === state.issue.key ? undefined : errors.notFound();
}

function unknownCompany(code: string | readonly string[] | undefined): Response | undefined {
  return state.companies.some((c) => c.code === code) ? undefined : errors.notFound();
}

/** What the server does with a good upload: stores it and answers 201 with the attachment. */
export async function acceptUpload(request: Request): Promise<Response> {
  const parts = await readParts(request);
  const part = parts.find((p) => p.name === 'file');
  const commentId = parts.find((p) => p.name === 'commentId')?.text;
  const created = attachment({
    id: crypto.randomUUID(),
    fileName: partFileName(part),
    size: part?.text.length ?? 1,
    uploadedBy: { accountId: state.me.id, name: state.me.name, role: state.me.role, photoUpdatedAt: null },
    commentId: commentId ? Number(commentId) : null,
  });
  state.attachments = [...state.attachments, created];
  return HttpResponse.json(created, { status: 201 });
}

const defaultHandlers = [
  http.get('*/api/public/csrf', () => new HttpResponse(null, { status: 204 })),
  http.post('*/api/public/sign-in', () => {
    state.signedIn = true;
    return new HttpResponse(null, { status: 204 });
  }),
  http.post('*/api/public/sign-out', () => {
    state.signedIn = false;
    return new HttpResponse(null, { status: 204 });
  }),
  http.post('*/api/public/invitations/accept', () => {
    state.signedIn = true;
    return new HttpResponse(null, { status: 204 });
  }),
  http.get('*/api/console/me', () => requireSession() ?? HttpResponse.json(state.me)),
  http.get('*/api/console/notifications/unread-count', () => requireSession() ?? HttpResponse.json({ count: 0 })),
  http.get('*/api/console/notifications', () => requireSession() ?? HttpResponse.json(paged([]))),

  http.get('*/api/console/issues', () => requireSession() ?? HttpResponse.json(paged(state.issues))),
  http.get('*/api/console/issues/:key', ({ params }) => {
    const denied = requireSession();
    if (denied) return denied;
    return params.key === state.issue.key ? HttpResponse.json(state.issue) : errors.notFound();
  }),
  http.get('*/api/console/issues/:key/comments', ({ params }) => {
    const denied = requireSession() ?? unknownIssue(params.key);
    return denied ?? HttpResponse.json(paged(state.comments));
  }),
  http.post('*/api/console/issues/:key/comments', async ({ request, params }) => {
    const denied = requireSession() ?? unknownIssue(params.key);
    if (denied) return denied;
    const body = (await request.json()) as { content: string; internal?: boolean };
    const created = comment({
      id: 900 + state.comments.length,
      author: { accountId: state.me.id, name: state.me.name, role: state.me.role, photoUpdatedAt: null },
      content: body.content,
      internal: body.internal ?? false,
      createdAt: '2026-10-08T05:00:00Z',
    });
    state.comments = [...state.comments, created];
    return HttpResponse.json(created, { status: 201 });
  }),
  http.get('*/api/console/issues/:key/attachments', ({ params }) => {
    const denied = requireSession() ?? unknownIssue(params.key);
    return denied ?? HttpResponse.json(paged(state.attachments));
  }),
  http.post('*/api/console/issues/:key/attachments', async ({ request, params }) => {
    const denied = requireSession() ?? unknownIssue(params.key);
    return denied ?? (await acceptUpload(request));
  }),
  http.head('*/api/console/issues/:key/attachments/:id', ({ params }) => {
    const denied = requireSession() ?? unknownIssue(params.key);
    if (denied) return denied;
    const found = state.attachments.some((a) => a.id === params.id && !a.deleted);
    return new HttpResponse(null, { status: found ? 200 : 404 });
  }),
  http.delete('*/api/console/issues/:key/attachments/:id', ({ params }) => {
    const denied = requireSession() ?? unknownIssue(params.key);
    if (denied) return denied;
    const by: Person = { accountId: state.me.id, name: state.me.name, role: state.me.role, photoUpdatedAt: null };
    state.attachments = state.attachments.map((a) =>
      a.id === params.id ? { ...a, deleted: true, fileName: null, deletedAt: T0, deletedBy: by } : a,
    );
    return new HttpResponse(null, { status: 204 });
  }),

  http.get('*/api/console/companies', () => requireSession() ?? HttpResponse.json(paged(state.companies))),
  http.post('*/api/console/companies', async ({ request }) => {
    const denied = requireAdmin();
    if (denied) return denied;
    const body = (await request.json()) as { code: string; name: string; legalName: string };
    const created = company({ code: body.code, name: body.name, legalName: body.legalName });
    state.companies = [...state.companies, created];
    return HttpResponse.json(created, { status: 201 });
  }),
  http.get('*/api/console/companies/:code', ({ params }) => {
    const denied = requireSession() ?? unknownCompany(params.code);
    return denied ?? HttpResponse.json(state.companies.find((c) => c.code === params.code));
  }),
  http.get('*/api/console/companies/:code/customers', ({ params }) => {
    const denied = requireSession() ?? unknownCompany(params.code) ?? requireAdmin();
    return denied ?? HttpResponse.json(paged(state.customers));
  }),
  http.get('*/api/console/companies/:code/agents', ({ params }) => {
    const denied = requireSession() ?? unknownCompany(params.code) ?? requireAdmin();
    return denied ?? HttpResponse.json(paged(state.agents));
  }),
  http.put('*/api/console/companies/:code/agents/:accountId', ({ params }) => {
    const denied = requireSession() ?? unknownCompany(params.code) ?? requireAdmin();
    if (denied) return denied;
    const id = Number(params.accountId);
    const known = state.accounts.find((a) => a.id === id);
    const assigned = member({ accountId: id, name: known?.name ?? '상담원', email: known?.email ?? 'x@popdesk.test', status: known?.status ?? 'ACTIVE' });
    if (!state.agents.some((a) => a.accountId === id)) state.agents = [...state.agents, assigned];
    return HttpResponse.json(assigned);
  }),

  http.get('*/api/console/accounts', ({ request }) => {
    const denied = requireAdmin();
    if (denied) return denied;
    const role = new URL(request.url).searchParams.get('role');
    return HttpResponse.json(paged(role ? state.accounts.filter((a) => a.role === role) : state.accounts));
  }),
  http.get('*/api/console/invitations', () => requireAdmin() ?? HttpResponse.json(paged(state.invitations))),
  http.post('*/api/console/invitations', async ({ request }) => {
    const denied = requireAdmin();
    if (denied) return denied;
    const body = (await request.json()) as { email: string; name: string; role: Account['role'] };
    const id = 100 + state.accounts.length;
    state.accounts = [...state.accounts, account({ id, email: body.email, name: body.name, role: body.role, status: 'INVITED' })];
    const result: InvitationResult = { accountId: id, inviteLink: state.status.mail.enabled ? null : INVITE_LINK };
    return HttpResponse.json(result, { status: 201, headers: { 'Cache-Control': 'no-store' } });
  }),
  http.post('*/api/console/invitations/:accountId/link', () => {
    const denied = requireAdmin();
    if (denied) return denied;
    if (state.status.mail.enabled) return errors.notFound();
    return HttpResponse.json({ inviteLink: INVITE_LINK }, { headers: { 'Cache-Control': 'no-store' } });
  }),

  http.get('*/api/console/system/status', () => requireAdmin() ?? HttpResponse.json(state.status)),
  http.get('*/api/console/system/failed-deliveries', () => requireAdmin() ?? HttpResponse.json(paged(state.failed))),
];

server.resetHandlers(...defaultHandlers);

server.events.on('request:start', ({ request }) => {
  const url = new URL(request.url);
  // Read the copy only when a test asks. Reading it while the handler reads the
  // original stalls a streamed multipart body.
  const copy = request.clone();
  let body: Promise<unknown> | undefined;
  calls.push({
    method: request.method,
    path: url.pathname,
    url: request.url,
    headers: request.headers,
    get body() {
      body ??= readBody(copy);
      return body;
    },
  });
});

/** Replaces one endpoint for this test. */
export function on(
  method: 'get' | 'post' | 'put' | 'delete' | 'head',
  path: string,
  resolver: Parameters<typeof http.get>[1],
) {
  server.use(http[method](`*${path}`, resolver));
}

/** A response the test releases when it wants, to see the saving state. */
export function gate() {
  let open!: () => void;
  const opened = new Promise<void>((resolve) => {
    open = resolve;
  });
  return { open, opened };
}

beforeEach(() => {
  resetState();
  calls.length = 0;
  document.documentElement.lang = 'ko';
  Object.defineProperty(window.navigator, 'language', { value: 'ko', configurable: true });
  Object.defineProperty(window.navigator, 'languages', { value: ['ko'], configurable: true });
  document.title = '';
});

afterEach(() => {
  server.resetHandlers(...defaultHandlers);
  vi.useRealTimers();
  window.history.replaceState(null, '', '/');
});

// ---------------------------------------------------------------------------
// Rendering

/** Renders the whole console app at `path` (path, query and `#` fragment). */
export function renderAt(path: string, options: { fakeTimers?: boolean } = {}) {
  window.history.replaceState(null, '', path);
  const user = options.fakeTimers
    ? userEvent.setup({ advanceTimers: (ms) => vi.advanceTimersByTime(ms) })
    : userEvent.setup();
  const view = render(createElement(App));
  return { user, ...view };
}

/**
 * Fake timers that still move with real time, so Testing Library's waits keep
 * working, while the test can jump ahead with `vi.advanceTimersByTime`.
 */
export function useFakeClock() {
  vi.useFakeTimers({ shouldAdvanceTime: true, now: new Date('2026-10-08T03:20:00Z') });
}

// ---------------------------------------------------------------------------
// Finding things. Each one waits and fails through `expect`.

/** Waits until `query` returns an element and returns it. */
export async function found<T extends Element>(query: () => T | null | undefined, what: string): Promise<T> {
  let el: T | null | undefined;
  await waitFor(() => {
    el = query();
    expect(el ?? null, what).not.toBeNull();
  });
  return el as T;
}

/** Waits until `check` stops throwing. */
export async function eventually(check: () => void) {
  await waitFor(check);
}

export function text(el: Element | null | undefined): string {
  return (el?.textContent ?? '').replace(/\s+/g, ' ').trim();
}

/** Text a screen reader would read: leaves out `aria-hidden` subtrees. */
export function spokenText(el: Element): string {
  const parts: string[] = [];
  const walk = (node: Node) => {
    if (node instanceof Element && node.getAttribute('aria-hidden') === 'true') return;
    if (node.nodeType === Node.TEXT_NODE) parts.push(node.textContent ?? '');
    node.childNodes.forEach(walk);
  };
  walk(el);
  return parts.join('').replace(/\s+/g, ' ').trim();
}

export function pageText(): string {
  return text(document.body);
}

/** All live regions: `role="alert"`, `role="status"`, and `aria-live` other than off. */
export function liveRegions(): Element[] {
  return Array.from(document.querySelectorAll('[role="alert"], [role="status"], [aria-live]')).filter(
    (el) => el.getAttribute('aria-live') !== 'off',
  );
}

export function matchText(haystack: string, wanted: string | RegExp): boolean {
  return typeof wanted === 'string' ? haystack.includes(wanted) : wanted.test(haystack);
}

/** Waits for a `role="alert"` band holding `wanted`. */
export async function alertWith(wanted: string | RegExp): Promise<HTMLElement> {
  return found(
    () => screen.queryAllByRole('alert').find((el) => matchText(text(el), wanted)),
    `role="alert" 띠: ${String(wanted)}`,
  );
}

/** Waits for a `role="status"` region holding `wanted`. */
export async function statusWith(wanted: string | RegExp): Promise<HTMLElement> {
  return found(
    () => screen.queryAllByRole('status').find((el) => matchText(text(el), wanted)),
    `role="status" 안내: ${String(wanted)}`,
  );
}

export async function heading1(): Promise<HTMLElement> {
  return found(() => screen.queryByRole('heading', { level: 1 }), '화면 제목 h1');
}

/** A field by its label (works for inputs without a role, such as password and file). */
export async function field<T extends HTMLElement = HTMLInputElement>(label: string | RegExp, selector = '*'): Promise<T> {
  return found(() => screen.queryAllByLabelText<T>(label, { selector })[0], `칸 "${String(label)}"`);
}

export async function button(name: string | RegExp): Promise<HTMLElement> {
  return found(() => screen.queryByRole('button', { name }), `버튼 "${String(name)}"`);
}

export async function link(name: string | RegExp): Promise<HTMLAnchorElement> {
  return found(() => screen.queryByRole<HTMLAnchorElement>('link', { name }), `링크 "${String(name)}"`);
}

/** A control that may be drawn as a link or a button, e.g. "고객사 만들기". */
export async function action(name: string | RegExp): Promise<HTMLElement> {
  return found(
    () => screen.queryByRole('link', { name }) ?? screen.queryByRole('button', { name }),
    `링크 또는 버튼 "${String(name)}"`,
  );
}

export function queryAction(name: string | RegExp): HTMLElement | null {
  return screen.queryByRole('link', { name }) ?? screen.queryByRole('button', { name });
}

export async function dialog(): Promise<HTMLElement> {
  return found(() => screen.queryByRole('dialog') ?? screen.queryByRole('alertdialog'), '대화상자');
}

export function openDialog(): HTMLElement | null {
  return screen.queryByRole('dialog') ?? screen.queryByRole('alertdialog');
}

/** Path of a link's target inside this app. */
export function hrefPath(a: Element): string {
  const href = a.getAttribute('href') ?? '';
  if (href.startsWith('#')) return href;
  return new URL(href, window.location.href).pathname;
}

export async function atPath(pathname: string) {
  await eventually(() => {
    expect(window.location.pathname).toBe(pathname);
  });
}

/** Saving state (2절 저장 중): blocked with `aria-disabled` or `disabled`. */
export function isBlocked(el: Element): boolean {
  return el.getAttribute('aria-disabled') === 'true' || (el as HTMLButtonElement).disabled;
}

export function callsTo(method: string, path: string | RegExp): Call[] {
  return calls.filter((c) => c.method === method && (typeof path === 'string' ? c.path === path : path.test(c.path)));
}

export async function sent(method: string, path: string | RegExp, count = 1): Promise<Call[]> {
  await eventually(() => {
    expect(callsTo(method, path).length, `${method} ${String(path)} 요청 수`).toBeGreaterThanOrEqual(count);
  });
  return callsTo(method, path);
}

/** Elements the Tab key reaches, in document order. */
export function tabbables(root: ParentNode = document.body): HTMLElement[] {
  const sel = 'a[href], button, input, select, textarea, [tabindex]';
  return Array.from(root.querySelectorAll<HTMLElement>(sel)).filter((el) => {
    if (el.getAttribute('tabindex') === '-1') return false;
    if ((el as HTMLButtonElement).disabled) return false;
    if (el instanceof HTMLInputElement && el.type === 'hidden') return false;
    return el.closest('[hidden], [inert]') === null;
  });
}

/** axe on the whole page. jsdom has no layout, so the contrast rule is off. */
export async function expectNoAxeViolations() {
  const result = await axe.run(document.body, { rules: { 'color-contrast': { enabled: false } } });
  const violations = result.violations.map((v) => `${v.id}: ${v.nodes.map((n) => n.target.join(' ')).join(', ')}`);
  expect(violations).toEqual([]);
}

/** Field error wiring (2절 접근성): `aria-invalid="true"` and an `aria-describedby` text. */
export function describedText(el: Element): string {
  const ids = (el.getAttribute('aria-describedby') ?? '').split(/\s+/).filter(Boolean);
  return ids.map((id) => text(document.getElementById(id))).join(' ');
}

export async function expectFieldError(el: HTMLElement, wanted?: string) {
  await eventually(() => {
    expect(el).toHaveAttribute('aria-invalid', 'true');
    expect(describedText(el), '칸 오류 문구(aria-describedby)').not.toBe('');
    if (wanted !== undefined) expect(describedText(el)).toContain(wanted);
  });
}

/** Value of every text input and textarea plus the page text: was a draft kept? */
export function draftKept(draft: string): boolean {
  const values = Array.from(document.querySelectorAll<HTMLInputElement | HTMLTextAreaElement>('textarea, input')).map(
    (el) => el.value,
  );
  return values.some((v) => v.includes(draft)) || pageText().includes(draft);
}

/** Navigation links in the console menu, by name. */
export function menuLinks(): string[] {
  return screen
    .queryAllByRole('navigation')
    .flatMap((nav) => Array.from(nav.querySelectorAll('a')))
    .map((a) => text(a));
}

export { server };
export { screen, within } from '@testing-library/react';
