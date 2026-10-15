// The task answers for the slice 6b screen stories (6b 묶음 screens.md 5절, S6B-AC-90). storyFetch.ts
// answers the rest of the console API; this file wraps the `fetch` it put in place and answers the
// tasks of an issue (`IssueTasks`) and 내 작업 (`PagedMyTask`) from a scenario the story sets.
//
// Import this file before any module that reaches `src/api.ts`: openapi-fetch keeps the `fetch`
// it finds when the client is made.
import { T0 } from './storyFetch';
import type { Schemas } from '../api';

type Task = Schemas['Task'];
type TaskNote = Schemas['TaskNote'];
type MyTask = Schemas['MyTask'];
type Person = Schemas['Person'];

export const LEE: Person = { accountId: 2, name: '이상담', role: 'AGENT', photoUpdatedAt: null };
export const CHOI: Person = { accountId: 1, name: '최관리', role: 'ADMIN', photoUpdatedAt: null };

/** A hundred characters: the longest title a task takes. */
export const LONG_TITLE = '고객사 결제 모듈 장애 대응을 위해 서버 로그와 결제 대행사 응답 기록을 모아 원인과 재현 절차를 정리한 뒤 담당 개발자에게 넘기고 고객에게 진행 상황을 알리기까지'.padEnd(100, '끝').slice(0, 100);

export function task(over: Partial<Task> = {}): Task {
  return {
    id: 41,
    title: '로그 모으기',
    status: 'OPEN',
    assignee: LEE,
    dueDate: '2026-10-01',
    overdue: true,
    createdBy: CHOI,
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

export function note(over: Partial<TaskNote> = {}): TaskNote {
  return {
    id: 801,
    kind: 'NOTE',
    author: LEE,
    content: '고객에게 로그를 받았습니다.\n결제 대행사 응답은 내일 옵니다.',
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

export function myTask(over: Partial<MyTask> = {}): MyTask {
  return {
    id: 41,
    title: '로그 모으기',
    status: 'OPEN',
    dueDate: '2026-10-01',
    overdue: true,
    doneAt: null,
    issue: { key: 'ACME-7', title: '로그인이 안 됩니다', status: 'IN_PROGRESS' },
    companyCode: 'ACME',
    companyName: '에이크미',
    ...over,
  };
}

export interface TaskScenario {
  /** The tasks of every issue the story shows. */
  tasks: Task[];
  /** The caller's tasks over every issue, both statuses. */
  mine: MyTask[];
}

let current: TaskScenario = { tasks: [], mine: [] };

/** The tasks the next requests are answered from. */
export function setTaskScenario(next: TaskScenario): void {
  current = next;
}

function json(body: unknown): Response {
  return new Response(JSON.stringify(body), { status: 200, headers: { 'Content-Type': 'application/json' } });
}

const before = globalThis.fetch;

globalThis.fetch = (input: RequestInfo | URL, init?: RequestInit): Promise<Response> => {
  const request = input instanceof Request ? input : new Request(input, init);
  const url = new URL(request.url, window.location.href);
  if (request.method === 'GET' && /^\/api\/console\/issues\/[^/]+\/tasks$/.test(url.pathname)) {
    const items = current.tasks;
    return Promise.resolve(json({ items, openCount: items.filter((t) => !t.deleted && t.status === 'OPEN').length }));
  }
  if (request.method === 'GET' && url.pathname === '/api/console/tasks') {
    const status = url.searchParams.get('status') ?? 'OPEN';
    const items = current.mine.filter((t) => t.status === status);
    return Promise.resolve(json({ items, page: 1, size: 20, totalElements: items.length, totalPages: items.length === 0 ? 0 : 1 }));
  }
  return before(input, init);
};
