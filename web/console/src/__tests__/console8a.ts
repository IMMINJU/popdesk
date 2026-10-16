// More test support for slice 8a (design/slices/8a-sla/screens.md): the SLA policy screen C-13, the
// SLA column, filter and sort of C-3, the SLA part of C-4 and the audit words of C-12. The earlier
// support files stay as they are; this file adds the policy endpoints to the fake server, in the
// shape of docs/integrate/openapi-console.json, and the helpers the slice 8a screen tests share.
//
// Every answer made here goes through `contract` of console6a.ts: a fixture that does not fit the
// contract file fails the test that used it.
import type { ConsoleComponents } from '@popdesk/api/console';
import { waitFor } from '@testing-library/react';
import { HttpResponse, http } from 'msw/http';
import { beforeEach, expect } from 'vitest';
import { ADMIN_PERSON, T0, errors, found, issue, screen, server, spokenText, state, summary, text, within, type Issue, type IssueSummary } from './console';
import { contract, rejectedWith } from './console6a';

type Schemas = ConsoleComponents['schemas'];
export type SlaPolicy = Schemas['SlaPolicy'];
export type SlaTarget = Schemas['SlaTarget'];
export type SlaHours = Schemas['SlaHours'];
export type ChangeSlaPolicyRequest = Schemas['ChangeSlaPolicyRequest'];
export type IssueSla = Schemas['IssueSla'];
export type SlaGoal = Schemas['SlaGoal'];
export type Priority = SlaTarget['priority'];
export type Weekday = SlaHours['weekday'];

export const URL_SLA = '/sla';
export const SLA_POLICY = '/api/console/sla-policy';

export const PRIORITIES: readonly Priority[] = ['LOW', 'NORMAL', 'HIGH', 'URGENT'];
export const PRIORITY_WORDS: Readonly<Record<Priority, string>> = { LOW: '낮음', NORMAL: '보통', HIGH: '높음', URGENT: '긴급' };
export const WEEKDAYS: readonly Weekday[] = ['MONDAY', 'TUESDAY', 'WEDNESDAY', 'THURSDAY', 'FRIDAY', 'SATURDAY', 'SUNDAY'];
/** The weekday as the screen names it: "월" or "월요일" (screens.md 1절 writes "{요일}"). */
export const WEEKDAY_WORDS: Readonly<Record<Weekday, string>> = {
  MONDAY: '월',
  TUESDAY: '화',
  WEDNESDAY: '수',
  THURSDAY: '목',
  FRIDAY: '금',
  SATURDAY: '토',
  SUNDAY: '일',
};

// screens.md 1절의 문구
export const TITLE = 'SLA 설정';
export const NOTICE = '목표를 하나라도 정하면 그때부터 올라오는 문의에 기한이 붙습니다. 이미 있는 문의에는 붙지 않습니다.';
export const OFF = 'SLA가 꺼져 있습니다.';
export const ON = 'SLA가 켜져 있습니다.';
export const BY_SYSTEM = '시간대가 바뀌어 시스템이 바꿈';
export const ALL_DAY = '업무일을 하나도 고르지 않으면 하루 24시간으로 셉니다.';
export const HOLIDAY_LIMIT = '쉬는 날은 500개까지입니다.';
export const NOTHING_CHANGED = '바뀐 것이 없습니다.';
export const SAVED = 'SLA 정책을 저장했습니다. 열린 문의의 기한을 다시 계산합니다.';
export const OUT_OF_RANGE = '1분에서 525,600분 사이로 정합니다.';
export const TOO_SHORT = '해결 목표는 첫 응답 목표보다 짧을 수 없습니다.';
export const END_BEFORE_START = '끝 시각은 시작 시각보다 늦어야 합니다.';
export const DAY_TAKEN = '이미 있는 날짜입니다.';
export const CONFLICT = '다른 관리자가 먼저 바꿨습니다. 다시 읽은 뒤 고쳐 주세요.';
export const RELOAD = '다시 불러오기';
export const READ_FAILED = 'SLA 정책을 읽지 못했습니다. 다시 해 보세요.';

