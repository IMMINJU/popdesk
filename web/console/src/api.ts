import { createConsoleClient, type ConsoleComponents } from '@popdesk/api/console';

export const api = createConsoleClient();

export type Schemas = ConsoleComponents['schemas'];

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
  const current = readCookie(CSRF_COOKIE);
  if (current) return current;
  try {
    await fetch(new URL('/api/public/csrf', window.location.href), { credentials: 'same-origin' });
  } catch {
    // The request that needs the token reports the connection problem.
  }
  return readCookie(CSRF_COOKIE);
}

api.use({
  async onRequest({ request }) {
    if (SAFE_METHODS.has(request.method)) return undefined;
    const token = await csrfToken();
    if (token) request.headers.set(CSRF_HEADER, token);
    return request;
  },
});
