// 11 묶음 screens.md "C-10 구성원 목록": OIDC가 묶인 줄의 배지 "OIDC 연결"(정지 계정 포함), 그 줄 메뉴의 "OIDC 연결 풀기",
// 확인 대화상자(제목, 이름을 넣은 본문, 단추), 보낸 요청(DELETE …/oidc-link, CSRF), 보내는 중, 성공·404·그 밖의 실패,
// 닫은 뒤와 대상이 바뀐 뒤 온 답(S11-AC-33). 늦은 답은 배지를 바꾸지 않고 목록만 다시 읽고, 성공은 배지를 지운 뒤 목록도
// 다시 읽는다(C-7 웹 점검 D2). 그것을 가리려고 다시 읽는 목록의 답을 잡아 둔다(`holdList`).
import { HttpResponse } from 'msw/http';
import { describe, expect, test } from 'vitest';
import {
  account,
  callsTo,
  errors,
  eventually,
  expectNoAxeViolations,
  found,
  gate,
  isBlocked,
  openDialog,
  paged,
  renderAt,
  screen,
  sent,
  state,
  text,
  within,
  type Account,
} from '../__tests__/console';
import { answered, closed, confirmButton, openRowAction, rowActions, rowOf, route, setCsrfCookie, settle } from '../__tests__/console1a';
import { expectNotSaid, said } from '../__tests__/console2';
import { s3 } from '../__tests__/console3';
import { ACCOUNTS } from '../__tests__/console10a';
import { ALREADY_UNLINKED, CANCEL, LINKED_BADGE, UNLINK, UNLINKED, UNLINK_GO, UNLINK_TITLE, unlinkBody, unlinkPath } from '../__tests__/console11';

const SERVER_FAILED = '서버에 연결하지 못했습니다. 잠시 뒤 다시 하세요.';

const LEE = account({ id: 2, email: 'lee@popdesk.test', name: '이상담', role: 'AGENT', oidcLinked: true });
const JUNG = account({ id: 3, email: 'jung@popdesk.test', name: '정상담', role: 'AGENT' });
const HAN = account({ id: 4, email: 'han@popdesk.test', name: '한정지', role: 'AGENT', status: 'SUSPENDED', oidcLinked: true });
const KIM = account({ id: 11, email: 'kim@acme.test', name: '김고객', role: 'CUSTOMER', oidcLinked: true });

type User = ReturnType<typeof renderAt>['user'];

function seed() {
  state.accounts = [account(), LEE, JUNG, HAN, KIM];
  s3.openAssignments = 0;
}

const listCalls = () => callsTo('GET', ACCOUNTS).length;

/** What the server does when another admin unlinked `who` or the late answer was a 204. */
function unlinkedOnServer(who: Account) {
  state.accounts = state.accounts.map((a) => (a.id === who.id ? { ...a, oidcLinked: false } : a));
}

/** From now on every 계정 목록 read waits until the gate opens, then answers what the server holds then. */
function holdList() {
  const hold = gate();
  route('get', ACCOUNTS, async ({ request }) => {
    await hold.opened;
    const role = new URL(request.url).searchParams.get('role');
    return HttpResponse.json(paged(role ? state.accounts.filter((a) => a.role === role) : state.accounts));
  });
  return hold;
}

/** A late answer to 연결 풀기 of 이상담, and whether the account is still linked on the server after it. */
const LATE = [
  ['204', () => new HttpResponse(null, { status: 204 }), false],
  ['404', () => errors.notFound(), false],
  ['500', () => errors.internal(), true],
] as const;

/** The badges drawn in the 이름 cell of the row holding `name`, as their text and tone. */
function nameBadges(row: HTMLElement): { text: string; tone: string | null }[] {
  const head = screen.queryAllByRole('columnheader').find((h) => text(h) === '이름');
  const index = head?.parentElement ? Array.from(head.parentElement.children).indexOf(head) : -1;
  const cell = index < 0 ? null : row.children[index];
  return Array.from(cell?.querySelectorAll('[data-pd="Badge"]') ?? []).map((b) => ({ text: text(b), tone: b.getAttribute('data-tone') }));
}

const linkedNow = (row: HTMLElement) => nameBadges(row).some((b) => b.text === LINKED_BADGE);