/** "시간대: {timeZone}(설정 파일의 `POPDESK_TIMEZONE`)": the backticks of screens.md are markup, not text. */
export function zoneLine(zone: string): RegExp {
  return new RegExp(`시간대: ${zone.replace('/', '\\/')} ?\\(설정 파일의 \`?POPDESK_TIMEZONE\`?\\)`);
}

// ---------------------------------------------------------------------------
// Fixtures

export function target(priority: Priority, firstResponseMinutes: number | null, resolutionMinutes: number | null): SlaTarget {
  return { priority, firstResponseMinutes, resolutionMinutes };
}

export function hours(weekday: Weekday, opens: string | null, closes: string | null): SlaHours {
  return { weekday, opens, closes };
}

/** The policy of an installation that never set one: version 1, nothing set, made by popdesk (S8A-AC-01). */
export function offPolicy(over: Partial<SlaPolicy> = {}): SlaPolicy {
  return {
    version: 1,
    targets: PRIORITIES.map((p) => target(p, null, null)),
    hours: WEEKDAYS.map((d) => hours(d, null, null)),
    holidays: [],
    timeZone: 'Asia/Seoul',
    changedAt: T0,
    changedBy: null,
    ...over,
  };
}

/** Goals for every priority, weekdays 09:00–18:00, one holiday: version 3 by 최관리. */
export function onPolicy(over: Partial<SlaPolicy> = {}): SlaPolicy {
  return {
    version: 3,
    targets: [target('LOW', 480, 2880), target('NORMAL', 240, 1440), target('HIGH', 60, 480), target('URGENT', 20, 240)],
    hours: WEEKDAYS.map((d) => (d === 'SATURDAY' || d === 'SUNDAY' ? hours(d, null, null) : hours(d, '09:00', '18:00'))),
    holidays: ['2026-12-25'],
    timeZone: 'Asia/Seoul',
    changedAt: '2026-10-07T01:00:00Z',
    changedBy: ADMIN_PERSON,
    ...over,
  };
}

/** `n` holidays from 2027-01-01 on, earliest first. */
export function manyHolidays(n: number): string[] {
  const start = Date.parse('2027-01-01T00:00:00Z');
  return Array.from({ length: n }, (_, i) => new Date(start + i * 24 * 60 * 60 * 1000).toISOString().slice(0, 10));
}

/** A goal with no target for the issue's priority (state null). */
export const NO_GOAL: SlaGoal = { state: null, dueAt: null, remainingMinutes: null };

/** A running or stopped goal. `remainingMinutes` is null unless the state runs. */
export function goal(state: NonNullable<SlaGoal['state']>, remainingMinutes: number | null, dueAt = '2026-10-11T03:15:00Z'): SlaGoal {
  return { state, dueAt, remainingMinutes: state === 'MET' || state === 'MISSED' ? null : remainingMinutes };
}

export function sla(firstResponse: SlaGoal, resolution: SlaGoal): IssueSla {
  return { firstResponse, resolution };
}

/** An issue of the list with `sla`. */
export function withSla(key: string, value: IssueSla | null, over: Partial<IssueSummary> = {}): IssueSummary {
  return summary({ key, title: `${key} 문의`, sla: value, ...over });
}

/** ACME-7 with `sla`. */
export function issueWithSla(value: IssueSla | null, over: Partial<Issue> = {}): Issue {
  return issue({ sla: value, ...over });
}

// ---------------------------------------------------------------------------
// Fake server

/** State the slice 8a handlers read. Tests change it before rendering. */
export const s8a: {
  /** The policy as the server holds it. */
  policy: SlaPolicy;
  /** The `changedAt` of the next version a save makes. */
  savedAt: string;
} = { policy: offPolicy(), savedAt: '2026-10-08T03:30:00Z' };

