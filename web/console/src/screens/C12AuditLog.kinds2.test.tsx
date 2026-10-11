// 2번 묶음 screens.md 2절 C-12 감사 로그: 새 종류 여섯의 말. 묶음은 "소속·배정"이다.
// 1b의 가짜 서버(console1b.ts)는 모르는 종류를 400으로 답하므로, 묶음을 거는 테스트는 목록 답을 바꿔 둔다.
import { HttpResponse } from 'msw/http';
import { describe, expect, test } from 'vitest';
import { on, paged, renderAt } from '../__tests__/console';
import { AUDIT, audit, chosenKind, column, entry, entryRows, kindSelect, kindsOf, listedKinds } from '../__tests__/console1b';

const URL_LOG = '/audit-log';

const NEW_KINDS = [
  ['MANAGER_MARKED', '담당자 지정'],
  ['MANAGER_UNMARKED', '담당자 해제'],
  ['MEMBERSHIP_REQUESTED', '소속 요청'],
  ['MEMBERSHIP_REQUEST_WITHDRAWN', '소속 요청 거둠'],
  ['MEMBERSHIP_REQUEST_APPROVED', '소속 요청 승인'],
  ['MEMBERSHIP_REQUEST_REJECTED', '소속 요청 거절'],
] as const;

describe('C-12 감사 로그 (2번 묶음)', () => {
  test.each(NEW_KINDS)('C-12 일의 종류 %s의 말: "%s"', async (action, word) => {
    audit.entries = [entry({ id: 7801, action, target: { type: 'COMPANY', code: 'ACME', accountId: 12 }, changes: null })];
    renderAt(URL_LOG);

    await entryRows(1);
    expect(column('일')).toEqual([word]);
  });

  test('C-12 종류 필터의 묶음 "소속·배정": 있던 종류 넷과 새 종류 여섯을 모두 보낸다', async () => {
    on('get', AUDIT, () => HttpResponse.json(paged(audit.entries)));
    const { user } = renderAt(URL_LOG);
    await entryRows(audit.entries.length);
    const kind = await kindSelect();
    await user.selectOptions(kind, '소속·배정');

    await listedKinds([...kindsOf('소속·배정'), ...NEW_KINDS.map(([action]) => action)]);
    expect(chosenKind(kind)).toBe('소속·배정');
  });
});
