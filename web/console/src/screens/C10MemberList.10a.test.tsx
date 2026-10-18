// 10a 묶음 screens.md C-10 구성원 목록: 줄 메뉴의 "정보 내보내기"와 "익명화"(상태별 항목과 차례, S10A-AC-28),
// 정보 내보내기(HEAD로 확인한 뒤 내려받기, 503·404·401, 두 번 누름, S10A-AC-29), 익명화 대화상자(본문, 이메일 확인,
// 담당 수, 보내는 중, S10A-AC-30), 결과(성공, 400·409, 닫은 뒤 온 답, S10A-AC-31), "익명화됨" 표시(S10A-AC-32).
// 내려받기는 화면이 주소로 옮겨 가는 것이라 jsdom에서는 보이지 않는다. 여기서는 C-12 내보내기 테스트처럼 HEAD와 띠로 보고,
// 옮겨 가는 것은 같이 쓰는 도움의 테스트(app/exportDownload.10a.test.ts)가 본다.
// C-7 점검 뒤 더한 것: 늦은 실패 답과 다른 사람의 대화상자가 열린 동안 온 답, 고객의 익명화, 옛 줄의 400, 첫 초점.
import { HttpResponse } from 'msw/http';
import { describe, expect, test } from 'vitest';
import {
  account,
  atPath,
  callsTo,
  errors,
  eventually,
  expectNoAxeViolations,
  found,
  gate,
  invitation,
  isBlocked,
  link,
  on,
  openDialog,
  pageText,
  renderAt,
  screen,
  sent,
  setSmtp,
  state,
  text,
  within,
  type Account,
} from '../__tests__/console';
import { answered, closed, openRowAction, photosIn, rejected, route, rowAction, rowOf, settle } from '../__tests__/console1a';
import { expectNotSaid, said } from '../__tests__/console2';
import { CLEAR, clearBox, s3 } from '../__tests__/console3';
import { rejectedWith } from '../__tests__/console6a';
import {
  ACCOUNTS,
  ALREADY,
  ANONYMIZE,
  ANONYMIZED,
  ANONYMIZED_BADGE,
  ANONYMIZING,
  ANON_NAME,
  CANNOT_UNDO,
  CHANGES,
  EXPORT,
  EXPORT_NOT_FOUND,
  LAST_ADMIN,
  OWN_ACCOUNT,
  RUNNING,
  anonEmail,
  anonymizePath,
  anonymizeTitle,
  anonymous,
  emailLabel,
  exportBusy,
  exportBusyText,
  exportPath,
  nameChange,
} from '../__tests__/console10a';

const LEE = account({ id: 2, email: 'lee@popdesk.test', name: '이상담', role: 'AGENT' });
const JUNG = account({ id: 3, email: 'jung@popdesk.test', name: '정상담', role: 'AGENT' });
const HAN = account({ id: 4, email: 'han@popdesk.test', name: '한정지', role: 'AGENT', status: 'SUSPENDED' });
const KIM = account({ id: 11, email: 'kim@acme.test', name: '김고객', role: 'CUSTOMER' });
const PARK = account({ id: 21, email: 'park@acme.test', name: '박초대', role: 'CUSTOMER', status: 'INVITED' });
const OH = account({ id: 22, email: 'oh@acme.test', name: '오취소', role: 'CUSTOMER', status: 'SUSPENDED' });
/** An agent whose stored email has capitals (C-7 웹 점검 D6). */
const YOON = account({ id: 5, email: 'Yoon.Mixed@PopDesk.test', name: '윤대문', role: 'AGENT' });
/**
 * An anonymized account as the server leaves it: `SUSPENDED`, no invitation (anonymizing removes it and
 * sets a password, so the invitation list does not hold it; C-7 웹 점검 D2).
 */
const GONE = anonymous(10);
/** An anonymized customer the invitation list still shows as cancelled: the server does not make this; kept as a guard. */
const ANON = anonymous(9);

type User = ReturnType<typeof renderAt>['user'];

/** Every state a row can be in; `withAnon` adds the anonymized accounts. */
function seed(withAnon = true) {
  state.accounts = [account(), LEE, JUNG, HAN, YOON, KIM, PARK, OH, ...(withAnon ? [ANON, GONE] : [])];
  state.invitations = [
    invitation({ accountId: PARK.id, email: PARK.email, name: PARK.name }),
    invitation({ accountId: OH.id, email: OH.email, name: OH.name, cancelled: true }),
    invitation({ accountId: ANON.id, email: ANON.email, name: ANON.name, cancelled: true }),
  ];
}

