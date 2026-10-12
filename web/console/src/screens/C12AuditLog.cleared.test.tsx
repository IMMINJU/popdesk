// 3번 묶음 screens.md 2절 C-12 감사 로그: 담당 비우기와 함께 한 배정 빼기와 계정 정지의 바뀐 값(S3-AC-72).
import { describe, expect, test } from 'vitest';
import { renderAt } from '../__tests__/console';
import { audit, column, entry, entryRows } from '../__tests__/console1b';

const URL_LOG = '/audit-log';
const AGENT_OF_ACME = { type: 'COMPANY', code: 'ACME', accountId: 2 } as const;

describe('C-12 감사 로그 (3번 묶음)', () => {
  test.each([
    ['배정 빼기', entry({ id: 7801, action: 'AGENT_UNASSIGNED', target: AGENT_OF_ACME, changes: { assigned: false } })],
    ['계정 정지', entry({ id: 7802, changes: { status: { from: 'ACTIVE', to: 'SUSPENDED' } } })],
  ])('S3-AC-72 C-12 %s의 바뀐 값: clearedIssues가 있으면 "비운 담당: 3건"이 보이고, 없는 항목에는 없다', async (_, kept) => {
    const cleared = { ...kept, id: kept.id + 100, seq: kept.seq + 100, changes: { ...kept.changes, clearedIssues: 3 } };
    audit.entries = [cleared, kept];
    renderAt(URL_LOG);

    await entryRows(2);
    const [withCleared, without] = column('바뀐 값');
    expect(withCleared).toContain('비운 담당: 3건');
    expect(withCleared, '칸 이름을 그대로 보이지 않는다').not.toContain('clearedIssues');
    expect(without).not.toContain('비운 담당');
    expect(without).not.toBe('');
  });
});
