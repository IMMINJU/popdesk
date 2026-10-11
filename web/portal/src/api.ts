import { createPortalClient, type PortalComponents } from '@popdesk/api/portal';

export const api = createPortalClient();

export type Schemas = PortalComponents['schemas'];

const CSRF_COOKIE = 'XSRF-TOKEN';
const CSRF_HEADER = 'X-XSRF-TOKEN';
const SAFE_METHODS = new Set(['GET', 'HEAD', 'OPTIONS']);

function readCookie(name: string): string | null {
  for (const part of document.cookie.split(';')) {
    const [key, ...rest] = part.trim().split('=');
    if (key === name) return decodeURIComponent(rest.join('='));
  }
  return null;
}

/**
 * The CSRF token for a request that changes data (conventions.md "Sessions and
 * CSRF"). The server keeps it in a readable cookie; before sign-in
 * `GET /api/public/csrf` sets it.
 */
export async function csrfToken(): Promise<string | null> {
  return readCookie(CSRF_COOKIE) ?? (await newCsrfToken());
}

/** Asks the server for a token even when the cookie holds one, e.g. a stale one. */
async function newCsrfToken(): Promise<string | null> {
  try {
    await fetch(new URL('/api/public/csrf', window.location.href), { credentials: 'same-origin' });
  } catch {
    // The request that needs the token reports the connection problem.
  }
  return readCookie(CSRF_COOKIE);
}

async function isCsrfInvalid(response: Response): Promise<boolean> {
  if (response.status !== 403) return false;
  try {
    const body = (await response.clone().json()) as { code?: unknown };
    return body.code === 'CSRF_TOKEN_INVALID';
  } catch {
    return false;
  }
}

// A copy of each request that changes data, by openapi-fetch request id, until
// its answer comes: the body of the request that was sent cannot be read again.
const copies = new Map<string, Request>();

api.use({
  async onRequest({ request, id }) {
    if (SAFE_METHODS.has(request.method)) return undefined;
    const token = await csrfToken();
    if (token) request.headers.set(CSRF_HEADER, token);
    copies.set(id, request.clone());
    return request;
  },
  /**
   * 403 `CSRF_TOKEN_INVALID`: gets a new token and sends the same request once
   * more (S1A-AC-57). The second answer goes to the screen whatever it is.
   */
  async onResponse({ response, id }) {
    const copy = copies.get(id);
    copies.delete(id);
    if (!copy || !(await isCsrfInvalid(response))) return undefined;
    const token = await newCsrfToken();
    if (token) copy.headers.set(CSRF_HEADER, token);
    return fetch(copy);
  },
  onError({ id }) {
    copies.delete(id);
  },
});
