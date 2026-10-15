// C-12 "바뀐 값"의 말: 4b의 변이 테스트에서 살아남은 줄이 보지 않던 것(design/archive/4b-delivery/verify.md 6절).
// 상태 값의 말(계정은 "정지", 고객사는 "멈춤"), 없는 값과 목록, 내보내기 조건의 종류 목록과 한 사람.
import { describe, expect, test } from 'vitest';
import { changeLines } from './auditWords';

describe('changeLines: 상태 값의 말', () => {
  test.each([
    { action: 'COMPANY_STATUS_CHANGED', from: 'ACTIVE', to: 'SUSPENDED', says: '상태: 사용 중 → 멈춤' },
    { action: 'COMPANY_STATUS_CHANGED', from: 'SUSPENDED', to: 'ARCHIVED', says: '상태: 멈춤 → 보관' },
    { action: 'ACCOUNT_SUSPENDED', from: 'ACTIVE', to: 'SUSPENDED', says: '상태: 사용 중 → 정지' },
    { action: 'INVITATION_CANCELLED', from: 'INVITED', to: 'INVITE_CANCELLED', says: '상태: 수락 전 → 초대 취소됨' },
  ])('S1B-AC-36 changeLines 상태($action, $from → $to): "$says"', ({ action, from, to, says }) => {
    expect(changeLines(action, { status: { from, to } })).toEqual([says]);
  });

  test('S1B-AC-36 changeLines 상태: 그 대상의 상태가 아닌 이름은 그대로', () => {
    // 보관 is a company's state and 수락 전 an account's.
    expect(changeLines('ACCOUNT_SUSPENDED', { status: { from: 'ARCHIVED', to: 'SUSPENDED' } })).toEqual(['상태: ARCHIVED → 정지']);
    expect(changeLines('COMPANY_STATUS_CHANGED', { status: { from: 'INVITED', to: 'SUSPENDED' } })).toEqual(['상태: INVITED → 멈춤']);
  });

  test('S1B-AC-36 changeLines 상태가 아닌 칸: 값이 상태의 이름과 같아도 상태의 말로 바꾸지 않는다', () => {
    expect(changeLines('ACCOUNT_NAME_CHANGED', { name: { from: 'ACTIVE', to: 'SUSPENDED' } })).toEqual(['이름: ACTIVE → SUSPENDED']);
    expect(changeLines('COMPANY_UPDATED', { legalName: { from: 'ACTIVE', to: 'ARCHIVED' } })).toEqual(['회사명: ACTIVE → ARCHIVED']);
  });
});

describe('changeLines: 없는 값과 목록', () => {
  test('S1B-AC-36 changeLines 전이나 뒤가 없음: null도, 값이 빠진 것도 "없음"', () => {
    expect(changeLines('ACCOUNT_ROLE_CHANGED', { role: { from: null, to: 'ADMIN' } })).toEqual(['역할: 없음 → 관리자']);
    expect(changeLines('ACCOUNT_ROLE_CHANGED', { role: { from: 'AGENT', to: undefined } })).toEqual(['역할: 상담원 → 없음']);
    expect(changeLines('ACCOUNT_NAME_CHANGED', { name: null })).toEqual(['이름: 없음']);
    expect(changeLines('ACCOUNT_NAME_CHANGED', { name: undefined })).toEqual(['이름: 없음']);
  });

  test('S1B-AC-36 changeLines 목록: 든 것을 쉼표로 이어 보이고, 빈 목록만 "없음"', () => {
    expect(changeLines('INVITATION_CREATED', { companyCodes: ['ACME', 'GLOBEX'] })).toEqual(['고객사: ACME, GLOBEX']);
    expect(changeLines('INVITATION_CREATED', { companyCodes: ['ACME'] })).toEqual(['고객사: ACME']);
    expect(changeLines('INVITATION_CREATED', { companyCodes: { from: [], to: ['ACME', 'GLOBEX'] } })).toEqual(['고객사: 없음 → ACME, GLOBEX']);
  });
});

describe('changeLines: 내보내기의 조건', () => {
  const filtered = (filter: Record<string, unknown>) => changeLines('AUDIT_EXPORTED', { filter });

  test('S1B-AC-36 changeLines 조건의 종류: 묶음의 종류가 모두 있을 때만 묶음 이름이다. 차례는 달라도 된다', () => {
    expect(filtered({ action: ['ACCOUNT_LOCKED', 'SIGN_IN_SUCCEEDED', 'SIGN_IN_FAILED'] })).toEqual(['조건: 종류: 로그인 기록']);
    expect(
      filtered({
        action: ['COMPANY_STATUS_CHANGED', 'ISSUE_PURGED', 'LABEL_DELETED', 'COMPANY_CREATED', 'ISSUE_DELETED', 'LABEL_CREATED', 'COMPANY_UPDATED', 'ISSUE_RESTORED', 'LABEL_UPDATED'],
      }),
    ).toEqual(['조건: 종류: 고객사']);
  });

  test.each([
    { what: '수는 같고 하나가 다른 묶음의 것', action: ['SIGN_IN_SUCCEEDED', 'SIGN_IN_FAILED', 'ACCOUNT_SUSPENDED'], says: '로그인, 로그인 실패, 계정 정지' },
    { what: '묶음에서 하나가 빠짐', action: ['SIGN_IN_SUCCEEDED', 'SIGN_IN_FAILED'], says: '로그인, 로그인 실패' },
    { what: '묶음에 하나를 더함', action: ['DELIVERY_RESENT', 'TEST_MAIL_SENT', 'AUDIT_EXPORTED'], says: '다시 보내기, 시험 메일, 감사 로그 내보내기' },
  ])('S1B-AC-36 changeLines 조건의 종류($what): 묶음 이름이 아니라 종류를 하나씩 보인다', ({ action, says }) => {
    expect(filtered({ action })).toEqual([`조건: 종류: ${says}`]);
  });

  test.each([
    { what: '종류가 아닌 키의 글자 목록', filter: { flavor: ['mint', 'lime'] }, says: 'flavor: mint, lime' },
    { what: '글자가 아닌 것이 섞인 action', filter: { action: ['ACCOUNT_SUSPENDED', 7] }, says: 'action: ACCOUNT_SUSPENDED, 7' },
    { what: '목록이 아닌 action', filter: { action: 'ACCOUNT_SUSPENDED' }, says: 'action: ACCOUNT_SUSPENDED' },
  ])('S1B-AC-36 changeLines 조건의 종류($what): 종류의 말로 풀지 않고 키와 값 그대로', ({ filter, says }) => {
    expect(filtered(filter)).toEqual([`조건: ${says}`]);
  });

  test.each([
    { what: '시스템(0)', actor: 0, says: '시스템' },
    { what: '계정', actor: 12, says: '계정 12' },
    { what: '수가 아닌 값', actor: 'someone', says: 'someone' },
    { what: '없는 값', actor: null, says: '없음' },
  ])('S1B-AC-36 changeLines 조건의 한 사람($what): "한 사람: $says"', ({ actor, says }) => {
    expect(filtered({ actorAccountId: actor })).toEqual([`조건: 한 사람: ${says}`]);
  });
});