function requireAdmin(): Response | undefined {
  if (!state.signedIn) return errors.unauthenticated();
  return state.me.role === 'ADMIN' ? undefined : errors.forbidden();
}

/** The 200 of `getSlaPolicy` for `policy`, through the contract check. */
export function policyResponse(policy: SlaPolicy = s8a.policy): Response {
  return HttpResponse.json(contract('getSlaPolicy', 200, policy));
}

/** The 409 of `updateSlaPolicy`, through the contract check. */
export function versionConflict(): Response {
  return rejectedWith('updateSlaPolicy', 'VERSION_CONFLICT', 409);
}

const inRange = (m: number | null) => m === null || (Number.isInteger(m) && m >= 1 && m <= 525600);

/** The field errors the server finds in `body` (design.md 5절 400 표), named with the place in the sent lists. */
export function fieldErrorsOf(body: ChangeSlaPolicyRequest): Schemas['FieldError'][] {
  const fields: Schemas['FieldError'][] = [];
  body.targets.forEach((t, i) => {
    if (!inRange(t.firstResponseMinutes)) fields.push({ field: `targets[${String(i)}].firstResponseMinutes`, code: 'OUT_OF_RANGE' });
    if (!inRange(t.resolutionMinutes)) fields.push({ field: `targets[${String(i)}].resolutionMinutes`, code: 'OUT_OF_RANGE' });
    else if (t.firstResponseMinutes !== null && t.resolutionMinutes !== null && t.resolutionMinutes < t.firstResponseMinutes) {
      fields.push({ field: `targets[${String(i)}].resolutionMinutes`, code: 'OUT_OF_RANGE' });
    }
  });
  body.hours.forEach((h, i) => {
    if (h.opens !== null && h.closes !== null && h.closes <= h.opens) fields.push({ field: `hours[${String(i)}].closes`, code: 'OUT_OF_RANGE' });
  });
  if (body.holidays.length > 500) fields.push({ field: 'holidays', code: 'TOO_MANY' });
  body.holidays.forEach((d, i) => {
    if (body.holidays.indexOf(d) < i) fields.push({ field: `holidays[${String(i)}]`, code: 'UNKNOWN_VALUE' });
  });
  return fields;
}

/** The policy `body` makes, in the contract's order. */
function policyOf(body: ChangeSlaPolicyRequest, version: number): SlaPolicy {
  return {
    version,
    targets: PRIORITIES.map((p) => body.targets.find((t) => t.priority === p) ?? target(p, null, null)),
    hours: WEEKDAYS.map((d) => body.hours.find((h) => h.weekday === d) ?? hours(d, null, null)),
    holidays: [...body.holidays].sort(),
    timeZone: s8a.policy.timeZone,
    changedAt: s8a.savedAt,
    changedBy: ADMIN_PERSON,
  };
}

function samePolicy(a: SlaPolicy, b: SlaPolicy): boolean {
  return JSON.stringify([a.targets, a.hours, a.holidays]) === JSON.stringify([b.targets, b.hours, b.holidays]);
}

/** What the server does with a save: 409, 400, the same policy, or the next version (S8A-AC-02~04). */
export async function savePolicy(request: Request): Promise<Response> {
  const denied = requireAdmin();
  if (denied) return denied;
  const body = (await request.json()) as ChangeSlaPolicyRequest;
  const fields = fieldErrorsOf(body);
  if (fields.length > 0) return rejectedWith('updateSlaPolicy', 'INVALID_INPUT', 400, fields);
  if (body.version !== s8a.policy.version) return versionConflict();
  const next = policyOf(body, s8a.policy.version + 1);
  if (!samePolicy(next, s8a.policy)) s8a.policy = next;
  return HttpResponse.json(contract('updateSlaPolicy', 200, s8a.policy));
}

const handlers8a = [
  http.get(`*${SLA_POLICY}`, () => requireAdmin() ?? policyResponse()),
  http.put(`*${SLA_POLICY}`, ({ request }) => savePolicy(request)),
];

