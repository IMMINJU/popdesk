// The C-9 customer answers for the slice b6 screen stories (design/archive/b6-small screens.md 1절): the
// customers of a company with a 67-character email, a long name, an anonymized account and rows with and
// without the company manager mark. storyFetch.ts answers the rest of the console API; this file wraps the
// `fetch` in place and answers the customer list (`listCompanyCustomers`) from a scenario the story sets.
//
// Import this file before any module that reaches `src/api.ts`: openapi-fetch keeps the `fetch`
// it finds when the client is made.
import type { Schemas } from '../api';
import { ADMIN } from './storyFetch';

type Member = Schemas['CompanyMember'];

/** Sixty-seven characters with no place to break but the dots (B6-AC-01). */
export const EMAIL_67 = 'customer.success.escalation.lead@acme-global-enterprise.example.com';
/** The name the server puts in when it anonymizes an account, in every language (B6-AC-02). */
export const ANONYMIZED_NAME = '삭제된 사용자';

/** An English admin (`Me.locale: 'en'`, b5). */
export const ADMIN_EN: Schemas['Me'] = { ...ADMIN, name: 'Morgan Admin', locale: 'en' };

export function customer(over: Partial<Member> = {}): Member {
  return { accountId: 11, email: 'kim@acme.test', name: '김고객', status: 'ACTIVE', photoUpdatedAt: null, manager: false, anonymized: false, ...over };
}

/** An anonymized customer as the server gives it: `SUSPENDED`, the fixed name and an address that goes nowhere. */
export function anonymizedCustomer(accountId: number, manager = false): Member {
  return customer({ accountId, name: ANONYMIZED_NAME, email: `deleted-${String(accountId)}@invalid`, status: 'SUSPENDED', anonymized: true, manager });
}

let customers: Member[] = [];

/** The customers every company lists next. */
export function setCustomers(next: Member[]): void {
  customers = next;
}

function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } });
}

const before = globalThis.fetch;

globalThis.fetch = (input: RequestInfo | URL, init?: RequestInit): Promise<Response> => {
  const request = input instanceof Request ? input : new Request(input, init);
  const url = new URL(request.url, window.location.href);
  if (request.method === 'GET' && /^\/api\/console\/companies\/[^/]+\/customers$/.test(url.pathname)) {
    return Promise.resolve(json({ items: customers, page: 1, size: 20, totalElements: customers.length, totalPages: customers.length === 0 ? 0 : 1 }));
  }
  return before(input, init);
};
