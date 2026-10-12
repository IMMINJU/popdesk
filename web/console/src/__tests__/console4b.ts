// More test support for slice 4b (design/slices/4b-delivery/screens.md). The earlier
// support files stay as they are; this file adds the two new endpoints of C-18 (다시
// 보내기, 시험 메일) to the fake server, in the shape of docs/integrate/openapi-console.json,
// and the helpers the 4b screen tests share.
import type { ConsoleComponents } from '@popdesk/api/console';
import { http, HttpResponse } from 'msw/http';
import { beforeEach } from 'vitest';
import { T0, auditForwardOff, channel, errors, found, screen, server, state, text, within, type ChannelStatus, type FailedDelivery, type SystemStatus } from './console';
import { rejected } from './console1a';
import { cellUnder } from './console2';
import { noContent } from './console4';

type Schemas = ConsoleComponents['schemas'];
export type CleanupJobStatus = Schemas['CleanupJobStatus'];
export type TestMailResult = Schemas['TestMailResult'];

export const STATUS = '/api/console/system/status';
export const FAILED = '/api/console/system/failed-deliveries';
/** The path pattern of 다시 보내기, for `on`. */
export const RESEND = `${FAILED}/:id/resend`;
export const TEST_MAIL = '/api/console/system/test-mail';

export function resendOf(id: number): string {
  return `${FAILED}/${String(id)}/resend`;
}

// ---------------------------------------------------------------------------
// Fixtures

const OFF: ChannelStatus = channel({ enabled: false, lastSuccessAt: null });

/** The status outside a sandbox: mail and webhook on, the outside copy not set up, no cleanup job. */
export function status(over: Partial<SystemStatus> = {}): SystemStatus {
  return { mail: channel(), webhook: channel(), auditForward: auditForwardOff(), sandbox: false, sandboxMailToSet: false, cleanupJobs: [], ...over };
}

/**
 * The status of a sandbox as the contract gives it (S4B-AC-08): the webhook and the outside
 * copy are off; mail is on only with an address to send to.
 */
export function sandboxed(mailToSet: boolean, over: Partial<SystemStatus> = {}): SystemStatus {
  return status({ sandbox: true, sandboxMailToSet: mailToSet, mail: mailToSet ? channel() : OFF, webhook: OFF, auditForward: OFF, ...over });
}

/** A cleanup job that ran. One that never ran has `lastRunAt` and `deleted` null. */
export function cleanupJob(over: Partial<CleanupJobStatus> = {}): CleanupJobStatus {
  return { name: 'session-cleanup', lastRunAt: T0, deleted: 0, ...over };
}

/**
 * A test email the SMTP server took, with what it answered. A failure has `ok` false and an
 * `errorKind`. `serverReply` is null only when the server did not answer; a test of that says so.
 */
export function mailResult(over: Partial<TestMailResult> = {}): TestMailResult {
  return { ok: true, errorKind: null, serverReply: '250 2.0.0 Ok: queued as 4B7XQ1', ...over };
}

// ---------------------------------------------------------------------------
// Fake server

/** State the 4b handlers read. Tests change it before rendering. */
export const s4b: { testMail: TestMailResult } = { testMail: mailResult() };

const CHANNEL_KEYS = { MAIL: 'mail', WEBHOOK: 'webhook', AUDIT_FORWARD: 'auditForward' } as const satisfies Record<FailedDelivery['channel'], keyof SystemStatus>;

function requireAdmin(): Response | undefined {
  if (!state.signedIn) return errors.unauthenticated();
  return state.me.role === 'ADMIN' ? undefined : errors.forbidden();
}

/** What the 4b server does when nothing goes wrong. Tests replace one with `on` of console.ts. */
const handlers4b = [
  // The row goes back to the queue: out of the `failed` list, one fewer failed and one more waiting on its channel.
  http.post(`*${RESEND}`, ({ params }) => {
    const denied = requireAdmin();
    if (denied) return denied;
    const row = state.failed.find((f) => String(f.id) === params.id);
    if (!row) return errors.notFound();
    const key = CHANNEL_KEYS[row.channel];
    const before = state.status[key];
    if (!before.enabled) return rejected('CHANNEL_DISABLED');
    state.failed = state.failed.filter((f) => f !== row);
    state.status = { ...state.status, [key]: { ...before, failed: Math.max(0, before.failed - 1), queued: before.queued + 1 } };
    return noContent();
  }),
  http.post(`*${TEST_MAIL}`, () => {
    const denied = requireAdmin();
    if (denied) return denied;
    if (!state.status.mail.enabled) return rejected('CHANNEL_DISABLED');
    return HttpResponse.json(s4b.testMail);
  }),
];

beforeEach(() => {
  s4b.testMail = mailResult();
  server.use(...handlers4b);
});

// ---------------------------------------------------------------------------
// Finding things

/** The channel table row whose first cell is exactly `name`. */
export function channelRow(name: string): Promise<HTMLElement> {
  return found(
    () => screen.queryAllByRole('row').find((r) => text(within(r).queryAllByRole('cell')[0] ?? within(r).queryAllByRole('rowheader')[0]) === name),
    `채널 줄 "${name}"`,
  );
}

/** The text of `row`'s cell under the column head `column` of its own table. */
export function under(row: HTMLElement, column: string): string {
  return text(cellUnder(row.closest('table') ?? document.body, row, column));
}

function failedTableNow(): HTMLElement | undefined {
  return screen.queryAllByRole('table').find((t) => within(t).queryAllByRole('columnheader').some((h) => /오류/.test(text(h))));
}

/** The table of the `failed` rows: the one with a column head about the error (screens.md 0절). */
export function failedTable(): Promise<HTMLElement> {
  return found(failedTableNow, '`failed` 행 표(마지막 오류 종류 열)');
}

/** The `failed` row whose line holds `part`, or undefined. */
export function failedRowNow(part: string): HTMLElement | undefined {
  const table = failedTableNow();
  if (!table) return undefined;
  return within(table)
    .queryAllByRole('row')
    .find((r) => within(r).queryAllByRole('cell').length > 0 && text(r).includes(part));
}

export function failedRow(part: string): Promise<HTMLElement> {
  return found(() => failedRowNow(part), `\`failed\` 표의 줄 "${part}"`);
}

export const RESEND_NAME = '다시 보내기';
export const TEST_MAIL_NAME = '시험 메일 보내기';

/** The 다시 보내기 button of the `failed` row holding `part`: the name exactly, inside that row. */
export function resendButton(part: string): Promise<HTMLElement> {
  return found(() => {
    const row = failedRowNow(part);
    return row && within(row).queryByRole('button', { name: RESEND_NAME });
  }, `"${part}" 줄의 버튼 "${RESEND_NAME}"`);
}

/** The part of the screen under the `h2` named `name`: its `section`, or what holds the heading. Null when the heading is not drawn. */
export function sectionNow(name: string): HTMLElement | null {
  const heading = screen.queryByRole('heading', { level: 2, name });
  return heading ? (heading.closest('section') ?? heading.parentElement) : null;
}

export function section(name: string): Promise<HTMLElement> {
  return found(() => sectionNow(name), `구역 "${name}"(h2)`);
}

/** True when `first` stands before `second` in the document. */
export function before(first: Element, second: Element): boolean {
  return (first.compareDocumentPosition(second) & Node.DOCUMENT_POSITION_FOLLOWING) !== 0;
}
