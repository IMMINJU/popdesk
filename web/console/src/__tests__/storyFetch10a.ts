// The member answers for the slice 10a screen stories (10a 묶음 screens.md): the C-10 list with an
// anonymized account and long emails, and the anonymize dialog. storyFetch.ts answers the rest of the
// console API; this file wraps the `fetch` in place and answers the account list (`listAccounts`), the
// system status C-10 reads for SMTP, the open assignments of the anonymize dialog and the anonymize
// request itself from a scenario the story sets. It also answers the C-12 audit log list, for the row of
// AUDIT_RECHAINED with its two whole hashes (C-7 웹 점검 D5).
//
// Import this file before any module that reaches `src/api.ts`: openapi-fetch keeps the `fetch`
// it finds when the client is made.
import type { Schemas } from '../api';
import { T0 } from './storyFetch';

type Account = Schemas['Account'];
type AuditEntry = Schemas['AuditEntry'];

/** Fifty Korean characters with spaces: the longest name an account takes. */
export const LONG_PERSON = '에이크미 글로벌 고객 지원 센터 서울 본사 결제 장애 대응 담당 책임 상담원 이름 오십자'.padEnd(50, '끝').slice(0, 50);
/** A long email without a place to break. */
export const LONG_EMAIL = 'customer.success.escalation.team.lead.for.payments@acme-global-enterprise-holdings.example.com';

export function member(over: Partial<Account> = {}): Account {
  return { id: 2, email: 'lee@popdesk.test', name: '이상담', role: 'AGENT', status: 'ACTIVE', createdAt: T0, photoUpdatedAt: null, anonymized: false, oidcLinked: false, ...over };
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

/** A hash as the server gives it: 64 lowercase hex characters with no place to break. */
export const hashOf = (seq: number) => seq.toString(16).padStart(8, '0').repeat(8);

/** The C-12 entry of the chain computed again after an anonymization (design.md 0절 12, S10A-AC-18). */
export const RECHAINED: AuditEntry = {
  id: 9302,
  seq: 9302,
  action: 'AUDIT_RECHAINED',
  actor: { accountId: 1, name: '최관리', email: 'admin@popdesk.test' },
  target: { type: 'AUDIT_LOG' },
  changes: { fromSeq: 120, toSeq: 4410, headHash: { from: hashOf(4410), to: 'ab'.repeat(32) } },
  occurredAt: '2026-10-08T03:15:00.123456Z',
  hash: hashOf(9302),
  prevHash: hashOf(9301),
};

let entries: AuditEntry[] = [];

/** The entries C-12 lists next (every page and filter gets them all). */
export function setAuditScenario(next: AuditEntry[]): void {
  entries = next;
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
  if (get && url.pathname === '/api/console/audit-log') {
    return Promise.resolve(json({ items: entries, page: 1, size: 50, totalElements: entries.length, totalPages: entries.length === 0 ? 0 : 1 }));
  }
  if (get && url.pathname === '/api/console/system/status') {
    return Promise.resolve(
      json({ mail: channel, webhook: channel, auditForward: { ...channel, enabled: false, lastSuccessAt: null }, sandbox: false, sandboxMailToSet: false, cleanupJobs: [], oidc: { enabled: false, apps: [], lastSuccessAt: null, providerFailures: 0, lastFailure: null } }),
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