/** The text of the cell of the row holding `name` under the column `header`; null while there is none. */
function cellUnder(name: string, header: string): string | null {
  const head = screen.queryAllByRole('columnheader').find((h) => text(h).includes(header));
  const row = screen.queryAllByRole('row').find((r) => within(r).queryAllByRole('cell').some((c) => text(c).includes(name)));
  if (!head?.parentElement || !row) return null;
  const index = Array.from(head.parentElement.children).indexOf(head);
  return text(row.children[index]);
}

async function expectCell(name: string, header: string, word: string) {
  await eventually(() => {
    expect(cellUnder(name, header), `${name}의 ${header}`).toBe(word);
  });
}

/**
 * The items of the menu of the row holding `name` (a name or an email), in the order the menu shows
 * them; rowActions of console1a sorts them, this one does not.
 */
async function menuOrder(user: User, name: string): Promise<string[]> {
  const row = await rowOf(name);
  const trigger = within(row)
    .queryAllByRole('button')
    .find((b) => b.hasAttribute('aria-haspopup'));
  expect(trigger ?? null, `${name} 줄의 메뉴 버튼`).not.toBeNull();
  if (trigger) await user.click(trigger);
  const menu = await found(() => screen.queryByRole('menu'), '줄 메뉴');
  return within(menu)
    .queryAllByRole('menuitem')
    .map((i) => text(i));
}

const listCalls = () => callsTo('GET', ACCOUNTS).length;
const anonymizeQuery = (id: number) => new URL(callsTo('POST', anonymizePath(id)).at(-1)?.url ?? 'http://x/').searchParams;

/** The email field of the anonymize dialog for `email`. */
const emailField = (box: HTMLElement, email: string) =>
  found(() => within(box).queryAllByLabelText<HTMLInputElement>(emailLabel(email), { selector: 'input' })[0], `칸 "${emailLabel(email)}"`);

/** The button "익명화" of the dialog (also while it says "익명화하는 중…"), or null. */
const goNow = (box: HTMLElement) =>
  within(box)
    .queryAllByRole('button')
    .find((b) => [ANONYMIZE, ANONYMIZING].includes(text(b))) ?? null;

const goButton = (box: HTMLElement) => found(() => goNow(box), '대화상자의 "익명화" 단추');

/** Opens 익명화 of `who` and types `typed` into the email field. */
async function openAndType(user: User, who: Account, typed: string): Promise<HTMLElement> {
  const box = await openRowAction(user, who.name, ANONYMIZE);
  if (typed !== '') await user.type(await emailField(box, who.email), typed);
  return box;
}

describe('C-10 구성원 목록의 줄 메뉴 (10a 묶음)', () => {
  test.each([
    ['ACTIVE 상담원', LEE.name, ['이름 고치기', '역할 바꾸기', '비밀번호 초기화', EXPORT, '정지', ANONYMIZE]],
    ['ACTIVE 고객', KIM.name, ['이름 고치기', '비밀번호 초기화', EXPORT, '정지', ANONYMIZE]],
    ['나 자신(익명화 없음)', '최관리', ['이름 고치기', '비밀번호 초기화', EXPORT]],
    ['SUSPENDED(익명 아님)', HAN.name, ['이름 고치기', '정지 풀기', EXPORT, ANONYMIZE]],
    ['수락 전(정보 내보내기는 초대 취소 앞, 익명화는 맨 끝)', PARK.name, ['이름 고치기', '초대 다시 보내기', EXPORT, '초대 취소', ANONYMIZE]],
    ['초대 취소됨', OH.name, ['이름 고치기', '초대 다시 보내기', EXPORT, ANONYMIZE]],
    ['익명 계정(초대 없음, 정보 내보내기만)', anonEmail(GONE.id), [EXPORT]],
    ['익명 계정(취소된 초대가 남음, 정보 내보내기만)', anonEmail(ANON.id), [EXPORT]],
  ])('S10A-AC-28 C-10 줄 메뉴 %s: 항목과 차례', async (_, name, wanted) => {
    seed();
    const { user } = renderAt('/members');
    await expectCell(OH.name, '상태', '초대 취소됨');

    expect(await menuOrder(user, name)).toEqual(wanted);
  });

  test.each([
    ['초대 없음', GONE.id],
    ['취소된 초대가 남음', ANON.id],
  ])('S10A-AC-28 C-10 익명 계정(%s, SMTP 없음): 메뉴는 "정보 내보내기"만이다', async (_, id) => {
    seed();
    setSmtp(false);
    const { user } = renderAt('/members');
    await expectCell(OH.name, '상태', '초대 취소됨');

    expect(await menuOrder(user, anonEmail(id))).toEqual([EXPORT]);
  });
});