async function expectLinked(name: string, linked: boolean) {
  await eventually(() => {
    const row = screen.queryAllByRole('row').find((r) => within(r).queryAllByRole('cell').some((c) => text(c).includes(name)));
    expect(row ?? null, `${name} 줄`).not.toBeNull();
    expect(linkedNow(row as HTMLElement), `${name} 줄의 "${LINKED_BADGE}"`).toBe(linked);
  });
}

/** The 연결 풀기 button of the dialog. */
const goButton = (box: HTMLElement) => found(() => within(box).queryByRole('button', { name: UNLINK_GO }), `대화상자의 "${UNLINK_GO}"`);

async function openUnlink(user: User, who: Account): Promise<HTMLElement> {
  return openRowAction(user, who.name, UNLINK);
}

/** Opens 연결 풀기 of `who`, sends it, and closes the dialog with 취소 before the answer. */
async function sendThenCancel(user: User, who: Account): Promise<HTMLElement> {
  const box = await openUnlink(user, who);
  await user.click(await goButton(box));
  await sent('DELETE', unlinkPath(who.id));
  await user.click(within(box).getByRole('button', { name: CANCEL }));
  await closed();
  return box;
}

describe('C-10 구성원 목록의 "OIDC 연결" 표시 (11 묶음)', () => {
  test('S11-AC-33 C-10 묶인 계정: 이름 칸에 neutral 배지 "OIDC 연결". 묶이지 않은 줄에는 없다', async () => {
    seed();
    renderAt('/members');

    await expectLinked(LEE.name, true);
    expect(nameBadges(await rowOf(LEE.name)).find((b) => b.text === LINKED_BADGE)?.tone).toBe('neutral');
    await expectLinked(KIM.name, true);
    await expectLinked(JUNG.name, false);
  });

  test('S11-AC-33 C-10 정지 계정(SuspendedAccount)도 묶였으면 배지가 있고, 묶인 계정을 정지한 뒤에도 배지가 남는다', async () => {
    seed();
    const { user } = renderAt('/members');
    await expectLinked(HAN.name, true);

    const box = await openRowAction(user, LEE.name, '정지');
    await user.click(confirmButton(box));
    await sent('POST', `${ACCOUNTS}/${String(LEE.id)}/suspend`);
    await closed();
    await said('이상담 님을 정지했습니다');
    await expectLinked(LEE.name, true);
  });

  test.each([
    ['묶인 상담원', LEE, true],
    ['묶인 고객', KIM, true],
    ['묶인 정지 계정', HAN, true],
    ['묶이지 않은 상담원', JUNG, false],
  ])('S11-AC-33 C-10 줄 메뉴(%s): "OIDC 연결 풀기"는 묶인 줄에만 있다', async (_, who, linked) => {
    seed();
    const { user } = renderAt('/members');
    await expectLinked(LEE.name, true);

    const actions = await rowActions(user, await rowOf(who.name));
    expect(actions.includes(UNLINK), `${who.name} 줄의 "${UNLINK}"`).toBe(linked);
  });
});

