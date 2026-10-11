// Test support for the portal screen tests (design/slices/0-first/screens.md 2·3절).
//
// - The API is answered at the network level by MSW, in the shape of
//   docs/integrate/openapi-portal.json. Bodies are typed with the generated types.
// - Screens are found by role and accessible name, and by the Korean text in screens.md.
// - Every check goes through `expect`, so a missing screen fails as an assertion.
import type { PortalComponents } from '@popdesk/api/portal';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import axe from 'axe-core';
import { createElement } from 'react';
import { http, HttpResponse } from 'msw/http';
import { afterEach, beforeEach, expect, vi } from 'vitest';
// Before the app: the fake network must be in place when src/api.ts creates its client.
import { server } from './network';
import { App } from '../App';

type Schemas = PortalComponents['schemas'];
export type Issue = Schemas['Issue'];
export type IssueSummary = Schemas['IssueSummary'];
export type Comment = Schemas['Comment'];
export type Attachment = Schemas['Attachment'];
export type Company = Schemas['Company'];
export type Person = Schemas['Person'];
export type Me = Schemas['Me'];
export type ApiError = Schemas['ApiError'];
export type FieldError = Schemas['FieldError'];

// ---------------------------------------------------------------------------
// Fixtures

export const ME: Me = {
  id: 11,
  email: 'kim@acme.test',
  name: '김고객',
  role: 'CUSTOMER',
  locale: 'ko',
  photoUpdatedAt: null,
  companies: [{ code: 'ACME', name: '에이크미', status: 'ACTIVE' }],
};
export const ME_PERSON: Person = { accountId: 11, kind: 'CUSTOMER', name: '김고객', photoUpdatedAt: null };
export const COLLEAGUE: Person = { accountId: 12, kind: 'CUSTOMER', name: '박동료', photoUpdatedAt: null };
/** Staff as the portal API shows them: no name, no ID (D-49). */
export const SUPPORT_TEAM: Person = { accountId: null, kind: 'SUPPORT_TEAM', name: null, photoUpdatedAt: null };

export const ACME: Company = { code: 'ACME', name: '에이크미', status: 'ACTIVE' };
export const BETA: Company = { code: 'BETA', name: '베타상사', status: 'ACTIVE' };

export const T0 = '2026-10-08T03:15:00Z';

export function issue(over: Partial<Issue> = {}): Issue {
  return {
    key: 'ACME-7',
    title: '로그인이 안 됩니다',
    content: '어제부터 **로그인**이 안 됩니다.',
    status: 'RECEIVED',
    priority: 'NORMAL',
    private: false,
    reporter: ME_PERSON,
    companyCode: ACME.code,
    companyName: ACME.name,
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
  return { id: 101, author: ME_PERSON, content: '확인 부탁드립니다.', createdAt: T0, ...over };
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
    size: 2048,
    uploadedBy: ME_PERSON,
    ...over,
  };
}

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
  forbidden: () => apiError(403, { code: 'FORBIDDEN', message: 'Not allowed.' }),
  notFound: () => apiError(404, { code: 'NOT_FOUND', message: 'Not found.' }),
  rateLimited: (seconds: number) =>
    apiError(429, { code: 'RATE_LIMITED', message: 'Too many requests.' }, { 'Retry-After': String(seconds) }),
  invalid: (fields: FieldError[], message = 'Some fields are invalid.') =>
    apiError(400, { code: 'INVALID_INPUT', message, fields }),
  internal: () => apiError(500, { code: 'INTERNAL_ERROR', message: 'Unexpected error.' }),
  /** The app's own 413 body. */
  payloadTooLarge: () => apiError(413, { code: 'PAYLOAD_TOO_LARGE', message: 'Too large.' }),
  /** A proxy in front answers 413 first with its own HTML body (conventions.md, S0-AC-87). */
  proxyPayloadTooLarge: () =>
    new HttpResponse(
      '<html><head><title>413 Request Entity Too Large</title></head><body><center><h1>413 Request Entity Too Large</h1></center><hr><center>nginx</center></body></html>',
      { status: 413, headers: { 'Content-Type': 'text/html' } },
    ),
};

