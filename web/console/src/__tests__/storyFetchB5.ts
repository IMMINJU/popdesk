// The English answers for the slice b5 screen stories (design/slices/b5-english): an admin whose
// language is English (`Me.locale: 'en'`, B5-AC-01) and long English data, since English runs longer
// than Korean. storyFetch.ts answers the rest of the console API; this file wraps the `fetch` in place
// and answers the comments and attachments of C-4, the customers and agents of C-9, the C-12 audit
// log and the C-18 system status from fixed English samples.
//
// Import this file after storyFetch.ts and before any module that reaches `src/api.ts`: openapi-fetch
// keeps the `fetch` it finds when the client is made.
import type { Schemas } from '../api';
import { T0, company, issue, scenario, summary, type Scenario } from './storyFetch';

type Person = Schemas['Person'];

/** Fifty characters: the longest name an account takes, in English words. */
export const LONG_PERSON_EN = 'Alexandra Montgomery-Fitzgerald Wellington-Smythe'.padEnd(50, 'e').slice(0, 50);
/** A long company name, the way English names run. */
export const LONG_COMPANY_EN = 'Acme Global Enterprise Holdings Asia Pacific Customer Support Division Ltd.';
/** A long issue title in English. */
export const LONG_TITLE_EN = 'Payment confirmation emails are not delivered to customers in the Asia Pacific region after the October release';
/** A long email without a place to break. */
export const LONG_EMAIL_EN = 'customer.success.escalation.team.lead.for.payments@acme-global-enterprise-holdings.example.com';

export const ADMIN_EN: Schemas['Me'] = { id: 1, email: 'admin@popdesk.test', name: 'Morgan Admin', role: 'ADMIN', locale: 'en', photoUpdatedAt: null, companies: [] };

const CUSTOMER: Person = { accountId: 11, name: LONG_PERSON_EN, role: 'CUSTOMER', photoUpdatedAt: null };
const AGENT: Person = { accountId: 2, name: 'Jordan Agent', role: 'AGENT', photoUpdatedAt: null };

/** The story scenario of storyFetch.ts, in English: one issue with a long title in a company with a long name. */
export function englishScenario(): Scenario {
  const one = issue({
    title: LONG_TITLE_EN,
    content: 'Since the October release, **payment confirmation** emails stopped reaching customers in the Asia Pacific region.',
    reporter: CUSTOMER,
    assignee: AGENT,
    companyName: LONG_COMPANY_EN,
  });
  return scenario({ me: ADMIN_EN, companies: [company({ name: LONG_COMPANY_EN, legalName: `${LONG_COMPANY_EN} Incorporated` })], issue: one, issues: [summary(one)], labels: { ACME: [] } });
}

const COMMENTS: Schemas['Comment'][] = [
  { id: 101, author: CUSTOMER, content: 'We checked the spam folders of three customers and the emails are not there either.', createdAt: T0, internal: false, editedAt: null, deleted: false, deletedAt: null, deletedBy: null, mentions: [] },
  { id: 102, author: AGENT, content: 'Escalated to the mail delivery team; waiting for the provider logs.', createdAt: T0, internal: true, editedAt: T0, deleted: false, deletedAt: null, deletedBy: null, mentions: [] },
];

const ATTACHMENTS: Schemas['Attachment'][] = [
  {
    id: '7f1c2a9e-0c4b-4d8e-9a51-3b2f6c1d0e01',
    commentId: null,
    contentType: 'text/plain',
    createdAt: T0,
    deleted: false,
    deletedAt: null,
    deletedBy: null,
    fileName: 'asia-pacific-payment-confirmation-delivery-failures-october.log',
    inline: false,
    internal: false,
    size: 1_234_567,
    uploadedBy: CUSTOMER,
  },
];

function member(accountId: number, name: string, email: string, manager = false): Schemas['CompanyMember'] {
  return { accountId, email, name, status: 'ACTIVE', photoUpdatedAt: null, manager, anonymized: false };
}

const CUSTOMERS = [member(11, LONG_PERSON_EN, LONG_EMAIL_EN, true), member(12, 'Park Colleague', 'park@acme.test')];
const AGENTS = [member(2, 'Jordan Agent', 'jordan@popdesk.test'), member(3, LONG_PERSON_EN, 'escalations@popdesk.test')];

const INT32_MAX = 2_147_483_647;
const hashOf = (seq: number) => seq.toString(16).padStart(8, '0').repeat(8);

