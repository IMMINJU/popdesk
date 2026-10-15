// More test support for slice 6b (design/slices/6b-tasks/screens.md): tasks under an issue, their
// notes, and 내 작업 (C-15). The earlier support files stay as they are; this file adds the task
// endpoints to the fake server, in the shape of docs/integrate/openapi-console.json, and the
// helpers the slice 6b screen tests share.
//
// Every answer made here goes through `contract` of console6a.ts: a fixture that does not fit the
// contract file fails the test that used it.
import type { ConsoleComponents } from '@popdesk/api/console';
import { HttpResponse, http } from 'msw/http';
import { beforeEach, expect } from 'vitest';
import { ADMIN_PERSON, AGENT_PERSON, T0, callsTo, errors, found, paged, screen, server, state, text, within, type Person } from './console';
// The slice 3 handlers too: the assignee candidates (`s3.candidates`) and the status of the issue.
import { s3 } from './console3';
import { contract, rejectedWith } from './console6a';

type Schemas = ConsoleComponents['schemas'];
export type Task = Schemas['Task'];
export type TaskNote = Schemas['TaskNote'];
export type IssueTasks = Schemas['IssueTasks'];
export type MyTask = Schemas['MyTask'];

export const TASKS_7 = '/api/console/issues/ACME-7/tasks';
export const MY_TASKS = '/api/console/tasks';

/** The path of task `id` of an issue. */
export function taskPath(id: number, key = 'ACME-7'): string {
  return `/api/console/issues/${key}/tasks/${String(id)}`;
}

/** The path of the notes of task `id`, or of note `noteId` of it. */
export function notePath(id: number, noteId?: number, key = 'ACME-7'): string {
  return noteId === undefined ? `${taskPath(id, key)}/notes` : `${taskPath(id, key)}/notes/${String(noteId)}`;
}

/** When the fake server marks a task done, and when the fixtures were edited and deleted. */
export const DONE_AT = '2026-10-09T02:00:00Z';
export const EDITED_AT = '2026-10-09T05:00:00Z';
export const DELETED_AT = '2026-10-10T04:05:06Z';

// ---------------------------------------------------------------------------
// Fixtures

/** An open task of nobody, made by 최관리, with no due date and no notes. */
export function task(over: Partial<Task> = {}): Task {
  return {
    id: 41,
    title: '로그 모으기',
    status: 'OPEN',
    assignee: null,
    dueDate: null,
    overdue: false,
    createdBy: ADMIN_PERSON,
    createdAt: T0,
    doneBy: null,
    doneAt: null,
    deleted: false,
    deletedBy: null,
    deletedAt: null,
    notes: [],
    ...over,
  };
}

/** A task 최관리 made `DONE`. */
export function doneTask(over: Partial<Task> = {}): Task {
  return task({ status: 'DONE', doneBy: ADMIN_PERSON, doneAt: DONE_AT, ...over });
}

/** A deleted task as the contract gives it: no title, no assignee, no due date, no notes (S6B-AC-10). */
export function deletedTask(over: Partial<Task> = {}): Task {
  return task({ title: '', deleted: true, deletedBy: AGENT_PERSON, deletedAt: DELETED_AT, ...over, assignee: null, dueDate: null, overdue: false, notes: [] });
}

/** A note 이상담 wrote. */
export function note(over: Partial<TaskNote> = {}): TaskNote {
  return {
    id: 801,
    kind: 'NOTE',
    author: AGENT_PERSON,
    content: '고객에게 로그를 받았습니다.',
    field: null,
    oldValue: null,
    newValue: null,
    oldPerson: null,
    newPerson: null,
    createdAt: T0,
    editedAt: null,
    deleted: false,
    deletedBy: null,
    deletedAt: null,
    ...over,
  };
}

/** A deleted note: empty content and the trace (S6B-AC-22). */
export function deletedNote(over: Partial<TaskNote> = {}): TaskNote {
  return note({ deleted: true, deletedBy: ADMIN_PERSON, deletedAt: DELETED_AT, ...over, content: '' });
}

/** A system entry: 이상담 changed `field` (S6B-AC-30). */
export function change(field: string, over: Partial<TaskNote> = {}): TaskNote {
  return note({ id: 851, kind: 'CHANGE', content: '', field, ...over });
}

/** One line of 내 작업: an open task on ACME-7. */
export function myTask(over: Partial<MyTask> = {}): MyTask {
  return {
    id: 41,
    title: '로그 모으기',
    status: 'OPEN',
    dueDate: null,
    overdue: false,
    doneAt: null,
    issue: { key: 'ACME-7', title: '로그인이 안 됩니다', status: 'IN_PROGRESS' },
    companyCode: 'ACME',
    companyName: '에이크미',
    ...over,
  };
}

