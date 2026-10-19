// More test support for slice 5 (design/archive/5-attachments/screens.md). The earlier
// support files stay as they are; this file adds the content image endpoints of
// slice 5 to the fake server, in the shape of docs/integrate/openapi-console.json,
// and the helpers the slice 5 screen tests share.
//
// The answers made here (the image upload, its 400 made with `imageRejected`, the
// attachment list and the history) go through `checkResponse`: a fixture that does not
// fit the contract file fails the test that used it. A 400 of a save made with
// `rejected` of console1a does not; its shape is checked against the contract file in
// packages/api/src/testing/contract.test.ts.
import { HttpResponse, http } from 'msw/http';
import { afterEach, beforeEach, expect } from 'vitest';
import { checkResponse } from '../../../packages/api/src/testing/contract';
import {
  T0,
  attachment,
  callsTo,
  errors,
  file,
  found,
  paged,
  partFileName,
  readParts,
  screen,
  server,
  state,
  text,
  within,
  type ApiError,
  type Attachment,
  type Call,
  type Part,
  type Person,
} from './console';
import { history, s3, type IssueHistoryEntry } from './console3';

export const IMAGES = '/api/console/images';
export const ISSUE_7 = '/api/console/issues/ACME-7';
export const ATTACHMENTS_7 = `${ISSUE_7}/attachments`;

/** IDs of content images already attached to ACME-7. */
export const OLD_IMAGE = '5a000000-0000-4000-8000-000000000001';
export const REPLY_IMAGE = '5a000000-0000-4000-8000-000000000002';
export const MEMO_IMAGE = '5a000000-0000-4000-8000-000000000003';
/** IDs the fake server gives the next uploads, in this order. */
export const NEW_IMAGE = '5b000000-0000-4000-8000-000000000001';
export const NEW_IMAGE_2 = '5b000000-0000-4000-8000-000000000002';

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

/** A PNG the editor takes. Its bytes are its own name. */
export function imageFile(name = 'shot.png'): File {
  return file(name, 1024, 'image/png');
}

/** A content image attached to the issue: listed like every other attachment, marked `inline` (S5-AC-24). */
export function inlineAttachment(over: Partial<Attachment> = {}): Attachment {
  return attachment({ id: OLD_IMAGE, fileName: 'old.png', contentType: 'image/png', inline: true, ...over });
}

/** The trace of a deleted attachment as the contract gives it: no file name. */
export function deletedAttachment(over: Partial<Attachment> = {}): Attachment {
  return attachment({ deleted: true, fileName: null, deletedAt: T0, deletedBy: over.uploadedBy ?? attachment().uploadedBy, ...over });
}

/** A history line about an attachment: attached (`newValue` holds its ID) or deleted (`oldValue` holds it). Never a file name (S5-AC-43). */
export function attachmentHistory(what: 'attached' | 'deleted', id: string, over: Partial<IssueHistoryEntry> = {}): IssueHistoryEntry {
  return history({ field: 'ATTACHMENT', oldValue: what === 'deleted' ? id : null, newValue: what === 'attached' ? id : null, ...over });
}

/** The markdown the editor writes for an uploaded image. */
export function imageSyntax(name: string, id: string): string {
  return `![${name}](attachment:${id})`;
}

/** The address a content image of ACME-7 is drawn from. */
export function attachmentPath(id: string): string {
  return `${ATTACHMENTS_7}/${id}`;
}

/** The address an image not attached yet is drawn from in a preview. */
export function temporaryPath(id: string): string {
  return `${IMAGES}/${id}`;
}

// ---------------------------------------------------------------------------
// Fake server

/** State the slice 5 handlers read. Tests change it before rendering. */
export const s5: {
  /** IDs for the next uploads, first one first. */
  nextIds: string[];
  /** The images uploaded and not attached yet: the file name of each, by ID. */
  temporary: Map<string, string>;
} = { nextIds: [], temporary: new Map() };

function requireSession(): Response | undefined {
  return state.signedIn ? undefined : errors.unauthenticated();
}

