// More test support for slice 1b (design/slices/1b-audit/screens.md). `console.ts` and
// `console1a.ts` stay as they are; this file adds the audit log endpoints to the fake
// server, in the shape of docs/integrate/openapi-console.json, and the helpers the
// C-12 screen tests share.
import type { ConsoleComponents } from '@popdesk/api/console';
import { http, HttpResponse } from 'msw/http';
import { beforeEach, expect } from 'vitest';
import { callsTo, errors, eventually, found, paged, screen, server, state, text, within } from './console';
// The 1a handlers too: the account search of the 한 사람 filter (`q`).
import './console1a';

type Schemas = ConsoleComponents['schemas'];
export type AuditEntry = Schemas['AuditEntry'];
export type AuditActor = Schemas['AuditActor'];
export type AuditVerification = Schemas['AuditVerification'];

export const AUDIT = '/api/console/audit-log';
export const AUDIT_EXPORT = '/api/console/audit-log/export';
export const AUDIT_VERIFY = '/api/console/audit-log/verify';

// ---------------------------------------------------------------------------
// The kinds of action and their Korean words (screens.md "일의 종류")

export const KIND_GROUPS: readonly { group: string; kinds: Readonly<Record<string, string>> }[] = [
  { group: '로그인 기록', kinds: { SIGN_IN_SUCCEEDED: '로그인', SIGN_IN_FAILED: '로그인 실패', ACCOUNT_LOCKED: '로그인 잠김' } },
  {
    group: '계정',
    kinds: {
      FIRST_ADMIN_CREATED: '첫 관리자 만들기',
      ACCOUNT_NAME_CHANGED: '이름 고치기',
      ACCOUNT_ROLE_CHANGED: '역할 바꾸기',
      ACCOUNT_SUSPENDED: '계정 정지',
      ACCOUNT_REACTIVATED: '정지 풀기',
      ACCOUNT_PASSWORD_RESET: '비밀번호 초기화',
      PASSWORD_CHANGED: '비밀번호 바꾸기',
      PASSWORD_RESET_COMPLETED: '비밀번호 재설정',
      // Slice 10a (design.md 0절 12).
      ACCOUNT_ANONYMIZED: '익명화',
      AUDIT_RECHAINED: '감사 사슬 다시 계산',
      // Slice 11 (design.md 2절).
      OIDC_LINKED: 'OIDC 연결',
      OIDC_UNLINKED: 'OIDC 연결 풀기',
    },
  },
  {
    group: '초대',
    kinds: {
      INVITATION_CREATED: '초대',
      INVITATION_RESENT: '초대 다시 보내기',
      INVITATION_CANCELLED: '초대 취소',
      INVITATION_LINK_CREATED: '초대 링크 복사',
      // Slice 11: accepting by OIDC (design.md 2절).
      INVITATION_ACCEPTED: '초대 수락',
    },
  },
  {
    group: '고객사',
    kinds: {
      COMPANY_CREATED: '고객사 만들기',
      COMPANY_UPDATED: '고객사 고치기',
      COMPANY_STATUS_CHANGED: '고객사 상태 바꾸기',
      LABEL_CREATED: '라벨 만들기',
      LABEL_UPDATED: '라벨 고치기',
      LABEL_DELETED: '라벨 지우기',
      ISSUE_DELETED: '문의 지우기',
      ISSUE_RESTORED: '문의 복구',
      ISSUE_PURGED: '문의 영구 삭제',
      // Slice 10b (design.md 2절): in 고객사, so the 10a group 내보내기 stays two.
      COMPANY_EXPORTED: '고객사 내보내기',
      COMPANY_EXPORT_DOWNLOADED: '고객사 내보내기 받기',
    },
  },
  {
    group: '소속·배정',
    kinds: {
      CUSTOMER_ADDED: '소속 더하기',
      CUSTOMER_REMOVED: '소속 빼기',
      AGENT_ASSIGNED: '배정 더하기',
      AGENT_UNASSIGNED: '배정 빼기',
      MANAGER_MARKED: '담당자 지정',
      MANAGER_UNMARKED: '담당자 해제',
      MEMBERSHIP_REQUESTED: '소속 요청',
      MEMBERSHIP_REQUEST_WITHDRAWN: '소속 요청 거둠',
      MEMBERSHIP_REQUEST_APPROVED: '소속 요청 승인',
      MEMBERSHIP_REQUEST_REJECTED: '소속 요청 거절',
    },
  },
  { group: '운영 명령', kinds: { ADMIN_PASSWORD_RESET: '관리자 비밀번호 복구', BACKUP_CREATED: '백업', DATABASE_RESTORED: '복원' } },
  { group: '내보내기', kinds: { AUDIT_EXPORTED: '감사 로그 내보내기', ACCOUNT_EXPORTED: '계정 정보 내보내기' } },
  { group: '설정', kinds: { SLA_POLICY_CHANGED: 'SLA 정책 바꾸기', BRANDING_CHANGED: '브랜딩 바꾸기', BRANDING_LOGO_CHANGED: '로고 바꾸기' } },
  { group: '보내기', kinds: { DELIVERY_RESENT: '다시 보내기', TEST_MAIL_SENT: '시험 메일' } },
];

