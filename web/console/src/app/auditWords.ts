// Words for the audit log (1b screens.md C-12): the kinds of action and
// their groups, what an action was done to, and the changed values. A name the
// screen does not know shows as the server gave it. The words are in the screen
// language (b5): every table reads its text when it is read.
import type { MessageKey } from '@popdesk/i18n';
import { ACCOUNT_STATUS_WORDS, PRIORITY_WORDS, PROJECT_STATUS_WORDS } from '@popdesk/ui';
import { slaLength } from './messages';
import { screenLocale, t, texts, tf } from './locale';

export interface KindGroup {
  /** The value of the group's choice in the 종류 filter. No kind has this name. */
  key: string;
  name: string;
  kinds: Readonly<Record<string, string>>;
}

type GroupKey = 'SIGN_IN' | 'ACCOUNT' | 'INVITATION' | 'COMPANY' | 'MEMBERSHIP' | 'OPERATION' | 'EXPORT' | 'SETTINGS' | 'DELIVERY';

function group(key: GroupKey, kinds: Readonly<Record<string, MessageKey>>): KindGroup {
  return {
    key,
    get name() {
      return t(`audit.group.${key}`);
    },
    kinds: texts(kinds),
  };
}

export const KIND_GROUPS: readonly KindGroup[] = [
  group('SIGN_IN', { SIGN_IN_SUCCEEDED: 'audit.kind.SIGN_IN_SUCCEEDED', SIGN_IN_FAILED: 'audit.kind.SIGN_IN_FAILED', ACCOUNT_LOCKED: 'audit.kind.ACCOUNT_LOCKED' }),
  group(
    'ACCOUNT',
    {
      FIRST_ADMIN_CREATED: 'audit.kind.FIRST_ADMIN_CREATED',
      ACCOUNT_NAME_CHANGED: 'audit.kind.ACCOUNT_NAME_CHANGED',
      ACCOUNT_ROLE_CHANGED: 'audit.kind.ACCOUNT_ROLE_CHANGED',
      ACCOUNT_SUSPENDED: 'audit.kind.ACCOUNT_SUSPENDED',
      ACCOUNT_REACTIVATED: 'audit.kind.ACCOUNT_REACTIVATED',
      ACCOUNT_PASSWORD_RESET: 'audit.kind.ACCOUNT_PASSWORD_RESET',
      PASSWORD_CHANGED: 'audit.kind.PASSWORD_CHANGED',
      PASSWORD_RESET_COMPLETED: 'audit.kind.PASSWORD_RESET_COMPLETED',
      // Slice 10a (10a design.md 0절 12): 익명화 and the audit chain it computes again.
      ACCOUNT_ANONYMIZED: 'audit.kind.ACCOUNT_ANONYMIZED',
      AUDIT_RECHAINED: 'audit.kind.AUDIT_RECHAINED',
      // Slice 11: an account linked to its OIDC provider account, and the link removed.
      OIDC_LINKED: 'audit.kind.OIDC_LINKED',
      OIDC_UNLINKED: 'audit.kind.OIDC_UNLINKED',
    },
  ),
  group(
    'INVITATION',
    {
      INVITATION_CREATED: 'audit.kind.INVITATION_CREATED',
      INVITATION_RESENT: 'audit.kind.INVITATION_RESENT',
      INVITATION_CANCELLED: 'audit.kind.INVITATION_CANCELLED',
      INVITATION_LINK_CREATED: 'audit.kind.INVITATION_LINK_CREATED',
      // Slice 11: written when an OIDC sign-in turns on an account not accepted yet.
      INVITATION_ACCEPTED: 'audit.kind.INVITATION_ACCEPTED',
    },
  ),
  group(
    'COMPANY',
    // Slice 6b: a company's labels are in this group (6b-tasks screens.md 4절); slice 7a its issues in the trash;
    // slice 10b its export, so the group 내보내기 stays the two of slice 10a (10b design.md 2절).
    {
      COMPANY_CREATED: 'audit.kind.COMPANY_CREATED',
      COMPANY_UPDATED: 'audit.kind.COMPANY_UPDATED',
      COMPANY_STATUS_CHANGED: 'audit.kind.COMPANY_STATUS_CHANGED',
      LABEL_CREATED: 'audit.kind.LABEL_CREATED',
      LABEL_UPDATED: 'audit.kind.LABEL_UPDATED',
      LABEL_DELETED: 'audit.kind.LABEL_DELETED',
      ISSUE_DELETED: 'audit.kind.ISSUE_DELETED',
      ISSUE_RESTORED: 'audit.kind.ISSUE_RESTORED',
      ISSUE_PURGED: 'audit.kind.ISSUE_PURGED',
      COMPANY_EXPORTED: 'audit.kind.COMPANY_EXPORTED',
      COMPANY_EXPORT_DOWNLOADED: 'audit.kind.COMPANY_EXPORT_DOWNLOADED',
    },
  ),
  group(
    'MEMBERSHIP',
    {
      CUSTOMER_ADDED: 'audit.kind.CUSTOMER_ADDED',
      CUSTOMER_REMOVED: 'audit.kind.CUSTOMER_REMOVED',
      AGENT_ASSIGNED: 'audit.kind.AGENT_ASSIGNED',
      AGENT_UNASSIGNED: 'audit.kind.AGENT_UNASSIGNED',
      MANAGER_MARKED: 'audit.kind.MANAGER_MARKED',
      MANAGER_UNMARKED: 'audit.kind.MANAGER_UNMARKED',
      MEMBERSHIP_REQUESTED: 'audit.kind.MEMBERSHIP_REQUESTED',
      MEMBERSHIP_REQUEST_WITHDRAWN: 'audit.kind.MEMBERSHIP_REQUEST_WITHDRAWN',
      MEMBERSHIP_REQUEST_APPROVED: 'audit.kind.MEMBERSHIP_REQUEST_APPROVED',
      MEMBERSHIP_REQUEST_REJECTED: 'audit.kind.MEMBERSHIP_REQUEST_REJECTED',
    },
  ),
  group('OPERATION', { ADMIN_PASSWORD_RESET: 'audit.kind.ADMIN_PASSWORD_RESET', BACKUP_CREATED: 'audit.kind.BACKUP_CREATED', DATABASE_RESTORED: 'audit.kind.DATABASE_RESTORED' }),
  group('EXPORT', { AUDIT_EXPORTED: 'audit.kind.AUDIT_EXPORTED', ACCOUNT_EXPORTED: 'audit.kind.ACCOUNT_EXPORTED' }),
  // Slice 8a: settings of the whole installation, before 보내기 (8a-sla screens.md 4절).
  group(
    'SETTINGS',
    { SLA_POLICY_CHANGED: 'audit.kind.SLA_POLICY_CHANGED', BRANDING_CHANGED: 'audit.kind.BRANDING_CHANGED', BRANDING_LOGO_CHANGED: 'audit.kind.BRANDING_LOGO_CHANGED' },
  ),
  group('DELIVERY', { DELIVERY_RESENT: 'audit.kind.DELIVERY_RESENT', TEST_MAIL_SENT: 'audit.kind.TEST_MAIL_SENT' }),
];

