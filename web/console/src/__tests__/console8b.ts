// More test support for slice 8b (design/archive/8b-sla-alerts/screens.md): the SLA items of C-7 and
// the SLA period and columns of C-5. The earlier support files stay as they are; this file adds the
// `days` of the statistics endpoint to the fake server, in the shape of
// docs/integrate/openapi-console.json, and the helpers the slice 8b screen tests share.
//
// Every answer made here goes through `contract` of console6a.ts: a fixture that does not fit the
// contract file fails the test that used it.
import type { ConsoleComponents } from '@popdesk/api/console';
import { HttpResponse, http } from 'msw/http';
import { beforeEach, expect } from 'vitest';
import { errors, on, paged, screen, server, state, text, type Call } from './console';
import { NOTIFICATIONS, alertItem, notification, type Notification, type NotificationKind } from './console4';
import { contract } from './console6a';
import { STATS, cellOf, stat, statsTableNow, type CompanyStat, type Column } from './console7b';

type Schemas = ConsoleComponents['schemas'];
export type SlaGoalStat = Schemas['SlaGoalStat'];
export type CompanySlaStat = Schemas['CompanySlaStat'];
export type SlaSummary = Schemas['SlaSummary'];

// ---------------------------------------------------------------------------
// C-7 (screens.md 1절)

/** The four kinds about one issue, with their sentence. */
export const SLA_ISSUE_KINDS = [
  { kind: 'SLA_RESPONSE_WARNING', says: '첫 응답 기한을 곧 넘깁니다' },
  { kind: 'SLA_RESPONSE_BREACHED', says: '첫 응답 기한을 넘겼습니다' },
  { kind: 'SLA_RESOLUTION_WARNING', says: '해결 기한을 곧 넘깁니다' },
  { kind: 'SLA_RESOLUTION_BREACHED', says: '해결 기한을 넘겼습니다' },
] as const satisfies readonly { kind: NotificationKind; says: string }[];

export const RECALCULATED = 'SLA 기한을 다시 계산했습니다';

/** An SLA item about ACME-7 as the server makes it: no actor, no comment (S8B-AC-03). */
export function slaItem(kind: NotificationKind, over: Partial<Notification> = {}): Notification {
  return notification({ kind, actor: null, commentId: null, ...over });
}

/** The summary item of a recalculation: about no issue, for admins (S8B-AC-14, S8B-AC-15). */
export function summaryItem(slaSummary: SlaSummary, over: Partial<Notification> = {}): Notification {
  return alertItem('SLA_RECALCULATED', { slaSummary, ...over });
}

/** The inbox answers `items`, through the contract check. */
export function inboxOf(items: Notification[]): void {
  on('get', NOTIFICATIONS, () => HttpResponse.json(contract('listNotifications', 200, paged(items))));
}

// ---------------------------------------------------------------------------
// C-5 (screens.md 2절)

export const PERIOD = 'SLA 기간';
export const PERIODS = { 7: '최근 7일', 30: '최근 30일', 90: '최근 90일' } as const;
export const SLA_COLUMNS = ['첫 응답 SLA', '해결 SLA'] as const;
export const SLA_NOTICE = 'SLA 칸은 고른 기간에 멈춘 목표만 셉니다. 시간은 업무 시간입니다.';
export type SlaColumn = (typeof SLA_COLUMNS)[number];

export function goal(medianMinutes: number | null, met: number, missed: number): SlaGoalStat {
  return { medianMinutes, met, missed };
}

/** `stat()` of console7b.ts with the SLA columns of a policy that is on. */
export function slaStat(sla: CompanySlaStat, over: Partial<CompanyStat> = {}): CompanyStat {
  return stat({ ...over, sla });
}

/** State the slice 8b handler reads. Tests change it before rendering. */
export const s8b: {
  /** The items `getCompanyStats` gives for each `days`; a missing `days` is 30. */
  byDays: Record<'7' | '30' | '90', CompanyStat[]>;
} = { byDays: { 7: [], 30: [], 90: [] } };

/** The `days` of a statistics request: its query, 30 without one (the contract's default). */
export function daysOf(url: string): string {
  return new URL(url).searchParams.get('days') ?? '30';
}

/** The `days` of every statistics request so far, in order. */
export function daysSent(calls: readonly Call[]): string[] {
  return calls.filter((c) => c.method === 'GET' && c.path === STATS).map((c) => daysOf(c.url));
}

/** The 200 of `getCompanyStats` for the request's `days`, through the contract check; 400 for another value. */
export function statsFor(request: Request): Response {
  const days = daysOf(request.url);
  if (days !== '7' && days !== '30' && days !== '90') return errors.invalid([{ field: 'days', code: 'UNKNOWN_VALUE' }]);
  return HttpResponse.json(contract('getCompanyStats', 200, { items: s8b.byDays[days] }));
}

const handlers8b = [http.get(`*${STATS}`, ({ request }) => (state.signedIn ? statsFor(request) : errors.unauthenticated()))];

beforeEach(() => {
  s8b.byDays = { 7: [], 30: [], 90: [] };
  server.use(...handlers8b);
});

/** The "SLA 기간" select of C-5, or null now. */
export function periodNow(): HTMLSelectElement | null {
  return screen.queryByRole<HTMLSelectElement>('combobox', { name: PERIOD });
}

/** The `days` the address holds now, or null. */
export function addressDays(): string | null {
  return new URLSearchParams(window.location.search).get('days');
}

/**
 * The lines of a cell: the parts of its two-line box (`pd-two-lines`, screens.md 2절), or its text
 * as one line.
 */
export function linesOf(cell: HTMLElement): string[] {
  const box = cell.querySelector('.pd-two-lines');
  if (box === null) return [text(cell)];
  return Array.from(box.children)
    .map((el) => text(el))
    .filter((line) => line !== '');
}

/** The cell of `row` under an SLA column or a 7b column. */
export function slaCellOf(row: HTMLElement, column: SlaColumn | Column): HTMLElement {
  return cellOf(row, column as Column);
}

/**
 * The box around the C-5 table, inside `main`, that says it is busy, or null. `main` itself is left
 * out: the app marks it busy for its own reasons (`useMainBusy`).
 */
export function busyAroundTable(): Element | null {
  const table = statsTableNow();
  expect(table, 'C-5의 표').not.toBeNull();
  for (let el = table?.parentElement ?? null; el !== null && el.tagName !== 'MAIN'; el = el.parentElement) {
    if (el.getAttribute('aria-busy') === 'true') return el;
  }
  return null;
}
