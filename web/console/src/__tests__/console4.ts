// More test support for slice 4 (design/slices/4-talk/screens.md). The earlier
// support files stay as they are; this file adds the comment and inbox endpoints of
// slice 4 to the fake server, in the shape of docs/integrate/openapi-console.json,
// and the helpers the slice 4 screen tests share.
import type { ConsoleComponents } from '@popdesk/api/console';
import { http, HttpResponse } from 'msw/http';
import { beforeEach, expect } from 'vitest';
import {
  ADMIN_PERSON,
  CUSTOMER_PERSON,
  OTHER_AGENT_PERSON,
  T0,
  comment,
  errors,
  found,
  liveRegions,
  paged,
  screen,
  server,
  state,
  text,
  within,
  type Comment,
  type Person,
} from './console';
import { rejected } from './console1a';

type Schemas = ConsoleComponents['schemas'];
export type Notification = Schemas['Notification'];
export type NotificationKind = Notification['kind'];
export type UpdateCommentRequest = Schemas['UpdateCommentRequest'];
export type CreateCommentRequest = Schemas['CreateCommentRequest'];

export const NOTIFICATIONS = '/api/console/notifications';
export const UNREAD = `${NOTIFICATIONS}/unread-count`;
export const READ_ALL = `${NOTIFICATIONS}/read-all`;
export const COMMENTS_7 = '/api/console/issues/ACME-7/comments';
export const CANDIDATES_7 = '/api/console/issues/ACME-7/mention-candidates';

/** When the fake server changes a comment. */
export const EDITED_AT = '2026-10-09T01:02:03Z';
export const DELETED_AT = '2026-10-10T04:05:06Z';

// ---------------------------------------------------------------------------
// Fixtures

/** The kinds that are about a comment: the contract gives these a `commentId`. */
const COMMENT_KINDS: readonly NotificationKind[] = ['CUSTOMER_COMMENT', 'STAFF_COMMENT', 'MENTIONED'];

/** An unread item about ACME-7: 김고객 wrote a comment. An item of another kind is about no comment. */
export function notification(over: Partial<Notification> = {}): Notification {
  return {
    id: 501,
    kind: 'CUSTOMER_COMMENT',
    issue: { key: 'ACME-7', title: '로그인이 안 됩니다' },
    companyCode: 'ACME',
    commentId: COMMENT_KINDS.includes(over.kind ?? 'CUSTOMER_COMMENT') ? 12 : null,
    actor: CUSTOMER_PERSON,
    createdAt: T0,
    readAt: null,
    task: null,
    ...over,
  };
}

/** An item about no issue: the two alerts for admins and the membership request (S4-AC-40). */
export function alertItem(kind: NotificationKind, over: Partial<Notification> = {}): Notification {
  return notification({ kind, issue: null, companyCode: kind === 'MEMBERSHIP_REQUESTED' ? 'ACME' : null, actor: null, ...over });
}

/** A deleted comment as the contract gives it: no content, the trace fields set (COM-7). */
export function deletedComment(over: Partial<Comment> = {}): Comment {
  return comment({ content: '', deleted: true, deletedAt: DELETED_AT, deletedBy: ADMIN_PERSON, ...over });
}

/** `c` after `by` deleted it: the same comment, emptied, with the trace fields set. */
export function deletedFrom(c: Comment, by: Person): Comment {
  return { ...c, content: '', deleted: true, deletedAt: DELETED_AT, deletedBy: by };
}

// ---------------------------------------------------------------------------
// Fake server

/** State the slice 4 handlers read. Tests change it before rendering. */
export const s4: {
  /** The caller's inbox, newest first, as the server orders it. */
  inbox: Notification[];
  /** Who can be mentioned on the issue, by the kind of comment. The caller is taken out when the server answers. */
  candidates: { public: Person[]; internal: Person[] };
} = { inbox: [], candidates: { public: [], internal: [] } };