describe('C-10 "익명화됨" 표시 (10a 묶음)', () => {
  test('S10A-AC-32 C-10 익명 계정의 줄: 이름 "삭제된 사용자", 이메일 deleted-{id}@invalid, 상태 칸은 "익명화됨" 하나이고 정지·초대 취소됨 배지는 없다. 사진 없이 머리글자', async () => {
    seed();
    renderAt('/members');
    await expectCell(OH.name, '상태', '초대 취소됨');

    for (const id of [GONE.id, ANON.id]) {
      await expectCell(anonEmail(id), '이메일', anonEmail(id));
      await expectCell(anonEmail(id), '상태', ANONYMIZED_BADGE);
      expect(cellUnder(anonEmail(id), '이름'), `${anonEmail(id)} 줄의 이름`).toContain(ANON_NAME);
      expect(photosIn(await rowOf(anonEmail(id))), `${anonEmail(id)} 줄의 사진`).toEqual([]);
    }
    await expectCell(HAN.name, '상태', '정지');
  });

  test('S10A-AC-32 C-10 상태 필터 "정지": 익명 계정이 정지 계정과 함께 나오고 "익명화됨"으로 구분된다', async () => {
    seed();
    renderAt('/members?status=SUSPENDED');

    await expectCell(ANON_NAME, '상태', ANONYMIZED_BADGE);
    await expectCell(HAN.name, '상태', '정지');
    const query = new URL(callsTo('GET', ACCOUNTS).at(-1)?.url ?? 'http://x/').searchParams;
    expect(query.get('status')).toBe('SUSPENDED');
    expect(query.has('anonymized'), '구성원 목록은 익명 여부로 거르지 않는다').toBe(false);
  });
});

