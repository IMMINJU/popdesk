// 4b screens.md 6절 7, 8 C-12 감사 로그: 종류 묶음 "보내기"와 두 종류의 말, 그 둘의 바뀐 값(S4B-AC-20, S4B-AC-27).
import { describe, expect, test } from 'vitest';
import { renderAt, text } from '../__tests__/console';
import { audit, chosenKind, column, entry, entryRows, kindSelect, listedKinds } from '../__tests__/console1b';
import { changeLines } from '../app/auditWords';

const URL_LOG = '/audit-log';
const GROUP = '보내기';

/** What the server records (design.md 0절 3): the channel and the kind of the row; whether the test email went, and the error kind when it did not. */
const RESENT = entry({ id: 7902, action: 'DELIVERY_RESENT', target: null, changes: { channel: 'MAIL', kind: 'STAFF_COMMENT' } });
const TEST_MAIL_OK = entry({ id: 7901, action: 'TEST_MAIL_SENT', target: null, changes: { ok: true } });
const TEST_MAIL_FAILED = entry({ id: 7900, action: 'TEST_MAIL_SENT', target: null, changes: { ok: false, errorKind: 'SMTP_REFUSED' } });

/** The choices of the 종류 filter that stand for more than one kind: the ones outside an `optgroup`. */
function wholeChoices(select: HTMLSelectElement): string[] {
  return Array.from(select.options)
    .filter((o) => o.closest('optgroup') === null)
    .map((o) => text(o));
}

describe('C-12 감사 로그 (4b)', () => {
  test('S4B-AC-20 S4B-AC-27 C-12 일의 종류: DELIVERY_RESENT는 "다시 보내기", TEST_MAIL_SENT는 "시험 메일"이고 바뀐 값은 한국어 말로 보인다', async () => {
    audit.entries = [RESENT, TEST_MAIL_OK, TEST_MAIL_FAILED];
    renderAt(URL_LOG);

    await entryRows(3);
    expect(column('일')).toEqual(['다시 보내기', '시험 메일', '시험 메일']);
    const shown = column('바뀐 값');
    expect(shown[0]).toContain('채널: 메일');
    expect(shown[0]).toContain('종류: STAFF_COMMENT');
    expect(shown[1]).toBe('보냄: 예');
    expect(shown[2]).toContain('보냄: 아니오');
    expect(shown[2]).toContain('오류 종류: SMTP_REFUSED');
  });

  test('S4B-AC-20 S4B-AC-27 C-12 종류 필터의 묶음 "보내기": 묶음 선택지의 맨 끝이고 그 아래 두 종류가 있다. 고르면 action이 그 둘이다', async () => {
    audit.entries = [RESENT, TEST_MAIL_OK, entry({ id: 7890 })];
    const { user } = renderAt(URL_LOG);
    await entryRows(3);
    const kind = await kindSelect();

    expect(wholeChoices(kind).at(-1), '묶음 선택지의 맨 끝').toBe(GROUP);
    const last = Array.from(kind.querySelectorAll('optgroup')).at(-1);
    expect(last?.label, '낱개 묶음의 맨 끝').toBe(GROUP);
    expect(Array.from(last?.querySelectorAll('option') ?? []).map((o) => text(o))).toEqual(['다시 보내기', '시험 메일']);

    await user.selectOptions(kind, GROUP);

    await listedKinds(['DELIVERY_RESENT', 'TEST_MAIL_SENT']);
    await entryRows(2);
    expect(column('일')).toEqual(['다시 보내기', '시험 메일']);
    expect(chosenKind(kind)).toBe(GROUP);
  });

  test.each([
    ['MAIL', '메일'],
    ['WEBHOOK', '웹훅'],
    ['AUDIT_FORWARD', '감사 바깥 사본'],
  ])('S4B-AC-20 changeLines 다시 보내기의 채널 %s: "채널: %s", 종류는 서버 글자 그대로', (channel, word) => {
    expect(changeLines('DELIVERY_RESENT', { channel, kind: 'INVITATION' })).toEqual([`채널: ${word}`, '종류: INVITATION']);
  });

  test.each([
    { what: '됐다', changes: { ok: true }, lines: ['보냄: 예'] },
    { what: '실패', changes: { ok: false, errorKind: 'CONNECTION_FAILED' }, lines: ['보냄: 아니오', '오류 종류: CONNECTION_FAILED'] },
  ])('S4B-AC-27 changeLines 시험 메일의 결과($what): $lines', ({ changes, lines }) => {
    expect(changeLines('TEST_MAIL_SENT', changes)).toEqual(lines);
  });
});
