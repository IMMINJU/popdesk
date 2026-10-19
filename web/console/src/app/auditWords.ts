// Korean words for the audit log (1b screens.md C-12): the kinds of action and
// their groups, what an action was done to, and the changed values. A name the
// screen does not know shows as the server gave it.
import { ACCOUNT_STATUS_WORDS, PRIORITY_WORDS, PROJECT_STATUS_WORDS } from '@popdesk/ui';
import { slaLength } from './messages';
import { screenLocale } from './locale';

export interface KindGroup {
  /** The value of the group's choice in the 종류 filter. No kind has this name. */
  key: string;
  name: string;
  kinds: Readonly<Record<string, string>>;
}

export const KIND_GROUPS: readonly KindGroup[] = [
  { key: 'SIGN_IN', name: '로그인 기록', kinds: { SIGN_IN_SUCCEEDED: '로그인', SIGN_IN_FAILED: '로그인 실패', ACCOUNT_LOCKED: '로그인 잠김' } },
  {
    key: 'ACCOUNT',
    name: '계정',
    kinds: {
      FIRST_ADMIN_CREATED: '첫 관리자 만들기',
      ACCOUNT_NAME_CHANGED: '이름 고치기',
      ACCOUNT_ROLE_CHANGED: '역할 바꾸기',
      ACCOUNT_SUSPENDED: '계정 정지',
      ACCOUNT_REACTIVATED: '정지 풀기',
      ACCOUNT_PASSWORD_RESET: '비밀번호 초기화',
      PASSWORD_CHANGED: '비밀번호 바꾸기',
      PASSWORD_RESET_COMPLETED: '비밀번호 재설정',
      // Slice 10a (10a design.md 0절 12): 익명화 and the audit chain it computes again.
      ACCOUNT_ANONYMIZED: '익명화',
      AUDIT_RECHAINED: '감사 사슬 다시 계산',
      // Slice 11: an account linked to its OIDC provider account, and the link removed.
      OIDC_LINKED: 'OIDC 연결',
      OIDC_UNLINKED: 'OIDC 연결 풀기',
    },
  },
  {
    key: 'INVITATION',
    name: '초대',
    kinds: {
      INVITATION_CREATED: '초대',
      INVITATION_RESENT: '초대 다시 보내기',
      INVITATION_CANCELLED: '초대 취소',
      INVITATION_LINK_CREATED: '초대 링크 복사',
      // Slice 11: written when an OIDC sign-in turns on an account not accepted yet.
      INVITATION_ACCEPTED: '초대 수락',
    },
  },
  {
    key: 'COMPANY',
    name: '고객사',
    // Slice 6b: a company's labels are in this group (6b-tasks screens.md 4절); slice 7a its issues in the trash;
    // slice 10b its export, so the group 내보내기 stays the two of slice 10a (10b design.md 2절).
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
      COMPANY_EXPORTED: '고객사 내보내기',
      COMPANY_EXPORT_DOWNLOADED: '고객사 내보내기 받기',
    },
  },
  {
    key: 'MEMBERSHIP',
    name: '소속·배정',
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
  { key: 'OPERATION', name: '운영 명령', kinds: { ADMIN_PASSWORD_RESET: '관리자 비밀번호 복구', BACKUP_CREATED: '백업', DATABASE_RESTORED: '복원' } },
  { key: 'EXPORT', name: '내보내기', kinds: { AUDIT_EXPORTED: '감사 로그 내보내기', ACCOUNT_EXPORTED: '계정 정보 내보내기' } },
  // Slice 8a: settings of the whole installation, before 보내기 (8a-sla screens.md 4절).
  {
    key: 'SETTINGS',
    name: '설정',
    kinds: { SLA_POLICY_CHANGED: 'SLA 정책 바꾸기', BRANDING_CHANGED: '브랜딩 바꾸기', BRANDING_LOGO_CHANGED: '로고 바꾸기' },
  },
  { key: 'DELIVERY', name: '보내기', kinds: { DELIVERY_RESENT: '다시 보내기', TEST_MAIL_SENT: '시험 메일' } },
];

/** The 종류 choice "로그인 기록 빼고": every kind outside the first group. */
export const NOT_SIGN_IN = 'NOT_SIGN_IN';

const KIND_WORDS: Readonly<Record<string, string>> = Object.assign({}, ...KIND_GROUPS.map((g) => g.kinds)) as Record<string, string>;

/** The word for a kind of action; undefined for a name the screen does not know. */
export function kindWord(action: string): string | undefined {
  return Object.hasOwn(KIND_WORDS, action) ? KIND_WORDS[action] : undefined;
}