describe('C-10 정보 내보내기 (10a 묶음)', () => {
  test.each([
    ['사용 중인 상담원', LEE.name, LEE.id],
    ['익명 계정', anonEmail(GONE.id), GONE.id],
  ])('S10A-AC-29 C-10 정보 내보내기(%s): 먼저 HEAD로 묻고, 200이면 띠도 알림도 없이 내려받는다', async (_, name, id) => {
    seed();
    const { user } = renderAt('/members');
    await user.click(await rowAction(user, await rowOf(name), EXPORT));

    await sent('HEAD', exportPath(id));
    await settle();
    expect(screen.queryAllByRole('alert'), '실패 띠').toHaveLength(0);
    expect(openDialog(), '대화상자').toBeNull();
    expectNotSaid(/내보냈/);
  });

  test.each([
    ['503(Retry-After 7)', () => exportBusy(7), exportBusyText(7)],
    ['503(Retry-After 30)', () => exportBusy(30), exportBusyText(30)],
    // A `HEAD` answer has no body (the contract of headAccountExport).
    ['404', () => new HttpResponse(null, { status: 404 }), EXPORT_NOT_FOUND],
  ])('S10A-AC-29 C-10 정보 내보내기 %s: 목록 위 실패 자리에 까닭을 보이고 내려받지 않는다', async (_, answer, says) => {
    seed();
    on('head', exportPath(LEE.id), () => answer());
    const { user } = renderAt('/members');
    await user.click(await rowAction(user, await rowOf(LEE.name), EXPORT));

    const band = await found(() => screen.queryAllByRole('alert').find((a) => text(a).includes(says)), `띠 "${says}"`);
    expect(band.closest('table'), '띠는 표 밖(목록 위)').toBeNull();
    expect(callsTo('HEAD', exportPath(LEE.id))).toHaveLength(1);
    expect(callsTo('GET', exportPath(LEE.id)), '내려받기 요청').toHaveLength(0);
  });

  test('S10A-AC-29 C-10 정보 내보내기 401: 로그인 화면으로 간다', async () => {
    seed();
    on('head', exportPath(LEE.id), () => errors.unauthenticated());
    const { user } = renderAt('/members');
    await user.click(await rowAction(user, await rowOf(LEE.name), EXPORT));

    await atPath('/login');
  });

  test.each(['503', '401'] as const)('S10A-AC-29 C-10 정보 내보내기: 화면을 떠난 뒤 온 HEAD %s는 버린다(띠도 로그인 이동도 없다)', async (status) => {
    seed();
    const release = gate();
    on('head', exportPath(LEE.id), async () => {
      await release.opened;
      return status === '503' ? exportBusy(5) : new HttpResponse(null, { status: 401 });
    });
    const { user } = renderAt('/members');
    await user.click(await rowAction(user, await rowOf(LEE.name), EXPORT));
    await sent('HEAD', exportPath(LEE.id));
    await user.click(await link('감사 로그'));
    await atPath('/audit-log');

    const done = answered('HEAD', exportPath(LEE.id));
    release.open();
    await done;
    await settle();
    expect(pageText()).not.toContain(exportBusyText(5));
    expect(window.location.pathname, '떠난 화면의 답이 로그인으로 보내지 않는다').toBe('/audit-log');
  });

  test('S10A-AC-29 C-10 정보 내보내기 두 번 누름: 확인하는 동안 다시 누르면 무시한다(HEAD 한 번). 답이 온 뒤에는 다시 누를 수 있다', async () => {
    seed();
    const release = gate();
    on('head', exportPath(LEE.id), async () => {
      await release.opened;
      return exportBusy(5);
    });
    const { user } = renderAt('/members');
    await user.click(await rowAction(user, await rowOf(LEE.name), EXPORT));
    await sent('HEAD', exportPath(LEE.id));
    await user.click(await rowAction(user, await rowOf(LEE.name), EXPORT));
    await settle();

    const done = answered('HEAD', exportPath(LEE.id));
    release.open();
    await done;
    await found(() => screen.queryAllByRole('alert').find((a) => text(a).includes(exportBusyText(5))), '503 띠');
    await settle();
    expect(callsTo('HEAD', exportPath(LEE.id)), '확인 중에 누른 것').toHaveLength(1);

    await user.click(await rowAction(user, await rowOf(LEE.name), EXPORT));
    await sent('HEAD', exportPath(LEE.id), 2);
  });
});

