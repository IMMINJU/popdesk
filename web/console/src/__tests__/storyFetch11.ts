// The answers for the slice 11 screen stories (11 묶음 screens.md): C-1 with the OIDC button and a band,
// and the C-18 OIDC part. storyFetch.ts answers the rest of the console API; this file wraps the `fetch`
// in place and answers the sign-in options, the session read (401 with `signedOut`, so C-1 stays) and
// the system status from a scenario the story sets.
//
// Import this file before any module that reaches `src/api.ts`: openapi-fetch keeps the `fetch`
// it finds when the client is made.
import type { Schemas } from '../api';
import { T0 } from './storyFetch';

type OidcStatus = Schemas['OidcStatus'];

export interface OidcScenario {
  /** What `getSignInOptions` answers. */
  signInOidc: boolean;
  /** The session read answers 401. */
  signedOut: boolean;
  /** The `oidc` part of the system status. */
  status: OidcStatus;
}

export const OIDC_OFF: OidcStatus = { enabled: false, apps: [], lastSuccessAt: null, providerFailures: 0, lastFailure: null };

let current: OidcScenario = { signInOidc: false, signedOut: false, status: OIDC_OFF };

/** The answers the next requests get. */
export function setOidcScenario(next: OidcScenario): void {
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
  if (get && url.pathname === '/api/public/sign-in-options') return Promise.resolve(json({ oidc: current.signInOidc }));
  if (get && url.pathname === '/api/console/me' && current.signedOut) return Promise.resolve(json({ code: 'UNAUTHENTICATED', message: 'Sign in first.' }, 401));
  if (get && url.pathname === '/api/console/system/status') {
    return Promise.resolve(
      json({
        mail: channel,
        webhook: channel,
        auditForward: { ...channel, enabled: false, lastSuccessAt: null },
        sandbox: false,
        sandboxMailToSet: false,
        cleanupJobs: [{ name: 'session-cleanup', lastRunAt: T0, deleted: 5 }],
        oidc: current.status,
        mailIn: { enabled: false, offReason: 'NOT_SET', lastSuccessAt: null, roundFailures: 0, lastFailure: null, retrying: 0, quarantined90d: 0 },
      }),
    );
  }
  return before(input, init);
};