/** The 종류 choice "로그인 기록 빼고": every kind outside the first group. */
export const NOT_SIGN_IN = 'NOT_SIGN_IN';

/** The word for a kind of action; undefined for a name the screen does not know. */
export function kindWord(action: string): string | undefined {
  const owner = KIND_GROUPS.find((g) => Object.hasOwn(g.kinds, action));
  return owner?.kinds[action];
}

/** The sign-ins whose changes may say how the person signed in (slice 11). */
const SIGN_IN_KINDS: ReadonlySet<string> = new Set(['SIGN_IN_SUCCEEDED', 'SIGN_IN_FAILED']);

/** The word for an entry's action: a sign-in through OIDC reads "로그인 (회사 계정)" (slice 11 screens.md C-12). */
export function entryKindWord(action: string, changes: Readonly<Record<string, unknown>> | null): string | undefined {
  const kind = kindWord(action);
  if (kind === undefined || !SIGN_IN_KINDS.has(action) || changes?.method !== 'OIDC') return kind;
  return `${kind} (${t('audit.method.OIDC')})`;
}

/**
 * The kinds a 종류 choice asks the server for: none for 전체, a group's kinds,
 * all but the sign-in kinds, or the one kind. A value from an old address goes
 * out as it is and the server answers 400.
 */
export function kindsOfChoice(choice: string): string[] {
  if (choice === '') return [];
  if (choice === NOT_SIGN_IN) return KIND_GROUPS.slice(1).flatMap((g) => Object.keys(g.kinds));
  const group = KIND_GROUPS.find((g) => g.key === choice);
  return group ? Object.keys(group.kinds) : [choice];
}