export const ALL_KINDS: readonly string[] = KIND_GROUPS.flatMap((g) => Object.keys(g.kinds));

/** The names of the kinds in one group, sorted. */
export function kindsOf(group: string): string[] {
  return Object.keys(KIND_GROUPS.find((g) => g.group === group)?.kinds ?? {}).sort();
}

// ---------------------------------------------------------------------------
// Fixtures

export const ADMIN_ACTOR: AuditActor = { accountId: 1, name: '최관리', email: 'admin@popdesk.test' };
export const AGENT_ACTOR: AuditActor = { accountId: 2, name: '이상담', email: 'lee@popdesk.test' };
/** popdesk itself: the first admin and the commands in the container. */
export const SYSTEM_ACTOR: AuditActor = { accountId: 0, name: null, email: null };

/** A hash as the server gives it: 64 lowercase hex characters, one for each place in the chain. */
export function hashOf(seq: number): string {
  return seq <= 0 ? '0'.repeat(64) : seq.toString(16).padStart(8, '0').repeat(8);
}

/** An entry; `seq` follows `id` and the hashes follow `seq` unless given. */
export function entry(over: Partial<AuditEntry> = {}): AuditEntry {
  const seq = over.seq ?? over.id ?? 31;
  return {
    id: seq,
    seq,
    action: 'ACCOUNT_SUSPENDED',
    actor: ADMIN_ACTOR,
    target: { type: 'ACCOUNT', accountId: 12 },
    changes: { status: { from: 'ACTIVE', to: 'SUSPENDED' } },
    occurredAt: '2026-10-08T03:15:00.123456Z',
    hash: hashOf(seq),
    prevHash: hashOf(seq - 1),
    ...over,
  };
}

/** 최관리 suspended account 12. */
export const SUSPENDED = entry({ id: 7731 });
/** 이상담 signed in: no changed value. */
export const SIGNED_IN = entry({
  id: 7730,
  action: 'SIGN_IN_SUCCEEDED',
  actor: AGENT_ACTOR,
  target: { type: 'ACCOUNT', accountId: 2 },
  changes: null,
  occurredAt: '2026-10-08T02:00:00.000001Z',
});
/** 최관리 made the company ACME: values set for the first time, not from and to. */
export const COMPANY_MADE = entry({
  id: 7729,
  action: 'COMPANY_CREATED',
  target: { type: 'COMPANY', code: 'ACME' },
  changes: { code: 'ACME', name: '에이크미', legalName: '에이크미 주식회사', status: 'ACTIVE' },
  occurredAt: '2026-10-07T09:30:00.000000Z',
});
/** A backup made by a command in the container. */
export const BACKED_UP = entry({
  id: 7728,
  action: 'BACKUP_CREATED',
  actor: SYSTEM_ACTOR,
  target: { type: 'DATABASE' },
  changes: { file: 'popdesk-20261007.dump' },
  occurredAt: '2026-10-07T01:00:00.000000Z',
});
/** What the server records when an export starts. */
export const EXPORTED = entry({
  id: 7740,
  action: 'AUDIT_EXPORTED',
  target: { type: 'AUDIT_LOG' },
  changes: { format: 'CSV', count: 3, filter: {} },
  occurredAt: '2026-10-08T04:00:00.000000Z',
});

export const VALID: AuditVerification = { valid: true, checkedCount: 57, reason: null, brokenEntry: null };

// ---------------------------------------------------------------------------
// Fake server

/** State the 1b handlers read. Tests change it before rendering. */
export const audit: { entries: AuditEntry[]; verification: AuditVerification } = { entries: [SUSPENDED, SIGNED_IN], verification: VALID };

function requireAdmin(): Response | undefined {
  if (!state.signedIn) return errors.unauthenticated();
  return state.me.role === 'ADMIN' ? undefined : errors.forbidden();
}

