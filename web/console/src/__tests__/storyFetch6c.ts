// The link answers for the slice 6c screen stories (6c 묶음 screens.md 4절, S6C-AC-56). storyFetch.ts
// answers the rest of the console API; this file wraps the `fetch` in place and answers the links of
// an issue (`IssueLinks`), making a link, and reading the other issues "문의 잇기" finds, from a
// scenario the story sets.
//
// Import this file before any module that reaches `src/api.ts`: openapi-fetch keeps the `fetch`
// it finds when the client is made.
import { T0 } from './storyFetch';
import type { Schemas } from '../api';

type Issue = Schemas['Issue'];
type IssueLink = Schemas['IssueLink'];
type LinkedIssue = Schemas['LinkedIssue'];
type Person = Schemas['Person'];

export const LEE: Person = { accountId: 2, name: '이상담', role: 'AGENT', photoUpdatedAt: null };

/** A company name as long as the screens are likely to meet. */
export const LONG_COMPANY = '에이크미 글로벌 결제 서비스 아시아 태평양 지역 본부 주식회사';

export function linked(over: Partial<LinkedIssue> = {}): LinkedIssue {
  return { key: 'BETA-3', title: '결제가 두 번 됩니다', status: 'IN_PROGRESS', companyCode: 'BETA', companyName: '베타상사', ...over };
}

export function link(over: Partial<IssueLink> = {}): IssueLink {
  return { type: 'RELATED', issue: linked(), createdBy: LEE, createdAt: T0, ...over };
}

export interface LinkScenario {
  /** The links of the issue the story shows. */
  links: IssueLink[];
  /** The issues "찾기" finds, besides the one shown. */
  others: Issue[];
  /** What making a link answers: an error code and status, or null for 201. */
  linkError: { status: number; code: string } | null;
}

let current: LinkScenario = { links: [], others: [], linkError: null };

/** The links the next requests are answered from. */
export function setLinkScenario(next: LinkScenario): void {
  current = next;
}

function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } });
}

const before = globalThis.fetch;

globalThis.fetch = (input: RequestInfo | URL, init?: RequestInit): Promise<Response> => {
  const request = input instanceof Request ? input : new Request(input, init);
  const url = new URL(request.url, window.location.href);
  if (/^\/api\/console\/issues\/[^/]+\/links$/.test(url.pathname)) {
    if (request.method === 'GET') return Promise.resolve(json({ items: current.links }));
    if (request.method === 'POST') {
      const error = current.linkError;
      return Promise.resolve(error ? json({ code: error.code, message: `${error.code} (server text).` }, error.status) : json(link(), 201));
    }
  }
  const one = /^\/api\/console\/issues\/([^/]+)$/.exec(url.pathname);
  if (request.method === 'GET' && one) {
    const other = current.others.find((i) => i.key === decodeURIComponent(one[1] ?? ''));
    if (other) return Promise.resolve(json(other));
  }
  return before(input, init);
};
