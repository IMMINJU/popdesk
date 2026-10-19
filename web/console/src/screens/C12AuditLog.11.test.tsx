// 11 묶음 screens.md "C-12 감사 로그": 새 종류 셋(OIDC_LINKED "OIDC 연결", OIDC_UNLINKED "OIDC 연결 풀기"는 "계정" 묶음,
// INVITATION_ACCEPTED "초대 수락"은 "초대" 묶음)과, 로그인 성공·실패 줄의 `method: OIDC`에 붙는 "(회사 계정)"
// (S11-AC-24의 화면). 새 묶음은 없다(design.md 2절).
import { describe, expect, test } from 'vitest';
import { expectNoAxeViolations, renderAt, text } from '../__tests__/console';
import { AGENT_ACTOR, SIGNED_IN, audit, column, entry, entryRows, kindSelect, kindsOf, listedKinds } from '../__tests__/console1b';

const URL_LOG = '/audit-log';
const LEE_ACCOUNT = { type: 'ACCOUNT', accountId: 2 } as const;
const BY_COMPANY_ACCOUNT = '(회사 계정)';

const WORDS = { OIDC_LINKED: 'OIDC 연결', OIDC_UNLINKED: 'OIDC 연결 풀기', INVITATION_ACCEPTED: '초대 수락' } as const;

const LINKED = entry({ id: 9501, action: 'OIDC_LINKED', actor: AGENT_ACTOR, target: LEE_ACCOUNT, changes: { issuer: 'https://id.acme.example' } });
const UNLINKED = entry({ id: 9502, action: 'OIDC_UNLINKED', target: LEE_ACCOUNT, changes: null });
const ACCEPTED = entry({ id: 9503, action: 'INVITATION_ACCEPTED', actor: AGENT_ACTOR, target: LEE_ACCOUNT, changes: { method: 'OIDC' } });
const OIDC_SIGNED_IN = entry({ id: 9504, action: 'SIGN_IN_SUCCEEDED', actor: AGENT_ACTOR, target: LEE_ACCOUNT, changes: { method: 'OIDC' } });
const OIDC_SIGN_IN_FAILED = entry({ id: 9505, action: 'SIGN_IN_FAILED', actor: AGENT_ACTOR, target: LEE_ACCOUNT, changes: { method: 'OIDC' } });

describe('C-12 감사 로그의 OIDC 항목 (11 묶음)', () => {
  test.each([
    ['OIDC_LINKED', LINKED, WORDS.OIDC_LINKED],
    ['OIDC_UNLINKED', UNLINKED, WORDS.OIDC_UNLINKED],
    ['INVITATION_ACCEPTED', ACCEPTED, WORDS.INVITATION_ACCEPTED],
  ])('S11-AC-24 C-12 %s: 일의 이름이 그 문장이다', async (_, one, word) => {
    audit.entries = [one];
    renderAt(URL_LOG);

    await entryRows(1);
    expect(column('일')).toEqual([word]);
    expect(column('대상')).toEqual(['계정 2']);
  });

  test('S11-AC-24 C-12 종류 고르기: "계정" 아래에 OIDC 연결과 풀기, "초대" 아래에 초대 수락. 묶음을 고르면 새 종류도 보낸다. axe 위반이 없다', async () => {
    audit.entries = [LINKED, UNLINKED, ACCEPTED];
    const { user } = renderAt(URL_LOG);
    await entryRows(3);
    const kind = await kindSelect();

    const groups = Array.from(kind.querySelectorAll('optgroup'));
    const wordsOf = (name: string) => Array.from(groups.find((g) => g.label === name)?.querySelectorAll('option') ?? []).map((o) => text(o));
    expect(wordsOf('계정')).toEqual(expect.arrayContaining([WORDS.OIDC_LINKED, WORDS.OIDC_UNLINKED]));
    expect(wordsOf('초대')).toEqual(expect.arrayContaining([WORDS.INVITATION_ACCEPTED]));
    expect(groups.map((g) => g.label).filter((l) => /OIDC|회사 계정/.test(l)), '새 묶음').toEqual([]);
    await expectNoAxeViolations();

    await user.selectOptions(kind, '계정');
    expect(kindsOf('계정')).toEqual(expect.arrayContaining(['OIDC_LINKED', 'OIDC_UNLINKED']));
    await listedKinds(kindsOf('계정'));
    await user.selectOptions(kind, '초대');
    expect(kindsOf('초대')).toContain('INVITATION_ACCEPTED');
    await listedKinds(kindsOf('초대'));
  });

  test('S11-AC-24 C-12 로그인 성공·실패의 method OIDC: 그 줄에 "(회사 계정)"이 붙고, 비밀번호 로그인 줄에는 없다. "method"를 그대로 쓰지 않는다', async () => {
    audit.entries = [OIDC_SIGNED_IN, OIDC_SIGN_IN_FAILED, SIGNED_IN];
    renderAt(URL_LOG);

    const rows = await entryRows(3);
    const [oidcIn, oidcFailed, password] = rows.map(text);
    expect(oidcIn, 'OIDC 로그인 줄').toContain(BY_COMPANY_ACCOUNT);
    expect(oidcFailed, 'OIDC 로그인 실패 줄').toContain(BY_COMPANY_ACCOUNT);
    expect(password, '비밀번호 로그인 줄').not.toContain(BY_COMPANY_ACCOUNT);
    expect(column('바뀐 값').join(' ')).not.toContain('method');
    expect(column('일').slice(0, 2).every((w) => w.startsWith('로그인')), '일의 이름은 그대로 로그인·로그인 실패').toBe(true);
  });
});
