// Screen texts chosen by error `code` (UX-5, screens.md 2절). A `code` the
// screen does not know shows the server `message`.
import type { Failure } from './result';

export const TEXT = {
  displayName: 'popdesk',
  skipLink: '본문으로 건너뛰기',
  logout: '로그아웃',
  menu: '메뉴',
  sidebar: '옆 메뉴 펼치기·접기',
  sending: '보내는 중…',
  close: '닫기',
  cancel: '취소',
  retryLoad: '다시 불러오기',
  notFound: '찾을 수 없습니다',
  notFoundHelp: '없는 주소이거나 볼 수 없는 대상입니다.',
  noPermission: '권한이 없습니다',
  noPermissionHelp: '이 화면은 관리자만 씁니다.',
  sessionEnded: '로그인이 끝났습니다. 다시 로그인하세요.',
  saveSessionEnded: '로그인이 끝났습니다. 다른 탭에서 다시 로그인한 뒤 다시 보내세요.',
  openLogin: '새 탭에서 로그인',
  forbidden: '이 작업을 할 권한이 없습니다.',
  server: '서버에 연결하지 못했습니다. 잠시 뒤 다시 하세요.',
  tooLarge: '파일이 서버 앞단의 크기 상한에 걸렸습니다',
  canRetry: '이제 다시 보낼 수 있습니다.',
  leaveTitle: '쓰던 글이 있습니다',
  leaveMessage: '이 화면을 떠나면 쓰던 글이 사라집니다.',
  leave: '떠나기',
  stay: '머무르기',
} as const;

export function rateLimitedText(seconds: number): string {
  return `요청이 많습니다. ${String(seconds)}초 뒤 다시 하세요.`;
}

export function fieldSummaryTitle(count: number): string {
  return `${String(count)}개 칸을 고쳐 주세요`;
}

/** Page announcement after paging (screens.md P-3): "2쪽, 45건 가운데 21~40". */
export function pageAnnouncement(page: number, size: number, total: number, shown: number): string {
  const from = (page - 1) * size + 1;
  const to = from + shown - 1;
  return `${String(page)}쪽, ${String(total)}건 가운데 ${String(from)}~${String(to)}`;
}

export interface FieldLimits {
  min?: number;
  max?: number;
  /** What the field holds, e.g. "이메일" for INVALID_FORMAT. */
  format?: string;
}

/** Text under a field for a `fields[].code` (conventions.md field codes). */
export function fieldText(code: string, limits: FieldLimits = {}): string {
  switch (code) {
    case 'REQUIRED':
      return '값을 넣어 주세요.';
    case 'BLANK':
      return '공백만으로는 안 됩니다.';
    case 'TOO_SHORT':
      return limits.min === undefined ? '너무 짧습니다.' : `${String(limits.min)}자 이상 넣어 주세요.`;
    case 'TOO_LONG':
      return limits.max === undefined ? '너무 깁니다.' : `${limits.max.toLocaleString('en-US')}자까지 쓸 수 있습니다.`;
    case 'INVALID_FORMAT':
      return limits.format === undefined ? '모양이 맞지 않습니다.' : `${limits.format} 모양이 아닙니다.`;
    case 'UNKNOWN_VALUE':
      return '고를 수 없는 값입니다.';
    case 'SAME_AS_EMAIL':
      return '이메일과 같은 비밀번호는 쓸 수 없습니다.';
    case 'NOT_ALLOWED':
      return '이 칸은 비워 두어야 합니다.';
    default:
      return '값을 고쳐 주세요.';
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
      return TEXT.forbidden;
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