function auditEntry(id: number, action: string, target: Record<string, unknown> | null): Schemas['AuditEntry'] {
  return {
    id,
    seq: id,
    action,
    actor: { accountId: 31, name: LONG_PERSON_EN, email: LONG_EMAIL_EN },
    target,
    changes: null,
    occurredAt: '2026-12-31T14:59:59.123456Z',
    hash: hashOf(id),
    prevHash: hashOf(id - 1),
  };
}

/** Kinds whose English names run long, and targets with large numbers. */
export const AUDIT_EN: Schemas['AuditEntry'][] = [
  auditEntry(9501, 'SIGN_IN_SUCCEEDED', { type: 'ACCOUNT', accountId: INT32_MAX }),
  auditEntry(9502, 'OIDC_UNLINKED', { type: 'ACCOUNT', accountId: INT32_MAX }),
  auditEntry(9503, 'ISSUE_PURGED', { type: 'COMPANY', code: 'ACME', issueKey: 'ACME-123456' }),
  auditEntry(9504, 'COMPANY_MANAGER_GRANTED', { type: 'COMPANY', code: 'ACME', accountId: INT32_MAX }),
  auditEntry(9505, 'MEMBERSHIP_REQUEST_REJECTED', { type: 'COMPANY', code: 'ACME', accountId: INT32_MAX }),
];

const channel = (over: Partial<Schemas['ChannelStatus']> = {}): Schemas['ChannelStatus'] => ({ enabled: true, failed: 0, queued: 0, lastSuccessAt: T0, ...over });

/** C-18 with every part filled: failures, cleanup jobs with large counts, OIDC and receiving email on. */
export const STATUS_EN: Schemas['SystemStatus'] = {
  mail: channel({ failed: INT32_MAX, queued: INT32_MAX, lastSuccessAt: '2026-12-31T14:59:59Z' }),
  webhook: channel({ failed: 3 }),
  auditForward: channel({ enabled: false, lastSuccessAt: null }),
  sandbox: false,
  sandboxMailToSet: false,
  cleanupJobs: [
    { name: 'attachment-cleanup', lastRunAt: '2026-12-31T14:59:59Z', deleted: INT32_MAX },
    { name: 'idempotency-key-cleanup', lastRunAt: null, deleted: null },
  ],
  oidc: { enabled: true, apps: ['CONSOLE', 'PORTAL'], lastSuccessAt: '2026-12-31T14:59:59Z', providerFailures: INT32_MAX, lastFailure: { kind: 'DISCOVERY', at: '2026-12-31T14:58:47Z' } },
  mailIn: { enabled: true, offReason: null, lastSuccessAt: '2026-12-31T14:59:59Z', roundFailures: INT32_MAX, lastFailure: { kind: 'UNSUPPORTED', at: '2026-12-31T14:58:47Z' }, retrying: INT32_MAX, quarantined90d: INT32_MAX },
};

const FAILED: Schemas['FailedDelivery'][] = [
  { id: 501, channel: 'MAIL', createdAt: T0, lastErrorKind: 'CONNECTION_REFUSED', recipientAccountId: INT32_MAX, auditEntryId: null },
  { id: 502, channel: 'WEBHOOK', createdAt: T0, lastErrorKind: 'TIMEOUT', recipientAccountId: null, auditEntryId: null },
];

function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } });
}

function paged<T>(items: readonly T[]) {
  return { items, page: 1, size: 20, totalElements: items.length, totalPages: items.length === 0 ? 0 : 1 };
}

const before = globalThis.fetch;

globalThis.fetch = (input: RequestInfo | URL, init?: RequestInit): Promise<Response> => {
  const request = input instanceof Request ? input : new Request(input, init);
  const path = new URL(request.url, window.location.href).pathname;
  if (request.method !== 'GET') return before(input, init);
  if (/^\/api\/console\/issues\/[^/]+\/comments$/.test(path)) return Promise.resolve(json(paged(COMMENTS)));
  if (/^\/api\/console\/issues\/[^/]+\/attachments$/.test(path)) return Promise.resolve(json(paged(ATTACHMENTS)));
  if (/^\/api\/console\/companies\/[^/]+\/customers$/.test(path)) return Promise.resolve(json(paged(CUSTOMERS)));
  if (/^\/api\/console\/companies\/[^/]+\/agents$/.test(path)) return Promise.resolve(json(paged(AGENTS)));
  if (path === '/api/console/audit-log') return Promise.resolve(json({ ...paged(AUDIT_EN), size: 50 }));
  if (path === '/api/console/system/status') return Promise.resolve(json(STATUS_EN));
  if (path === '/api/console/system/failed-deliveries') return Promise.resolve(json(paged(FAILED)));
  return before(input, init);
};
