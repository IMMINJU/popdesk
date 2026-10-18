// The export answers for the slice 10b screen stories (10b 묶음 screens.md "C-9 내보내기 구역"): the
// list of a company's exports and the export request. storyFetch.ts answers the rest of the console
// API; this file wraps the `fetch` in place and answers `listCompanyExports` and `createCompanyExport`
// from a scenario the story sets.
//
// Import this file before any module that reaches `src/api.ts`: openapi-fetch keeps the `fetch`
// it finds when the client is made.
import type { Schemas } from '../api';

type CompanyExport = Schemas['CompanyExport'];

/** Fifty Korean characters with spaces: the longest name an account takes. */
export const LONG_REQUESTER = '에이크미 글로벌 고객 지원 센터 서울 본사 개인정보 보호 담당 책임 관리자 이름 오십자'.padEnd(50, '끝').slice(0, 50);

/** A `DONE` export of 최관리, 9.5 MB. */
export function companyExport(over: Partial<CompanyExport> = {}): CompanyExport {
  return {
    id: '0b7e2d4c-5a61-4f3e-9c2b-000000000001',
    state: 'DONE',
    failure: null,
    includeInternal: false,
    includePrivate: false,
    requestedBy: { accountId: 1, name: '최관리' },
    requestedAt: '2026-10-08T03:00:00Z',
    finishedAt: '2026-10-08T03:10:00Z',
    size: 9_961_472,
    ...over,
  };
}

export interface ExportScenario {
  /** The exports of every company, the latest first. */
  exports: CompanyExport[];
  /** The code `createCompanyExport` refuses with, or null for 202. */
  refuse: { code: string; status: number } | null;
}

let current: ExportScenario = { exports: [], refuse: null };

/** The answers the next requests get. */
export function setExportScenario(next: ExportScenario): void {
  current = next;
}

function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } });
}

const before = globalThis.fetch;

globalThis.fetch = (input: RequestInfo | URL, init?: RequestInit): Promise<Response> => {
  const request = input instanceof Request ? input : new Request(input, init);
  const url = new URL(request.url, window.location.href);
  if (/^\/api\/console\/companies\/[^/]+\/exports$/.test(url.pathname)) {
    if (request.method === 'GET') return Promise.resolve(json(current.exports));
    if (request.method === 'POST') {
      if (current.refuse) return Promise.resolve(json({ code: current.refuse.code, message: `${current.refuse.code} (server text).` }, current.refuse.status));
      return Promise.resolve(json(companyExport({ state: 'RUNNING', finishedAt: null, size: null }), 202));
    }
  }
  return before(input, init);
};