/** What the server does with a good image: stores it and answers 201 with its ID. */
export function acceptImage(fileName = 'image.png'): Response {
  const id = s5.nextIds.shift() ?? crypto.randomUUID();
  s5.temporary.set(id, fileName);
  return HttpResponse.json(contract('uploadImage', 201, { id }), { status: 201 });
}

/** A 400 of the image upload with `code`, in the contract's error shape. */
export function imageRejected(code: string): Response {
  const body: ApiError = { code, message: `${code} (server text).` };
  return HttpResponse.json(contract('uploadImage', 400, body), { status: 400 });
}

/** A proxy in front answers 413 first with its own HTML body (conventions.md, S0-AC-87). */
export function proxyPayloadTooLarge(): Response {
  return new HttpResponse('<html><head><title>413 Request Entity Too Large</title></head><body><h1>413 Request Entity Too Large</h1></body></html>', {
    status: 413,
    headers: { 'Content-Type': 'text/html' },
  });
}

/** A save on its way: the `imageIds` it sent, and the comment they go to once the answer says it was saved. */
interface Save {
  ids: string[];
  commentId: () => number | null;
}

type PathParams = Record<string, string | readonly string[] | undefined>;

const saving = new Map<string, Save>();

/** `imageIds` of a save request. */
async function sentImageIds(request: Request): Promise<string[]> {
  const text = await request.clone().text();
  const ids = (JSON.parse(text === '' ? '{}' : text) as { imageIds?: unknown }).imageIds;
  return Array.isArray(ids) ? ids.filter((id): id is string => typeof id === 'string') : [];
}

/**
 * Stands in front of a save handler of the earlier support files: it notes the
 * `imageIds` and answers nothing, so the earlier handler answers as before.
 */
function noting(commentId: (params: PathParams) => () => number | null) {
  return async ({ request, requestId, params }: { request: Request; requestId: string; params: PathParams }) => {
    saving.set(requestId, { ids: await sentImageIds(request), commentId: commentId(params) });
    return undefined;
  };
}

/** What a save that went through does with its images: each becomes a content image in the list (S5-AC-20). */
function attach(save: Save) {
  const commentId = save.commentId();
  const internal = state.comments.find((c) => c.id === commentId)?.internal ?? false;
  const uploadedBy: Person = { accountId: state.me.id, name: state.me.name, role: state.me.role, photoUpdatedAt: state.me.photoUpdatedAt };
  for (const id of save.ids) {
    const fileName = s5.temporary.get(id);
    if (fileName === undefined) continue;
    s5.temporary.delete(id);
    state.attachments = [...state.attachments, inlineAttachment({ id, fileName, commentId, internal, uploadedBy })];
  }
}

server.events.on('response:mocked', ({ requestId, response }) => {
  const save = saving.get(requestId);
  if (!save) return;
  saving.delete(requestId);
  if (response.ok) attach(save);
});

const onIssue = () => () => null;
const onNewComment = () => () => state.comments.at(-1)?.id ?? null;
const onComment = (params: PathParams) => () => Number(params.id);

/** What the slice 5 server does when nothing goes wrong. Tests replace one with `on` of console.ts. */
const handlers5 = [
  http.post(`*${IMAGES}`, async ({ request }) => {
    const denied = requireSession();
    if (denied) return denied;
    const parts = await readParts(request);
    return acceptImage(partFileName(parts.find((p) => p.name === 'file')));
  }),
  // The saves of the earlier files again: those do not know `imageIds`.
  http.post('*/api/console/issues', noting(onIssue)),
  http.patch('*/api/console/issues/:key', noting(onIssue)),
  http.post('*/api/console/issues/:key/comments', noting(onNewComment)),
  http.patch('*/api/console/issues/:key/comments/:id', noting(onComment)),
  // The lists again, through the contract check.
  http.get('*/api/console/issues/:key/attachments', ({ params }) => {
    const denied = requireSession() ?? (params.key === state.issue.key ? undefined : errors.notFound());
    return denied ?? HttpResponse.json(contract('listAttachments', 200, paged(state.attachments)));
  }),
  http.get('*/api/console/issues/:key/history', ({ params }) => {
    const denied = requireSession() ?? (params.key === state.issue.key ? undefined : errors.notFound());
    return denied ?? HttpResponse.json(contract('listIssueHistory', 200, paged(s3.history)));
  }),
];

