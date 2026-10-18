// The answers for the slice 8b screen stories (8b 묶음 screens.md 2절·3절). storyFetch.ts answers the
// rest of the console API; this file wraps the `fetch` in place and answers the inbox list
// (`listNotifications`) and the statistics (`getCompanyStats`, whatever `days` asks) from a scenario
// the story sets.
//
// Import this file before any module that reaches `src/api.ts`: openapi-fetch keeps the `fetch`
// it finds when the client is made.
import type { Schemas } from '../api';

type CompanyStat = Schemas['CompanyStat'];
type CompanySlaStat = Schemas['CompanySlaStat'];
type Notification = Schemas['Notification'];

/** `stat()` of storyFetch7b.ts with the SLA columns of a policy that is on. */
export function slaStat(sla: CompanySlaStat, over: Partial<CompanyStat> = {}): CompanyStat {
  const base = { companyCode: 'ACME', companyName: '에이크미', companyStatus: 'ACTIVE' as const, open: 5, inProgress: 3, resolved: 0, closed: 4, ...over, sla };
  return { ...base, total: over.total ?? base.open + base.inProgress + base.resolved + base.closed };
}

/** An inbox item as the console gets it; an SLA summary item about no issue unless given. */
export function inboxItem(over: Partial<Notification> = {}): Notification {
  return {
    id: 855,
    kind: 'SLA_RECALCULATED',
    issue: null,
    companyCode: null,
    commentId: null,
    actor: null,
    createdAt: '2026-10-08T03:15:00Z',
    readAt: null,
    task: null,
    linkedIssue: null,
    slaSummary: null,
    retentionCount: null,
    retentionDays: null,
    ...over,
  };
}

export interface Scenario8b {
  /** The statistics items, for every `days`. */
  stats: CompanyStat[];
  /** The caller's inbox, newest first. */
  inbox: Notification[];
}

let current: Scenario8b = { stats: [], inbox: [] };

/** The answers the next requests get. */
export function setScenario8b(next: Scenario8b): void {
  current = next;
}

function json(body: unknown): Response {
  return new Response(JSON.stringify(body), { status: 200, headers: { 'Content-Type': 'application/json' } });
}

const before = globalThis.fetch;

globalThis.fetch = (input: RequestInfo | URL, init?: RequestInit): Promise<Response> => {
  const request = input instanceof Request ? input : new Request(input, init);
  const url = new URL(request.url, window.location.href);
  if (request.method === 'GET' && url.pathname === '/api/console/stats') return Promise.resolve(json({ items: current.stats }));
  if (request.method === 'GET' && url.pathname === '/api/console/notifications') {
    const items = current.inbox;
    return Promise.resolve(json({ items, page: 1, size: 20, totalElements: items.length, totalPages: items.length === 0 ? 0 : 1 }));
  }
  return before(input, init);
};
