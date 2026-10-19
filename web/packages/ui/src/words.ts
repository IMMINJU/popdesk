// Words for the value types (tokens.md 1·7절) in the screen language. The components use them,
// and screens use them where a value shows as plain text (a filter, a toast). Each word is read
// when it is used, so a screen drawn after the language changed gets the new word.
import { inLocale } from './language';
import type { AccountStatus, Audience, IssueStatus, Priority, ProjectStatus } from './types';

function words<K extends string>(ko: Readonly<Record<K, string>>, en: Readonly<Record<K, string>>): Readonly<Record<K, string>> {
  const out = {} as Record<K, string>;
  for (const key of Object.keys(ko) as K[]) {
    Object.defineProperty(out, key, { enumerable: true, get: () => inLocale(ko[key], en[key]) });
  }
  return out;
}

export const PRIORITY_WORDS: Readonly<Record<Priority, string>> = words(
  { LOW: '낮음', NORMAL: '보통', HIGH: '높음', URGENT: '긴급' },
  { LOW: 'Low', NORMAL: 'Normal', HIGH: 'High', URGENT: 'Urgent' },
);

/** Customers read plain words, agents read short names (UX-3). */
export const ISSUE_STATUS_WORDS: Readonly<Record<IssueStatus, Readonly<Record<Audience, string>>>> = {
  RECEIVED: words({ customer: '접수됨', agent: '접수' }, { customer: 'Received', agent: 'Received' }),
  CONFIRMED: words({ customer: '확인됨', agent: '확인' }, { customer: 'Confirmed', agent: 'Confirmed' }),
  IN_PROGRESS: words({ customer: '처리 중', agent: '처리 중' }, { customer: 'In progress', agent: 'In progress' }),
  RESOLVED: words({ customer: '해결됨', agent: '해결' }, { customer: 'Resolved', agent: 'Resolved' }),
  CLOSED: words({ customer: '종료됨', agent: '종료' }, { customer: 'Closed', agent: 'Closed' }),
};

export const PROJECT_STATUS_WORDS: Readonly<Record<ProjectStatus, string>> = words(
  { ACTIVE: '사용 중', SUSPENDED: '멈춤', ARCHIVED: '보관' },
  { ACTIVE: 'Active', SUSPENDED: 'Suspended', ARCHIVED: 'Archived' },
);

export const ACCOUNT_STATUS_WORDS: Readonly<Record<AccountStatus, string>> = words(
  { ACTIVE: '사용 중', INVITED: '수락 전', INVITE_CANCELLED: '초대 취소됨', SUSPENDED: '정지' },
  { ACTIVE: 'Active', INVITED: 'Not accepted yet', INVITE_CANCELLED: 'Invitation cancelled', SUSPENDED: 'Suspended' },
);