describe('C-10 익명화 대화상자 (10a 묶음)', () => {
  test('S10A-AC-30 C-10 익명화 대화상자의 본문: 제목, 굵은 "되돌릴 수 없습니다.", 바뀌는 것 넷, 담당 수, 이메일 확인 칸이 이 차례로 있다', async () => {
    seed();
    s3.openAssignments = 4;
    const { user } = renderAt('/members');
    const box = await openRowAction(user, LEE.name, ANONYMIZE);
    const field = await emailField(box, LEE.email);
    const count = `${LEE.name} 님이 담당인 열린 문의가 4건 있습니다.`;
    await eventually(() => {
      expect(text(box)).toContain(count);
    });

    const all = text(box);
    expect(all).toContain(anonymizeTitle(LEE.name));
    const bold = Array.from(box.querySelectorAll('strong, b')).some((el) => text(el).includes(CANNOT_UNDO));
    expect(bold, '"되돌릴 수 없습니다."는 굵게').toBe(true);
    const order = [CANNOT_UNDO, nameChange(LEE.id), ...CHANGES, count, emailLabel(LEE.email)].map((s) => all.indexOf(s));
    expect(order.every((at) => at >= 0), `본문의 문장: ${JSON.stringify(order)}`).toBe(true);
    expect([...order].sort((a, b) => a - b), '본문 차례').toEqual(order);
    expect(field).toHaveValue('');
  });

  test('S10A-AC-30 C-10 익명화 대화상자를 열면 초점은 이메일 칸이고 "익명화" 단추는 눌리지 않는다. 눌러도 보내지 않는다', async () => {
    seed();
    const { user } = renderAt('/members');
    const box = await openRowAction(user, LEE.name, ANONYMIZE);
    const field = await emailField(box, LEE.email);

    await eventually(() => {
      expect(document.activeElement).toBe(field);
    });
    const go = await goButton(box);
    expect(isBlocked(go), '처음').toBe(true);
    await user.click(go);
    await settle();
    expect(callsTo('POST', anonymizePath(LEE.id))).toHaveLength(0);
  });

  test.each([
    ['그대로', 'lee@popdesk.test', true, LEE],
    ['앞뒤 공백', '  lee@popdesk.test  ', true, LEE],
    ['대소문자', 'Lee@PopDesk.TEST', true, LEE],
    ['한 글자 모자람', 'lee@popdesk.tes', false, LEE],
    ['가운데 공백', 'lee @popdesk.test', false, LEE],
    ['옆 줄의 이메일', 'jung@popdesk.test', false, LEE],
    ['저장된 이메일에 대문자', 'yoon.mixed@popdesk.test', true, YOON],
  ])('S10A-AC-30 C-10 익명화 이메일 확인(%s): "%s"이면 단추가 눌리는지 %s', async (_, typed, opens, who) => {
    seed();
    const { user } = renderAt('/members');
    const box = await openAndType(user, who, typed);

    await goButton(box);
    await eventually(() => {
      const go = goNow(box);
      expect(go !== null && isBlocked(go)).toBe(!opens);
    });
  });

  test('S10A-AC-30 C-10 익명화 Enter: 이메일이 맞기 전의 Enter는 보내지 않고, 맞은 뒤의 Enter로 보낸다', async () => {
    seed();
    const { user } = renderAt('/members');
    const box = await openAndType(user, LEE, 'lee@popdesk');
    await user.keyboard('{Enter}');
    await settle();
    expect(callsTo('POST', anonymizePath(LEE.id)), '맞기 전').toHaveLength(0);

    await user.type(await emailField(box, LEE.email), '.test{Enter}');
    await sent('POST', anonymizePath(LEE.id));
  });

  test('S10A-AC-30 C-10 익명화 담당 수: 4건이면 문장과 꺼진 체크 상자. 체크하고 보내면 clearAssignments=true, 알림의 비운 수는 응답의 cleared에서 온다("담당 3건을 비웠습니다")', async () => {
    seed(false);
    s3.openAssignments = 4;
    // One of the four was closed meanwhile: the server clears three.
    s3.cleared = 3;
    const { user } = renderAt('/members');
    const box = await openAndType(user, LEE, LEE.email);
    await sent('GET', `${ACCOUNTS}/${String(LEE.id)}/open-assignments`);
    const check = await found(() => clearBox(box), `체크 상자 "${CLEAR}"`);
    expect(check).not.toBeChecked();

    await user.click(check);
    await user.click(await goButton(box));
    await sent('POST', anonymizePath(LEE.id));
    expect(anonymizeQuery(LEE.id).get('clearAssignments')).toBe('true');
    await closed();
    await said(ANONYMIZED);
    await said('담당 3건을 비웠습니다');
    expectNotSaid('담당 4건을 비웠습니다');
  });

  test('S10A-AC-30 C-10 익명화 담당 수(체크하지 않음): clearAssignments=true를 보내지 않고 알림에 비운 수가 없다', async () => {
    seed(false);
    s3.openAssignments = 4;
    const { user } = renderAt('/members');
    const box = await openAndType(user, LEE, LEE.email);
    await found(() => clearBox(box), `체크 상자 "${CLEAR}"`);

    await user.click(await goButton(box));
    await sent('POST', anonymizePath(LEE.id));
    expect(anonymizeQuery(LEE.id).get('clearAssignments'), 'clearAssignments는 없거나 false').not.toBe('true');
    await said(ANONYMIZED);
    await settle();
    expectNotSaid(/담당 \d+건을 비웠습니다/);
  });

  test('S10A-AC-30 C-10 익명화 담당 수 0: 조각을 숨긴다(문장도 체크 상자도 없다)', async () => {
    seed();
    s3.openAssignments = 0;
    const { user } = renderAt('/members');
    const box = await openRowAction(user, LEE.name, ANONYMIZE);
    await emailField(box, LEE.email);
    await sent('GET', `${ACCOUNTS}/${String(LEE.id)}/open-assignments`);
    await settle();

    expect(clearBox(box)).toBeNull();
    expect(text(box)).not.toContain('담당인 열린 문의');
    expect(text(box)).not.toContain('확인하지 못했습니다');
  });

  test('S10A-AC-30 C-10 익명화 담당 수를 읽는 중: 체크 상자가 아직 없고, 이메일이 맞으면 단추는 눌린다. 답이 오면 수와 체크 상자가 나온다', async () => {
    seed();
    s3.openAssignments = 4;
    const release = gate();
    route('get', `${ACCOUNTS}/${String(LEE.id)}/open-assignments`, async () => {
      await release.opened;
      return Response.json({ issues: 4 });
    });
    const { user } = renderAt('/members');
    const box = await openAndType(user, LEE, LEE.email);
    await sent('GET', `${ACCOUNTS}/${String(LEE.id)}/open-assignments`);

    expect(clearBox(box), '읽는 중').toBeNull();
    expect(isBlocked(await goButton(box)), '수는 단추를 막지 않는다').toBe(false);
    release.open();
    await found(() => clearBox(box), `체크 상자 "${CLEAR}"`);
    expect(text(box)).toContain(`${LEE.name} 님이 담당인 열린 문의가 4건 있습니다.`);
  });

  test('S10A-AC-30 C-10 익명화 담당 수 읽기 실패: "담당인 문의 수를 확인하지 못했습니다."', async () => {
    seed();
    route('get', `${ACCOUNTS}/${String(LEE.id)}/open-assignments`, () => errors.internal());
    const { user } = renderAt('/members');
    const box = await openRowAction(user, LEE.name, ANONYMIZE);

    await eventually(() => {
      expect(text(box)).toContain('담당인 문의 수를 확인하지 못했습니다.');
    });
  });

  test('S10A-AC-30 C-10 고객의 익명화: 담당 수를 묻지 않고(open-assignments 요청 없음) clearAssignments를 보내지 않는다. 정지와 같다', async () => {
    seed(false);
    s3.openAssignments = 4;
    const { user } = renderAt('/members');
    const box = await openAndType(user, KIM, KIM.email);
    await settle();

    expect(clearBox(box), '담당 비우기 체크 상자').toBeNull();
    expect(text(box)).not.toContain('담당인 열린 문의');
    await user.click(await goButton(box));
    await sent('POST', anonymizePath(KIM.id));
    expect(callsTo('GET', `${ACCOUNTS}/${String(KIM.id)}/open-assignments`), '담당 수 요청').toHaveLength(0);
    expect(anonymizeQuery(KIM.id).has('clearAssignments'), 'clearAssignments').toBe(false);
  });

  test('S10A-AC-30 C-10 익명화 첫 초점(담당 수가 와 있음): 체크 상자가 있어도 초점은 이메일 칸이고, 이메일 칸의 Enter로 보낸다', async () => {
    seed(false);
    s3.openAssignments = 4;
    const { user } = renderAt('/members');
    const box = await openRowAction(user, LEE.name, ANONYMIZE);
    const field = await emailField(box, LEE.email);
    await found(() => clearBox(box), `체크 상자 "${CLEAR}"`);

    expect(document.activeElement, '첫 초점').toBe(field);
    await user.keyboard('lee@popdesk.test{Enter}');
    await sent('POST', anonymizePath(LEE.id));
    expect(anonymizeQuery(LEE.id).get('clearAssignments'), '체크하지 않음').not.toBe('true');
  });

  test('S10A-AC-30 C-10 익명화 보내는 중: 단추가 "익명화하는 중…"이다', async () => {
    seed();
    const release = gate();
    route('post', anonymizePath(LEE.id), async () => {
      await release.opened;
      return errors.internal();
    });
    const { user } = renderAt('/members');
    const box = await openAndType(user, LEE, LEE.email);
    await user.click(await goButton(box));
    await sent('POST', anonymizePath(LEE.id));

    await eventually(() => {
      expect(text(goNow(box))).toBe(ANONYMIZING);
    });
    release.open();
  });
});

