// A fake server for the portal screen stories of slice b6 (design/slices/b6-small screens.md 3절). The
// stories run in a real browser where MSW's node server does not work, so this replaces `fetch` and
// answers the portal API for P-3: a customer of two companies (so the company filter shows) whose
// language the story sets, Korean or English (`Me.locale`, b5), and an empty issue list.
//
// Import this file before any module that reaches `src/api.ts`: openapi-fetch keeps the `fetch` it
// finds when the client is made.
import type { PortalComponents } from '@popdesk/api/portal';

type Schemas = PortalComponents['schemas'];
type Language = 'ko' | 'en';

const COMPANIES: Record<Language, Schemas['Company'][]> = {
  ko: [
    { code: 'ACME', name: '에이크미 글로벌 엔터프라이즈 홀딩스', status: 'ACTIVE', manager: false },
    { code: 'BETA', name: '베타 물산', status: 'ACTIVE', manager: false },
  ],
  en: [
    { code: 'ACME', name: 'Acme Global Enterprise Holdings', status: 'ACTIVE', manager: false },
    { code: 'BETA', name: 'Beta Trading', status: 'ACTIVE', manager: false },
  ],
};

function me(language: Language): Schemas['Me'] {
  return {
    id: 11,
    email: 'kim@acme.test',
    name: language === 'en' ? 'Robin Customer' : '김고객',
    role: 'CUSTOMER',
    locale: language,
    photoUpdatedAt: null,
    companies: COMPANIES[language],
  };
}

let language: Language = 'ko';

/** The language of the customer the next requests are answered for. */
export function setLanguage(next: Language): void {
  language = next;
}

function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } });
}

function paged<T>(items: readonly T[]) {
  return { items, page: 1, size: 20, totalElements: items.length, totalPages: items.length === 0 ? 0 : 1 };
}

/** What the fake server answers: by method and path, the rest a page with nothing in it. */
function answer(method: string, path: string): Response {
  if (path === '/api/public/csrf') return new Response(null, { status: 204 });
  if (method === 'GET' && path === '/api/public/branding') return json({ displayName: 'popdesk', colors: null, logos: { light: null, dark: null } });
  if (method === 'GET' && path === '/api/public/sign-in-options') return json({ oidc: false });
  if (method !== 'GET') return json({ code: 'NOT_FOUND', message: 'Not found.' }, 404);
  if (path === '/api/portal/me') return json(me(language));
  if (path === '/api/portal/notifications/unread-count') return json({ count: 0 });
  if (path === '/api/portal/companies') return json(paged(COMPANIES[language]));
  return json(paged([]));
}

const realFetch = globalThis.fetch.bind(globalThis);

globalThis.fetch = (input: RequestInfo | URL, init?: RequestInit): Promise<Response> => {
  const request = input instanceof Request ? input : new Request(input, init);
  const url = new URL(request.url, window.location.href);
  if (!url.pathname.startsWith('/api/')) return realFetch(input, init);
  return Promise.resolve(answer(request.method, url.pathname));
};
