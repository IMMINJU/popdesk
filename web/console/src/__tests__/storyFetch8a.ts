// The SLA policy answers for the slice 8a screen stories (8a 묶음 screens.md 5절). storyFetch.ts answers
// the rest of the console API (the issue list and the issue carry `sla` from its scenario); this file
// wraps the `fetch` in place and answers reading (`getSlaPolicy`) and saving (`updateSlaPolicy`) the
// policy from a scenario the story sets.
//
// Import this file before any module that reaches `src/api.ts`: openapi-fetch keeps the `fetch`
// it finds when the client is made.
import type { Schemas } from '../api';

type SlaPolicy = Schemas['SlaPolicy'];
type SlaTarget = Schemas['SlaTarget'];
type SlaHours = Schemas['SlaHours'];
type SlaGoal = Schemas['SlaGoal'];
type Person = Schemas['Person'];

const CHOI: Person = { accountId: 1, name: '최관리', role: 'ADMIN', photoUpdatedAt: null };
const PRIORITIES: SlaTarget['priority'][] = ['LOW', 'NORMAL', 'HIGH', 'URGENT'];
const WEEKDAYS: SlaHours['weekday'][] = ['MONDAY', 'TUESDAY', 'WEDNESDAY', 'THURSDAY', 'FRIDAY', 'SATURDAY', 'SUNDAY'];

/** The policy of an installation that never set one (S8A-AC-01). */
export function offPolicy(): SlaPolicy {
  return {
    version: 1,
    targets: PRIORITIES.map((priority) => ({ priority, firstResponseMinutes: null, resolutionMinutes: null })),
    hours: WEEKDAYS.map((weekday) => ({ weekday, opens: null, closes: null })),
    holidays: [],
    timeZone: 'Asia/Seoul',
    changedAt: '2026-10-08T03:15:00Z',
    changedBy: null,
  };
}

/** Goals for every priority (the longest 525,600 minutes), weekdays 09:00–18:00, Friday to midnight, holidays. */
export function onPolicy(over: Partial<SlaPolicy> = {}): SlaPolicy {
  return {
    version: 12,
    targets: [
      { priority: 'LOW', firstResponseMinutes: 525600, resolutionMinutes: 525600 },
      { priority: 'NORMAL', firstResponseMinutes: 240, resolutionMinutes: 3120 },
      { priority: 'HIGH', firstResponseMinutes: 60, resolutionMinutes: 480 },
      { priority: 'URGENT', firstResponseMinutes: 20, resolutionMinutes: null },
    ],
    hours: WEEKDAYS.map((weekday) =>
      weekday === 'SATURDAY' || weekday === 'SUNDAY' ? { weekday, opens: null, closes: null } : { weekday, opens: '09:00', closes: weekday === 'FRIDAY' ? '24:00' : '18:00' },
    ),
    holidays: ['2026-10-09', '2026-12-25', '2027-01-01'],
    timeZone: 'America/Argentina/ComodRivadavia',
    changedAt: '2026-10-07T01:00:00Z',
    changedBy: CHOI,
    ...over,
  };
}

/** `n` holidays from 2027-01-01 on, earliest first. */
export function manyHolidays(n: number): string[] {
  const start = Date.parse('2027-01-01T00:00:00Z');
  return Array.from({ length: n }, (_, i) => new Date(start + i * 24 * 60 * 60 * 1000).toISOString().slice(0, 10));
}

/** A goal in `state`; `remainingMinutes` only while it runs. */
export function goal(state: SlaGoal['state'], remainingMinutes: number | null = null): SlaGoal {
  const runs = state === 'OK' || state === 'WARNING' || state === 'BREACHED';
  return { state, dueAt: state === null ? null : '2026-10-11T03:15:00Z', remainingMinutes: runs ? remainingMinutes : null };
}

export interface PolicyScenario {
  policy: SlaPolicy;
  /** A save answers 409 `VERSION_CONFLICT` when true, else 200 with the policy one version up. */
  conflict: boolean;
}

let current: PolicyScenario = { policy: offPolicy(), conflict: false };

/** The policy the next requests are answered from. */
export function setPolicyScenario(next: PolicyScenario): void {
  current = next;
}

function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } });
}

const before = globalThis.fetch;

globalThis.fetch = (input: RequestInfo | URL, init?: RequestInit): Promise<Response> => {
  const request = input instanceof Request ? input : new Request(input, init);
  const url = new URL(request.url, window.location.href);
  if (url.pathname === '/api/console/sla-policy' && request.method === 'GET') return Promise.resolve(json(current.policy));
  if (url.pathname === '/api/console/sla-policy' && request.method === 'PUT') {
    if (current.conflict) return Promise.resolve(json({ code: 'VERSION_CONFLICT', message: 'VERSION_CONFLICT (server text).' }, 409));
    current = { ...current, policy: { ...current.policy, version: current.policy.version + 1 } };
    return Promise.resolve(json(current.policy));
  }
  return before(input, init);
};
