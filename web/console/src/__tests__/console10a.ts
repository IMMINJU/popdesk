// More test support for slice 10a (design/archive/10a-privacy/screens.md): the C-10 row menu with
// 정보 내보내기 and 익명화, the anonymize dialog and the anonymous row. The earlier support files
// stay as they are; this file adds the account export (`HEAD`, `GET`) and anonymize endpoints to
// the fake server, in the shape of docs/integrate/openapi-console.json, and the account list with
// its `anonymized` query value (S10A-AC-36).
//
// Every JSON answer made here goes through `contract` of console6a.ts: a fixture that does not fit
// the contract file fails the test that used it.
import type { ConsoleComponents } from '@popdesk/api/console';
import { HttpResponse, http } from 'msw/http';
import { beforeEach } from 'vitest';
import { T0, account, errors, paged, server, state, type Account } from './console';
import { contract, rejectedWith } from './console6a';
import { s3 } from './console3';

type Schemas = ConsoleComponents['schemas'];
export type SuspendedAccount = Schemas['SuspendedAccount'];
export type AccountExport = Schemas['AccountExport'];

export const ACCOUNTS = '/api/console/accounts';
export const exportPath = (id: number) => `${ACCOUNTS}/${String(id)}/export`;
export const anonymizePath = (id: number) => `${ACCOUNTS}/${String(id)}/anonymize`;

// screens.md의 문구
export const ANON_NAME = '삭제된 사용자';
export const ANONYMIZED_BADGE = '익명화됨';
export const EXPORT = '정보 내보내기';
export const ANONYMIZE = '익명화';
export const ANONYMIZING = '익명화하는 중…';
export const ANONYMIZED = '익명화했습니다';
export const CANNOT_UNDO = '되돌릴 수 없습니다.';
export const CHANGES = [
  '프로필 사진과 로그인한 세션을 지웁니다',
  "이 사람이 쓴 문의와 댓글은 남고 작성자는 '삭제된 사용자'로 보입니다",
  '감사 로그의 이름과 이메일도 바뀝니다',
] as const;
export const EXPORT_NOT_FOUND = '이 계정을 찾을 수 없습니다.';
export const RUNNING = '다른 익명화가 진행 중입니다. 잠시 뒤 다시 시도해 주세요.';
export const OWN_ACCOUNT = '자기 계정은 익명화할 수 없습니다.';
export const ALREADY = '이미 익명화한 계정입니다.';
export const LAST_ADMIN = '사용 중인 관리자가 한 명은 남아야 합니다.';

/** The title of the anonymize dialog for `name`. */
export const anonymizeTitle = (name: string) => `${name} 님을 익명화할까요?`;
/** The first item of the list of changes, with the account's ID. */
export const nameChange = (id: number) => `이름은 '삭제된 사용자', 이메일은 'deleted-${String(id)}@invalid'로 바뀝니다`;
/** The label of the email field. */
export const emailLabel = (email: string) => `확인하려면 이 사람의 이메일 ${email}을 입력하세요`;
/** The 503 text with the seconds of `Retry-After`. */
export const exportBusyText = (seconds: number) => `내보내기가 몰려 있습니다. ${String(seconds)}초 뒤 다시 시도해 주세요.`;

/** The anonymous email of account `id`. */
export const anonEmail = (id: number) => `deleted-${String(id)}@invalid`;

/** An anonymized account as the server gives it: anonymous name and email, `SUSPENDED`, no photo. */
export function anonymous(id: number, over: Partial<Account> = {}): Account {
  return account({ id, name: ANON_NAME, email: anonEmail(id), role: 'CUSTOMER', status: 'SUSPENDED', photoUpdatedAt: null, anonymized: true, ...over });
}

/** The export file of `a`: what the account holds, nothing it wrote. */
export function exportOf(a: Account): AccountExport {
  const { id, email, name, role, status, createdAt, anonymized } = a;
  return {
    exportedAt: T0,
    account: { id, email, name, role, status, locale: 'ko', createdAt, anonymized },
    photo: null,
    issues: [],
    comments: [],
    attachments: [],
  };
}

function requireAdmin(): Response | undefined {
  if (!state.signedIn) return errors.unauthenticated();
  return state.me.role === 'ADMIN' ? undefined : errors.forbidden();
}

const known = (id: string | readonly string[] | undefined) => state.accounts.find((a) => String(a.id) === id);

/** What the slice 10a server does when nothing goes wrong. Tests replace one with `route` of console1a.ts. */
const handlers10a = [
  // The account list of 1a with `anonymized` (S10A-AC-36): left out, both.
  http.get('*/api/console/accounts', ({ request }) => {
    const denied = requireAdmin();
    if (denied) return denied;
    const query = new URL(request.url).searchParams;
    const role = query.get('role');
    const status = query.get('status');
    const anonymized = query.get('anonymized');
    const q = query.get('q')?.toLowerCase();
    const items = state.accounts.filter(
      (a) =>
        (!role || a.role === role) &&
        (!status || a.status === status) &&
        (anonymized === null || String(a.anonymized) === anonymized) &&
        (!q || a.name.toLowerCase().includes(q) || a.email.toLowerCase().includes(q)),
    );
    return HttpResponse.json(contract('listAccounts', 200, paged(items)));
  }),

  http.head('*/api/console/accounts/:accountId/export', ({ params }) => {
    const denied = requireAdmin();
    if (denied) return new HttpResponse(null, { status: denied.status });
    const a = known(params.accountId);
    if (!a) return new HttpResponse(null, { status: 404 });
    return new HttpResponse(null, { status: 200, headers: exportHeaders(a.id) });
  }),
  http.get('*/api/console/accounts/:accountId/export', ({ params }) => {
    const denied = requireAdmin();
    if (denied) return denied;
    const a = known(params.accountId);
    if (!a) return errors.notFound();
    return HttpResponse.json(contract('exportAccount', 200, exportOf(a)), { headers: exportHeaders(a.id) });
  }),

  // 익명화: the same answer as 정지, with `anonymized: true` (S10A-AC-08). Never my own account (S10A-AC-12).
  http.post('*/api/console/accounts/:accountId/anonymize', ({ request, params }) => {
    const denied = requireAdmin();
    if (denied) return denied;
    const before = known(params.accountId);
    if (!before) return errors.notFound();
    if (before.anonymized) return rejectedWith('anonymizeAccount', 'ACCOUNT_ANONYMIZED');
    if (before.id === state.me.id) return rejectedWith('anonymizeAccount', 'OWN_ACCOUNT');
    const after: Account = { ...before, name: ANON_NAME, email: anonEmail(before.id), status: 'SUSPENDED', photoUpdatedAt: null, anonymized: true };
    state.accounts = state.accounts.map((a) => (a === before ? after : a));
    const clear = new URL(request.url).searchParams.get('clearAssignments') === 'true';
    const body: SuspendedAccount = { ...after, cleared: { issues: clear ? (s3.cleared ?? s3.openAssignments) : 0 } };
    return HttpResponse.json(contract('anonymizeAccount', 200, body));
  }),
];

function exportHeaders(id: number): Record<string, string> {
  return { 'Content-Disposition': `attachment; filename="popdesk-account-${String(id)}.json"`, 'Cache-Control': 'no-store' };
}

beforeEach(() => {
  server.use(...handlers10a);
});

/** The 503 of a `HEAD` while both export places are taken: no body, only the status and `Retry-After`. */
export function exportBusy(seconds: number): Response {
  return new HttpResponse(null, { status: 503, headers: { 'Retry-After': String(seconds) } });
}
