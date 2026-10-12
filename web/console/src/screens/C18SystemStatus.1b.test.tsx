// 1b screens.md 2절 C-18 시스템 상태: 채널 표의 "감사 바깥 사본" 줄(꺼짐과 켜짐),
// `failed` 목록의 채널 말과 감사 항목 ID(S1B-AC-33).
import { describe, expect, test } from 'vitest';
import { channel, expectNoAxeViolations, failedDelivery, found, renderAt, screen, state, text, within } from '../__tests__/console';

const FORWARD = '감사 바깥 사본';
const LAST_FORWARD = '2026-10-08T01:30:00Z';

/** The channel table row whose first cell names the channel. */
function channelRow(name: string): Promise<HTMLElement> {
  return found(
    () => screen.queryAllByRole('row').find((r) => text(within(r).queryAllByRole('cell')[0] ?? within(r).queryAllByRole('rowheader')[0]) === name),
    `채널 줄 "${name}"`,
  );
}

/** The text of `row`'s cell under the column head `header` of its own table. */
function under(row: HTMLElement, header: string): string {
  const head = within(row.closest('table') ?? document.body)
    .queryAllByRole('columnheader')
    .find((h) => text(h).includes(header));
  if (!head?.parentElement) return '';
  return text(row.children[Array.from(head.parentElement.children).indexOf(head)]);
}

describe('C-18 시스템 상태 (1b)', () => {
  test('S1B-AC-33 C-18 감사 바깥 사본(꺼짐): 채널 표에 줄이 있고 "꺼짐"과 설정 방법 링크', async () => {
    state.status = { mail: channel(), webhook: channel(), auditForward: channel({ enabled: false, lastSuccessAt: null }), sandbox: false, sandboxMailToSet: false, cleanupJobs: [] };
    renderAt('/system');

    const row = await channelRow(FORWARD);
    expect(under(row, '상태')).toContain('꺼짐');
    expect(within(row).queryAllByRole('link').length, '설정 방법 링크').toBeGreaterThan(0);
    expect(text(row)).not.toContain('켜짐');
  });

  test('S1B-AC-33 C-18 감사 바깥 사본(켜짐): 메일·웹훅과 같은 칸에 켜짐, 마지막 성공 시각, 보낼 차례, 실패 수', async () => {
    state.status = {
      mail: channel(),
      webhook: channel(),
      auditForward: channel({ enabled: true, lastSuccessAt: LAST_FORWARD, queued: 4, failed: 2 }),
      sandbox: false,
      sandboxMailToSet: false,
      cleanupJobs: [],
    };
    renderAt('/system');

    const row = await channelRow(FORWARD);
    expect(under(row, '상태')).toBe('켜짐');
    const time = row.querySelector('time');
    expect(time ?? null, '마지막 성공 시각 <time>').not.toBeNull();
    expect(new Date(time?.getAttribute('datetime') ?? '').getTime()).toBe(new Date(LAST_FORWARD).getTime());
    expect(under(row, '보낼 차례')).toBe('4');
    expect(under(row, '실패 수')).toBe('2');
    expect(within(row).queryAllByRole('link'), '켜진 채널의 설정 링크').toHaveLength(0);
  });

  test('S1B-AC-33 C-18 `failed` 목록: 채널 칸에 "감사 바깥 사본", 그 줄에 감사 항목 ID. 접근성 위반이 없다', async () => {
    state.status = { mail: channel(), webhook: channel(), auditForward: channel({ failed: 1 }), sandbox: false, sandboxMailToSet: false, cleanupJobs: [] };
    state.failed = [
      failedDelivery({ id: 1, channel: 'AUDIT_FORWARD', lastErrorKind: 'HTTP_503', recipientAccountId: null, auditEntryId: 7731 }),
      failedDelivery({ id: 2, channel: 'MAIL', recipientAccountId: 11 }),
    ];
    renderAt('/system');

    const row = await found(
      () => screen.queryAllByRole('row').find((r) => text(r).includes('HTTP_503')),
      '`failed` 표의 감사 바깥 사본 줄',
    );
    expect(under(row, '채널')).toBe(FORWARD);
    expect(text(row)).toContain('7731');
    const mail = screen.getAllByRole('row').find((r) => text(r).includes('CONNECTION_REFUSED'));
    expect(text(mail), '메일 줄에는 감사 항목 ID가 없다').not.toContain('7731');

    await expectNoAxeViolations();
  });
});