function resetS4() {
  s4.inbox = [];
  s4.candidates = {
    public: [ADMIN_PERSON, OTHER_AGENT_PERSON, CUSTOMER_PERSON],
    internal: [ADMIN_PERSON, OTHER_AGENT_PERSON],
  };
}

type PathValue = string | readonly string[] | undefined;

function requireSession(): Response | undefined {
  return state.signedIn ? undefined : errors.unauthenticated();
}

function unknownIssue(key: PathValue): Response | undefined {
  return requireSession() ?? (key === state.issue.key ? undefined : errors.notFound());
}

function me(): Person {
  return { accountId: state.me.id, name: state.me.name, role: state.me.role, photoUpdatedAt: state.me.photoUpdatedAt };
}

/** The mentioned accounts as people, or the 400 the server gives for one that cannot be mentioned here. */
function mentioned(ids: readonly number[], internal: boolean): Person[] | Response {
  const unique = [...new Set(ids)];
  if (unique.length > 10) return errors.invalid([{ field: 'mentions', code: 'TOO_MANY' }]);
  const allowed = candidates(internal);
  const people = unique.map((id) => allowed.find((p) => p.accountId === id));
  if (people.some((p) => p === undefined)) return errors.invalid([{ field: 'mentions', code: 'UNKNOWN_VALUE' }]);
  return people as Person[];
}

/** The candidates as the contract gives them: never the caller, in name order. */
export function candidates(internal: boolean): Person[] {
  return (internal ? s4.candidates.internal : s4.candidates.public)
    .filter((p) => p.accountId !== state.me.id)
    .sort((a, b) => a.name.localeCompare(b.name, 'ko'));
}

/** The page of the inbox a list request asks for. */
export function inboxPage(request: Request): Response {
  const query = new URL(request.url).searchParams;
  const page = Number(query.get('page') ?? '1');
  const size = Number(query.get('size') ?? '20');
  return HttpResponse.json(paged(s4.inbox.slice((page - 1) * size, page * size), { page, size, totalElements: s4.inbox.length }));
}

export function noContent(): Response {
  return new HttpResponse(null, { status: 204 });
}

