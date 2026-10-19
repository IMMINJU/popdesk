// The answers for the slice 12 screen stories (12 묶음 screens.md): the C-18 part "메일 받기". The earlier
// story files answer the rest of the console API; this file wraps the `fetch` in place and gives the
// system status the `mailIn` part a story sets, keeping the rest of the status as the earlier answer has it.
//
// Import this file after storyFetch.ts (and storyFetch11.ts when a story uses it) and before any module
// that reaches `src/api.ts`: openapi-fetch keeps the `fetch` it finds when the client is made.
import type { Schemas } from '../api';

type MailInStatus = Schemas['MailInStatus'];

export const MAIL_IN_OFF: MailInStatus = {
  enabled: false,
  offReason: 'NOT_SET',
  lastSuccessAt: null,
  roundFailures: 0,
  lastFailure: null,
  retrying: 0,
  quarantined90d: 0,
};

let current: MailInStatus = MAIL_IN_OFF;

/** The `mailIn` part the next status reads get. */
export function setMailInScenario(next: MailInStatus): void {
  current = next;
}

const before = globalThis.fetch;

globalThis.fetch = async (input: RequestInfo | URL, init?: RequestInit): Promise<Response> => {
  const request = input instanceof Request ? input : new Request(input, init);
  const url = new URL(request.url, window.location.href);
  const answer = await before(input, init);
  if (request.method !== 'GET' || url.pathname !== '/api/console/system/status' || !answer.ok) return answer;
  const status = (await answer.json()) as Record<string, unknown>;
  return new Response(JSON.stringify({ ...status, mailIn: current }), { status: 200, headers: { 'Content-Type': 'application/json' } });
};