/**
 * A file whose `size` says what the test needs without allocating it. Its bytes
 * are its own name, so the fake server can tell files apart even if a name is lost.
 */
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
  /** Parsed body: JSON, `FormData`, text, or undefined. */
  body: Promise<unknown>;
}

/** Every request the screens sent in this test, in order. */
export const calls: Call[] = [];

/** State the default handlers read. Tests change it before rendering. */
export const state: {
  signedIn: boolean;
  companies: Company[];
  issues: IssueSummary[];
  issue: Issue;
  comments: Comment[];
  attachments: Attachment[];
  createdKey: string;
} = {
  signedIn: true,
  companies: [ACME],
  issues: [summary()],
  issue: issue(),
  comments: [],
  attachments: [],
  createdKey: 'ACME-8',
};

function resetState() {
  state.signedIn = true;
  state.companies = [ACME];
  state.issues = [summary()];
  state.issue = issue();
  state.comments = [];
  state.attachments = [];
  state.createdKey = 'ACME-8';
}

/** One part of a multipart body. */
export interface Part {
  name: string;
  filename: string | null;
  type: string | null;
  text: string;
}

/**
 * Reads a multipart body by hand. `Request.formData()` fails in this jsdom setup
 * for bodies sent with jsdom's XMLHttpRequest, so the fake server parses the text.
 */
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

/** What the server does with a good upload: stores it and answers 201 with the attachment. */
export async function acceptUpload(request: Request): Promise<Response> {
  const part = (await readParts(request)).find((p) => p.name === 'file');
  const created = attachment({
    id: crypto.randomUUID(),
    fileName: partFileName(part),
    size: part?.text.length ?? 1,
  });
  state.attachments = [...state.attachments, created];
  return HttpResponse.json(created, { status: 201 });
}

/** Name of the file in an upload request. */
export async function uploadName(request: Request): Promise<string> {
  return partFileName((await readParts(request.clone())).find((p) => p.name === 'file'));
}