/** Open tasks that are not deleted: what the list gives as `openCount`. */
export function openOf(tasks: readonly Task[]): number {
  return tasks.filter((t) => !t.deleted && t.status === 'OPEN').length;
}

/** The list answer of an issue as the contract gives it. */
export function tasksAnswer(tasks: readonly Task[]): IssueTasks {
  return { items: [...tasks], openCount: openOf(tasks) };
}

// ---------------------------------------------------------------------------
// Fake server

/** State the slice 6b handlers read. Tests change it before rendering. */
export const s6b: {
  /** The tasks of each issue, by key, in the order they were made. */
  tasks: Record<string, Task[]>;
  /** The tasks of the caller over every issue, both statuses; the list filters by `status`. */
  mine: MyTask[];
  nextTaskId: number;
  nextNoteId: number;
} = { tasks: {}, mine: [], nextTaskId: 500, nextNoteId: 950 };

function resetS6b() {
  s6b.tasks = {};
  s6b.mine = [];
  s6b.nextTaskId = 500;
  s6b.nextNoteId = 950;
}

type PathValue = string | readonly string[] | undefined;

function me(): Person {
  return { accountId: state.me.id, name: state.me.name, role: state.me.role, photoUpdatedAt: state.me.photoUpdatedAt };
}

function requireSession(): Response | undefined {
  return state.signedIn ? undefined : errors.unauthenticated();
}

function keyOf(key: PathValue): string {
  return String(key);
}

/** 404 for an issue the fake server does not have: neither the one of `state` nor one with tasks set. */
function unknownIssue(key: PathValue): Response | undefined {
  const k = keyOf(key);
  return requireSession() ?? (k === state.issue.key || Object.hasOwn(s6b.tasks, k) ? undefined : errors.notFound());
}

function closed(key: PathValue): boolean {
  return keyOf(key) === state.issue.key && state.issue.status === 'CLOSED';
}

function tasksOf(key: PathValue): Task[] {
  return s6b.tasks[keyOf(key)] ?? [];
}

function save(key: PathValue, next: Task): Task {
  const k = keyOf(key);
  s6b.tasks = { ...s6b.tasks, [k]: tasksOf(key).map((t) => (t.id === next.id ? next : t)) };
  return next;
}

async function jsonOf(request: Request): Promise<Record<string, unknown>> {
  const raw = await request.text();
  return raw === '' ? {} : (JSON.parse(raw) as Record<string, unknown>);
}

