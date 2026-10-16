// 1b screens.md C-12 "바뀐 값": 시각 칸, 빈 목록과 빈 객체, 내보내기의 조건을 읽을 수 있는 말로 보인다(S1B-AC-36).
import { describe, expect, test } from 'vitest';
import { changeLines } from './auditWords';

/** The time the way RelativeTime shows an exact one, in the time zone the test runs in. */
function shownTime(iso: string): string {
  return new Intl.DateTimeFormat(document.documentElement.lang || 'ko', { dateStyle: 'long', timeStyle: 'short' }).format(new Date(iso));
}

describe('changeLines', () => {
  test('S1B-AC-36 changeLines 시각 칸: expiresAt은 "만료", lockedUntil은 "잠금 풀림"이고 값은 지역 시각', () => {
    expect(changeLines('INVITATION_RESENT', { expiresAt: '2026-10-19T03:04:05.123456Z' })).toEqual([`만료: ${shownTime('2026-10-19T03:04:05.123456Z')}`]);
    expect(changeLines('ACCOUNT_LOCKED', { lockedUntil: '2026-10-12T00:15:00Z' })).toEqual([`잠금 풀림: ${shownTime('2026-10-12T00:15:00Z')}`]);
  });

  test('S1B-AC-36 changeLines 시각 칸: 시각으로 읽히지 않는 값은 그대로', () => {
    expect(changeLines('INVITATION_RESENT', { expiresAt: 'soon' })).toEqual(['만료: soon']);
  });

  test('S1B-AC-36 changeLines 빈 값: 빈 목록과 빈 객체는 "없음"', () => {
    expect(changeLines('INVITATION_CREATED', { companyCodes: [] })).toEqual(['고객사: 없음']);
    expect(changeLines('AUDIT_EXPORTED', { format: 'CSV', count: 3, filter: {} })).toEqual(['형식: CSV', '건수: 3', '조건: 없음']);
    expect(changeLines('LEGACY_THING', { extra: {} })).toEqual(['extra: 없음']);
  });

  test('S1B-AC-36 changeLines 내보내기의 조건: 필터 줄의 말로 칸마다 보인다', () => {
    const filter = {
      action: ['ACCOUNT_SUSPENDED', 'INVITATION_CREATED'],
      actorAccountId: 0,
      targetType: 'COMPANY',
      targetAccountId: 12,
      targetCode: 'ACME',
      from: '2026-10-06T15:00:00Z',
      to: '2026-10-07T15:00:00Z',
    };

    expect(changeLines('AUDIT_EXPORTED', { filter })).toEqual([
      `조건: 종류: 계정 정지, 초대, 한 사람: 시스템, 대상: 고객사 ACME · 계정 12, 부터: ${shownTime(filter.from)}, 까지: ${shownTime(filter.to)}`,
    ]);
  });

  test('S1B-AC-36 changeLines 내보내기의 조건: 묶음의 종류를 모두 걸었으면 묶음 이름, 로그인 기록만 뺐으면 "로그인 기록 빼고"', () => {
    const signIn = ['SIGN_IN_SUCCEEDED', 'SIGN_IN_FAILED', 'ACCOUNT_LOCKED'];
    const others = [
      'FIRST_ADMIN_CREATED',
      'ACCOUNT_NAME_CHANGED',
      'ACCOUNT_ROLE_CHANGED',
      'ACCOUNT_SUSPENDED',
      'ACCOUNT_REACTIVATED',
      'ACCOUNT_PASSWORD_RESET',
      'PASSWORD_CHANGED',
      'PASSWORD_RESET_COMPLETED',
      'INVITATION_CREATED',
      'INVITATION_RESENT',
      'INVITATION_CANCELLED',
      'INVITATION_LINK_CREATED',
      'COMPANY_CREATED',
      'COMPANY_UPDATED',
      'COMPANY_STATUS_CHANGED',
      'LABEL_CREATED',
      'LABEL_UPDATED',
      'LABEL_DELETED',
      'ISSUE_DELETED',
      'ISSUE_RESTORED',
      'ISSUE_PURGED',
      'CUSTOMER_ADDED',
      'CUSTOMER_REMOVED',
      'AGENT_ASSIGNED',
      'AGENT_UNASSIGNED',
      'MANAGER_MARKED',
      'MANAGER_UNMARKED',
      'MEMBERSHIP_REQUESTED',
      'MEMBERSHIP_REQUEST_WITHDRAWN',
      'MEMBERSHIP_REQUEST_APPROVED',
      'MEMBERSHIP_REQUEST_REJECTED',
      'ADMIN_PASSWORD_RESET',
      'BACKUP_CREATED',
      'DATABASE_RESTORED',
      'AUDIT_EXPORTED',
      'SLA_POLICY_CHANGED',
      'DELIVERY_RESENT',
      'TEST_MAIL_SENT',
    ];

    expect(changeLines('AUDIT_EXPORTED', { filter: { action: signIn } })).toEqual(['조건: 종류: 로그인 기록']);
    expect(changeLines('AUDIT_EXPORTED', { filter: { action: others } })).toEqual(['조건: 종류: 로그인 기록 빼고']);
    expect(changeLines('AUDIT_EXPORTED', { filter: { action: ['AUDIT_EXPORTED'] } })).toEqual(['조건: 종류: 감사 로그 내보내기']);
  });

  test('S1B-AC-36 changeLines 내보내기의 조건: 부터와 까지만 걸려도 "전 → 뒤"로 읽지 않고, 모르는 키와 종류는 그대로', () => {
    const filter = { from: '2026-10-06T15:00:00Z', to: '2026-10-07T15:00:00Z' };

    expect(changeLines('AUDIT_EXPORTED', { filter })).toEqual([`조건: 부터: ${shownTime(filter.from)}, 까지: ${shownTime(filter.to)}`]);
    expect(changeLines('AUDIT_EXPORTED', { filter: { action: ['LEGACY_THING'], flavor: 'mint' } })).toEqual(['조건: 종류: LEGACY_THING, flavor: mint']);
  });

  test('S1B-AC-36 changeLines 모르는 칸의 객체: 그대로(JSON) 보인다', () => {
    expect(changeLines('LEGACY_THING', { extra: { a: 1 } })).toEqual(['extra: {"a":1}']);
  });
});
