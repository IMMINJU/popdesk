// The member answers for the slice 10a screen stories (10a 묶음 screens.md): the C-10 list with an
// anonymized account and long emails, and the anonymize dialog. storyFetch.ts answers the rest of the
// console API; this file wraps the `fetch` in place and answers the account list (`listAccounts`), the
// system status C-10 reads for SMTP, the open assignments of the anonymize dialog and the anonymize
// request itself from a scenario the story sets.
//
// Import this file before any module that reaches `src/api.ts`: openapi-fetch keeps the `fetch`
// it finds when the client is made.
import type { Schemas } from '../api';
import { T0 } from './storyFetch';

type Account = Schemas['Account'];

/** Fifty Korean characters with spaces: the longest name an account takes. */
export const LONG_PERSON = '에이크미 글로벌 고객 지원 센터 서울 본사 결제 장애 대응 담당 책임 상담원 이름 오십자'.padEnd(50, '끝').slice(0, 50);
/** A long email without a place to break. */
export const LONG_EMAIL = 'customer.success.escalation.team.lead.for.payments@acme-global-enterprise-holdings.example.com';

export function member(over: Partial<Account> = {}): Account {
  return { id: 2, email: 'lee@popdesk.test', name: '이상담', role: 'AGENT', status: 'ACTIVE', createdAt: T0, photoUpdatedAt: null, anonymized: false, ...over };
}

/** An anonymized account as the server gives it. */
export function anonymized(id: number): Account {
  return member({ id, name: '삭제된 사용자', email: `deleted-${String(id)}@invalid`, role: 'CUSTOMER', status: 'SUSPENDED', anonymized: true });
}

export interface MemberScenario {
  accounts: Account[];
  /** What `open-assignments` answers for every account. */
  open: { issues: number; tasks: number };
  /** The code `anonymizeAccount` refuses with (400 or 409), or null for 200. */
  refuse: { code: string; status: number } | null;
}

let current: MemberScenario = { accounts: [], open: { issues: 0, tasks: 0 }, refuse: null };

/** The members the next requests are answered from. */
export function setMemberScenario(next: MemberScenario): void {
  current = next;
}

function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } });
}

const channel = { enabled: true, failed: 0, queued: 0, lastSuccessAt: T0 };

const before = globalThis.fetch;

globalThis.fetch = (input: RequestInfo | URL, init?: RequestInit): Promise<Response> => {
  const request = input instanceof Request ? input : new Request(input, init);
  const url = new URL(request.url, window.location.href);
  const get = request.method === 'GET';
  if (get && url.pathname === '/api/console/accounts') {
    const items = current.accounts;
    return Promise.resolve(json({ items, page: 1, size: 20, totalElements: items.length, totalPages: items.length === 0 ? 0 : 1 }));
  }
  if (get && url.pathname === '/api/console/system/status') {
    return Promise.resolve(
      json({ mail: channel, webhook: channel, auditForward: { ...channel, enabled: false, lastSuccessAt: null }, sandbox: false, sandboxMailToSet: false, cleanupJobs: [] }),
    );
  }
  if (get && /^\/api\/console\/accounts\/\d+\/open-assignments$/.test(url.pathname)) return Promise.resolve(json(current.open));
  const anonymize = /^\/api\/console\/accounts\/(\d+)\/anonymize$/.exec(url.pathname);
  if (anonymize && request.method === 'POST') {
    if (current.refuse) return Promise.resolve(json({ code: current.refuse.code, message: `${current.refuse.code} (server text).` }, current.refuse.status));
    const id = Number(anonymize[1]);
    return Promise.resolve(json({ ...anonymized(id), cleared: { issues: 0 } }));
  }
  return before(input, init);
};
