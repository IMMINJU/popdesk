// Screen texts chosen by error `code` (UX-5, screens.md 2절) that read the same
// in both apps. A `code` the screen does not know shows the server `message`.
// An app adds its own texts on top of `TEXT`.
import type { MessageKey } from '@popdesk/i18n';
import { t, texts, tf } from './locale';
import type { Failure } from './result';

// The words for SLA minutes (8a-sla), exported through this file.
export { slaLength, slaWords } from './slaWords';

/** The keys of `TEXT`; an app adds its own to these (`texts` in locale.ts). */
export const TEXT_KEYS = {
  skipLink: 'app.skipLink',
  logout: 'app.logout',
  menu: 'app.menu',
  sending: 'app.sending',
  close: 'app.close',
  cancel: 'app.cancel',
  retryLoad: 'app.retryLoad',
  notFound: 'app.notFound',
  notFoundHelp: 'app.notFoundHelp',
  noPermission: 'app.noPermission',
  sessionEnded: 'app.sessionEnded',
  saveSessionEnded: 'app.saveSessionEnded',
  openLogin: 'app.openLogin',
  forbidden: 'app.forbidden',
  stale: 'app.stale',
  server: 'app.server',
  tooLarge: 'app.tooLarge',
  canRetry: 'app.canRetry',
  leaveTitle: 'app.leaveTitle',
  leaveMessage: 'app.leaveMessage',
  leave: 'app.leave',
  stay: 'app.stay',
  toIssueList: 'app.toIssueList',
} as const satisfies Record<string, MessageKey>;

/** Each field reads the screen language when it is read (B5-AC-03). */
export const TEXT = withName(texts(TEXT_KEYS));

/** `TEXT` with the product name, which is the same in every language. */
export function withName<T extends object>(table: T): T & { readonly displayName: 'popdesk' } {
  return Object.defineProperty(table, 'displayName', { value: 'popdesk', enumerable: true }) as T & { readonly displayName: 'popdesk' };
}

export function rateLimitedText(seconds: number): string {
  return tf('app.rateLimited', { seconds });
}

export function fieldSummaryTitle(count: number): string {
  return tf('app.fieldSummary', { count });
}

/** Page announcement after paging (screens.md P-3): "2쪽, 45건 가운데 21~40". */
export function pageAnnouncement(page: number, size: number, total: number, shown: number): string {
  const from = (page - 1) * size + 1;
  const to = from + shown - 1;
  return tf('app.pageAnnouncement', { page, total, from, to });
}

export interface FieldLimits {
  min?: number;
  max?: number;
  /** What the field holds, e.g. `t('field.email')` for INVALID_FORMAT. */
  format?: string;
}

/** Text under a field for a `fields[].code` (conventions.md field codes). */
export function fieldText(code: string, limits: FieldLimits = {}): string {
  switch (code) {
    case 'REQUIRED':
      return t('field.required');
    case 'BLANK':
      return t('field.blank');
    case 'TOO_SHORT':
      return limits.min === undefined ? t('field.tooShort') : tf('field.tooShortMin', { min: limits.min });
    case 'TOO_LONG':
      return limits.max === undefined ? t('field.tooLong') : tf('field.tooLongMax', { max: limits.max.toLocaleString('en-US') });
    case 'INVALID_FORMAT':
      return limits.format === undefined ? t('field.invalidFormat') : tf('field.invalidFormatOf', { format: limits.format });
    case 'UNKNOWN_VALUE':
      return t('field.unknownValue');
    case 'SAME_AS_EMAIL':
      return t('field.sameAsEmail');
    case 'NOT_ALLOWED':
      return t('field.notAllowed');
    default:
      return t('field.other');
  }
}

/**
 * Band text for a failure that is not about fields. `known` maps codes the
 * screen knows (e.g. ISSUE_CLOSED) to its own text.
 */
export function failureText(failure: Failure, known: Record<string, string> = {}): string {
  switch (failure.kind) {
    case 'unauthenticated':
      return TEXT.saveSessionEnded;
    case 'forbidden':
      // The request was already sent again with a new token (S1A-AC-57).
      return failure.code === 'CSRF_TOKEN_INVALID' ? TEXT.stale : TEXT.forbidden;
    case 'notFound':
      return known.NOT_FOUND ?? TEXT.notFound;
    case 'rateLimited':
      return rateLimitedText(failure.seconds);
    case 'invalid':
      return failure.message;
    case 'rejected':
      return known[failure.code] ?? failure.message;
    case 'tooLarge':
      return TEXT.tooLarge;
    case 'server':
    case 'network':
      return TEXT.server;
  }
}
