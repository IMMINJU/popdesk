// A fake server for the portal screen stories of slice b5 (design/slices/b5-english). The stories run
// in a real browser where MSW's node server does not work, so this replaces `fetch` and answers the
// portal API with English samples: a customer whose language is English (`Me.locale: 'en'`,
// B5-AC-01) and long English data, since English runs longer than Korean.
//
// Import this file before any module that reaches `src/api.ts`: openapi-fetch keeps the `fetch` it
// finds when the client is made.
import type { PortalComponents } from '@popdesk/api/portal';

type Schemas = PortalComponents['schemas'];

export const T0 = '2026-10-08T03:15:00Z';

/** Fifty characters: the longest name an account takes, in English words. */
export const LONG_PERSON_EN = 'Alexandra Montgomery-Fitzgerald Wellington-Smythe'.padEnd(50, 'e').slice(0, 50);
/** A long company name, the way English names run. */
export const LONG_COMPANY_EN = 'Acme Global Enterprise Holdings Asia Pacific Customer Support Division Ltd.';
/** A long issue title in English. */
export const LONG_TITLE_EN = 'Payment confirmation emails are not delivered to customers in the Asia Pacific region after the October release';
export const LONG_FILE_EN = 'asia-pacific-payment-confirmation-delivery-failures-october.log';

const ACME: Schemas['Company'] = { code: 'ACME', name: LONG_COMPANY_EN, status: 'ACTIVE', manager: false };

export const ME_EN: Schemas['Me'] = {
  id: 11,
  email: 'customer.success.escalation.team.lead.for.payments@acme-global-enterprise-holdings.example.com',
  name: LONG_PERSON_EN,
  role: 'CUSTOMER',
  locale: 'en',
  photoUpdatedAt: null,
  companies: [ACME],
};

const ME_PERSON: Schemas['Person'] = { accountId: 11, kind: 'CUSTOMER', name: LONG_PERSON_EN, photoUpdatedAt: null };
const SUPPORT_TEAM: Schemas['Person'] = { accountId: null, kind: 'SUPPORT_TEAM', name: null, photoUpdatedAt: null };

export const ISSUE_EN: Schemas['Issue'] = {
  key: 'ACME-123456',
  title: LONG_TITLE_EN,
  content: 'Since the October release, **payment confirmation** emails stopped reaching customers in the Asia Pacific region.',
  status: 'IN_PROGRESS',
  priority: 'URGENT',
  private: false,
  reporter: ME_PERSON,
  companyCode: ACME.code,
  companyName: ACME.name,
  filedBySupport: false,
  resolvedAt: null,
  closedAt: null,
  reopenable: false,
  createdAt: T0,
  updatedAt: T0,
  version: 1,
};

const COMMENTS: Schemas['Comment'][] = [
  { id: 101, author: ME_PERSON, content: 'We checked the spam folders of three customers and the emails are not there either.', createdAt: T0, editedAt: T0, deleted: false, deletedAt: null },
  { id: 102, author: SUPPORT_TEAM, content: 'Thank you. We escalated this to the mail delivery team and are waiting for the provider logs.', createdAt: T0, editedAt: null, deleted: false, deletedAt: null },
  { id: 103, author: ME_PERSON, content: '', createdAt: T0, editedAt: null, deleted: true, deletedAt: T0 },
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
    fileName: LONG_FILE_EN,
    inline: false,
    size: 1_234_567,
    uploadedBy: ME_PERSON,
  },
];

function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } });
}

function paged<T>(items: readonly T[]) {
  return { items, page: 1, size: 20, totalElements: items.length, totalPages: items.length === 0 ? 0 : 1 };
}

const notFound = () => json({ code: 'NOT_FOUND', message: 'Not found.' }, 404);

/** What the fake server answers: by method and path, the rest a page with nothing in it. */
function answer(method: string, path: string): Response {
  if (path === '/api/public/csrf') return new Response(null, { status: 204 });
  if (method === 'GET' && path === '/api/public/branding') return json({ displayName: 'popdesk', colors: null, logos: { light: null, dark: null } });
  if (method === 'GET' && path === '/api/public/sign-in-options') return json({ oidc: false });
  if (method !== 'GET') return notFound();
  if (path === '/api/portal/me') return json(ME_EN);
  if (path === '/api/portal/notifications/unread-count') return json({ count: 0 });
  if (path === '/api/portal/companies') return json(paged([ACME]));
  if (path === '/api/portal/issues') return json(paged([]));
  const key = encodeURIComponent(ISSUE_EN.key);
  if (path === `/api/portal/issues/${key}`) return json(ISSUE_EN);
  if (path === `/api/portal/issues/${key}/comments`) return json(paged(COMMENTS));
  if (path === `/api/portal/issues/${key}/attachments`) return json(paged(ATTACHMENTS));
  if (path.startsWith('/api/portal/issues/')) return notFound();
  return json(paged([]));
}

const realFetch = globalThis.fetch.bind(globalThis);

globalThis.fetch = (input: RequestInfo | URL, init?: RequestInit): Promise<Response> => {
  const request = input instanceof Request ? input : new Request(input, init);
  const url = new URL(request.url, window.location.href);
  if (!url.pathname.startsWith('/api/')) return realFetch(input, init);
  return Promise.resolve(answer(request.method, url.pathname));
};