/** What an action was done to, as the keys of the stored target that name it. */
export interface Target {
  type: string;
  accountId?: number;
  code?: string;
  /** A label of the company `code` (slice 6b). */
  labelId?: number;
  /** An issue of the company `code` deleted, restored, or purged (slice 7a). */
  issueKey?: string;
  /** The version of the SLA policy a change made (slice 8a). */
  version?: number;
  /** The theme of a branding logo, LIGHT or DARK (slice 9). */
  theme?: string;
}

/** Reads a stored target (a free JSON object); null when it names nothing. */
export function targetOf(stored: Readonly<Record<string, unknown>> | null): Target | null {
  if (stored === null || typeof stored.type !== 'string') return null;
  return {
    type: stored.type,
    ...(typeof stored.accountId === 'number' ? { accountId: stored.accountId } : {}),
    ...(typeof stored.code === 'string' ? { code: stored.code } : {}),
    ...(typeof stored.labelId === 'number' ? { labelId: stored.labelId } : {}),
    ...(typeof stored.issueKey === 'string' ? { issueKey: stored.issueKey } : {}),
    ...(typeof stored.version === 'number' ? { version: stored.version } : {}),
    ...(typeof stored.theme === 'string' ? { theme: stored.theme } : {}),
  };
}

/** "계정 12", "고객사 ACME", "고객사 ACME · 계정 12", "고객사 ACME · 라벨 61", "고객사 ACME · 문의 ACME-7", "데이터베이스", "감사 로그", "SLA 정책 버전 4", "브랜딩 (밝은 테마)". */
export function targetWord(target: Target): string {
  if (target.type === 'SLA_POLICY' && target.version !== undefined) return tf('audit.target.slaPolicy', { version: target.version });
  if (target.type === 'BRANDING') {
    return target.theme === undefined ? t('audit.target.branding') : tf('audit.target.brandingTheme', { theme: word(LOGO_THEME_WORDS, target.theme) });
  }
  const account = target.accountId === undefined ? null : accountWord(target.accountId);
  const company = target.code === undefined ? null : tf('audit.target.company', { code: target.code });
  const label = target.labelId === undefined ? null : tf('audit.target.label', { id: target.labelId });
  const issue = target.issueKey === undefined ? null : tf('audit.target.issue', { key: target.issueKey });
  if (company !== null || account !== null || label !== null || issue !== null) {
    return [company, account, label, issue].filter((part) => part !== null).join(' · ');
  }
  if (target.type === 'DATABASE') return t('audit.target.database');
  if (target.type === 'AUDIT_LOG') return t('audit.target.auditLog');
  return target.type;
}

/** "계정 12": an account by its ID. */
export function accountWord(accountId: number): string {
  return tf('audit.target.account', { id: accountId });
}

/** "시스템": popdesk itself as the one who did it. */
export function systemWord(): string {
  return t('audit.system');
}

// Field names as the screens that change them call them (C-8, C-9, C-10, 초대).
const FIELD_WORDS: Readonly<Record<string, string>> = texts({
  name: 'audit.field.name',
  email: 'audit.field.email',
  role: 'audit.field.role',
  status: 'audit.field.status',
  code: 'audit.field.code',
  legalName: 'audit.field.legalName',
  companyCodes: 'audit.field.companyCodes',
  member: 'audit.field.member',
  manager: 'audit.field.manager',
  assigned: 'audit.field.assigned',
  clearedIssues: 'audit.field.clearedIssues',
  clearedTasks: 'audit.field.clearedTasks',
  color: 'audit.field.color',
  file: 'audit.field.file',
  format: 'audit.field.format',
  count: 'audit.field.count',
  filter: 'audit.field.filter',
  expiresAt: 'audit.field.expiresAt',
  lockedUntil: 'audit.field.lockedUntil',
  channel: 'audit.field.channel',
  kind: 'audit.field.kind',
  ok: 'audit.field.ok',
  errorKind: 'audit.field.errorKind',
  targets: 'audit.field.targets',
  hours: 'audit.field.hours',
  holidays: 'audit.field.holidays',
  displayName: 'audit.field.displayName',
  accent: 'audit.field.accent',
  logo: 'audit.field.logo',
  // Slice 10a: the part of the chain 익명화 computed again (AUDIT_RECHAINED).
  fromSeq: 'audit.field.fromSeq',
  toSeq: 'audit.field.toSeq',
  headHash: 'audit.field.headHash',
  // Slice 10b: what a company export took in, and which export was downloaded.
  includeInternal: 'audit.field.includeInternal',
  includePrivate: 'audit.field.includePrivate',
  exportId: 'audit.field.exportId',
  // Slice 11: how a person signed in or accepted an invitation, and the provider an account was linked to.
  method: 'audit.field.method',
  issuer: 'audit.field.issuer',
});