beforeEach(() => {
  s8a.policy = offPolicy();
  s8a.savedAt = '2026-10-08T03:30:00Z';
  server.use(...handlers8a);
});

/** The list answer for `items`, through the contract check. */
export function listResponse(items: IssueSummary[]): Response {
  return HttpResponse.json(contract('listIssues', 200, { items, page: 1, size: 20, totalElements: items.length, totalPages: items.length === 0 ? 0 : 1 }));
}

/** The issue answer for `value`, through the contract check. */
export function issueResponse(value: Issue): Response {
  return HttpResponse.json(contract('getIssue', 200, value));
}

// ---------------------------------------------------------------------------
// Finding things on C-13 SLA 설정 (screens.md 1절)

/** The h1 "SLA 설정", waited for generously: the first wait of a test may meet a busy machine. */
export async function slaTitle(): Promise<HTMLElement> {
  let el: HTMLElement | null = null;
  await waitFor(
    () => {
      el = screen.queryByRole('heading', { level: 1, name: TITLE });
      expect(el, `h1 "${TITLE}"`).not.toBeNull();
    },
    { timeout: 5000 },
  );
  return el as unknown as HTMLElement;
}

/** Waits until the page text holds `part` (a word of the policy that was read). */
export async function pageShows(part: string | RegExp, timeout = 5000): Promise<void> {
  await waitFor(
    () => {
      const all = text(document.body);
      if (typeof part === 'string') expect(all).toContain(part);
      else expect(all).toMatch(part);
    },
    { timeout },
  );
}

/** The goal table: the table whose column heads hold 우선순위, 첫 응답 and 해결. */
export function goalTableNow(): HTMLElement | null {
  return (
    screen.queryAllByRole('table').find((t) => {
      const heads = within(t).queryAllByRole('columnheader').map(text);
      return heads.some((h) => h.includes('우선순위')) && heads.some((h) => h.includes('첫 응답')) && heads.some((h) => h.includes('해결'));
    }) ?? null
  );
}

export function goalTable(): Promise<HTMLElement> {
  return found(goalTableNow, '목표 표(우선순위, 첫 응답, 해결)');
}

/** The number fields of the row of `priority`: [첫 응답, 해결]. */
export async function goalFields(priority: Priority): Promise<[HTMLInputElement, HTMLInputElement]> {
  const table = await goalTable();
  const row = await found(
    () => within(table).queryAllByRole('row').find((r) => within(r).queryAllByRole('spinbutton').length === 2 && text(r).includes(PRIORITY_WORDS[priority])),
    `목표 표의 "${PRIORITY_WORDS[priority]}" 줄`,
  );
  const [first, resolution] = within(row).getAllByRole<HTMLInputElement>('spinbutton');
  return [first as HTMLInputElement, resolution as HTMLInputElement];
}

function dayPattern(day: Weekday): string {
  return `${WEEKDAY_WORDS[day]}(요일)?`;
}

/** The checkbox "{요일} 업무일". */
export function workdayBox(day: Weekday): Promise<HTMLInputElement> {
  return found(() => screen.queryByRole<HTMLInputElement>('checkbox', { name: new RegExp(`^${dayPattern(day)} 업무일$`) }), `"${WEEKDAY_WORDS[day]} 업무일"`);
}

function workdayBoxNow(day: Weekday): HTMLInputElement | null {
  return screen.queryByRole<HTMLInputElement>('checkbox', { name: new RegExp(`^${dayPattern(day)} 업무일$`) });
}

/**
 * The row of `day` in 업무 시간: the widest box around its 업무일 checkbox that holds no other
 * day's checkbox. The fields of the row are found in it, whatever their names.
 */