describe('C-10 익명화 결과 (10a 묶음)', () => {
  test('S10A-AC-31 C-10 익명화 성공: 닫고 목록을 다시 읽어 그 줄이 익명 이름, 익명 이메일, "익명화됨"이 되고 "익명화했습니다"를 알린다', async () => {
    seed(false);
    const { user } = renderAt('/members');
    const box = await openAndType(user, LEE, LEE.email);
    await expectCell(LEE.name, '상태', '사용 중');
    const before = listCalls();
    await user.click(await goButton(box));

    await sent('POST', anonymizePath(LEE.id));
    await closed();
    await said(ANONYMIZED);
    expect(listCalls(), '목록을 다시 읽음').toBeGreaterThan(before);
    await expectCell(ANON_NAME, '이메일', anonEmail(LEE.id));
    await expectCell(ANON_NAME, '상태', ANONYMIZED_BADGE);
    expect(cellUnder(LEE.name, '이름'), '옛 이름의 줄').toBeNull();
  });

  test.each([
    ['400 OWN_ACCOUNT', 'OWN_ACCOUNT', 400, OWN_ACCOUNT],
    ['400 LAST_ADMIN', 'LAST_ADMIN', 400, LAST_ADMIN],
    ['400 ACCOUNT_ANONYMIZED', 'ACCOUNT_ANONYMIZED', 400, ALREADY],
    ['409 ANONYMIZATION_RUNNING', 'ANONYMIZATION_RUNNING', 409, RUNNING],
  ])('S10A-AC-31 C-10 익명화 %s: 대화상자 안에 까닭을 보이고 닫지 않는다', async (_, code, status, says) => {
    seed();
    route('post', anonymizePath(LEE.id), () => rejected(code, status));
    const { user } = renderAt('/members');
    const box = await openAndType(user, LEE, LEE.email);
    await user.click(await goButton(box));

    await sent('POST', anonymizePath(LEE.id));
    await eventually(() => {
      expect(text(box)).toContain(says);
    });
    expect(openDialog(), '대화상자').not.toBeNull();
    expect(box.isConnected, '같은 대화상자').toBe(true);
    expectNotSaid(ANONYMIZED);
    expect(cellUnder(LEE.name, '상태')).toBe('사용 중');
  });

  test('S10A-AC-31 C-10 익명화 닫은 뒤 온 답: 대화상자를 다시 열지 않고 알림도 없다. 목록은 다시 읽는다. 다른 사람으로 열면 빈 칸부터', async () => {
    seed(false);
    const release = gate();
    route('post', anonymizePath(LEE.id), async () => {
      await release.opened;
      const after = anonymous(LEE.id, { role: 'AGENT' });
      state.accounts = state.accounts.map((a) => (a.id === LEE.id ? after : a));
      return Response.json({ ...after, cleared: { issues: 0 } });
    });
    const { user } = renderAt('/members');
    const first = await openAndType(user, LEE, LEE.email);
    await user.click(await goButton(first));
    await sent('POST', anonymizePath(LEE.id));
    await user.click(within(first).getByRole('button', { name: '취소' }));
    await closed();
    const before = listCalls();

    const done = answered('POST', anonymizePath(LEE.id));
    release.open();
    await done;
    await settle();
    expect(openDialog(), '늦은 답이 대화상자를 다시 열지 않는다').toBeNull();
    expectNotSaid(ANONYMIZED);
    await eventually(() => {
      expect(listCalls(), '목록을 다시 읽음').toBeGreaterThan(before);
    });
    await expectCell(ANON_NAME, '상태', ANONYMIZED_BADGE);

    const second = await openRowAction(user, JUNG.name, ANONYMIZE);
    expect(await emailField(second, JUNG.email)).toHaveValue('');
    expect(within(second).queryAllByRole('alert'), '앞 대화상자의 오류').toHaveLength(0);
    expect(isBlocked(await goButton(second))).toBe(true);
  });

  test.each([
    ['400 LAST_ADMIN', () => rejected('LAST_ADMIN'), LAST_ADMIN],
    ['409 ANONYMIZATION_RUNNING', () => rejected('ANONYMIZATION_RUNNING', 409), RUNNING],
    ['500', () => errors.internal(), ANONYMIZED],
  ])('S10A-AC-31 C-10 익명화 닫은 뒤 온 실패 %s: 대화상자·알림·띠가 없고 목록은 다시 읽는다', async (_, answer, says) => {
    seed(false);
    const release = gate();
    route('post', anonymizePath(LEE.id), async () => {
      await release.opened;
      return answer();
    });
    const { user } = renderAt('/members');
    const first = await openAndType(user, LEE, LEE.email);
    await user.click(await goButton(first));
    await sent('POST', anonymizePath(LEE.id));
    await user.click(within(first).getByRole('button', { name: '취소' }));
    await closed();
    const before = listCalls();

    const done = answered('POST', anonymizePath(LEE.id));
    release.open();
    await done;
    await settle();
    expect(openDialog(), '늦은 답이 대화상자를 다시 열지 않는다').toBeNull();
    expect(screen.queryAllByRole('alert'), '띠').toHaveLength(0);
    expectNotSaid(says);
    await eventually(() => {
      expect(listCalls(), '목록을 다시 읽음').toBeGreaterThan(before);
    });
  });

  test.each(['성공', '400 LAST_ADMIN'] as const)(
    'S10A-AC-31 C-10 익명화 앞 사람의 답(%s)이 다른 사람의 대화상자가 열린 동안 온다: 지금 대화상자의 입력, 단추, 오류가 그대로다',
    async (how) => {
      seed(false);
      const release = gate();
      route('post', anonymizePath(LEE.id), async () => {
        await release.opened;
        if (how !== '성공') return rejected('LAST_ADMIN');
        const after = anonymous(LEE.id, { role: 'AGENT' });
        state.accounts = state.accounts.map((a) => (a.id === LEE.id ? after : a));
        return Response.json({ ...after, cleared: { issues: 0 } });
      });
      const { user } = renderAt('/members');
      const first = await openAndType(user, LEE, LEE.email);
      await user.click(await goButton(first));
      await sent('POST', anonymizePath(LEE.id));
      await user.click(within(first).getByRole('button', { name: '취소' }));
      await closed();
      const second = await openRowAction(user, JUNG.name, ANONYMIZE);
      await user.type(await emailField(second, JUNG.email), 'jung@pop');
      expect(text(await goButton(second)), '앞 사람의 보내는 중이 새지 않는다').toBe(ANONYMIZE);

      const done = answered('POST', anonymizePath(LEE.id));
      release.open();
      await done;
      await settle();
      expect(second.isConnected, '같은 대화상자').toBe(true);
      expect(openDialog(), '열린 대화상자').not.toBeNull();
      expect(text(second)).toContain(anonymizeTitle(JUNG.name));
      expect(await emailField(second, JUNG.email), '친 글').toHaveValue('jung@pop');
      expect(within(second).queryAllByRole('alert'), '앞 사람의 오류').toHaveLength(0);
      expect(text(second)).not.toContain(LAST_ADMIN);
      const go = await goButton(second);
      expect(text(go), '단추 글').toBe(ANONYMIZE);
      expect(isBlocked(go), '이메일이 아직 맞지 않음').toBe(true);
      expectNotSaid(ANONYMIZED);
    },
  );

  test('S10A-AC-31 C-10 옛 줄의 400 ACCOUNT_ANONYMIZED: 다른 관리자가 익명화한 뒤 다시 읽지 않은 줄에서 정지 풀기를 누르면 목록 위 띠에 "이미 익명화한 계정입니다."를 보이고 목록을 다시 읽는다', async () => {
    seed(false);
    const { user } = renderAt('/members');
    await expectCell(HAN.name, '상태', '정지');
    // Another admin anonymized 한정지 after this list was read.
    state.accounts = state.accounts.map((a) => (a.id === HAN.id ? anonymous(HAN.id, { role: 'AGENT' }) : a));
    route('post', `${ACCOUNTS}/${String(HAN.id)}/reactivate`, () => rejectedWith('reactivateAccount', 'ACCOUNT_ANONYMIZED'));
    const before = listCalls();
    await user.click(await rowAction(user, await rowOf(HAN.name), '정지 풀기'));

    const band = await found(() => screen.queryAllByRole('alert').find((a) => text(a).includes(ALREADY)), `띠 "${ALREADY}"`);
    expect(band.closest('table'), '띠는 표 밖(목록 위)').toBeNull();
    await eventually(() => {
      expect(listCalls(), '목록을 다시 읽음').toBeGreaterThan(before);
    });
    await expectCell(anonEmail(HAN.id), '상태', ANONYMIZED_BADGE);
  });

  test('C-10 axe (10a): 익명 계정의 줄이 있는 목록과 담당 수가 있는 익명화 대화상자에 접근성 위반이 없다', async () => {
    seed();
    s3.openAssignments = 4;
    const { user } = renderAt('/members');
    await expectCell(ANON_NAME, '상태', ANONYMIZED_BADGE);
    const box = await openRowAction(user, LEE.name, ANONYMIZE);
    await found(() => clearBox(box), `체크 상자 "${CLEAR}"`);

    await expectNoAxeViolations();
  });
});