/** The entries the filters of `listAuditEntries` find, in the order of `audit.entries` (newest first), or the 400. */
function filtered(query: URLSearchParams): AuditEntry[] | Response {
  const actions = query.getAll('action');
  if (actions.some((a) => !ALL_KINDS.includes(a))) return errors.invalid([{ field: 'action', code: 'UNKNOWN_VALUE' }]);
  const actor = query.get('actorAccountId');
  const targetType = query.get('targetType');
  const targetAccountId = query.get('targetAccountId');
  const targetCode = query.get('targetCode');
  const from = query.get('from');
  const to = query.get('to');
  if (from !== null && to !== null && Date.parse(from) > Date.parse(to)) return errors.invalid([{ field: 'from', code: 'FROM_AFTER_TO' }]);
  return audit.entries.filter((e) => {
    const at = Date.parse(e.occurredAt);
    return (
      (actions.length === 0 || actions.includes(e.action)) &&
      (actor === null || String(e.actor.accountId) === actor) &&
      (targetType === null || e.target?.type === targetType) &&
      (targetAccountId === null || String(e.target?.accountId) === targetAccountId) &&
      (targetCode === null || e.target?.code === targetCode) &&
      (from === null || at >= Date.parse(from)) &&
      (to === null || at < Date.parse(to))
    );
  });
}

/** What the 1b server does when nothing goes wrong. Tests replace one with `on`. */
const handlers1b = [
  http.get(`*${AUDIT}`, ({ request }) => {
    const denied = requireAdmin();
    if (denied) return denied;
    const query = new URL(request.url).searchParams;
    const items = filtered(query);
    if (items instanceof Response) return items;
    const page = Number(query.get('page') ?? '1');
    const size = Number(query.get('size') ?? '20');
    return HttpResponse.json(paged(items.slice((page - 1) * size, page * size), { page, size, totalElements: items.length }));
  }),
  http.head(`*${AUDIT_EXPORT}`, ({ request }) => {
    const denied = requireAdmin();
    if (denied) return new HttpResponse(null, { status: denied.status });
    const jsonl = new URL(request.url).searchParams.get('format') === 'JSONL';
    return new HttpResponse(null, {
      status: 200,
      headers: {
        'Content-Type': jsonl ? 'application/x-ndjson' : 'text/csv',
        'Content-Disposition': `attachment; filename="audit-log.${jsonl ? 'jsonl' : 'csv'}"`,
        'Cache-Control': 'no-store',
      },
    });
  }),
  http.post(`*${AUDIT_VERIFY}`, () => requireAdmin() ?? HttpResponse.json(audit.verification)),
];

beforeEach(() => {
  audit.entries = [SUSPENDED, SIGNED_IN];
  audit.verification = VALID;
  server.use(...handlers1b);
});

/** 503 of the export `HEAD`: both export places are taken. A `HEAD` answer has no body. */
export function exportBusy(): Response {
  return new HttpResponse(null, { status: 503, headers: { 'Retry-After': '5' } });
}

/** The query of the last list request so far; empty when there was none. */
export function lastList(): URLSearchParams {
  return new URL(callsTo('GET', AUDIT).at(-1)?.url ?? 'http://x/').searchParams;
}

/** Waits until the last list request carries exactly these kinds. */
export async function listedKinds(wanted: readonly string[]) {
  await eventually(() => {
    expect(lastList().getAll('action').sort(), '목록 요청의 action').toEqual([...wanted].sort());
  });
}

// ---------------------------------------------------------------------------
// Finding things

export const COLUMNS = ['시각', '한 사람', '일', '대상', '바뀐 값'] as const;
export type Column = (typeof COLUMNS)[number];

/** The rows of the entry table: one for each entry, in the order shown. An opened row's detail is not one. */
function entryRowsNow(): HTMLElement[] {
  return screen.queryAllByRole('row').filter((r) => within(r).queryAllByRole('cell').length >= COLUMNS.length);
}

/** Waits until the table shows `count` entries and returns their rows. */
export async function entryRows(count: number): Promise<HTMLElement[]> {
  await eventually(() => {
    expect(entryRowsNow(), '감사 항목 줄').toHaveLength(count);
  });
  return entryRowsNow();
}

/** The cell of `row` under the column head `column`. */
export function cellOf(row: HTMLElement | undefined, column: Column): Element | null {
  const head = screen.queryAllByRole('columnheader').find((h) => text(h) === column);
  if (!head?.parentElement || !row) return null;
  return row.children[Array.from(head.parentElement.children).indexOf(head)] ?? null;
}

/** The text of every shown entry under `column`, top to bottom. */
export function column(name: Column): string[] {
  return entryRowsNow().map((r) => text(cellOf(r, name)));
}

/** The 종류 filter. */
export function kindSelect(): Promise<HTMLSelectElement> {
  return found(() => screen.queryAllByLabelText<HTMLSelectElement>(/^종류/, { selector: 'select' })[0], '고르기 "종류"');
}

/** The label of the choice the 종류 filter shows now. */
export function chosenKind(select: HTMLSelectElement): string {
  return text(select.selectedOptions[0]);
}

/** A date field of the 기간 filter: 부터 or 까지. */
export function dateField(label: '부터' | '까지'): Promise<HTMLInputElement> {
  return found(
    () => screen.queryAllByLabelText<HTMLInputElement>(new RegExp(label), { selector: 'input' }).find((i) => i.type === 'date'),
    `날짜 칸 "${label}"`,
  );
}