function unknownIssue(key: string | readonly string[] | undefined): Response | undefined {
  return key === state.issue.key ? undefined : errors.notFound();
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
  http.get('*/api/portal/me', () => requireSession() ?? HttpResponse.json(ME)),
  http.get('*/api/portal/companies', () => requireSession() ?? HttpResponse.json(paged(state.companies))),
  http.get('*/api/portal/issues', () => requireSession() ?? HttpResponse.json(paged(state.issues))),
  http.post('*/api/portal/issues', () => {
    const denied = requireSession();
    if (denied) return denied;
    const created = issue({ key: state.createdKey });
    state.issue = created;
    return HttpResponse.json(created, { status: 201 });
  }),
  http.get('*/api/portal/issues/:key', ({ params }) => {
    const denied = requireSession();
    if (denied) return denied;
    return params.key === state.issue.key ? HttpResponse.json(state.issue) : errors.notFound();
  }),
  http.get('*/api/portal/issues/:key/comments', ({ params }) => {
    const denied = requireSession() ?? unknownIssue(params.key);
    return denied ?? HttpResponse.json(paged(state.comments));
  }),
  http.post('*/api/portal/issues/:key/comments', async ({ request }) => {
    const denied = requireSession();
    if (denied) return denied;
    const body = (await request.json()) as { content: string };
    const created = comment({ id: 900 + state.comments.length, content: body.content });
    state.comments = [...state.comments, created];
    return HttpResponse.json(created, { status: 201 });
  }),
  http.get('*/api/portal/issues/:key/attachments', ({ params }) => {
    const denied = requireSession() ?? unknownIssue(params.key);
    return denied ?? HttpResponse.json(paged(state.attachments));
  }),
  http.post('*/api/portal/issues/:key/attachments', async ({ request }) => requireSession() ?? (await acceptUpload(request))),
  http.head('*/api/portal/issues/:key/attachments/:id', ({ params }) => {
    const denied = requireSession() ?? unknownIssue(params.key);
    if (denied) return denied;
    const found = state.attachments.some((a) => a.id === params.id && !a.deleted);
    return new HttpResponse(null, { status: found ? 200 : 404 });
  }),
  http.delete('*/api/portal/issues/:key/attachments/:id', ({ params }) => {
    const denied = requireSession();
    if (denied) return denied;
    state.attachments = state.attachments.map((a) =>
      a.id === params.id ? { ...a, deleted: true, fileName: null, deletedAt: T0, deletedBy: ME_PERSON } : a,
    );
    return new HttpResponse(null, { status: 204 });
  }),
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
  method: 'get' | 'post' | 'delete' | 'head',
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

/** Renders the whole portal app at `path` (path, query and `#` fragment). */
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

export function matchText(haystack: string, wanted: string | RegExp): boolean {
  return typeof wanted === 'string' ? haystack.includes(wanted) : wanted.test(haystack);
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

/** A control that may be drawn as a link or a button, e.g. "문의 올리기". */
export async function action(name: string | RegExp): Promise<HTMLElement> {
  return found(
    () => screen.queryByRole('link', { name }) ?? screen.queryByRole('button', { name }),
    `링크 또는 버튼 "${String(name)}"`,
  );
}

export async function dialog(): Promise<HTMLElement> {
  return found(() => screen.queryByRole('dialog') ?? screen.queryByRole('alertdialog'), '대화상자');
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

/** The create-issue request as JSON, whether it was sent as JSON or as the `issue` part of a multipart body. */
export async function issueBody(call: Call | undefined): Promise<Record<string, unknown>> {
  expect(call, '문의 등록 요청').toBeDefined();
  const body = await call?.body;
  if (Array.isArray(body)) {
    const part = (body as Part[]).find((p) => p.name === 'issue');
    return JSON.parse(part?.text ?? '{}') as Record<string, unknown>;
  }
  return (body ?? {}) as Record<string, unknown>;
}

/** Names of the files sent to the attachment upload endpoint, in order. */
export async function uploadedNames(): Promise<string[]> {
  const names: string[] = [];
  for (const c of callsTo('POST', /\/api\/portal\/issues\/[^/]+\/attachments$/)) {
    const body = (await c.body) as Part[] | undefined;
    names.push(partFileName(body?.find((p) => p.name === 'file')));
  }
  return names;
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
  const found = result.violations.map((v) => `${v.id}: ${v.nodes.map((n) => n.target.join(' ')).join(', ')}`);
  expect(found).toEqual([]);
}

/** Field error wiring (2절 접근성): `aria-invalid="true"` and an `aria-describedby` text. */
export function describedText(el: Element): string {
  const ids = (el.getAttribute('aria-describedby') ?? '').split(/\s+/).filter(Boolean);
  return ids.map((id) => text(document.getElementById(id))).join(' ');
}

/** A field's error wiring before the error, taken just before the request that fails. */
export interface FieldState {
  invalid: string | null;
  described: string;
}

export function fieldState(el: Element): FieldState {
  return { invalid: el.getAttribute('aria-invalid'), described: describedText(el) };
}

/**
 * The field turned `aria-invalid="true"` and its `aria-describedby` text changed.
 * Comparing with `before` keeps a field whose rules or counter are already
 * described (P-2 password, MarkdownEditor) from passing without an error text.
 * The wording is not fixed.
 */
export async function expectFieldError(el: HTMLElement, before: FieldState) {
  expect(before.invalid, '오류 전 aria-invalid').not.toBe('true');
  await eventually(() => {
    expect(el).toHaveAttribute('aria-invalid', 'true');
    const after = describedText(el);
    expect(after, '칸 오류 문구(aria-describedby)').not.toBe('');
    expect(after, '오류 뒤 aria-describedby 글자가 오류 전과 같다').not.toBe(before.described);
  });
}

/** Value of every text input and textarea plus the page text: was a draft kept? */
export function draftKept(draft: string): boolean {
  const values = Array.from(document.querySelectorAll<HTMLInputElement | HTMLTextAreaElement>('textarea, input')).map(
    (el) => el.value,
  );
  return values.some((v) => v.includes(draft)) || pageText().includes(draft);
}

export { server };
export { screen, within } from '@testing-library/react';
