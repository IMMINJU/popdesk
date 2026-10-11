// 2번 묶음 screens.md 1절 P-8 우리 회사 사람: 떠나거나 고객사가 바뀐 뒤 온 답을 버린다(S2-AC-49).
// 보는 중에 담당자 지정이 풀리면 다음 요청이 403이고 화면 전체가 403 상태가 된다(S2-AC-11). 목록, 초대, 빼기, 다시 보내기 어느 것이든 같다(screens.md 5절).
import { describe, expect, test } from 'vitest';
import { atPath, callsTo, errors, found, gate, isBlocked, link, pageText, renderAt, sent, within } from '../__tests__/portal';
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
  fillInvite,
  forbiddenScreen,
  membersPath,
  openDialog,
  openInvite,
  people,
  rowAction,
  rowOf,
} from '../__tests__/portal2';

const ACME_LIST = membersPath('ACME');
const BETA_LIST = membersPath('BETA');
const RESEND = `${ACME_LIST}/${INVITED_ROW.id}/resend`;
const REMOVE = `${ACME_LIST}/${COLLEAGUE.id}`;
const TOO_MANY = '수락을 기다리는 초대가 너무 많습니다';
const ALREADY_GONE = '이미 목록에 없는 사람입니다';
const INVITED = '요청을 처리했습니다';
const RESENT = '초대를 다시 보냈습니다';
const NOT_RESENDABLE = '이 초대는 다시 보낼 수 없습니다';
const REMOVED = '소속에서 뺐습니다';
const noContent = () => new Response(null, { status: 204 });

type User = ReturnType<typeof renderAt>['user'];

/** Opens 소속에서 빼기 of 최동료 and returns the button that goes on. */
async function removeConfirm(user: User): Promise<HTMLElement> {
  await user.click(await rowAction(user, await rowOf(COLLEAGUE.email), '소속에서 빼기'));
  return confirmButton(await found(() => openDialog(), '확인 대화상자'));
}

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

  test.each([
    ['409 TOO_MANY_PENDING', () => rejected('TOO_MANY_PENDING', 409)],
    ['204', noContent],
  ])('S2-AC-49 P-8 초대: 보내는 중에 닫고 고객사를 바꾼 뒤 온 답(%s)은 버린다. 토스트도 없고 목록을 다시 부르지도 않는다. 다시 열면 오류 없이 새 고객사의 빈 대화상자다', async (_, answer) => {
    belongTo(ACME_MANAGED, BETA_MANAGED);
    const release = gate();
    route('post', ACME_LIST, async () => {
      await release.opened;
      return answer();
    });
    const { user } = renderAt('/members?company=ACME');
    await rowOf(COLLEAGUE.email);
    const first = await openInvite(user, ACME_MANAGED.name);
    await fillInvite(user, first);
    await user.click(confirmButton(first.box));
    await sent('POST', ACME_LIST);
    await cancel(user, first.box);
    await choose(user, BETA_MANAGED);
    await rowOf(BETA_ROW.email);
    const loads = callsTo('GET', BETA_LIST).length;

    const done = answered('POST', ACME_LIST);
    release.open();
    await done;
    await settle();

    expect(openDialog(), '늦은 답이 대화상자를 다시 열지 않는다').toBeNull();
    expectNotSaid(TOO_MANY);
    expectNotSaid(INVITED);
    expect(callsTo('GET', BETA_LIST), '늦은 답 때문에 다시 부른 목록').toHaveLength(loads);
    await showsBeta();
    const second = await openInvite(user, BETA_MANAGED.name);
    expect(within(second.box).queryAllByRole('alert'), 'ACME의 오류').toHaveLength(0);
    expect(second.email).toHaveValue('');
    expect(isBlocked(confirmButton(second.box)), 'BETA의 "초대" 버튼').toBe(false);
  });

  test.each([
    ['204', noContent],
    ['400 INVITATION_NOT_RESENDABLE', () => rejected('INVITATION_NOT_RESENDABLE')],
  ])('S2-AC-49 P-8 초대 다시 보내기: 보내는 중에 고객사를 바꾼 뒤 온 답(%s)은 버린다. 토스트도 없고 표도 그대로다', async (_, answer) => {
    belongTo(ACME_MANAGED, BETA_MANAGED);
    const release = gate();
    route('post', RESEND, async () => {
      await release.opened;
      return answer();
    });
    const { user } = renderAt('/members?company=ACME');
    await user.click(await rowAction(user, await rowOf(INVITED_ROW.email), '초대 다시 보내기'));
    await sent('POST', RESEND);
    await choose(user, BETA_MANAGED);
    await rowOf(BETA_ROW.email);
    const loads = callsTo('GET', BETA_LIST).length;

    const done = answered('POST', RESEND);
    release.open();
    await done;
    await settle();

    expectNotSaid(RESENT);
    expectNotSaid(NOT_RESENDABLE);
    expect(callsTo('GET', BETA_LIST), '늦은 답 때문에 다시 부른 목록').toHaveLength(loads);
    await showsBeta();
  });

  test('S2-AC-49 P-8 빼기: 빼는 중에 닫고 고객사를 바꾼 뒤 온 답은 버린다. 토스트도 없고 표도 그대로다', async () => {
    belongTo(ACME_MANAGED, BETA_MANAGED);
    const path = REMOVE;
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

  test.each([
    {
      what: '초대 다시 보내기',
      request: ['POST', RESEND],
      ready: (user: User) => rowOf(INVITED_ROW.email).then((row) => rowAction(user, row, '초대 다시 보내기')),
    },
    {
      what: '초대',
      request: ['POST', ACME_LIST],
      ready: async (user: User) => {
        const d = await openInvite(user, ACME_MANAGED.name);
        await fillInvite(user, d);
        return confirmButton(d.box);
      },
    },
    { what: '빼기', request: ['DELETE', REMOVE], ready: removeConfirm },
    {
      // Someone else took the row out first: the 404 makes the screen read the list again, and that answer is the 403.
      what: '목록 다시 부르기',
      request: ['GET', ACME_LIST],
      ready: (user: User) => {
        route('delete', REMOVE, () => errors.notFound());
        return removeConfirm(user);
      },
    },
  ] as const)('S2-AC-11 P-8 보는 중에 담당자 지정이 풀림($what): 그 요청이 403이면 화면 전체가 403 상태가 된다', async ({ request: [method, path], ready }) => {
    belongTo(ACME_MANAGED);
    const { user } = renderAt('/members');
    await rowOf(INVITED_ROW.email);
    const go = await ready(user);
    const before = callsTo(method, path).length;
    // An admin took the manager mark away: the fake server answers 403 from now on.
    belongTo({ ...ACME_MANAGED, manager: false });
    await user.click(go);

    await sent(method, path, before + 1);
    await forbiddenScreen();
    expect(openDialog(), '대화상자').toBeNull();
    expect(pageText()).not.toContain(INVITED_ROW.email);
    for (const done of [RESENT, INVITED, REMOVED]) expectNotSaid(done);
  });
});