const METHOD_WORDS: Readonly<Record<string, string>> = texts({ OIDC: 'audit.method.OIDC' });
const LOGO_THEME_WORDS: Readonly<Record<string, string>> = texts({ LIGHT: 'audit.theme.LIGHT', DARK: 'audit.theme.DARK' });

/**
 * Fields read in a fixed order whatever order the server wrote them in: the part of the chain
 * AUDIT_RECHAINED computed again reads 시작 번호, 끝 번호, 마지막 hash (slice 10a).
 */
const FIELD_ORDER: readonly string[] = ['fromSeq', 'toSeq', 'headHash'];

/** The changed values with the fields of FIELD_ORDER in that order, before the rest; the rest keep theirs. */
function orderedFields(entries: [string, unknown][]): [string, unknown][] {
  const rank = (field: string) => {
    const at = FIELD_ORDER.indexOf(field);
    return at === -1 ? FIELD_ORDER.length : at;
  };
  // A stable sort: fields outside FIELD_ORDER stay as the server wrote them.
  return [...entries].sort(([a], [b]) => rank(a) - rank(b));
}

/** Fields that hold a time. */
const TIME_FIELDS: ReadonlySet<string> = new Set(['expiresAt', 'lockedUntil']);
/** The word for no value. */
const none = () => t('audit.none');

const ROLE_WORDS: Readonly<Record<string, string>> = texts({ CUSTOMER: 'audit.role.CUSTOMER', AGENT: 'audit.role.AGENT', ADMIN: 'audit.role.ADMIN' });
// The channel of a row sent again, as C-18 names it.
const CHANNEL_WORDS: Readonly<Record<string, string>> = texts({ MAIL: 'audit.channel.MAIL', WEBHOOK: 'audit.channel.WEBHOOK', AUDIT_FORWARD: 'audit.channel.AUDIT_FORWARD' });

function word(words: Readonly<Record<string, string>>, name: string): string {
  return Object.hasOwn(words, name) ? (words[name] ?? name) : name;
}

