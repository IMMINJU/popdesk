// 7a 묶음 screens.md 3절 C-4 이력: TRASH 항목의 문장 셋(휴지통으로 옮김, 복구, 그 밖)과 시스템이 한 자동 종료의 STATUS 문장
// (S7A-AC-52).
import { describe, expect, test } from 'vitest';
import { ADMIN_PERSON, found, renderAt, screen, sent, state, text } from '../__tests__/console';
import { history, issueIn, opener, s3, type IssueHistoryEntry } from '../__tests__/console3';
import { SYSTEM_HISTORY_ACTOR } from '../__tests__/console7a';

const URL_7 = '/issues/ACME-7';
const HISTORY_7 = '/api/console/issues/ACME-7/history';

describe('C-4 이력의 휴지통 항목 (7a 묶음)', () => {
  test.each<{ what: string; given: Partial<IssueHistoryEntry>; says: string }>([
    { what: '지우기', given: { field: 'TRASH', oldValue: null, newValue: 'TRASHED', actor: ADMIN_PERSON }, says: '최관리 님이 문의를 휴지통으로 옮겼습니다' },
    { what: '복구', given: { field: 'TRASH', oldValue: 'TRASHED', newValue: null, actor: ADMIN_PERSON }, says: '최관리 님이 문의를 휴지통에서 복구했습니다' },
    { what: '시스템의 영구 삭제(그 밖)', given: { field: 'TRASH', oldValue: 'TRASHED', newValue: 'PURGED', actor: SYSTEM_HISTORY_ACTOR }, says: '시스템이 문의를 바꿨습니다' },
    { what: '모르는 값(그 밖)', given: { field: 'TRASH', oldValue: null, newValue: 'ARCHIVED', actor: ADMIN_PERSON }, says: '최관리 님이 문의를 바꿨습니다' },
    { what: '자동 종료', given: { field: 'STATUS', oldValue: 'RESOLVED', newValue: 'CLOSED', actor: SYSTEM_HISTORY_ACTOR }, says: '시스템이 상태를 해결에서 종료로 바꿨습니다' },
  ])('S7A-AC-52 C-4 이력($what): "$says"', async ({ given, says }) => {
    state.issue = issueIn('IN_PROGRESS');
    s3.history = [history({ id: 5, ...given })];
    const { user } = renderAt(URL_7);
    await user.click(await opener(/^이력/));
    await sent('GET', HISTORY_7);

    const line = await found(() => screen.queryAllByRole('listitem').find((li) => /(최관리 님이|시스템이) /.test(text(li))), '이력의 줄');
    expect(text(line)).toContain(says);
  });
});