/** The sign-ins whose changes may say how the person signed in (slice 11). */
const SIGN_IN_KINDS: ReadonlySet<string> = new Set(['SIGN_IN_SUCCEEDED', 'SIGN_IN_FAILED']);

/** The word for an entry's action: a sign-in through OIDC reads "로그인 (회사 계정)" (slice 11 screens.md C-12). */
export function entryKindWord(action: string, changes: Readonly<Record<string, unknown>> | null): string | undefined {
  const kind = kindWord(action);
  if (kind === undefined || !SIGN_IN_KINDS.has(action) || changes?.method !== 'OIDC') return kind;
  return `${kind} (${METHOD_WORDS.OIDC})`;
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
  if (target.type === 'SLA_POLICY' && target.version !== undefined) return `SLA 정책 버전 ${String(target.version)}`;
  if (target.type === 'BRANDING') return target.theme === undefined ? '브랜딩' : `브랜딩 (${word(LOGO_THEME_WORDS, target.theme)})`;
  const account = target.accountId === undefined ? null : `계정 ${String(target.accountId)}`;
  const company = target.code === undefined ? null : `고객사 ${target.code}`;
  const label = target.labelId === undefined ? null : `라벨 ${String(target.labelId)}`;
  const issue = target.issueKey === undefined ? null : `문의 ${target.issueKey}`;
  if (company !== null || account !== null || label !== null || issue !== null) {
    return [company, account, label, issue].filter((part) => part !== null).join(' · ');
  }
  if (target.type === 'DATABASE') return '데이터베이스';
  if (target.type === 'AUDIT_LOG') return '감사 로그';
  return target.type;
}

// Field names as the screens that change them call them (C-8, C-9, C-10, 초대).
const FIELD_WORDS: Readonly<Record<string, string>> = {
  name: '이름',
  email: '이메일',
  role: '역할',
  status: '상태',
  code: '코드',
  legalName: '회사명',
  companyCodes: '고객사',
  member: '소속',
  manager: '담당자',
  assigned: '배정',
  clearedIssues: '비운 담당',
  clearedTasks: '비운 작업',
  color: '색',
  file: '파일',
  format: '형식',
  count: '건수',
  filter: '조건',
  expiresAt: '만료',
  lockedUntil: '잠금 풀림',
  channel: '채널',
  kind: '종류',
  ok: '보냄',
  errorKind: '오류 종류',
  targets: '목표',
  hours: '업무 시간',
  holidays: '쉬는 날',
  displayName: '표시 이름',
  accent: '강조색',
  logo: '로고',
  // Slice 10a: the part of the chain 익명화 computed again (AUDIT_RECHAINED).
  fromSeq: '시작 번호',
  toSeq: '끝 번호',
  headHash: '마지막 hash',
  // Slice 10b: what a company export took in, and which export was downloaded.
  includeInternal: '내부 넣기',
  includePrivate: '비공개 넣기',
  exportId: '작업',
  // Slice 11: how a person signed in or accepted an invitation, and the provider an account was linked to.
  method: '로그인 방법',
  issuer: '발급자',
};

const METHOD_WORDS = { OIDC: '회사 계정' } as const;
const LOGO_THEME_WORDS: Readonly<Record<string, string>> = { LIGHT: '밝은 테마', DARK: '어두운 테마' };

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
const NONE = '없음';

const ROLE_WORDS: Readonly<Record<string, string>> = { CUSTOMER: '고객', AGENT: '상담원', ADMIN: '관리자' };
// The channel of a row sent again, as C-18 names it.
const CHANNEL_WORDS: Readonly<Record<string, string>> = { MAIL: '메일', WEBHOOK: '웹훅', AUDIT_FORWARD: '감사 바깥 사본' };

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
    if (sameKinds(kinds, kindsOfChoice(NOT_SIGN_IN))) return '로그인 기록 빼고';
    const group = KIND_GROUPS.find((g) => sameKinds(kinds, Object.keys(g.kinds)));
    if (group) return group.name;
  }
  return kinds.map((kind) => kindWord(kind) ?? kind).join(', ');
}

function actorWord(value: unknown): string {
  if (value === 0) return '시스템';
  return typeof value === 'number' ? `계정 ${String(value)}` : valueWord('', '', value);
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
      parts.push(`종류: ${kindsWord(value)}`);
    } else if (key === 'actorAccountId') {
      parts.push(`한 사람: ${actorWord(value)}`);
    } else if (target && (key === 'targetType' || key === 'targetAccountId' || key === 'targetCode')) {
      if (!targetShown) parts.push(`대상: ${targetWord(target)}`);
      targetShown = true;
    } else if (key === 'from' || key === 'to') {
      parts.push(`${key === 'from' ? '부터' : '까지'}: ${timeWord(value) ?? valueWord('', '', value)}`);
    } else {
      parts.push(`${key}: ${valueWord('', '', value)}`);
    }
  }
  return parts.join(', ');
}

