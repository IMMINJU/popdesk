// The statistics answer for the slice 7b screen stories (7b 묶음 screens.md 2절, S7B-AC-22). storyFetch.ts
// answers the rest of the console API; this file wraps the `fetch` in place and answers
// `getCompanyStats` from a scenario the story sets.
//
// Import this file before any module that reaches `src/api.ts`: openapi-fetch keeps the `fetch`
// it finds when the client is made.
import type { Schemas } from '../api';

type CompanyStat = Schemas['CompanyStat'];

/** One company's counts; `total` is the sum of the four others unless given. */
export function stat(over: Partial<CompanyStat> = {}): CompanyStat {
  const base = { companyCode: 'ACME', companyName: '에이크미', companyStatus: 'ACTIVE' as const, open: 5, inProgress: 3, resolved: 0, closed: 4, ...over };
  return { ...base, total: over.total ?? base.open + base.inProgress + base.resolved + base.closed };
}

export interface StatsScenario {
  /** The items the statistics give, in their order. */
  items: CompanyStat[];
}

let current: StatsScenario = { items: [] };

/** The statistics the next requests are answered from. */
export function setStatsScenario(next: StatsScenario): void {
  current = next;
}

const before = globalThis.fetch;

globalThis.fetch = (input: RequestInfo | URL, init?: RequestInit): Promise<Response> => {
  const request = input instanceof Request ? input : new Request(input, init);
  const url = new URL(request.url, window.location.href);
  if (url.pathname === '/api/console/stats' && request.method === 'GET') {
    return Promise.resolve(new Response(JSON.stringify({ items: current.items }), { status: 200, headers: { 'Content-Type': 'application/json' } }));
  }
  return before(input, init);
};
