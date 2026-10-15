// 7a 묶음 screens.md 3절 C-12 감사 로그: 문의 항목 셋의 종류 이름, 종류 고르기의 "고객사" 묶음 자리와 묶음 차례,
// 대상(고객사 코드와 문의 키)(S7A-AC-52).
import { describe, expect, test } from 'vitest';
import { renderAt, text } from '../__tests__/console';
import { KIND_GROUPS, audit, column, entry, entryRows, kindSelect, kindsOf, listedKinds } from '../__tests__/console1b';

const URL_LOG = '/audit-log';
const ISSUE_TARGET = { type: 'COMPANY', code: 'ACME', issueKey: 'ACME-7' } as const;

describe('C-12 감사 로그의 문의 항목 (7a 묶음)', () => {
  test.each([
    ['ISSUE_DELETED', '문의 지우기'],
    ['ISSUE_RESTORED', '문의 복구'],
    ['ISSUE_PURGED', '문의 영구 삭제'],
  ])('S7A-AC-52 C-12 %s: 일은 "%s"이고 대상은 고객사 코드와 문의 키다', async (action, word) => {
    audit.entries = [entry({ id: 8101, action, target: ISSUE_TARGET, changes: null })];
    renderAt(URL_LOG);

    await entryRows(1);
    expect(column('일')).toEqual([word]);
    const [target] = column('대상');
    expect(target).toContain('고객사 ACME');
    expect(target).toContain('ACME-7');
  });

  test('S7A-AC-52 C-12 종류 고르기: 문의 셋은 "고객사" 묶음 아래에 있고 묶음 차례는 그대로다. 묶음을 고르면 셋도 함께 보낸다', async () => {
    const { user } = renderAt(URL_LOG);
    await entryRows(audit.entries.length);
    const kind = await kindSelect();

    const groups = Array.from(kind.querySelectorAll('optgroup'));
    expect(groups.map((g) => g.label), '묶음 차례').toEqual(KIND_GROUPS.map((g) => g.group));
    const company = groups.find((g) => g.label === '고객사');
    const words = Array.from(company?.querySelectorAll('option') ?? []).map((o) => text(o));
    expect(words).toEqual(expect.arrayContaining(['문의 지우기', '문의 복구', '문의 영구 삭제']));

    await user.selectOptions(kind, '고객사');
    expect(kindsOf('고객사')).toEqual(expect.arrayContaining(['ISSUE_DELETED', 'ISSUE_RESTORED', 'ISSUE_PURGED']));
    await listedKinds(kindsOf('고객사'));
  });
});