/** What the slice 6b server does when nothing goes wrong. Tests replace one with `route` of console1a. */
const handlers6b = [
  http.get('*/api/console/issues/:key/tasks', ({ params }) => {
    const denied = unknownIssue(params.key);
    return denied ?? HttpResponse.json(contract('listIssueTasks', 200, tasksAnswer(tasksOf(params.key))));
  }),
  http.post('*/api/console/issues/:key/tasks', async ({ request, params }) => {
    const denied = unknownIssue(params.key);
    if (denied) return denied;
    const body = await jsonOf(request);
    const title = typeof body['title'] === 'string' ? body['title'].trim() : '';
    if (title === '') return rejectedWith('createTask', 'INVALID_INPUT', 400, [{ field: 'title', code: 'BLANK' }]);
    if (closed(params.key)) return rejectedWith('createTask', 'ISSUE_CLOSED');
    const created = task({ id: s6b.nextTaskId++, title, createdBy: me(), createdAt: '2026-10-08T05:00:00Z' });
    s6b.tasks = { ...s6b.tasks, [keyOf(params.key)]: [...tasksOf(params.key), created] };
    return HttpResponse.json(contract('createTask', 201, created), { status: 201 });
  }),
  http.patch('*/api/console/issues/:key/tasks/:id', async ({ request, params }) => {
    const denied = unknownIssue(params.key);
    if (denied) return denied;
    const old = tasksOf(params.key).find((t) => String(t.id) === params.id);
    if (!old) return errors.notFound();
    const body = await jsonOf(request);
    if (old.deleted) return rejectedWith('updateTask', 'TASK_DELETED');
    if (closed(params.key)) return rejectedWith('updateTask', 'ISSUE_CLOSED');
    let next: Task = { ...old };
    if (typeof body['title'] === 'string') next = { ...next, title: body['title'].trim() };
    if (body['status'] === 'DONE' && old.status !== 'DONE') next = { ...next, status: 'DONE', doneBy: me(), doneAt: DONE_AT, overdue: false };
    if (body['status'] === 'OPEN' && old.status !== 'OPEN') next = { ...next, status: 'OPEN', doneBy: null, doneAt: null };
    if ('assigneeId' in body) {
      const id = body['assigneeId'];
      if (id === null) next = { ...next, assignee: null };
      else {
        const person = s3.candidates.find((p) => p.accountId === id);
        if (!person) return rejectedWith('updateTask', 'INVALID_INPUT', 400, [{ field: 'assigneeId', code: 'UNKNOWN_VALUE' }]);
        next = { ...next, assignee: person };
      }
    }
    if ('dueDate' in body) {
      const due = body['dueDate'];
      next = { ...next, dueDate: typeof due === 'string' ? due : null, overdue: false };
    }
    return HttpResponse.json(contract('updateTask', 200, save(params.key, next)));
  }),
  http.delete('*/api/console/issues/:key/tasks/:id', ({ params }) => {
    const denied = unknownIssue(params.key);
    if (denied) return denied;
    const old = tasksOf(params.key).find((t) => String(t.id) === params.id);
    if (!old) return errors.notFound();
    if (!old.deleted) save(params.key, deletedTask({ id: old.id, createdBy: old.createdBy, createdAt: old.createdAt, deletedBy: me() }));
    return new HttpResponse(null, { status: 204 });
  }),
  http.post('*/api/console/issues/:key/tasks/:id/notes', async ({ request, params }) => {
    const denied = unknownIssue(params.key);
    if (denied) return denied;
    const old = tasksOf(params.key).find((t) => String(t.id) === params.id);
    if (!old) return errors.notFound();
    const body = await jsonOf(request);
    if (old.deleted) return rejectedWith('createTaskNote', 'TASK_DELETED');
    if (closed(params.key)) return rejectedWith('createTaskNote', 'ISSUE_CLOSED');
    const created = note({ id: s6b.nextNoteId++, author: me(), content: typeof body['content'] === 'string' ? body['content'] : '', createdAt: '2026-10-08T05:00:00Z' });
    save(params.key, { ...old, notes: [...old.notes, created] });
    return HttpResponse.json(contract('createTaskNote', 201, created), { status: 201 });
  }),
  http.patch('*/api/console/issues/:key/tasks/:id/notes/:noteId', async ({ request, params }) => {
    const denied = unknownIssue(params.key);
    if (denied) return denied;
    const old = tasksOf(params.key).find((t) => String(t.id) === params.id);
    const was = old?.notes.find((n) => String(n.id) === params.noteId);
    if (!old || !was) return errors.notFound();
    const body = await jsonOf(request);
    if (was.deleted) return rejectedWith('updateTaskNote', 'TASK_NOTE_DELETED');
    const saved: TaskNote = { ...was, content: typeof body['content'] === 'string' ? body['content'] : was.content, editedAt: EDITED_AT };
    save(params.key, { ...old, notes: old.notes.map((n) => (n.id === saved.id ? saved : n)) });
    return HttpResponse.json(contract('updateTaskNote', 200, saved));
  }),
  http.delete('*/api/console/issues/:key/tasks/:id/notes/:noteId', ({ params }) => {
    const denied = unknownIssue(params.key);
    if (denied) return denied;
    const old = tasksOf(params.key).find((t) => String(t.id) === params.id);
    const was = old?.notes.find((n) => String(n.id) === params.noteId);
    if (!old || !was) return errors.notFound();
    const gone = was.deleted ? was : deletedNote({ id: was.id, author: was.author, createdAt: was.createdAt, deletedBy: me() });
    save(params.key, { ...old, notes: old.notes.map((n) => (n.id === gone.id ? gone : n)) });
    return new HttpResponse(null, { status: 204 });
  }),
  http.get('*/api/console/tasks', ({ request }) => {
    const denied = requireSession();
    if (denied) return denied;
    const query = new URL(request.url).searchParams;
    const status = query.get('status') ?? 'OPEN';
    const page = Number(query.get('page') ?? '1');
    const size = Number(query.get('size') ?? '20');
    const all = s6b.mine.filter((t) => t.status === status);
    const body = paged(all.slice((page - 1) * size, page * size), { page, size, totalElements: all.length });
    return HttpResponse.json(contract('listMyTasks', 200, body));
  }),
];

beforeEach(() => {
  resetS6b();
  server.use(...handlers6b);
});

