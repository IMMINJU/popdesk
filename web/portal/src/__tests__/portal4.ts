// More test support for slice 4 (design/slices/4-talk/screens.md). The earlier
// support files stay as they are; this file adds the comment and inbox endpoints of
// slice 4 to the fake server, in the shape of docs/integrate/openapi-portal.json,
// and the helpers the slice 4 screen tests share.
import type { PortalComponents } from '@popdesk/api/portal';
import { http, HttpResponse } from 'msw/http';
import { beforeEach, expect } from 'vitest';
import { ME_PERSON, T0, comment, errors, found, paged, screen, server, state, text, within, type Comment } from './portal';
// The 1a handlers too: `/api/portal/me` answers `session.me`.
import { rejected, session } from './portal1a';

type Schemas = PortalComponents['schemas'];
export type Notification = Schemas['Notification'];
export type NotificationKind = Notification['kind'];
export type NotificationActor = Schemas['NotificationActor'];
export type UpdateCommentRequest = Schemas['UpdateCommentRequest'];

export const NOTIFICATIONS = '/api/portal/notifications';
export const UNREAD = `${NOTIFICATIONS}/unread-count`;
export const READ_ALL = `${NOTIFICATIONS}/read-all`;
export const ISSUE_7 = '/api/portal/issues/ACME-7';
export const COMMENTS_7 = `${ISSUE_7}/comments`;
export const ATTACHMENTS_7 = `${ISSUE_7}/attachments`;

/** When the fake server changes a comment. */
export const EDITED_AT = '2026-10-09T01:02:03Z';
export const DELETED_AT = '2026-10-10T04:05:06Z';

// ---------------------------------------------------------------------------
// Fixtures

/** Who caused an item, as the portal API shows the staff: no name, no ID (S4-AC-68). */
export const SUPPORT_ACTOR: NotificationActor = { accountId: null, kind: 'SUPPORT_TEAM', name: null, photoUpdatedAt: null };

/** An unread item about ACME-7: the support team answered. */
export function notification(over: Partial<Notification> = {}): Notification {
  return {
    id: 501,
    kind: 'STAFF_COMMENT',
    issue: { key: 'ACME-7', title: '로그인이 안 됩니다' },
    companyCode: 'ACME',
    commentId: null,
    actor: SUPPORT_ACTOR,
    createdAt: T0,
    readAt: null,
    ...over,
  };
}

/** A deleted comment as the contract gives it: no content, the time of the deletion, nobody named (COM-7). */
export function deletedComment(over: Partial<Comment> = {}): Comment {
  return comment({ content: '', deleted: true, deletedAt: DELETED_AT, ...over });
}

/** `c` after it was deleted: the same comment, emptied. */
export function deletedFrom(c: Comment): Comment {
  return { ...c, content: '', deleted: true, deletedAt: DELETED_AT };
}

// ---------------------------------------------------------------------------
// Fake server

/** State the slice 4 handlers read. Tests change it before rendering. */
export const s4: {
  /** The caller's inbox, newest first, as the server orders it. */
  inbox: Notification[];
} = { inbox: [] };

type PathValue = string | readonly string[] | undefined;

function requireSession(): Response | undefined {
  return state.signedIn ? undefined : errors.unauthenticated();
}

function unknownIssue(key: PathValue): Response | undefined {
  return requireSession() ?? (key === state.issue.key ? undefined : errors.notFound());
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

/** What the slice 4 server does when nothing goes wrong. Tests replace one with `route` of portal1a. */
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

  http.patch('*/api/portal/issues/:key/comments/:id', async ({ request, params }) => {
    const denied = unknownIssue(params.key);
    if (denied) return denied;
    const before = state.comments.find((c) => String(c.id) === params.id);
    if (!before) return errors.notFound();
    if (before.author.accountId !== session.me.id) return errors.forbidden();
    if (before.deleted) return rejected('COMMENT_DELETED');
    if (state.issue.status === 'CLOSED') return rejected('ISSUE_CLOSED');
    const body = (await request.json()) as UpdateCommentRequest;
    const changed = body.content !== undefined && body.content !== before.content;
    const after: Comment = { ...before, content: body.content ?? before.content, editedAt: changed ? EDITED_AT : before.editedAt };
    state.comments = state.comments.map((c) => (c === before ? after : c));
    return HttpResponse.json(after);
  }),
  http.delete('*/api/portal/issues/:key/comments/:id', ({ params }) => {
    const denied = unknownIssue(params.key);
    if (denied) return denied;
    const before = state.comments.find((c) => String(c.id) === params.id);
    if (!before) return errors.notFound();
    if (before.author.accountId !== session.me.id) return errors.forbidden();
    if (!before.deleted) {
      state.comments = state.comments.map((c) => (c === before ? deletedFrom(c) : c));
      // The comment's attachments go with it (S4-AC-14).
      state.attachments = state.attachments.map((a) =>
        a.commentId === before.id && !a.deleted ? { ...a, deleted: true, fileName: null, deletedAt: DELETED_AT, deletedBy: ME_PERSON } : a,
      );
    }
    return noContent();
  }),
];

beforeEach(() => {
  s4.inbox = [];
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

/** The inbox link of the head: named "알림" or "알림 N건". */
export function inboxLink(): Promise<HTMLAnchorElement> {
  return found(() => screen.queryByRole<HTMLAnchorElement>('link', { name: /^알림( \d+건)?$/ }), '머리의 알림 링크');
}

/** Waits until focus is on `el`. */
export async function focused(el: () => Element | null, what: string) {
  await found(() => {
    const target = el();
    return target !== null && document.activeElement === target ? target : null;
  }, `포커스: ${what}`);
  expect(document.activeElement).toBe(el());
}
