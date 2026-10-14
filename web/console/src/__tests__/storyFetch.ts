// A fake server for the console screen stories (6a 묶음 screens.md 6절, S6A-AC-91). The stories
// run in a real browser where MSW's node server does not work, so this replaces `fetch` and
// answers the console API from a scenario the story sets before it renders.
//
// Import this file before any module that reaches `src/api.ts`: openapi-fetch keeps the `fetch`
// it finds when the client is made.
import type { Schemas } from '../api';

type Issue = Schemas['Issue'];
type IssueSummary = Schemas['IssueSummary'];
type Company = Schemas['Company'];
type Label = Schemas['Label'];
type Me = Schemas['Me'];

export interface Scenario {
  me: Me;
  companies: Company[];
  issues: IssueSummary[];
  issue: Issue;
  /** The labels of each company, by code. */
  labels: Record<string, Label[]>;
}

export const T0 = '2026-10-08T03:15:00Z';

export const ADMIN: Me = { id: 1, email: 'admin@popdesk.test', name: '최관리', role: 'ADMIN', locale: 'ko', photoUpdatedAt: null, companies: [] };

export function company(over: Partial<Company> = {}): Company {
  return { code: 'ACME', name: '에이크미', legalName: '에이크미 주식회사', status: 'ACTIVE', createdAt: T0, pendingMembershipRequests: 0, ...over };
}

export function issue(over: Partial<Issue> = {}): Issue {
  return {
    key: 'ACME-7',
    title: '로그인이 안 됩니다',
    content: '어제부터 **로그인**이 안 됩니다.',
    status: 'IN_PROGRESS',
    priority: 'NORMAL',
    private: false,
    reporter: { accountId: 11, name: '김고객', role: 'CUSTOMER', photoUpdatedAt: null },
    companyCode: 'ACME',
    companyName: '에이크미',
    channel: 'PORTAL',
    createdBy: null,
    assignee: { accountId: 2, name: '이상담', role: 'AGENT', photoUpdatedAt: null },
    assigneeUnavailable: false,
    nextStatuses: ['RESOLVED'],
    reopenable: false,
    firstResponseAt: null,
    resolvedAt: null,
    closedAt: null,
    createdAt: T0,
    updatedAt: T0,
    version: 1,
    labels: [],
    dueDate: null,
    overdue: false,
    ...over,
  };
}

export function summary(from: Issue): IssueSummary {
  const { key, title, status, priority, reporter, companyCode, companyName, createdAt, assignee, assigneeUnavailable, labels, dueDate, overdue } = from;
  return {
    key,
    title,
    status,
    priority,
    private: from.private,
    reporter,
    companyCode,
    companyName,
    filedOnBehalf: false,
    assignee,
    assigneeUnavailable,
    createdAt,
    labels,
    dueDate,
    overdue,
  };
}

export function label(id: number, name: string, color: string, issueCount = 0): Label {
  return { id, name, color, issueCount };
}

/** Thirty characters: the longest name a label takes. */
export const LONG_NAME = '고객사 결제 모듈 장애 대응 우선 처리 필요한 건 모음';

/** Name order whatever the case, as the contract gives labels (S6A-AC-10, S6A-AC-50). */
export function byName<T extends { name: string }>(items: readonly T[]): T[] {
  return [...items].sort((a, b) => a.name.toLowerCase().localeCompare(b.name.toLowerCase()));
}

/** Labels of every swatch plus long names, in name order: the widest lists the screens draw. */
export const WIDE_LABELS: Label[] = byName([
  label(61, '긴급', '#C0262D', 3),
  label(62, '결제', '#245FB0', 1),
  label(63, '모바일', '#F2C14E', 0),
  label(64, '보안', '#1F7A3A', 12),
  label(65, '청구서', '#0F766E', 4),
  label(66, '주문 취소', '#B07A00', 0),
  label(67, '보라색 라벨', '#6D28D9', 2),
  label(68, '기본 회색', '#808080', 0),
  label(69, LONG_NAME.slice(0, 30), '#245FB0', 7),
  label(70, `${LONG_NAME.slice(0, 27)} 둘`, '#C0262D', 1),
]);

/** `n` labels, the long ones first, in name order: a company at the limit of 200. */
export function manyLabels(n: number): Label[] {
  return byName(Array.from({ length: n }, (_, i) => label(1000 + i, `${LONG_NAME.slice(0, 25)} ${String(i + 1).padStart(3, '0')}`, '#245FB0', i % 13)));
}

export function scenario(over: Partial<Scenario> = {}): Scenario {
  const one = issue();
  return { me: ADMIN, companies: [company()], issues: [summary(one)], issue: one, labels: { ACME: WIDE_LABELS }, ...over };
}

let current: Scenario = scenario();

/** The scenario the next requests are answered from. */
export function setScenario(next: Scenario): void {
  current = next;
}

function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } });
}

function paged<T>(items: readonly T[]) {
  return { items, page: 1, size: 20, totalElements: items.length, totalPages: items.length === 0 ? 0 : 1 };
}

const notFound = () => json({ code: 'NOT_FOUND', message: 'Not found.' }, 404);

/** What the fake server answers: by method and path, the rest a page with nothing in it. */
function answer(method: string, path: string): Response {
  const s = current;
  if (path === '/api/public/csrf') return new Response(null, { status: 204 });
  if (method !== 'GET') return notFound();
  if (path === '/api/console/me') return json(s.me);
  if (path === '/api/console/notifications/unread-count') return json({ count: 0 });
  if (path === '/api/console/issues') return json(paged(s.issues));
  if (path === '/api/console/companies') return json(paged(s.companies));
  const issueMatch = /^\/api\/console\/issues\/([^/]+)$/.exec(path);
  if (issueMatch) return decodeURIComponent(issueMatch[1] ?? '') === s.issue.key ? json(s.issue) : notFound();
  const labels = /^\/api\/console\/companies\/([^/]+)\/labels$/.exec(path);
  if (labels) return json({ items: byName(s.labels[decodeURIComponent(labels[1] ?? '')] ?? []) });
  const companyMatch = /^\/api\/console\/companies\/([^/]+)$/.exec(path);
  if (companyMatch) {
    const found = s.companies.find((c) => c.code === decodeURIComponent(companyMatch[1] ?? ''));
    return found ? json(found) : notFound();
  }
  return json(paged([]));
}

const realFetch = globalThis.fetch.bind(globalThis);

globalThis.fetch = (input: RequestInfo | URL, init?: RequestInit): Promise<Response> => {
  const request = input instanceof Request ? input : new Request(input, init);
  const url = new URL(request.url, window.location.href);
  if (!url.pathname.startsWith('/api/')) return realFetch(input, init);
  return Promise.resolve(answer(request.method, url.pathname));
};
