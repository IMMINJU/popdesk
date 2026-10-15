// 7a 묶음 screens.md 3절 C-18 시스템 상태: 새 정리 작업 둘(issue-auto-close, trash-purge)이 있는 꼴대로 이름순 줄에
// 코드 이름(<code>)으로 늘어난다(S7A-AC-52).
import { describe, expect, test } from 'vitest';
import { found, renderAt, state, within } from '../__tests__/console';
import { cleanupJob, section, status, under } from '../__tests__/console4b';

const URL_SYSTEM = '/system';
const JOBS_TITLE = '정리 작업';

describe('C-18 시스템 상태의 정리 작업 (7a 묶음)', () => {
  test('S7A-AC-52 C-18 정리 작업 다섯: issue-auto-close와 trash-purge가 이름순 자리에 <code>로 있다', async () => {
    state.status = status({
      cleanupJobs: [
        cleanupJob({ name: 'attachment-cleanup', lastRunAt: '2026-10-08T01:00:00Z', deleted: 37 }),
        cleanupJob({ name: 'idempotency-key-cleanup', lastRunAt: '2026-10-07T18:30:00Z', deleted: 0 }),
        cleanupJob({ name: 'issue-auto-close', lastRunAt: '2026-10-08T03:10:00Z', deleted: 2 }),
        cleanupJob({ name: 'session-cleanup', lastRunAt: '2026-10-08T03:00:00Z', deleted: 5 }),
        cleanupJob({ name: 'trash-purge', lastRunAt: null, deleted: null }),
      ],
    });
    renderAt(URL_SYSTEM);

    const part = await section(JOBS_TITLE);
    const table = await found(() => within(part).queryByRole('table'), '정리 작업 표');
    const rows = within(table)
      .queryAllByRole('row')
      .filter((r) => within(r).queryAllByRole('cell').length > 0);
    const names = ['attachment-cleanup', 'idempotency-key-cleanup', 'issue-auto-close', 'session-cleanup', 'trash-purge'];
    expect(rows.map((r) => under(r, '작업'))).toEqual(names);
    expect(rows.map((r) => r.querySelector('code')?.textContent), '작업 이름은 code').toEqual(names);
    expect(under(rows[2] as HTMLElement, '지운 수')).toBe('2');
  });
});