/** What the slice 4 server does when nothing goes wrong. Tests replace one with `route` of console1a. */
const handlers4 = [
  http.get(`*${NOTIFICATIONS}`, ({ request }) => requireSession() ?? inboxPage(request)),
  http.get(`*${UNREAD}`, () => requireSession() ?? HttpResponse.json({ count: s4.inbox.filter((n) => n.readAt === null).length })),
  http.post(`*${READ_ALL}`, () => {
    const denied = requireSession();
    if (denied) return denied;
    s4.inbox = s4.inbox.map((n) => (n.readAt === null ? { ...n, readAt: EDITED_AT } : n));
    return noContent();
  }),
  http.post(`*${NOTIFICATIONS}/:id/read`, ({ params }) => {
    const denied = requireSession();
    if (denied) return denied;
    if (!s4.inbox.some((n) => String(n.id) === params.id)) return errors.notFound();
    s4.inbox = s4.inbox.map((n) => (String(n.id) === params.id && n.readAt === null ? { ...n, readAt: EDITED_AT } : n));
    return noContent();
  }),

  http.get('*/api/console/issues/:key/mention-candidates', ({ request, params }) => {
    const denied = unknownIssue(params.key);
    if (denied) return denied;
    const internal = new URL(request.url).searchParams.get('internal') === 'true';
    return HttpResponse.json({ items: candidates(internal) });
  }),
  // 댓글 달기 again: this one keeps the mentions (the handler of console.ts does not know them).
  http.post('*/api/console/issues/:key/comments', async ({ request, params }) => {
    const denied = unknownIssue(params.key);
    if (denied) return denied;
    const body = (await request.json()) as CreateCommentRequest;
    if (state.issue.status === 'CLOSED') return errors.issueClosed();
    const mentions = mentioned(body.mentions ?? [], body.internal ?? false);
    if (mentions instanceof Response) return mentions;
    const created = comment({
      id: 900 + state.comments.length,
      author: me(),
      content: body.content,
      internal: body.internal ?? false,
      createdAt: '2026-10-08T05:00:00Z',
      mentions,
    });
    state.comments = [...state.comments, created];
    return HttpResponse.json(created, { status: 201 });
  }),
  http.patch('*/api/console/issues/:key/comments/:id', async ({ request, params }) => {
    const denied = unknownIssue(params.key);
    if (denied) return denied;
    const before = state.comments.find((c) => String(c.id) === params.id);
    if (!before) return errors.notFound();
    if (before.author.accountId !== state.me.id) return errors.forbidden();
    // The order of the checks: scope (404), rights (403), input (400), then the state (400).
    const body = (await request.json()) as UpdateCommentRequest;
    const mentions = body.mentions === undefined ? before.mentions : mentioned(body.mentions, before.internal);
    if (mentions instanceof Response) return mentions;
    if (before.deleted) return rejected('COMMENT_DELETED');
    if (state.issue.status === 'CLOSED') return errors.issueClosed();
    const changed = body.content !== undefined && body.content !== before.content;
    const after: Comment = { ...before, content: body.content ?? before.content, mentions, editedAt: changed ? EDITED_AT : before.editedAt };
    state.comments = state.comments.map((c) => (c === before ? after : c));
    return HttpResponse.json(after);
  }),
  http.delete('*/api/console/issues/:key/comments/:id', ({ params }) => {
    const denied = unknownIssue(params.key);
    if (denied) return denied;
    const before = state.comments.find((c) => String(c.id) === params.id);
    if (!before) return errors.notFound();
    if (before.author.accountId !== state.me.id && state.me.role !== 'ADMIN') return errors.forbidden();
    if (!before.deleted) {
      const by = me();
      state.comments = state.comments.map((c) => (c === before ? { ...c, content: '', deleted: true, deletedAt: DELETED_AT, deletedBy: by } : c));
      // The comment's attachments go with it (S4-AC-14).
      state.attachments = state.attachments.map((a) =>
        a.commentId === before.id && !a.deleted ? { ...a, deleted: true, fileName: null, deletedAt: DELETED_AT, deletedBy: by } : a,
      );
    }
    return noContent();
  }),
];

beforeEach(() => {
  resetS4();
  server.use(...handlers4);
});

// ---------------------------------------------------------------------------
// Finding things

export const EDIT = '댓글 고치기';
export const DELETE = '댓글 지우기';

/** The comment `article` holding `part`: a comment's body, or "지워진 댓글" for the only deleted one. */
export function commentWith(part: string): Promise<HTMLElement> {
  return found(
    () => screen.queryAllByRole('article').find((a) => a.id.startsWith('comment-') && text(a).includes(part)),
    `댓글 "${part}"`,
  );
}

/** The comment `article` of the comment `id` (the `#comment-ID` target), or null. */
export function commentById(id: number): HTMLElement | null {
  return screen.queryAllByRole('article').find((a) => a.id === `comment-${String(id)}`) ?? null;
}

/** The names of the two comment actions drawn in `article`, in document order. */
export function commentActions(article: HTMLElement): string[] {
  return within(article)
    .queryAllByRole('button')
    .map((b) => text(b))
    .filter((name) => name === EDIT || name === DELETE);
}

/** A comment's action button, waited for. */
export function commentAction(part: string, name: typeof EDIT | typeof DELETE): Promise<HTMLElement> {
  return found(() => {
    const article = screen.queryAllByRole('article').find((a) => a.id.startsWith('comment-') && text(a).includes(part));
    return article && within(article).queryByRole('button', { name });
  }, `댓글 "${part}"의 "${name}"`);
}

/** The form that writes a new comment. */
export function writeForm(): Promise<HTMLElement> {
  return found(() => screen.queryByRole('form', { name: '댓글 쓰기' }), 'form "댓글 쓰기"');
}

/** The form that edits a comment, or null while no comment is edited. */
export function editForm(): HTMLElement | null {
  return screen.queryByRole('form', { name: EDIT });
}

