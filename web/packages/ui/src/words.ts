// Korean words for the value types (tokens.md 1·7절). The components use them,
// and screens use them where a value shows as plain text (a filter, a toast).
import type { AccountStatus, Audience, IssueStatus, Priority, ProjectStatus } from './types';

export const PRIORITY_WORDS: Readonly<Record<Priority, string>> = { LOW: '낮음', NORMAL: '보통', HIGH: '높음', URGENT: '긴급' };

/** Customers read plain words, agents read short names (UX-3). */
export const ISSUE_STATUS_WORDS: Readonly<Record<IssueStatus, Readonly<Record<Audience, string>>>> = {
  RECEIVED: { customer: '접수됨', agent: '접수' },
  CONFIRMED: { customer: '확인됨', agent: '확인' },
  IN_PROGRESS: { customer: '처리 중', agent: '처리 중' },
  RESOLVED: { customer: '해결됨', agent: '해결' },
  CLOSED: { customer: '종료됨', agent: '종료' },
};

export const PROJECT_STATUS_WORDS: Readonly<Record<ProjectStatus, string>> = { ACTIVE: '사용 중', SUSPENDED: '멈춤', ARCHIVED: '보관' };

export const ACCOUNT_STATUS_WORDS: Readonly<Record<AccountStatus, string>> = {
  ACTIVE: '사용 중',
  INVITED: '수락 전',
  INVITE_CANCELLED: '초대 취소됨',
  SUSPENDED: '정지',
};