beforeEach(() => {
  s5.nextIds = [NEW_IMAGE, NEW_IMAGE_2];
  s5.temporary = new Map();
  saving.clear();
  contractProblems.length = 0;
  server.use(...handlers5);
});

afterEach(() => {
  expect(contractProblems, '계약과 어긋난 가짜 응답').toEqual([]);
});

// ---------------------------------------------------------------------------
// Requests

/** The names of the files sent to the image upload endpoint, in order. */
export async function uploadedImages(): Promise<string[]> {
  const names: string[] = [];
  for (const c of callsTo('POST', IMAGES)) {
    const body = (await c.body) as Part[] | undefined;
    const part = Array.isArray(body) ? body.find((p) => p.name === 'file') : undefined;
    names.push(partFileName(part));
  }
  return names;
}

/** `imageIds` of a save request, as sent: undefined when the field is absent. */
export async function imageIdsOf(call: Call | undefined): Promise<unknown> {
  expect(call, '저장 요청').toBeDefined();
  return ((await call?.body) as { imageIds?: unknown } | undefined)?.imageIds;
}

// ---------------------------------------------------------------------------
// Finding things

/** The file input the "이미지" button opens: the nearest image file input around the button. */
export function imageInputOf(button: HTMLElement): HTMLInputElement | null {
  for (let el = button.parentElement; el; el = el.parentElement) {
    const input = el.querySelector<HTMLInputElement>('input[type="file"][accept*="image/png"]');
    if (input) return input;
  }
  return null;
}

/** The "이미지" button of the editor inside `scope`, or null. */
export function imageButtonIn(scope: () => HTMLElement | null): HTMLElement | null {
  const root = scope();
  return root && within(root).queryByRole('button', { name: '이미지' });
}

type User = { upload: (input: HTMLElement, files: File | File[]) => Promise<void> };

/** Picks `files` with the "이미지" button of the editor inside `scope`. */
export async function pickImages(user: User, scope: () => HTMLElement | null, files: File | File[]) {
  const button = await found(() => imageButtonIn(scope), '편집기의 "이미지" 단추');
  const input = imageInputOf(button);
  expect(input, '"이미지" 단추와 이어진 이미지 파일 입력').not.toBeNull();
  await user.upload(input as HTMLInputElement, files);
}

/** The picture named `name` (its description) inside `scope`, waited for. */
export function imageIn(scope: () => HTMLElement | null, name: string): Promise<HTMLImageElement> {
  return found(() => {
    const root = scope();
    return root && within(root).queryByRole<HTMLImageElement>('img', { name });
  }, `이미지 "${name}"`);
}

/** The path a picture is read from. */
export function srcPath(img: Element): string {
  return new URL(img.getAttribute('src') ?? '', window.location.href).pathname;
}

/** The article that shows the issue's content. */
export function issueBody(): HTMLElement | null {
  return screen.queryByRole('article', { name: '문의 내용' });
}

/** The preview panel of the editor inside `scope`, after pressing its preview tab ("미리 보기", or "고친 내용 미리 보기" of a comment being edited). */
export function previewIn(scope: () => HTMLElement | null): HTMLElement | null {
  const root = scope();
  return root && within(root).queryByRole('tabpanel', { name: /미리 보기$/ });
}

/** The line of the list named 첨부 that holds `part`. */
export function attachmentRow(part: string): HTMLElement | null {
  const list = screen.queryByRole('region', { name: '첨부' });
  return (list && within(list).queryAllByRole('listitem').find((li) => text(li).includes(part))) ?? null;
}