export function editFormShown(): Promise<HTMLElement> {
  return found(editForm, `form "${EDIT}"`);
}

/** The editor of the edit form. */
export function editBox(): Promise<HTMLTextAreaElement> {
  return found(() => {
    const form = editForm();
    return form && within(form).queryByRole<HTMLTextAreaElement>('textbox', { name: '댓글 내용 고치기' });
  }, '편집기 "댓글 내용 고치기"');
}

/** The editor of the new comment: the multi-line text box of the form "댓글 쓰기". */
export async function writeBox(): Promise<HTMLTextAreaElement> {
  const form = await writeForm();
  return found(() => within(form).queryAllByRole<HTMLTextAreaElement>('textbox').find((el) => el.tagName === 'TEXTAREA'), '댓글 편집칸');
}

/** A button inside `scope`, waited for. */
export function buttonIn(scope: () => HTMLElement | null, name: string | RegExp): Promise<HTMLElement> {
  return found(() => {
    const root = scope();
    return root && within(root).queryByRole('button', { name });
  }, `버튼 "${String(name)}"`);
}

/** The 알릴 사람 parts of one form. */
export function people(form: () => HTMLElement | null) {
  const inForm = <T extends Element>(query: (root: HTMLElement) => T | null | undefined) => () => {
    const root = form();
    return root ? query(root) : null;
  };
  const group = inForm((root) => within(root).queryByRole('group', { name: '알릴 사람' }));
  return {
    toggle: () => found(inForm((root) => within(root).queryByRole('button', { name: '알릴 사람 고르기' })), '"알릴 사람 고르기"'),
    group,
    opened: () => found(group, 'group "알릴 사람"'),
    /** The checkbox of the person whose line starts with `name`. */
    box: (name: string) =>
      found(
        inForm((root) => within(root).queryAllByRole<HTMLInputElement>('checkbox').find((b) => boxName(b).startsWith(name))),
        `상자 "${name}"`,
      ),
    /** The lines of the boxes, e.g. "정상담 (상담원)", sorted: the order of the candidates is not fixed. */
    lines: () => {
      const g = group();
      return g ? within(g).queryAllByRole<HTMLInputElement>('checkbox').map(boxName).sort() : [];
    },
    /** The folded line beside the button: "알릴 사람 없음" or "알릴 사람: 이름들". Empty when there is none. */
    summary: () => {
      const root = form();
      if (!root) return '';
      const hits = Array.from(root.querySelectorAll<HTMLElement>('*')).filter((el) => /^알릴 사람( 없음|: .+)$/.test(text(el)));
      // The innermost one: an element around it may hold the boxes too.
      return text(hits.find((el) => !hits.some((other) => other !== el && el.contains(other))));
    },
    /** The text of the form: the notice, the state of the candidates, the limit. */
    says: () => text(form()),
  };
}

function boxName(b: HTMLInputElement): string {
  const label = b.labels?.[0] ?? null;
  return b.getAttribute('aria-label') ?? text(label);
}

/** The inbox link of the head: named "알림" or "알림 N건". */
export function inboxLink(): Promise<HTMLAnchorElement> {
  return found(() => screen.queryByRole<HTMLAnchorElement>('link', { name: /^알림( \d+건)?$/ }), '머리의 알림 링크');
}

/** The mention IDs of a request body, sorted; null when the body has no `mentions` key. */
export function mentionsOf(body: unknown): number[] | null {
  const value = (body as { mentions?: number[] }).mentions;
  return value === undefined ? null : [...value].sort((a, b) => a - b);
}

/** What the live regions say now: a failure that is to stay silent leaves this as it was (alert, status and aria-live alike). */
export function liveText(): string[] {
  return liveRegions()
    .map((el) => text(el))
    .filter((words) => words !== '');
}

/** Waits until focus is on `el`. */
export async function focused(el: () => Element | null, what: string) {
  await found(() => {
    const target = el();
    return target !== null && document.activeElement === target ? target : null;
  }, `포커스: ${what}`);
  expect(document.activeElement).toBe(el());
}
