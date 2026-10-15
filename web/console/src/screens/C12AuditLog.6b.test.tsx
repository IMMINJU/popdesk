// 6b 묶음 screens.md 4절 C-12 감사 로그: 라벨 항목 셋의 종류 이름, 종류 고르기의 "고객사" 묶음 자리, 대상(고객사 코드와
// 라벨 ID), 바뀐 값("이름", "색"), 담당 비우기 항목의 "비운 작업: n건"(S6B-AC-88).
import { describe, expect, test } from 'vitest';
import { renderAt, text } from '../__tests__/console';
import { audit, column, entry, entryRows, kindSelect, kindsOf, listedKinds } from '../__tests__/console1b';

const URL_LOG = '/audit-log';
const LABEL_TARGET = { type: 'COMPANY', code: 'ACME', labelId: 61 } as const;

const MADE = entry({ id: 7901, action: 'LABEL_CREATED', target: LABEL_TARGET, changes: { name: '긴급', color: '#C0262D' } });
const CHANGED = entry({ id: 7902, action: 'LABEL_UPDATED', target: LABEL_TARGET, changes: { name: { from: '긴급', to: '급함' }, color: { from: '#C0262D', to: '#245FB0' } } });
const DELETED = entry({ id: 7903, action: 'LABEL_DELETED', target: LABEL_TARGET, changes: { name: '급함', color: '#245FB0' } });

describe('C-12 감사 로그 라벨과 비운 작업 (6b 묶음)', () => {
  test.each([
    ['LABEL_CREATED', '라벨 만들기', MADE],
    ['LABEL_UPDATED', '라벨 고치기', CHANGED],
    ['LABEL_DELETED', '라벨 지우기', DELETED],
  ])('S6B-AC-88 C-12 %s: 일은 "%s"이고 대상은 고객사 코드와 라벨 ID다', async (_, word, given) => {
    audit.entries = [given];
    renderAt(URL_LOG);

    await entryRows(1);
    expect(column('일')).toEqual([word]);
    const [target] = column('대상');
    expect(target).toContain('고객사 ACME');
    expect(target).toContain('61');
  });

  test('S6B-AC-88 C-12 라벨 항목의 바뀐 값: 만들기와 지우기는 "이름: 값", "색: 값", 고치기는 "이름: 전 → 뒤"다', async () => {
    audit.entries = [DELETED, CHANGED, MADE];
    renderAt(URL_LOG);

    await entryRows(3);
    const [deleted, changed, made] = column('바뀐 값');
    expect(made).toContain('이름: 긴급');
    expect(made).toContain('색: #C0262D');
    expect(changed).toContain('이름: 긴급 → 급함');
    expect(changed).toContain('색: #C0262D → #245FB0');
    expect(deleted).toContain('이름: 급함');
    expect(`${made ?? ''} ${changed ?? ''}`, '칸 이름을 그대로 보이지 않는다').not.toMatch(/\bname\b|\bcolor\b/);
  });

  test('S6B-AC-88 C-12 종류 고르기: 라벨 셋은 "고객사" 묶음 아래에 있고, 묶음을 고르면 셋도 함께 보낸다', async () => {
    const { user } = renderAt(URL_LOG);
    await entryRows(audit.entries.length);
    const kind = await kindSelect();

    const group = Array.from(kind.querySelectorAll('optgroup')).find((g) => g.label === '고객사');
    expect(group, '낱개 묶음 "고객사"').toBeDefined();
    const words = Array.from(group?.querySelectorAll('option') ?? []).map((o) => text(o));
    expect(words).toEqual(expect.arrayContaining(['라벨 만들기', '라벨 고치기', '라벨 지우기']));
    expect(Array.from(kind.querySelectorAll('optgroup')).filter((g) => /라벨/.test(g.label)), '새 묶음은 없다').toEqual([]);

    await user.selectOptions(kind, '고객사');
    expect(kindsOf('고객사')).toEqual(expect.arrayContaining(['LABEL_CREATED', 'LABEL_UPDATED', 'LABEL_DELETED']));
    await listedKinds(kindsOf('고객사'));
  });

  test.each([
    ['배정 빼기', entry({ id: 7811, action: 'AGENT_UNASSIGNED', target: { type: 'COMPANY', code: 'ACME', accountId: 2 }, changes: { assigned: false, clearedIssues: 1, clearedTasks: 4 } })],
    ['계정 정지(문의 0건)', entry({ id: 7812, changes: { status: { from: 'ACTIVE', to: 'SUSPENDED' }, clearedIssues: 0, clearedTasks: 4 } })],
  ])('S6B-AC-88 C-12 %s의 바뀐 값: clearedTasks가 있으면 "비운 작업: 4건"이고 칸 이름을 그대로 보이지 않는다', async (_, given) => {
    const kept = entry({ id: 7800 });
    audit.entries = [given, kept];
    renderAt(URL_LOG);

    await entryRows(2);
    const [withTasks, without] = column('바뀐 값');
    expect(withTasks).toContain('비운 작업: 4건');
    expect(withTasks).not.toContain('clearedTasks');
    expect(without).not.toContain('비운 작업');
  });
});