function valueWord(action: string, field: string, value: unknown): string {
  if (value === null || value === undefined) return NONE;
  if (typeof value === 'boolean') return value ? '예' : '아니오';
  if (Array.isArray(value)) return value.length === 0 ? NONE : value.map((item) => valueWord(action, '', item)).join(', ');
  if (isRecord(value)) {
    if (Object.keys(value).length === 0) return NONE;
    return field === 'filter' ? filterWord(value) : JSON.stringify(value);
  }
  const text = typeof value === 'string' ? value : JSON.stringify(value);
  if (TIME_FIELDS.has(field)) return timeWord(value) ?? text;
  // The open issues a 배정 빼기 or 계정 정지 cleared the assignee of (S3-AC-72), and the open tasks (slice 6b).
  if ((field === 'clearedIssues' || field === 'clearedTasks') && typeof value === 'number') return `${text}건`;
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
const WEEKDAY_WORDS: Readonly<Record<string, string>> = {
  MONDAY: '월요일',
  TUESDAY: '화요일',
  WEDNESDAY: '수요일',
  THURSDAY: '목요일',
  FRIDAY: '금요일',
  SATURDAY: '토요일',
  SUNDAY: '일요일',
};
const SHOWN_DAYS = 10;

function records(value: unknown): Record<string, unknown>[] | null {
  return Array.isArray(value) && value.every(isRecord) ? value : null;
}

function goalWord(minutes: unknown): string {
  return typeof minutes === 'number' ? slaLength(minutes) : NONE;
}

/** "보통 첫 응답 4시간 → 2시간, 해결 12시간 → 10시간 · 높음 첫 응답 1시간 → 없음": changed goals only. */
function targetsWord(from: Record<string, unknown>[], to: Record<string, unknown>[]): string {
  const parts: string[] = [];
  for (const after of to) {
    const before = from.find((t) => t.priority === after.priority);
    const goals: string[] = [];
    for (const [key, name] of [
      ['firstResponseMinutes', '첫 응답'],
      ['resolutionMinutes', '해결'],
    ] as const) {
      const was = before?.[key] ?? null;
      const is = after[key] ?? null;
      if (was !== is) goals.push(`${name} ${goalWord(was)} → ${goalWord(is)}`);
    }
    if (goals.length > 0) parts.push(`${word(PRIORITY_WORDS, String(after.priority))} ${goals.join(', ')}`);
  }
  return parts.length === 0 ? NONE : parts.join(' · ');
}

function spanWord(day: Record<string, unknown> | undefined): string {
  if (day === undefined || typeof day.opens !== 'string' || typeof day.closes !== 'string') return '쉼';
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
  return parts.length === 0 ? NONE : parts.join(' · ');
}

function daysWord(days: string[]): string {
  const shown = days.slice(0, SHOWN_DAYS).join(', ');
  return days.length > SHOWN_DAYS ? `${shown} 외 ${String(days.length - SHOWN_DAYS)}개` : shown;
}

/** "더함 2027-01-01, 뺌 2026-12-25": the days are written as they are stored, with the year. */
function holidaysWord(from: string[], to: string[]): string {
  const added = to.filter((d) => !from.includes(d)).sort();
  const removed = from.filter((d) => !to.includes(d)).sort();
  const parts = [added.length > 0 ? `더함 ${daysWord(added)}` : '', removed.length > 0 ? `뺌 ${daysWord(removed)}` : ''].filter(Boolean);
  return parts.length === 0 ? NONE : parts.join(', ');
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
  return typeof value === 'string' ? value : '기본';
}

/**
 * A branding change (slice 9 screens.md 4절): an accent of `null` is "기본"; a logo is only "없음" or
 * "바뀜", never its fingerprint. Null for any other field.
 */
function brandingWord(action: string, field: string, from: unknown, to: unknown): string | null {
  if (action === 'BRANDING_CHANGED' && field === 'accent') return `${accentWord(from)} → ${accentWord(to)}`;
  if (action !== 'BRANDING_LOGO_CHANGED' || field !== 'logo') return null;
  if (from !== null && to !== null) return '바뀜';
  return `${from === null ? NONE : '바뀜'} → ${to === null ? NONE : '바뀜'}`;
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