/** The answer of `listMyTasks` for one page, through the contract check. */
export function myTasksPage(items: MyTask[], over: { page?: number; size?: number; totalElements?: number; totalPages?: number } = {}): Response {
  return HttpResponse.json(contract('listMyTasks', 200, paged(items, over)));
}

/** The answer of `listIssueTasks` for `tasks`, through the contract check. */
export function tasksResponse(tasks: readonly Task[]): Response {
  return HttpResponse.json(contract('listIssueTasks', 200, tasksAnswer(tasks)));
}

/** The answer of `updateTask`, through the contract check. */
export function taskResponse(t: Task, operationId = 'updateTask', status = 200): Response {
  return HttpResponse.json(contract(operationId, status, t), { status });
}

// ---------------------------------------------------------------------------
// Requests

/** The body of the last `method path` request, parsed as JSON. */
export async function lastBody(method: string, path: string): Promise<Record<string, unknown>> {
  const call = callsTo(method, path).at(-1);
  expect(call, `${method} ${path} 요청`).toBeDefined();
  return ((await call?.body) ?? {}) as Record<string, unknown>;
}

/** The query of the last 내 작업 list request; empty when there was none. */
export function lastMine(): URLSearchParams {
  return new URL(callsTo('GET', MY_TASKS).at(-1)?.url ?? 'http://x/').searchParams;
}

// ---------------------------------------------------------------------------
// Finding things in the 작업 구역 of C-4 (screens.md 1절)

/** The 작업 구역: a region named "작업" or "작업 n/m", or null now. */
export function taskRegionNow(): HTMLElement | null {
  return screen.queryByRole('region', { name: /^작업( \d+\/\d+)?$/ });
}

export function taskRegion(): Promise<HTMLElement> {
  return found(taskRegionNow, '구역 "작업"');
}

/** The name of the 작업 구역 as a screen reader reads it (its heading). */
export function regionName(region: HTMLElement): string {
  const by = region.getAttribute('aria-labelledby');
  if (by !== null) return by.split(/\s+/).map((id) => text(document.getElementById(id))).join(' ');
  return region.getAttribute('aria-label') ?? '';
}

const LINE = 'li, tr, article, [role="listitem"], [role="row"], [role="article"]';

/** The outermost line inside `root` whose text holds `part`: the task line, whatever is nested in it. */
export function outerLine(root: HTMLElement, part: string): HTMLElement | null {
  const all = Array.from(root.querySelectorAll<HTMLElement>(LINE)).filter((el) => text(el).includes(part));
  return all.find((el) => !all.some((other) => other !== el && other.contains(el))) ?? null;
}

/** The innermost line inside `root` whose text holds `part`: one note or system entry. */
export function innerLine(root: HTMLElement, part: string): HTMLElement | null {
  const all = Array.from(root.querySelectorAll<HTMLElement>(LINE)).filter((el) => text(el).includes(part));
  return all.find((el) => !all.some((other) => other !== el && el.contains(other))) ?? null;
}

/** The line of the task titled `title` in the 작업 구역, waited for. */
export function taskLine(title: string): Promise<HTMLElement> {
  return found(() => {
    const region = taskRegionNow();
    return region ? outerLine(region, title) : null;
  }, `작업 줄 "${title}"`);
}

/** The status button of a task: "{제목} 완료" or "{제목} 다시 열기" (screens.md 1절), or null now. */
export function statusButtonNow(title: string, to: 'DONE' | 'OPEN'): HTMLElement | null {
  const region = taskRegionNow();
  return region ? within(region).queryByRole('button', { name: `${title} ${to === 'DONE' ? '완료' : '다시 열기'}` }) : null;
}

export function statusButton(title: string, to: 'DONE' | 'OPEN'): Promise<HTMLElement> {
  return found(() => statusButtonNow(title, to), `"${title} ${to === 'DONE' ? '완료' : '다시 열기'}"`);
}

type User = { click: (el: Element) => Promise<void> };

function escaped(s: string): string {
  return s.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
}

/**
 * Opens the notes of the task titled `title`: its "기록 n" button, or else the button of the line
 * that carries the title (screens.md 1절: 줄을 누르면 또는 "기록 n").
 */
export async function expand(user: User, title: string): Promise<HTMLElement> {
  const line = await taskLine(title);
  const opener = await found(
    () =>
      within(line).queryByRole('button', { name: /^기록 \d+/ }) ??
      within(line)
        .queryAllByRole('button', { name: new RegExp(escaped(title)) })
        .find((b) => !/(완료|다시 열기)$/.test(b.getAttribute('aria-label') ?? text(b))),
    `"${title}" 줄의 "기록 n" 또는 펼치는 단추`,
  );
  if (opener.getAttribute('aria-expanded') !== 'true') await user.click(opener);
  return line;
}