describe('C-10 OIDC 연결 풀기 (11 묶음)', () => {
  test('S11-AC-33 C-10 확인 대화상자: 제목 "OIDC 연결을 풀까요?", 이름을 넣은 본문, 단추 "연결 풀기"·"취소". 취소하면 보내지 않는다', async () => {
    seed();
    const { user } = renderAt('/members');
    const box = await openUnlink(user, LEE);

    expect(screen.queryAllByRole(box.getAttribute('role') ?? 'dialog', { name: UNLINK_TITLE }), '대화상자 이름').toContain(box);
    expect(text(box)).toContain(unlinkBody(LEE.name));
    await goButton(box);
    await user.click(within(box).getByRole('button', { name: CANCEL }));
    await closed();
    await settle();
    expect(callsTo('DELETE', unlinkPath(LEE.id)), '취소한 뒤의 요청').toHaveLength(0);
    await expectLinked(LEE.name, true);
  });

  test('S11-AC-33 C-10 "연결 풀기": 그 계정의 oidc-link로 본문 없는 DELETE 하나, CSRF 머리글을 단다', async () => {
    seed();
    setCsrfCookie('rst-xsrf-c10-11');
    const { user } = renderAt('/members');
    const box = await openUnlink(user, KIM);
    await user.click(await goButton(box));

    const [sentOne] = await sent('DELETE', unlinkPath(KIM.id));
    await settle();
    expect(callsTo('DELETE', /\/oidc-link$/), 'DELETE 수').toHaveLength(1);
    expect(sentOne?.headers.get('X-XSRF-TOKEN')).toBe('rst-xsrf-c10-11');
    expect(await sentOne?.body).toBeUndefined();
  });

  test('S11-AC-33 C-10 보내는 동안: "연결 풀기"가 눌리지 않고, 다시 눌러도 DELETE는 하나다', async () => {
    seed();
    const release = gate();
    route('delete', unlinkPath(LEE.id), async () => {
      await release.opened;
      unlinkedOnServer(LEE);
      return new HttpResponse(null, { status: 204 });
    });
    const { user } = renderAt('/members');
    const box = await openUnlink(user, LEE);
    const go = await goButton(box);
    await user.click(go);
    await sent('DELETE', unlinkPath(LEE.id));

    await eventually(() => {
      expect(isBlocked(go), '보내는 동안 눌리지 않음').toBe(true);
    });
    await user.click(go);
    await settle();
    expect(callsTo('DELETE', unlinkPath(LEE.id)), 'DELETE 수').toHaveLength(1);
    release.open();
    await closed();
    await said(UNLINKED);
  });

  test('S11-AC-33 C-10 풀기 성공: 대화상자를 닫고, 그 줄의 배지를 지우고, "OIDC 연결을 풀었습니다."를 알린다. 메뉴에서도 빠진다', async () => {
    seed();
    const { user } = renderAt('/members');
    const box = await openUnlink(user, LEE);
    await user.click(await goButton(box));

    await sent('DELETE', unlinkPath(LEE.id));
    await closed();
    await said(UNLINKED);
    await expectLinked(LEE.name, false);
    await expectLinked(KIM.name, true);
    expect(await rowActions(user, await rowOf(LEE.name))).not.toContain(UNLINK);
  });

  test('S11-AC-33 C-10 풀기 성공 뒤: 배지는 목록을 기다리지 않고 지우고, 그 뒤 목록을 다시 읽는다', async () => {
    seed();
    const { user } = renderAt('/members');
    const box = await openUnlink(user, LEE);
    await settle();
    const hold = holdList();
    const before = listCalls();
    await user.click(await goButton(box));

    await sent('DELETE', unlinkPath(LEE.id));
    await closed();
    await expectLinked(LEE.name, false);
    await eventually(() => {
      expect(listCalls(), '목록을 다시 읽음').toBeGreaterThan(before);
    });
    hold.open();
    await settle();
    await expectLinked(LEE.name, false);
    await expectLinked(KIM.name, true);
  });

  test('S11-AC-33 C-10 풀기 404: 대화상자 안에 "이미 연결이 풀렸습니다."이고 목록을 다시 읽는다', async () => {
    seed();
    route('delete', unlinkPath(LEE.id), () => {
      // Someone else unlinked it first.
      state.accounts = state.accounts.map((a) => (a.id === LEE.id ? { ...a, oidcLinked: false } : a));
      return errors.notFound();
    });
    const { user } = renderAt('/members');
    const box = await openUnlink(user, LEE);
    const before = listCalls();
    await user.click(await goButton(box));

    await sent('DELETE', unlinkPath(LEE.id));
    await eventually(() => {
      expect(text(box)).toContain(ALREADY_UNLINKED);
    });
    expect(openDialog(), '대화상자').toBe(box);
    await eventually(() => {
      expect(listCalls(), '목록을 다시 읽음').toBeGreaterThan(before);
    });
    await expectLinked(LEE.name, false);
    expectNotSaid(UNLINKED);
  });

  test.each([
    ['500', () => errors.internal()],
    ['네트워크 오류', () => HttpResponse.error()],
  ])('S11-AC-33 C-10 풀기 %s: 대화상자 안에 실패 문장이고 닫지 않는다. 배지와 알림은 그대로다', async (_, answer) => {
    seed();
    route('delete', unlinkPath(LEE.id), answer);
    const { user } = renderAt('/members');
    const box = await openUnlink(user, LEE);
    await user.click(await goButton(box));

    await sent('DELETE', unlinkPath(LEE.id));
    await eventually(() => {
      expect(text(box)).toContain(SERVER_FAILED);
    });
    expect(openDialog(), '대화상자').toBe(box);
    expectNotSaid(UNLINKED);
    expect(text(box)).not.toContain(ALREADY_UNLINKED);
    await expectLinked(LEE.name, true);
  });

  test.each(LATE)(
    'S11-AC-33 C-10 닫은 뒤 온 답(%s): 대화상자를 다시 열지 않고 알림도 띠도 없다. 배지는 그 답으로 바꾸지 않고, 목록을 다시 읽어 그대로 보인다',
    async (_, answer, linkedAfter) => {
      seed();
      const release = gate();
      route('delete', unlinkPath(LEE.id), async () => {
        await release.opened;
        if (!linkedAfter) unlinkedOnServer(LEE);
        return answer();
      });
      const { user } = renderAt('/members');
      await expectLinked(LEE.name, true);
      await sendThenCancel(user, LEE);
      await settle();
      const hold = holdList();
      const before = listCalls();

      const done = answered('DELETE', unlinkPath(LEE.id));
      release.open();
      await done;
      await settle();
      expect(openDialog(), '늦은 답이 대화상자를 다시 열지 않는다').toBeNull();
      expectNotSaid(UNLINKED);
      expect(screen.queryAllByRole('alert').map(text).filter((t) => t !== ''), '띠').toEqual([]);
      await eventually(() => {
        expect(listCalls(), '목록을 다시 읽음').toBeGreaterThan(before);
      });
      await expectLinked(LEE.name, true);
      hold.open();
      await expectLinked(LEE.name, linkedAfter);
    },
  );

  test.each(LATE)(
    'S11-AC-33 C-10 앞 사람의 답(%s)이 다른 사람의 대화상자가 열린 동안 온다: 지금 대화상자가 그대로이고 앞 사람의 문장도 알림도 없다. 목록은 다시 읽는다',
    async (_, answer, linkedAfter) => {
      seed();
      const release = gate();
      route('delete', unlinkPath(LEE.id), async () => {
        await release.opened;
        if (!linkedAfter) unlinkedOnServer(LEE);
        return answer();
      });
      const { user } = renderAt('/members');
      await expectLinked(LEE.name, true);
      await sendThenCancel(user, LEE);
      const second = await openUnlink(user, KIM);
      expect(text(second)).toContain(unlinkBody(KIM.name));
      await settle();
      const hold = holdList();
      const before = listCalls();

      const done = answered('DELETE', unlinkPath(LEE.id));
      release.open();
      await done;
      await settle();
      expect(second.isConnected, '같은 대화상자').toBe(true);
      expect(openDialog(), '열린 대화상자').toBe(second);
      expect(text(second)).toContain(unlinkBody(KIM.name));
      expect(text(second)).not.toContain(ALREADY_UNLINKED);
      expect(text(second)).not.toContain(SERVER_FAILED);
      expect(within(second).queryAllByRole('alert').map(text).filter((t) => t !== ''), '앞 대화상자의 오류').toEqual([]);
      expectNotSaid(UNLINKED);
      expect(isBlocked(await goButton(second)), '앞 사람의 보내는 중이 새지 않는다').toBe(false);
      expect(callsTo('DELETE', unlinkPath(KIM.id)), '두 번째 사람에게는 아직 보내지 않음').toHaveLength(0);
      await eventually(() => {
        expect(listCalls(), '목록을 다시 읽음').toBeGreaterThan(before);
      });
      await expectLinked(LEE.name, true);
      hold.open();
      await expectLinked(LEE.name, linkedAfter);
      expect(openDialog(), '목록을 다시 읽은 뒤에도 같은 대화상자').toBe(second);
    },
  );

  test('S11-AC-33 C-10 axe: 배지가 있는 목록과 연결 풀기 대화상자에 접근성 위반이 없다', async () => {
    seed();
    const { user } = renderAt('/members');
    await expectLinked(LEE.name, true);
    const box = await openUnlink(user, LEE);
    await goButton(box);

    await expectNoAxeViolations();
  });
});
