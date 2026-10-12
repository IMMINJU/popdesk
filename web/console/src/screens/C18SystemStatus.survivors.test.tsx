// C-18 시스템 상태: 4b의 변이 테스트에서 살아남은 줄이 보지 않던 것(design/archive/4b-delivery/verify.md 6절).
// 정리 작업의 차례(4b screens.md 4절), `failed` 표의 빈 ID 칸(1b screens.md 2절), 429의 초를 세는 동안의
// 시험 메일 버튼(spec/screens.md의 429), 오류의 종류 없이 온 실패.
import { describe, expect, test } from 'vitest';
import { alertWith, callsTo, channel, errors, eventually, failedDelivery, found, heading1, isBlocked, on, renderAt, state, text, within } from '../__tests__/console';
import { settle } from '../__tests__/console1a';
import { TEST_MAIL, TEST_MAIL_NAME, channelRow, cleanupJob, failedRow, mailResult, s4b, section, sectionNow, status, under } from '../__tests__/console4b';

const URL_SYSTEM = '/system';
const NONE = '없음';
const MAIL_TITLE = '시험 메일';
const MAIL_NOT_SENT = '보내지 못했습니다.';
const JOBS_TITLE = '정리 작업';

/** The screen with its channel table drawn. A file's first test does not lean on one wait for it. */
async function shown() {
  await heading1();
  await channelRow('메일 보내기');
}

const mailSection = () => sectionNow(MAIL_TITLE);

function mailButton(): Promise<HTMLElement> {
  return found(() => {
    const root = mailSection();
    return root && within(root).queryByRole('button', { name: TEST_MAIL_NAME });
  }, `버튼 "${TEST_MAIL_NAME}"`);
}

const ATTACHMENT = cleanupJob({ name: 'attachment-cleanup', lastRunAt: '2026-10-08T01:00:00Z', deleted: 37 });
const IDEMPOTENCY = cleanupJob({ name: 'idempotency-key-cleanup', lastRunAt: '2026-10-07T18:30:00Z', deleted: 0 });
const SESSION = cleanupJob({ name: 'session-cleanup', lastRunAt: '2026-10-08T03:00:00Z', deleted: 5 });

describe('C-18 시스템 상태: 정리 작업의 차례', () => {
  // The contract gives the jobs in name order. The fake server mixes them to see the screen put them in order itself.
  test.each([
    { what: '거꾸로', given: [SESSION, IDEMPOTENCY, ATTACHMENT] },
    { what: '가운데가 맨 앞', given: [IDEMPOTENCY, SESSION, ATTACHMENT] },
    { what: '끝이 맨 앞', given: [SESSION, ATTACHMENT, IDEMPOTENCY] },
    { what: '뒤의 둘만 바뀜', given: [ATTACHMENT, SESSION, IDEMPOTENCY] },
  ])('S4B-AC-35 C-18 정리 작업 표(받은 차례: $what): 줄은 작업 이름순이고 줄마다 제 값을 보인다', async ({ given }) => {
    state.status = status({ cleanupJobs: given });
    renderAt(URL_SYSTEM);
    await shown();

    const part = await section(JOBS_TITLE);
    const table = await found(() => within(part).queryByRole('table'), '정리 작업 표');
    const rows = within(table)
      .queryAllByRole('row')
      .filter((r) => within(r).queryAllByRole('cell').length > 0);
    expect(rows.map((r) => under(r, '작업'))).toEqual(['attachment-cleanup', 'idempotency-key-cleanup', 'session-cleanup']);
    expect(rows.map((r) => under(r, '지운 수')), '줄의 값이 이름을 따라간다').toEqual(['37', '0', '5']);
  });
});

describe('C-18 시스템 상태: `failed` 표의 빈 ID 칸', () => {
  test('S1B-AC-33 C-18 `failed` 표: 받는 계정이 없는 줄과 감사 항목이 없는 줄은 그 칸이 "없음"이고, 있는 줄은 그 ID다', async () => {
    state.status = status({ mail: channel({ failed: 1 }), webhook: channel({ failed: 1 }), auditForward: channel({ failed: 1 }) });
    state.failed = [
      failedDelivery({ id: 1, channel: 'MAIL', lastErrorKind: 'CONNECTION_REFUSED', recipientAccountId: 11, auditEntryId: null }),
      failedDelivery({ id: 2, channel: 'WEBHOOK', lastErrorKind: 'HTTP_500', recipientAccountId: null, auditEntryId: null }),
      failedDelivery({ id: 4, channel: 'AUDIT_FORWARD', lastErrorKind: 'TIMEOUT', recipientAccountId: null, auditEntryId: 7001 }),
    ];
    renderAt(URL_SYSTEM);
    await shown();

    const mail = await failedRow('CONNECTION_REFUSED');
    expect(under(mail, '받는 계정 ID')).toBe('11');
    expect(under(mail, '감사 항목 ID')).toBe(NONE);
    const hook = await failedRow('HTTP_500');
    expect(under(hook, '받는 계정 ID')).toBe(NONE);
    expect(under(hook, '감사 항목 ID')).toBe(NONE);
    const copy = await failedRow('TIMEOUT');
    expect(under(copy, '받는 계정 ID')).toBe(NONE);
    expect(under(copy, '감사 항목 ID')).toBe('7001');
  });
});

describe('C-18 시스템 상태: 시험 메일', () => {
  test('S4B-AC-34 C-18 시험 메일 429: 초를 세는 동안 버튼이 막히고, 눌러도 다시 보내지 않는다', async () => {
    on('post', TEST_MAIL, () => errors.rateLimited(30));
    const { user } = renderAt(URL_SYSTEM);
    await shown();

    await user.click(await mailButton());

    await alertWith(/요청이 많습니다\. \d+초 뒤 다시 하세요\./);
    const held = await mailButton();
    await eventually(() => {
      expect(isBlocked(held), '초를 세는 동안 버튼 막힘').toBe(true);
    });
    await user.click(held);
    await settle();
    expect(callsTo('POST', TEST_MAIL), '막힌 버튼은 보내지 않는다').toHaveLength(1);
  });

  test('S4B-AC-34 C-18 시험 메일 실패(오류의 종류가 null): 결과 줄은 "보내지 못했습니다."뿐이고 빈 code를 그리지 않는다', async () => {
    s4b.testMail = mailResult({ ok: false, errorKind: null, serverReply: null });
    const { user } = renderAt(URL_SYSTEM);
    await shown();

    await user.click(await mailButton());

    const line = await found(() => {
      const root = mailSection();
      return root && within(root).queryAllByRole('alert').find((el) => text(el).includes(MAIL_NOT_SENT));
    }, `시험 메일 결과 줄(role="alert"): ${MAIL_NOT_SENT}`);
    expect(text(line)).toBe(MAIL_NOT_SENT);
    expect(mailSection()?.querySelectorAll('code'), '오류의 종류 자리').toHaveLength(0);
  });
});