const ACTION_NAMES = ['고치기', '지우기'] as const;
type ActionName = (typeof ACTION_NAMES)[number];

function nameOfButton(b: HTMLElement): string {
  return (b.getAttribute('aria-label') ?? text(b)).trim();
}

/** Buttons of `root` named "고치기"/"지우기", or "기록 고치기"·"작업 지우기" and the like, not inside a line nested in `root`. */
function directActions(root: HTMLElement): { name: ActionName; el: HTMLElement }[] {
  return within(root)
    .queryAllByRole('button')
    .filter((b) => {
      const nested = b.parentElement?.closest(LINE) ?? null;
      return nested === root || nested === null || !root.contains(nested);
    })
    .flatMap((b) => {
      const name = ACTION_NAMES.find((n) => new RegExp(`(^|\\s)${n}$`).test(nameOfButton(b)));
      return name ? [{ name, el: b }] : [];
    });
}

function menuTrigger(root: HTMLElement): HTMLElement | undefined {
  return within(root)
    .queryAllByRole('button')
    .find((b) => b.hasAttribute('aria-haspopup') && (b.parentElement?.closest(LINE) === root || !root.contains(b.parentElement?.closest(LINE) ?? null)));
}

/**
 * Which of "고치기" and "지우기" a line offers: as buttons in it, or behind its menu button
 * (`aria-haspopup`), which is opened to read and closed again with Escape.
 */
export async function actionsOf(user: User & { keyboard: (keys: string) => Promise<void> }, root: HTMLElement): Promise<ActionName[]> {
  const names = new Set(directActions(root).map((a) => a.name));
  const trigger = menuTrigger(root);
  if (trigger) {
    await user.click(trigger);
    const menu = await found(() => screen.queryByRole('menu'), '줄 메뉴');
    for (const item of within(menu).queryAllByRole('menuitem')) {
      const name = ACTION_NAMES.find((n) => new RegExp(`(^|\\s)${n}$`).test(nameOfButton(item)));
      if (name) names.add(name);
    }
    await user.keyboard('{Escape}');
  }
  return ACTION_NAMES.filter((n) => names.has(n));
}

/** One of "고치기" and "지우기" of a line: its button, or the item of its menu (opened here). */
export async function actionOf(user: User, root: HTMLElement, name: ActionName): Promise<HTMLElement> {
  const direct = directActions(root).find((a) => a.name === name);
  if (direct) return direct.el;
  const trigger = menuTrigger(root);
  expect(trigger ?? null, `줄의 "${name}" 단추 또는 줄 메뉴`).not.toBeNull();
  if (trigger && trigger.getAttribute('aria-expanded') !== 'true') await user.click(trigger);
  return found(
    () => screen.queryAllByRole('menuitem').find((m) => new RegExp(`(^|\\s)${name}$`).test(nameOfButton(m))),
    `줄 메뉴의 "${name}"`,
  );
}

/** The note or system entry whose text holds `part`, inside the 작업 구역, waited for. */
export function noteLine(part: string): Promise<HTMLElement> {
  return found(() => {
    const region = taskRegionNow();
    return region ? innerLine(region, part) : null;
  }, `기록 줄 "${part}"`);
}

/** The textarea "기록 남기기" (screens.md 1절), or null now. */
export function noteBoxNow(): HTMLTextAreaElement | null {
  const region = taskRegionNow();
  return region ? within(region).queryByRole<HTMLTextAreaElement>('textbox', { name: '기록 남기기' }) : null;
}

/** The button that sends a new note: in the 작업 구역, its name holds "남기기" and it is not the textarea. */
export function sendNoteButton(): Promise<HTMLElement> {
  return found(() => {
    const region = taskRegionNow();
    return region ? within(region).queryAllByRole('button').find((b) => /남기기/.test(nameOfButton(b))) : null;
  }, '기록을 보내는 단추(이름에 "남기기")');
}

/** The date input of the opened 작업 마감일 고르기, inside the 작업 구역, or null now. */
export function taskDueInputNow(): HTMLInputElement | null {
  return taskRegionNow()?.querySelector<HTMLInputElement>('input[type="date"]') ?? null;
}

/** The text of an element keeps the line break of `a\nb`: as it is (pre-wrap) or as a `<br>`. */
export function keepsBreak(el: Element, first: string, second: string): boolean {
  return el.textContent.includes(`${first}\n${second}`) || (el.innerHTML.includes('<br') && text(el).includes(first) && text(el).includes(second));
}
