// More test support for slice 1a (design/archive/1a-people/screens.md). `portal.ts`
// stays as it is; this file adds the 1a endpoints to the fake server, in the shape
// of docs/integrate/openapi-portal.json, and the helpers the 1a screen tests share.
import type { PortalComponents } from '@popdesk/api/portal';
import { act } from '@testing-library/react';
import { http, HttpResponse } from 'msw/http';
import { afterEach, beforeEach } from 'vitest';
import { ME, apiError, errors, found, readParts, screen, server, state, type Me } from './portal';

type Schemas = PortalComponents['schemas'];

/** When a photo was last changed: the `v` of its address. */
export const PHOTO_AT = '2026-10-09T01:02:03Z';
export const PHOTO_AT_NEW = '2026-10-10T04:05:06Z';

/** Who is signed in. `portal.ts` always answers `ME`; the 1a handlers answer this. */
export const session: { me: Me } = { me: ME };

export function rejected(code: string, status = 400) {
  return apiError(status, { code, message: `${code} (server text).` });
}

export const csrfInvalid = () => rejected('CSRF_TOKEN_INVALID', 403);

function requireSession(): Response | undefined {
  return state.signedIn ? undefined : errors.unauthenticated();
}

/** What the 1a server does when nothing goes wrong. Tests replace one with `route`. */
const handlers1a = [
  http.post('*/api/public/password-reset/request', () => new HttpResponse(null, { status: 202 })),
  http.post('*/api/public/password-reset/confirm', () => new HttpResponse(null, { status: 204 })),

  http.get('*/api/portal/me', () => requireSession() ?? HttpResponse.json(session.me)),
  http.patch('*/api/portal/me', async ({ request }) => {
    const denied = requireSession();
    if (denied) return denied;
    const body = (await request.json()) as Schemas['UpdateMeRequest'];
    session.me = { ...session.me, ...body };
    return HttpResponse.json(session.me);
  }),
  http.put('*/api/portal/me/password', () => requireSession() ?? new HttpResponse(null, { status: 204 })),
  http.post('*/api/portal/me/photo', async ({ request }) => {
    const denied = requireSession();
    if (denied) return denied;
    await readParts(request);
    session.me = { ...session.me, photoUpdatedAt: PHOTO_AT_NEW };
    return HttpResponse.json(session.me, { status: 201 });
  }),
  http.delete('*/api/portal/me/photo', () => {
    const denied = requireSession();
    if (denied) return denied;
    session.me = { ...session.me, photoUpdatedAt: null };
    return new HttpResponse(null, { status: 204 });
  }),
];

beforeEach(() => {
  session.me = ME;
  server.use(...handlers1a);
});

afterEach(() => {
  // The CSRF cookie a test set (`setCsrfCookie`) must not reach the next test.
  document.cookie = 'XSRF-TOKEN=; expires=Thu, 01 Jan 1970 00:00:00 GMT; path=/';
});

/** Replaces one endpoint for this test. Like `on` of portal.ts, with PUT and PATCH. */
export function route(
  method: 'get' | 'post' | 'put' | 'patch' | 'delete',
  path: string,
  resolver: Parameters<typeof http.get>[1],
) {
  server.use(http[method](`*${path}`, resolver));
}

/** Signs in as `ME` with some fields changed, e.g. a photo or more companies. */
export function signInWith(over: Partial<Me>) {
  session.me = { ...ME, ...over };
}

/** What `Set-Cookie: XSRF-TOKEN=…` from the server does in a browser. */
export function setCsrfCookie(token: string) {
  document.cookie = `XSRF-TOKEN=${token}; path=/`;
}

// ---------------------------------------------------------------------------
// Late answers (S1A-AC-58)

/** Resolves when the fake server has answered `method path`. */
export function answered(method: string, path: string): Promise<void> {
  return new Promise((resolve) => {
    const listener = ({ request }: { request: Request }) => {
      if (request.method === method && new URL(request.url).pathname === path) {
        server.events.removeListener('response:mocked', listener);
        resolve();
      }
    };
    server.events.on('response:mocked', listener);
  });
}

/**
 * Lets an answer already released reach the screen code: two more full round
 * trips, inside `act` so React draws what the answer changed before the test looks.
 */
export async function settle() {
  await act(async () => {
    for (let i = 0; i < 2; i += 1) {
      await fetch(new URL('/api/public/csrf', window.location.href));
    }
  });
}

// ---------------------------------------------------------------------------
// Photos (S1A-AC-60). The screen makes the address: /api/portal/photos/{accountId}?v=<photoUpdatedAt>.

export interface PhotoRequest {
  path: string;
  v: string | null;
}

/** The profile photos drawn inside `root`, as the path and the `v` query of each. */
export function photosIn(root: Element): PhotoRequest[] {
  return Array.from(root.querySelectorAll('img'))
    .map((img) => new URL(img.getAttribute('src') ?? '', window.location.href))
    .filter((url) => url.pathname.startsWith('/api/portal/photos/'))
    .map((url) => ({ path: url.pathname, v: url.searchParams.get('v') }));
}

export function photoOf(accountId: number, updatedAt: string): PhotoRequest {
  return { path: `/api/portal/photos/${String(accountId)}`, v: updatedAt };
}

export async function region(name: string | RegExp): Promise<HTMLElement> {
  return found(() => screen.queryByRole('region', { name }), `구역 "${String(name)}"`);
}
