// 2번 묶음 screens.md 1절 P-8 우리 회사 사람: 떠나거나 고객사가 바뀐 뒤 온 답을 버린다(S2-AC-49).
// 보는 중에 담당자 지정이 풀리면 다음 요청이 403이고 화면 전체가 403 상태가 된다(S2-AC-11).
import { describe, expect, test } from 'vitest';
import { atPath, button, errors, found, gate, isBlocked, link, pageText, renderAt, screen, sent, within } from '../__tests__/portal';
import { answered, rejected, route, settle } from '../__tests__/portal1a';
import {
  ACME_MANAGED,
  BETA_MANAGED,
  BETA_ROW,
  COLLEAGUE,
  INVITED_ROW,
  belongTo,
  cancel,
  choose,
  confirmButton,
  expectNotSaid,
  forbiddenScreen,
  membersPath,
  openDialog,
  people,
  rowAction,
  rowOf,
} from '../__tests__/portal2';

const ACME_LIST = membersPath('ACME');
const TOO_MANY = '수락을 기다리는 초대가 너무 많습니다';
const ALREADY_GONE = '이미 목록에 없는 사람입니다';

type User = ReturnType<typeof renderAt>['user'];

async function openInvite(user: User, companyName: string): Promise<HTMLElement> {
  await user.click(await button(/^초대$/));
  return found(() => screen.queryByRole('dialog', { name: `${companyName}에 초대` }), `대화상자 "${companyName}에 초대"`);
}

const emailIn = (box: HTMLElement) => found(() => within(box).queryAllByLabelText<HTMLInputElement>(/^이메일/, { selector: 'input' })[0], '이메일 칸');
const nameIn = (box: HTMLElement) => found(() => within(box).queryAllByLabelText<HTMLInputElement>(/^이름/, { selector: 'input' })[0], '이름 칸');

/** BETA's rows are on the screen and none of ACME's. */
async function showsBeta() {
  await rowOf(BETA_ROW.email);
  expect(pageText()).not.toContain(COLLEAGUE.email);
}

describe('P-8 우리 회사 사람: 늦게 온 답', () => {
  test('S2-AC-49 P-8 목록: 화면을 떠난 뒤 온 목록의 401은 주소를 /login으로 바꾸지 않는다', async () => {
    belongTo(ACME_MANAGED);
    const release = gate();
    route('get', ACME_LIST, async () => {
      await release.opened;
      return errors.unauthenticated();
    });
    const { user } = renderAt('/members');
    await sent('GET', ACME_LIST);
    await user.click(await link('문의'));
    await atPath('/issues');

    const done = answered('GET', ACME_LIST);
    release.open();
    await done;
    await settle();

    expect(window.location.pathname).toBe('/issues');
  });

  test('S2-AC-49 P-8 목록: 고객사를 바꾼 뒤 온 앞 고객사의 목록은 표에 넣지 않는다', async () => {
    belongTo(ACME_MANAGED, BETA_MANAGED);
    const release = gate();
    route('get', ACME_LIST, async () => {
      await release.opened;
      return Response.json({ items: people.rows.ACME ?? [], page: 1, size: 20, totalElements: 5, totalPages: 1 });
    });
    const { user } = renderAt('/members?company=ACME');
    await sent('GET', ACME_LIST);
    await choose(user, BETA_MANAGED);
    await rowOf(BETA_ROW.email);

    const done = answered('GET', ACME_LIST);
    release.open();
    await done;
    await settle();

    await showsBeta();
  });

  test('S2-AC-49 P-8 초대: 보내는 중에 닫고 고객사를 바꾼 뒤 온 답은 버린다. 다시 열면 오류 없이 새 고객사의 빈 대화상자다', async () => {
    belongTo(ACME_MANAGED, BETA_MANAGED);
    const release = gate();
    route('post', ACME_LIST, async () => {
      await release.opened;
      return rejected('TOO_MANY_PENDING', 409);
    });
    const { user } = renderAt('/members?company=ACME');
    await rowOf(COLLEAGUE.email);
    const first = await openInvite(user, ACME_MANAGED.name);
    await user.type(await emailIn(first), 'new@acme.test');
    await user.type(await nameIn(first), '신입');
    await user.click(confirmButton(first));
    await sent('POST', ACME_LIST);
    await cancel(user, first);
    await choose(user, BETA_MANAGED);
    await rowOf(BETA_ROW.email);

    const done = answered('POST', ACME_LIST);
    release.open();
    await done;
    await settle();

    expect(openDialog(), '늦은 답이 대화상자를 다시 열지 않는다').toBeNull();
    expectNotSaid(TOO_MANY);
    await showsBeta();
    const second = await openInvite(user, BETA_MANAGED.name);
    expect(within(second).queryAllByRole('alert'), 'ACME의 오류').toHaveLength(0);
    expect(await emailIn(second)).toHaveValue('');
    expect(isBlocked(confirmButton(second)), 'BETA의 "초대" 버튼').toBe(false);
  });

  test('S2-AC-49 P-8 빼기: 빼는 중에 닫고 고객사를 바꾼 뒤 온 답은 버린다. 토스트도 없고 표도 그대로다', async () => {
    belongTo(ACME_MANAGED, BETA_MANAGED);
    const path = `${ACME_LIST}/${COLLEAGUE.id}`;
    const release = gate();
    route('delete', path, async () => {
      await release.opened;
      return errors.notFound();
    });
    const { user } = renderAt('/members?company=ACME');
    await user.click(await rowAction(user, await rowOf(COLLEAGUE.email), '소속에서 빼기'));
    const box = await found(() => openDialog(), '확인 대화상자');
    await user.click(confirmButton(box));
    await sent('DELETE', path);
    await cancel(user, box);
    await choose(user, BETA_MANAGED);
    await rowOf(BETA_ROW.email);

    const done = answered('DELETE', path);
    release.open();
    await done;
    await settle();

    expect(openDialog(), '늦은 답이 대화상자를 다시 열지 않는다').toBeNull();
    expectNotSaid(ALREADY_GONE);
    await showsBeta();
  });

  test('S2-AC-11 P-8 보는 중에 담당자 지정이 풀림: 다음 요청이 403이면 화면 전체가 403 상태가 된다', async () => {
    belongTo(ACME_MANAGED);
    const { user } = renderAt('/members');
    const row = await rowOf(INVITED_ROW.email);
    const resend = await rowAction(user, row, '초대 다시 보내기');
    // An admin took the manager mark away: the fake server answers 403 from now on.
    belongTo({ ...ACME_MANAGED, manager: false });
    await user.click(resend);

    await sent('POST', `${ACME_LIST}/${INVITED_ROW.id}/resend`);
    await forbiddenScreen();
    expect(pageText()).not.toContain(INVITED_ROW.email);
    expectNotSaid('초대를 다시 보냈습니다');
  });
});
