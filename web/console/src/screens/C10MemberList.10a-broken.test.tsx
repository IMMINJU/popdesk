// 10a 묶음 screens.md C-10 익명화 대화상자의 409 AUDIT_CHAIN_BROKEN(S10A-AC-37): 감사 로그의 사슬이 끊겨 있어
// 서버가 익명화를 거절하면 대화상자를 닫지 않고 그 안에 까닭을 보인다. 끊긴 번호(brokenSeq)는 보이지 않는다.
// 줄은 그대로다. 나머지 익명화 결과는 C10MemberList.10a.test.tsx가 본다.
import { describe, expect, test } from 'vitest';
import { account, apiError, eventually, expectNoAxeViolations, found, openDialog, renderAt, screen, sent, state, text, within, type Account } from '../__tests__/console';
import { openRowAction, route, rowOf } from '../__tests__/console1a';
import { expectNotSaid } from '../__tests__/console2';
import { contract } from '../__tests__/console6a';
import { ANONYMIZE, ANONYMIZED, ANONYMIZING, anonymizePath, emailLabel } from '../__tests__/console10a';

const LEE = account({ id: 2, email: 'lee@popdesk.test', name: '이상담', role: 'AGENT' });
const JUNG = account({ id: 3, email: 'jung@popdesk.test', name: '정상담', role: 'AGENT' });

/** screens.md의 문구(S10A-AC-37). */
const CHAIN_BROKEN = '감사 로그의 사슬이 끊겨 있어 익명화하지 않았습니다. 감사 로그에서 무결성 확인을 돌려 보세요.';
/** A seq that no other text on the screen holds, so its absence means the screen did not show it. */
const BROKEN_SEQ = 48213;

type User = ReturnType<typeof renderAt>['user'];

function seed() {
  state.accounts = [account(), LEE, JUNG];
}

/** The 409 of the contract: `AUDIT_CHAIN_BROKEN` with the first broken `seq`. */
function chainBroken(): Response {
  return apiError(
    409,
    contract('anonymizeAccount', 409, { code: 'AUDIT_CHAIN_BROKEN', message: 'AUDIT_CHAIN_BROKEN (server text).', brokenSeq: BROKEN_SEQ }),
  );
}

const goNow = (box: HTMLElement) =>
  within(box)
    .queryAllByRole('button')
    .find((b) => [ANONYMIZE, ANONYMIZING].includes(text(b))) ?? null;

/** Opens 익명화 of `who`, types the right email and presses "익명화". */
async function anonymize(user: User, who: Account): Promise<HTMLElement> {
  const box = await openRowAction(user, who.name, ANONYMIZE);
  const field = await found(
    () => within(box).queryAllByLabelText<HTMLInputElement>(emailLabel(who.email), { selector: 'input' })[0],
    `칸 "${emailLabel(who.email)}"`,
  );
  await user.type(field, who.email);
  await user.click(await found(() => goNow(box), '대화상자의 "익명화" 단추'));
  await sent('POST', anonymizePath(who.id));
  return box;
}

/** The text of the cell of the row holding `name` under the column `header`; null while there is none. */
function cellUnder(name: string, header: string): string | null {
  const head = screen.queryAllByRole('columnheader').find((h) => text(h).includes(header));
  const row = screen.queryAllByRole('row').find((r) => within(r).queryAllByRole('cell').some((c) => text(c).includes(name)));
  if (!head?.parentElement || !row) return null;
  const index = Array.from(head.parentElement.children).indexOf(head);
  return text(row.children[index]);
}

describe('C-10 익명화 409 AUDIT_CHAIN_BROKEN (10a 묶음)', () => {
  test('S10A-AC-37 C-10 익명화 409 AUDIT_CHAIN_BROKEN: 대화상자 안에 "감사 로그의 사슬이 끊겨 있어…"를 보이고 닫지 않는다. 끊긴 번호는 보이지 않고 알림도 없고 줄은 그대로다', async () => {
    seed();
    route('post', anonymizePath(LEE.id), () => chainBroken());
    const { user } = renderAt('/members');
    await rowOf(LEE.name);
    const box = await anonymize(user, LEE);

    await eventually(() => {
      expect(text(box)).toContain(CHAIN_BROKEN);
    });
    expect(openDialog(), '대화상자').not.toBeNull();
    expect(box.isConnected, '같은 대화상자').toBe(true);
    expect(text(box), '끊긴 번호').not.toContain(String(BROKEN_SEQ));
    expect(text(box), '서버 문구').not.toContain('server text');
    expectNotSaid(ANONYMIZED);
    expect(cellUnder(LEE.name, '상태')).toBe('사용 중');
    expect(cellUnder(LEE.name, '이메일')).toBe(LEE.email);
    expect(state.accounts.find((a) => a.id === LEE.id)?.anonymized, '서버의 계정').toBe(false);
  });

  test('S10A-AC-37 C-10 axe: 409 AUDIT_CHAIN_BROKEN의 까닭을 보인 익명화 대화상자에 접근성 위반이 없다', async () => {
    seed();
    route('post', anonymizePath(LEE.id), () => chainBroken());
    const { user } = renderAt('/members');
    await rowOf(LEE.name);
    const box = await anonymize(user, LEE);
    await eventually(() => {
      expect(text(box)).toContain(CHAIN_BROKEN);
    });

    await expectNoAxeViolations();
  });
});
