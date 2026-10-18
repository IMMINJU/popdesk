// 10a 묶음 design.md 0절 12 C-12 감사 로그: 새 종류 셋의 이름과 묶음 자리(ACCOUNT_EXPORTED는 "내보내기",
// ACCOUNT_ANONYMIZED와 AUDIT_RECHAINED는 "계정"), 대상 글, 바뀐 값 줄(fromSeq "시작 번호", toSeq "끝 번호",
// headHash "마지막 hash", clearedIssues는 지금처럼 "비운 담당")(S10A-AC-06, 16, 18의 화면).
// 한 사람 필터는 익명 계정도 찾는다: 계정 찾기에 anonymized를 보내지 않는다(S10A-AC-36의 반대쪽).
import { describe, expect, test } from 'vitest';
import { account, button, callsTo, expectNoAxeViolations, found, renderAt, screen, state, text } from '../__tests__/console';
import { KIND_GROUPS, audit, column, entry, entryRows, hashOf, kindSelect, kindsOf, listedKinds } from '../__tests__/console1b';
import { ANON_NAME, anonymous } from '../__tests__/console10a';

const URL_LOG = '/audit-log';
const ACCOUNT_12 = { type: 'ACCOUNT', accountId: 12 } as const;
const FROM_HASH = hashOf(4410);
const TO_HASH = 'ab'.repeat(32);

const EXPORTED = entry({ id: 9301, action: 'ACCOUNT_EXPORTED', target: ACCOUNT_12, changes: null });
const ANONYMIZED = entry({ id: 9303, action: 'ACCOUNT_ANONYMIZED', target: ACCOUNT_12, changes: { clearedIssues: 3 } });
const RECHAINED = entry({
  id: 9302,
  action: 'AUDIT_RECHAINED',
  target: { type: 'AUDIT_LOG' },
  changes: { fromSeq: 120, toSeq: 4410, headHash: { from: FROM_HASH, to: TO_HASH } },
});

describe('C-12 감사 로그의 익명화 항목 (10a 묶음)', () => {
  test.each([
    ['ACCOUNT_EXPORTED', EXPORTED, '계정 정보 내보내기', '계정 12'],
    ['ACCOUNT_ANONYMIZED', ANONYMIZED, '익명화', '계정 12'],
    ['AUDIT_RECHAINED', RECHAINED, '감사 사슬 다시 계산', '감사 로그'],
  ])('S10A-AC-06 S10A-AC-16 S10A-AC-18 C-12 %s: 일과 대상의 글', async (_, one, word, target) => {
    audit.entries = [one];
    renderAt(URL_LOG);

    await entryRows(1);
    expect(column('일')).toEqual([word]);
    expect(column('대상')).toEqual([target]);
  });

  test('S10A-AC-18 C-12 감사 사슬 다시 계산의 바뀐 값: "시작 번호: 120", "끝 번호: 4410", "마지막 hash: 앞 → 뒤"', async () => {
    audit.entries = [RECHAINED];
    renderAt(URL_LOG);

    await entryRows(1);
    const [shown] = column('바뀐 값');
    expect(shown).toContain('시작 번호: 120');
    expect(shown).toContain('끝 번호: 4410');
    expect(shown).toContain(`마지막 hash: ${FROM_HASH} → ${TO_HASH}`);
    expect(shown).not.toContain('fromSeq');
    expect(shown).not.toContain('headHash');
  });

  test('S10A-AC-16 C-12 익명화의 바뀐 값: 비운 담당이 있으면 "비운 담당: 3건", 없는 항목은 빈 칸. 옛 이름·이메일은 없다', async () => {
    audit.entries = [ANONYMIZED, entry({ id: 9304, action: 'ACCOUNT_ANONYMIZED', target: { type: 'ACCOUNT', accountId: 13 }, changes: null })];
    renderAt(URL_LOG);

    await entryRows(2);
    expect(column('일')).toEqual(['익명화', '익명화']);
    const [cleared, none] = column('바뀐 값');
    expect(cleared).toContain('비운 담당: 3건');
    expect(none).not.toContain('비운 담당');
  });

  test('S10A-AC-16 S10A-AC-18 C-12 종류 고르기: "계정" 아래에 익명화와 감사 사슬 다시 계산, "내보내기" 아래에 계정 정보 내보내기. 묶음 차례는 그대로이고 "내보내기"를 고르면 둘을 보낸다', async () => {
    const { user } = renderAt(URL_LOG);
    await entryRows(audit.entries.length);
    const kind = await kindSelect();

    const groups = Array.from(kind.querySelectorAll('optgroup'));
    expect(groups.map((g) => g.label), '묶음 차례').toEqual(KIND_GROUPS.map((g) => g.group));
    const wordsOf = (name: string) => Array.from(groups.find((g) => g.label === name)?.querySelectorAll('option') ?? []).map((o) => text(o));
    expect(wordsOf('계정')).toEqual(expect.arrayContaining(['익명화', '감사 사슬 다시 계산']));
    expect(wordsOf('내보내기').sort()).toEqual(['감사 로그 내보내기', '계정 정보 내보내기']);

    await user.selectOptions(kind, '내보내기');
    expect(kindsOf('내보내기')).toEqual(['ACCOUNT_EXPORTED', 'AUDIT_EXPORTED']);
    await listedKinds(kindsOf('내보내기'));
  });

  test('S10A-AC-36 C-12 한 사람 필터: 계정 찾기에 anonymized를 보내지 않고 익명 계정도 고를 수 있다', async () => {
    state.accounts = [account(), anonymous(9)];
    const { user } = renderAt(URL_LOG);
    await entryRows(audit.entries.length);
    if (!screen.queryByRole('searchbox')) await user.click(await button(/한 사람/));
    await user.type(await found(() => screen.queryByRole('searchbox'), '한 사람 찾기 칸'), '삭제된');

    await found(() => screen.queryByRole('radio', { name: new RegExp(ANON_NAME) }) ?? screen.queryByRole('option', { name: new RegExp(ANON_NAME) }), `선택지 "${ANON_NAME}"`);
    const query = new URL(callsTo('GET', '/api/console/accounts').at(-1)?.url ?? 'http://x/').searchParams;
    expect(query.get('q')).toBe('삭제된');
    expect(query.has('anonymized')).toBe(false);
  });

  test('C-12 axe (10a): 새 종류 셋의 줄에 접근성 위반이 없다', async () => {
    audit.entries = [ANONYMIZED, RECHAINED, EXPORTED];
    renderAt(URL_LOG);

    await entryRows(3);
    await expectNoAxeViolations();
  });
});