/** A stored time the way the other screens show an exact time, in the browser's time zone. Anything else stays as it is. */
function timeWord(value: unknown): string | null {
  if (typeof value !== 'string') return null;
  const at = new Date(value);
  if (Number.isNaN(at.getTime())) return null;
  return new Intl.DateTimeFormat(screenLocale(), { dateStyle: 'long', timeStyle: 'short' }).format(at);
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

function sameKinds(a: readonly string[], b: readonly string[]): boolean {
  return a.length === b.length && a.every((kind) => b.includes(kind));
}

/** The kinds an export was narrowed to, as the 종류 filter names them: a choice of the filter when they are one, else each kind. */
function kindsWord(kinds: readonly string[]): string {
  if (kinds.length > 1) {
    if (sameKinds(kinds, kindsOfChoice(NOT_SIGN_IN))) return t('audit.notSignIn');
    const group = KIND_GROUPS.find((g) => sameKinds(kinds, Object.keys(g.kinds)));
    if (group) return group.name;
  }
  return kinds.map((kind) => kindWord(kind) ?? kind).join(', ');
}

function actorWord(value: unknown): string {
  if (value === 0) return systemWord();
  return typeof value === 'number' ? accountWord(value) : valueWord('', '', value);
}

/**
 * The filters of an export (the query names of the list) in the words of the
 * filter row: "종류: 계정, 부터: 2026년 10월 7일 오전 12:00". A key the screen does
 * not know shows as it is.
 */
function filterWord(filter: Readonly<Record<string, unknown>>): string {
  const parts: string[] = [];
  const target = targetOf({
    type: typeof filter.targetType === 'string' ? filter.targetType : null,
    accountId: filter.targetAccountId,
    code: filter.targetCode,
  });
  let targetShown = false;
  for (const [key, value] of Object.entries(filter)) {
    if (key === 'action' && Array.isArray(value) && value.every((kind) => typeof kind === 'string')) {
      parts.push(tf('audit.filter.kind', { kinds: kindsWord(value) }));
    } else if (key === 'actorAccountId') {
      parts.push(tf('audit.filter.actor', { actor: actorWord(value) }));
    } else if (target && (key === 'targetType' || key === 'targetAccountId' || key === 'targetCode')) {
      if (!targetShown) parts.push(tf('audit.filter.target', { target: targetWord(target) }));
      targetShown = true;
    } else if (key === 'from' || key === 'to') {
      parts.push(tf(key === 'from' ? 'audit.filter.from' : 'audit.filter.to', { at: timeWord(value) ?? valueWord('', '', value) }));
    } else {
      parts.push(`${key}: ${valueWord('', '', value)}`);
    }
  }
  return parts.join(', ');
}

function valueWord(action: string, field: string, value: unknown): string {
  if (value === null || value === undefined) return none();
  if (typeof value === 'boolean') return t(value ? 'audit.yes' : 'audit.no');
  if (Array.isArray(value)) return value.length === 0 ? none() : value.map((item) => valueWord(action, '', item)).join(', ');
  if (isRecord(value)) {
    if (Object.keys(value).length === 0) return none();
    return field === 'filter' ? filterWord(value) : JSON.stringify(value);
  }
  const text = typeof value === 'string' ? value : JSON.stringify(value);
  if (TIME_FIELDS.has(field)) return timeWord(value) ?? text;
  // The open issues a 배정 빼기 or 계정 정지 cleared the assignee of (S3-AC-72), and the open tasks (slice 6b).
  if ((field === 'clearedIssues' || field === 'clearedTasks') && typeof value === 'number') return tf(`audit.${field}`, { count: text });
  if (field === 'role') return word(ROLE_WORDS, text);
  if (field === 'channel') return word(CHANNEL_WORDS, text);
  if (field === 'method') return word(METHOD_WORDS, text);
  // 정지 is an account's word and 멈춤 a company's for the same stored name.
  if (field === 'status') return word(action.startsWith('COMPANY_') ? PROJECT_STATUS_WORDS : ACCOUNT_STATUS_WORDS, text);
  return text;
}

function isFromTo(value: unknown): value is { from: unknown; to: unknown } {
  return isRecord(value) && Object.keys(value).length === 2 && 'from' in value && 'to' in value;
}

// The parts of an SLA policy change (8a-sla screens.md 4절): each part is a list, and only what
// differs between before and after is written.
const WEEKDAY_WORDS: Readonly<Record<string, string>> = texts({
  MONDAY: 'audit.weekday.MONDAY',
  TUESDAY: 'audit.weekday.TUESDAY',
  WEDNESDAY: 'audit.weekday.WEDNESDAY',
  THURSDAY: 'audit.weekday.THURSDAY',
  FRIDAY: 'audit.weekday.FRIDAY',
  SATURDAY: 'audit.weekday.SATURDAY',
  SUNDAY: 'audit.weekday.SUNDAY',
});
const SHOWN_DAYS = 10;

function records(value: unknown): Record<string, unknown>[] | null {
  return Array.isArray(value) && value.every(isRecord) ? value : null;
}

function goalWord(minutes: unknown): string {
  return typeof minutes === 'number' ? slaLength(minutes) : none();
}

/** "보통 첫 응답 4시간 → 2시간, 해결 12시간 → 10시간 · 높음 첫 응답 1시간 → 없음": changed goals only. */
function targetsWord(from: Record<string, unknown>[], to: Record<string, unknown>[]): string {
  const parts: string[] = [];
  for (const after of to) {
    const before = from.find((t) => t.priority === after.priority);
    const goals: string[] = [];
    for (const [key, name] of [
      ['firstResponseMinutes', t('audit.firstResponse')],
      ['resolutionMinutes', t('audit.resolution')],
    ] as const) {
      const was = before?.[key] ?? null;
      const is = after[key] ?? null;
      if (was !== is) goals.push(`${name} ${goalWord(was)} → ${goalWord(is)}`);
    }
    if (goals.length > 0) parts.push(`${word(PRIORITY_WORDS, String(after.priority))} ${goals.join(', ')}`);
  }
  return parts.length === 0 ? none() : parts.join(' · ');
}

function spanWord(day: Record<string, unknown> | undefined): string {
  if (day === undefined || typeof day.opens !== 'string' || typeof day.closes !== 'string') return t('audit.dayOff');
  return `${day.opens}–${day.closes}`;
}

/** "수요일 09:00–18:00 → 쉼": changed weekdays only, Monday first. A weekday missing from a list is 쉼. */
function hoursWord(from: Record<string, unknown>[], to: Record<string, unknown>[]): string {
  const parts: string[] = [];
  const known = Object.keys(WEEKDAY_WORDS);
  const named = [...from, ...to].map((d) => String(d.weekday));
  const days = [...known, ...named.filter((d, i) => !known.includes(d) && named.indexOf(d) === i)];
  for (const weekday of days) {
    const was = spanWord(from.find((d) => d.weekday === weekday));
    const is = spanWord(to.find((d) => d.weekday === weekday));
    if (was !== is) parts.push(`${word(WEEKDAY_WORDS, weekday)} ${was} → ${is}`);
  }
  return parts.length === 0 ? none() : parts.join(' · ');
}

function daysWord(days: string[]): string {
  const shown = days.slice(0, SHOWN_DAYS).join(', ');
  return days.length > SHOWN_DAYS ? tf('audit.moreDays', { shown, count: days.length - SHOWN_DAYS }) : shown;
}

/** "더함 2027-01-01, 뺌 2026-12-25": the days are written as they are stored, with the year. */
function holidaysWord(from: string[], to: string[]): string {
  const added = to.filter((d) => !from.includes(d)).sort();
  const removed = from.filter((d) => !to.includes(d)).sort();
  const parts = [
    added.length > 0 ? tf('audit.holidaysAdded', { days: daysWord(added) }) : '',
    removed.length > 0 ? tf('audit.holidaysRemoved', { days: daysWord(removed) }) : '',
  ].filter(Boolean);
  return parts.length === 0 ? none() : parts.join(', ');
}

function strings(value: unknown): string[] | null {
  return Array.isArray(value) && value.every((d) => typeof d === 'string') ? value : null;
}

/** The words of one part of an SLA policy change, or null when the value is not in the shape the screen knows. */
function slaPartWord(field: string, from: unknown, to: unknown): string | null {
  if (field === 'holidays') {
    const before = strings(from);
    const after = strings(to);
    return before && after ? holidaysWord(before, after) : null;
  }
  const before = records(from);
  const after = records(to);
  if (!before || !after) return null;
  if (field === 'targets') return targetsWord(before, after);
  if (field === 'hours') return hoursWord(before, after);
  return null;
}

/** An accent as stored: `#RRGGBB`, or `null` for the default. */
function accentWord(value: unknown): string {
  return typeof value === 'string' ? value : t('audit.accentDefault');
}

/**
 * A branding change (slice 9 screens.md 4절): an accent of `null` is "기본"; a logo is only "없음" or
 * "바뀜", never its fingerprint. Null for any other field.
 */
function brandingWord(action: string, field: string, from: unknown, to: unknown): string | null {
  if (action === 'BRANDING_CHANGED' && field === 'accent') return `${accentWord(from)} → ${accentWord(to)}`;
  if (action !== 'BRANDING_LOGO_CHANGED' || field !== 'logo') return null;
  const changed = t('audit.logoChanged');
  if (from !== null && to !== null) return changed;
  return `${from === null ? none() : changed} → ${to === null ? none() : changed}`;
}

/** One line for each changed field: "역할: 상담원 → 관리자", or "칸: 값" for a value that is not from and to. */
export function changeLines(action: string, changes: Readonly<Record<string, unknown>> | null): string[] {
  return orderedFields(Object.entries(changes ?? {})).map(([field, value]) => {
    const sla = action === 'SLA_POLICY_CHANGED' && isFromTo(value) ? slaPartWord(field, value.from, value.to) : null;
    if (sla !== null) return `${word(FIELD_WORDS, field)}: ${sla}`;
    const branding = isFromTo(value) ? brandingWord(action, field, value.from, value.to) : null;
    if (branding !== null) return `${word(FIELD_WORDS, field)}: ${branding}`;
    // An export's filter may hold just 부터 and 까지: it is not a change from one value to another.
    const shown = field !== 'filter' && isFromTo(value) ? `${valueWord(action, field, value.from)} → ${valueWord(action, field, value.to)}` : valueWord(action, field, value);
    return `${word(FIELD_WORDS, field)}: ${shown}`;
  });
}