export function dayRowNow(day: Weekday): HTMLElement | null {
  const box = workdayBoxNow(day);
  if (!box) return null;
  const others = WEEKDAYS.filter((d) => d !== day)
    .map(workdayBoxNow)
    .filter((b): b is HTMLInputElement => b !== null);
  let row: HTMLElement = box;
  for (let el = box.parentElement; el !== null && el !== document.body; el = el.parentElement) {
    if (others.some((o) => el.contains(o))) break;
    row = el;
  }
  return row;
}

/** The time fields of the row of `day` now: [시작, 끝], or fewer when hidden. */
export function timeFieldsNow(day: Weekday): HTMLInputElement[] {
  const row = dayRowNow(day);
  return row ? Array.from(row.querySelectorAll<HTMLInputElement>('input[type="time"]')) : [];
}

/** The checkbox "{요일} 자정까지", or null now. */
export function midnightBoxNow(day: Weekday): HTMLInputElement | null {
  return screen.queryByRole<HTMLInputElement>('checkbox', { name: new RegExp(`^${dayPattern(day)} 자정까지$`) });
}

/** A holiday as the screen writes it, for a RegExp: only the 2026-12-25 form (screens.md 1절). */
export function dayWords(iso: string): string {
  return `(${iso})`;
}

/** The "{날짜} 빼기" button of `iso`, or null now. */
export function removeHolidayNow(iso: string): HTMLElement | null {
  return screen.queryByRole('button', { name: new RegExp(`^${dayWords(iso)} 빼기$`) });
}

/** Every "{날짜} 빼기" button, in page order. */
export function removeHolidayButtons(): HTMLElement[] {
  return screen.queryAllByRole('button', { name: / 빼기$/ }).filter((b) => /\d/.test(b.getAttribute('aria-label') ?? text(b)));
}

/** The date field "날짜" of 쉬는 날. */
export function holidayField(): Promise<HTMLInputElement> {
  return found(() => screen.queryAllByLabelText<HTMLInputElement>(/^날짜/, { selector: 'input' }).find((i) => i.type === 'date'), '날짜 칸 "날짜"');
}

// ---------------------------------------------------------------------------
// C-3 and C-4

/** What a screen reader gets from `el`: its text without `aria-hidden` parts, and the `aria-label`s inside. */
export function speechOf(el: Element): string {
  const labels = [el, ...Array.from(el.querySelectorAll('[aria-label]'))].map((e) => e.getAttribute('aria-label') ?? '').filter(Boolean);
  return [spokenText(el), ...labels].join(' ').replace(/\s+/g, ' ').trim();
}

/** The badges (`Badge`) inside `el`: their text and tone. */
export function badgesIn(el: Element): { text: string; tone: string | null }[] {
  return Array.from(el.querySelectorAll('[data-pd="Badge"]')).map((b) => ({ text: text(b), tone: b.getAttribute('data-tone') }));
}

/** The SLA part of C-4 now: a region or group named SLA, or the box of a heading or a term "SLA". */
export function slaPartNow(): HTMLElement | null {
  const named = screen.queryAllByRole('region', { name: 'SLA' })[0] ?? screen.queryAllByRole('group', { name: 'SLA' })[0];
  if (named) return named;
  const head = screen.queryAllByRole('heading').find((h) => text(h) === 'SLA') ?? screen.queryAllByRole('term').find((t) => text(t) === 'SLA');
  return head?.parentElement ?? null;
}

export function slaPart(): Promise<HTMLElement> {
  return found(slaPartNow, 'C-4의 구역 "SLA"');
}

/** The line of `goal` in the SLA part: the widest element that names it ("{목표}:") and not the other goal. */
export function goalLine(part: HTMLElement, name: '첫 응답' | '해결'): HTMLElement | null {
  const other = name === '첫 응답' ? '해결:' : '첫 응답:';
  const lines = Array.from(part.querySelectorAll<HTMLElement>('*')).filter((el) => text(el).includes(`${name}:`) && !text(el).includes(other));
  return lines.find((el) => !lines.some((o) => o !== el && o.contains(el))) ?? null;
}
