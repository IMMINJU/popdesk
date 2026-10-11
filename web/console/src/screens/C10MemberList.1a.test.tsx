// 1a screens.md 2절 C-10 구성원 목록: 필터와 검색(300ms), 줄의 사진과 상태 배지 넷, 상태별 줄 메뉴,
// 확인 대화상자 넷, 비밀번호 초기화의 링크 한 번 보이기(S1A-AC-29~42), 닫힌 뒤 온 답 버리기(S1A-AC-58).
// "초대 취소됨"은 계정 목록(SUSPENDED)과 초대 목록(`cancelled: true`)을 맞춰 안다(S1A-AC-39, 42).
import { act } from '@testing-library/react';
import { describe, expect, test, vi } from 'vitest';
import {
  INVITE_LINK,
  account,
  button,
  callsTo,
  dialog,
  errors,
  eventually,
  expectNoAxeViolations,
  found,
  gate,
  invitation,
  isBlocked,
  openDialog,
  pageText,
  renderAt,
  screen,
  sent,
  setSmtp,
  state,
  statusWith,
  text,
  useFakeClock,
  within,
} from '../__tests__/console';
import {
  PHOTO_AT,
  RESET_LINK,
  answered,
  closed,
  confirmButton,
  leftOnPage,
  openRowAction,
  photoOf,
  photosIn,
  rejected,
  route,
  rowAction,
  rowActions,
  rowOf,
  settle,
} from '../__tests__/console1a';

const ACCOUNTS = '/api/console/accounts';

const LEE = account({ id: 2, email: 'lee@popdesk.test', name: '이상담', role: 'AGENT', photoUpdatedAt: PHOTO_AT });
const JUNG = account({ id: 3, email: 'jung@popdesk.test', name: '정상담', role: 'AGENT' });
const HAN = account({ id: 4, email: 'han@popdesk.test', name: '한정지', role: 'AGENT', status: 'SUSPENDED' });
const KIM = account({ id: 11, email: 'kim@acme.test', name: '김고객', role: 'CUSTOMER' });
const PARK = account({ id: 21, email: 'park@acme.test', name: '박초대', role: 'CUSTOMER', status: 'INVITED' });
const OH = account({ id: 22, email: 'oh@acme.test', name: '오취소', role: 'CUSTOMER', status: 'SUSPENDED' });
const NAM = account({ id: 23, email: 'nam@acme.test', name: '남초대', role: 'CUSTOMER', status: 'INVITED' });

/** Every state a row can be in: me, active staff and customer, suspended, not accepted, invitation cancelled. */
function seed() {
  state.accounts = [account(), LEE, JUNG, HAN, KIM, PARK, OH, NAM];
  state.invitations = [
    invitation({ accountId: PARK.id, email: PARK.email, name: PARK.name }),
    invitation({ accountId: OH.id, email: OH.email, name: OH.name, cancelled: true }),
    invitation({ accountId: NAM.id, email: NAM.email, name: NAM.name }),
  ];
}

const select = (label: RegExp) =>
  found(() => screen.queryAllByLabelText<HTMLSelectElement>(label, { selector: 'select' })[0], `고르기 ${String(label)}`);
const search = () =>
  found(
    () => screen.queryByRole<HTMLInputElement>('searchbox') ?? screen.queryAllByLabelText<HTMLInputElement>(/검색/, { selector: 'input' })[0],
    '이름·이메일 검색 칸',
  );

/** The `q` of every account list request so far that had one. */
const searched = () =>
  callsTo('GET', ACCOUNTS)
    .map((c) => new URL(c.url).searchParams.get('q'))
    .filter((q) => q !== null && q !== '');
const lastQuery = () => new URL(callsTo('GET', ACCOUNTS).at(-1)?.url ?? 'http://x/').searchParams;

/** The text of the cell of `name`'s row under the column whose header holds `header`; null while there is none. */
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

const expectStatus = (name: string, word: string) => expectCell(name, '상태', word);

const closeButton = (box: HTMLElement) => found(() => within(box).queryAllByRole('button', { name: '닫기' })[0], '"닫기" 버튼');

describe('C-10 구성원 목록 (1a)', () => {
  test('S1A-AC-29 C-10 필터: 역할과 상태를 고르면 그 값으로 목록을 다시 받는다', async () => {
    seed();
    const { user } = renderAt('/members');
    await rowOf(KIM.name);

    await user.selectOptions(await select(/^역할/), '상담원');
    await eventually(() => {
      expect(lastQuery().get('role')).toBe('AGENT');
      expect(screen.queryAllByRole('row').some((r) => text(r).includes(KIM.name)), '고객 줄').toBe(false);
    });
    await user.selectOptions(await select(/^상태/), '정지');
    await eventually(() => {
      expect(lastQuery().get('role')).toBe('AGENT');
      expect(lastQuery().get('status')).toBe('SUSPENDED');
    });
    await rowOf(HAN.name);
    expect(screen.queryAllByRole('row').some((r) => text(r).includes(LEE.name)), '사용 중인 상담원 줄').toBe(false);
  });

  test('S1A-AC-29 C-10 검색: 입력을 멈추고 300ms 뒤에 한 번만 보낸다', async () => {
    seed();
    useFakeClock();
    const { user } = renderAt('/members', { fakeTimers: true });
    const box = await search();
    await rowOf(KIM.name);

    await user.type(box, 'kim');
    act(() => {
      vi.advanceTimersByTime(100);
    });
    await settle();
    expect(searched(), '300ms 전').toEqual([]);

    act(() => {
      vi.advanceTimersByTime(250);
    });
    await eventually(() => {
      expect(searched()).toEqual(['kim']);
    });
    await eventually(() => {
      expect(screen.queryAllByRole('row').some((r) => text(r).includes(LEE.name)), '검색에 안 맞는 줄').toBe(false);
    });
    await rowOf(KIM.name);
  });

  test('C-10 검색 결과 없음: "조건에 맞는 구성원이 없습니다"와 조건 지우기. 지우면 검색 칸을 비우고 전체를 다시 받는다', async () => {
    seed();
    const { user } = renderAt('/members');
    const box = await search();
    await user.type(box, '없는사람');

    await eventually(() => {
      expect(pageText()).toContain('조건에 맞는 구성원이 없습니다');
    });
    await user.click(await button(/조건 지우기/));

    await rowOf(KIM.name);
    expect(box).toHaveValue('');
    expect(lastQuery().get('q') ?? '').toBe('');
  });

  test('S1A-AC-42 S1A-AC-60 C-10 줄: 이름 앞에 사진이나 머리글자, 상태 배지는 사용 중·수락 전·초대 취소됨·정지', async () => {
    seed();
    renderAt('/members');

    await expectStatus(OH.name, '초대 취소됨');
    await expectStatus(LEE.name, '사용 중');
    await expectStatus(PARK.name, '수락 전');
    await expectStatus(HAN.name, '정지');
    expect(photosIn(await rowOf(LEE.name))).toEqual([photoOf(LEE.id, PHOTO_AT)]);
    expect(photosIn(await rowOf(KIM.name)), '사진 없는 사람은 머리글자').toEqual([]);
  });

  test.each([
    ['ACTIVE 상담원', LEE.name, true, ['이름 고치기', '역할 바꾸기', '비밀번호 초기화', '정지']],
    ['ACTIVE 고객(역할 바꾸기는 상담 쪽만)', KIM.name, true, ['이름 고치기', '비밀번호 초기화', '정지']],
    ['정지', HAN.name, true, ['이름 고치기', '정지 풀기']],
    ['수락 전(SMTP 있음)', PARK.name, true, ['이름 고치기', '초대 다시 보내기', '초대 취소']],
    ['수락 전(SMTP 없음)', PARK.name, false, ['이름 고치기', '초대 다시 보내기', '초대 취소', '초대 링크 복사']],
    ['초대 취소됨', OH.name, false, ['이름 고치기', '초대 다시 보내기']],
    ['나 자신(역할 바꾸기와 정지는 없다)', '최관리', true, ['이름 고치기', '비밀번호 초기화']],
  ])('S1A-AC-33 C-10 줄 메뉴 %s', async (_, name, smtp, wanted) => {
    seed();
    setSmtp(smtp);
    const { user } = renderAt('/members');
    await expectStatus(OH.name, '초대 취소됨');

    expect(await rowActions(user, await rowOf(name))).toEqual([...wanted].sort());
  });

  test('S1A-AC-30 C-10 이름 고치기: 대화상자에 지금 이름, 저장하면 PATCH로 보내고 줄의 이름이 바뀐다. 수락 전 계정도 된다', async () => {
    seed();
    const { user } = renderAt('/members');
    const box = await openRowAction(user, PARK.name, '이름 고치기');
    const name = await found(() => within(box).queryAllByLabelText<HTMLInputElement>(/^이름/, { selector: 'input' })[0], '이름 칸');
    expect(name).toHaveValue(PARK.name);
    await user.clear(name);
    await user.type(name, '박새이름');
    await user.click(confirmButton(box));

    const [patch] = await sent('PATCH', `${ACCOUNTS}/${String(PARK.id)}`);
    expect(await patch?.body).toEqual({ name: '박새이름' });
    await rowOf('박새이름');
    await closed();
  });

  test.each([
    [
      'S1A-AC-34 C-10 정지: 확인 대화상자의 문구, 확인하면 줄의 배지가 "정지"로 바뀐다',
      {
        who: LEE,
        action: '정지',
        says: ['이상담 님을 정지합니다. 로그인이 끊기고 다시 로그인할 수 없습니다.'],
        method: 'POST',
        path: `${ACCOUNTS}/${String(LEE.id)}/suspend`,
        body: undefined,
        after: () => expectStatus(LEE.name, '정지'),
      },
    ],
    [
      'S1A-AC-31 C-10 역할 바꾸기: 확인 대화상자에 바뀔 역할과 "다시 로그인해야 합니다", 확인하면 그 역할을 보내고 줄이 바뀐다',
      {
        who: LEE,
        action: '역할 바꾸기',
        says: ['관리자', '이상담 님은 다시 로그인해야 합니다.'],
        method: 'PUT',
        path: `${ACCOUNTS}/${String(LEE.id)}/role`,
        body: { role: 'ADMIN' },
        after: () => expectCell(LEE.name, '역할', '관리자'),
      },
    ],
    [
      'S1A-AC-36 C-10 비밀번호 초기화(SMTP 있음): 확인 대화상자의 문구, 확인하면 토스트 "재설정 메일을 보냈습니다."',
      {
        who: LEE,
        action: '비밀번호 초기화',
        says: ['지금 비밀번호를 못 쓰게 하고 재설정 메일을 보냅니다.'],
        method: 'POST',
        path: `${ACCOUNTS}/${String(LEE.id)}/password-reset`,
        body: undefined,
        after: () => statusWith('재설정 메일을 보냈습니다.'),
      },
    ],
    [
      'S1A-AC-39 C-10 초대 취소: 확인 대화상자의 문구, 확인하면 줄의 배지가 "초대 취소됨"으로 바뀐다',
      {
        who: PARK,
        action: '초대 취소',
        says: ['보낸 초대 링크를 못 쓰게 합니다.'],
        method: 'POST',
        path: `/api/console/invitations/${String(PARK.id)}/cancel`,
        body: undefined,
        after: () => expectStatus(PARK.name, '초대 취소됨'),
      },
    ],
  ])('%s', async (_, { who, action, says, method, path, body, after }) => {
    seed();
    const { user } = renderAt('/members');
    const box = await openRowAction(user, who.name, action);
    for (const sentence of says) expect(text(box)).toContain(sentence);
    expect(callsTo(method, path), '확인 전').toHaveLength(0);
    await user.click(confirmButton(box));

    const [call] = await sent(method, path);
    if (body) expect(await call?.body).toEqual(body);
    await after();
    await closed();
  });

  test.each([
    ['상담원을 관리자로', LEE, '관리자로'],
    ['관리자를 상담원으로', account({ id: 5, email: 'yoon@popdesk.test', name: '윤관리', role: 'ADMIN' }), '상담원으로'],
  ])('S1A-AC-31 C-10 역할 바꾸기 문구 %s: 대화상자와 토스트에 조사를 맞춰 적고 "(으)로"는 보이지 않는다', async (_, who, word) => {
    seed();
    state.accounts = [...state.accounts.filter((a) => a.id !== who.id), who];
    const { user } = renderAt('/members');
    const box = await openRowAction(user, who.name, '역할 바꾸기');
    expect(text(box)).toContain(word);
    expect(text(box)).not.toContain('(으)로');
    await user.click(confirmButton(box));

    const toast = await statusWith(`${who.name} 님의 역할을`);
    expect(text(toast)).toContain(word);
    expect(text(toast)).not.toContain('(으)로');
  });

  test('S1A-AC-35 C-10 정지 풀기: 누르면 풀고 줄의 배지가 "사용 중"으로 바뀐다', async () => {
    seed();
    const { user } = renderAt('/members');
    await user.click(await rowAction(user, await rowOf(HAN.name), '정지 풀기'));

    await sent('POST', `${ACCOUNTS}/${String(HAN.id)}/reactivate`);
    await expectStatus(HAN.name, '사용 중');
  });

  test('S1A-AC-37 C-10 비밀번호 초기화(SMTP 없음): 재설정 링크를 대화상자에 한 번 보이고 닫으면 다시 볼 수 없다고 적는다. 닫으면 화면에 남지 않는다', async () => {
    seed();
    setSmtp(false);
    const { user } = renderAt('/members');
    await user.click(confirmButton(await openRowAction(user, LEE.name, '비밀번호 초기화')));

    const shown = await found(() => Array.from(document.querySelectorAll('input')).find((i) => i.value === RESET_LINK), '재설정 링크 칸');
    expect(shown).toHaveAttribute('readonly');
    const box = await dialog();
    expect(box.contains(shown), '링크는 대화상자 안').toBe(true);
    expect(text(box)).toMatch(/닫으면 다시 볼 수 없/);
    await found(() => within(box).queryByRole('button', { name: '복사' }), '"복사" 버튼');

    await user.click(await closeButton(box));
    await closed();
    expect(leftOnPage('rst-'), '닫은 뒤 화면에 남은 링크').toBe(false);
    expect(window.location.href).not.toContain('rst-');
    expect(callsTo('POST', `${ACCOUNTS}/${String(LEE.id)}/password-reset`)).toHaveLength(1);
  });

  test('S1A-AC-38 S1A-AC-40 C-10 초대 다시 보내기(SMTP 없음): 취소된 초대를 다시 보내면 새 링크를 한 번 보이고 줄은 "수락 전"으로 돌아간다', async () => {
    seed();
    setSmtp(false);
    const { user } = renderAt('/members');
    await expectStatus(OH.name, '초대 취소됨');
    await user.click(await rowAction(user, await rowOf(OH.name), '초대 다시 보내기'));

    await sent('POST', `/api/console/invitations/${String(OH.id)}/resend`);
    const shown = await found(() => Array.from(document.querySelectorAll('input')).find((i) => i.value === INVITE_LINK), '초대 링크 칸');
    const box = await dialog();
    expect(box.contains(shown), '링크는 대화상자 안').toBe(true);
    await user.click(await closeButton(box));
    await closed();
    expect(leftOnPage('inv-'), '닫은 뒤 화면에 남은 링크').toBe(false);
    await expectStatus(OH.name, '수락 전');
  });

  test('C-10 정지 400: 대화상자를 닫지 않고 오류 띠를 보이며 줄의 배지는 그대로다', async () => {
    seed();
    route('post', `${ACCOUNTS}/:accountId/suspend`, () => rejected('LAST_ADMIN'));
    const { user } = renderAt('/members');
    const box = await openRowAction(user, LEE.name, '정지');
    await user.click(confirmButton(box));

    await found(() => within(box).queryByRole('alert'), '대화상자의 오류 띠');
    expect(openDialog()).not.toBeNull();
    expect(cellUnder(LEE.name, '상태')).toBe('사용 중');
  });

  test.each([
    { action: '이름 고치기', a: LEE, b: JUNG, method: 'PATCH', path: (id: number) => `${ACCOUNTS}/${String(id)}`, named: true },
    { action: '정지', a: LEE, b: JUNG, method: 'POST', path: (id: number) => `${ACCOUNTS}/${String(id)}/suspend`, named: true },
    { action: '역할 바꾸기', a: LEE, b: JUNG, method: 'PUT', path: (id: number) => `${ACCOUNTS}/${String(id)}/role`, named: true },
    { action: '비밀번호 초기화', a: LEE, b: JUNG, method: 'POST', path: (id: number) => `${ACCOUNTS}/${String(id)}/password-reset`, named: false },
    { action: '초대 취소', a: PARK, b: NAM, method: 'POST', path: (id: number) => `/api/console/invitations/${String(id)}/cancel`, named: false },
  ] as const)(
    'S1A-AC-58 C-10 닫힌 뒤 온 답($action): A의 요청 중에 취소한 뒤 온 실패는 버린다. B로 열면 오류 없이 처음부터 묻는다',
    async ({ action, a, b, method, path, named }) => {
      seed();
      const release = gate();
      route(method.toLowerCase() as 'post' | 'put' | 'patch', path(a.id), async () => {
        await release.opened;
        return errors.internal();
      });
      const { user } = renderAt('/members');
      const first = await openRowAction(user, a.name, action);
      // 이름은 고친 것이 있어야 보낸다.
      const nameBox = action === '이름 고치기' ? within(first).queryAllByLabelText(/^이름/, { selector: 'input' })[0] : undefined;
      if (nameBox) await user.type(nameBox, ' 2');
      await user.click(confirmButton(first));
      await sent(method, path(a.id));
      await user.click(within(first).getByRole('button', { name: '취소' }));
      await closed();

      const done = answered(method, path(a.id));
      release.open();
      await done;
      await settle();
      expect(openDialog(), '늦은 답이 대화상자를 다시 열지 않는다').toBeNull();

      const second = await openRowAction(user, b.name, action);
      expect(within(second).queryAllByRole('alert'), 'A의 오류').toHaveLength(0);
      expect(isBlocked(confirmButton(second)), 'B의 확인 버튼').toBe(false);
      expect(callsTo(method, path(b.id)), 'B는 확인 전').toHaveLength(0);
      if (named) {
        const shown = `${text(second)} ${Array.from(second.querySelectorAll('input')).map((i) => i.value).join(' ')}`;
        expect(shown).toContain(b.name);
        expect(shown).not.toContain(a.name);
      }
    },
  );

  test('S1A-AC-58 C-10 닫힌 뒤 온 재설정 링크: A의 초기화 중에 취소한 뒤 링크가 와도, B로 열면 A의 링크 없이 확인부터 묻는다', async () => {
    seed();
    setSmtp(false);
    const release = gate();
    const lateLink = 'http://console.localhost/reset-password#token=rst-LateAnswerA0001';
    route('post', `${ACCOUNTS}/${String(LEE.id)}/password-reset`, async () => {
      await release.opened;
      return Response.json({ resetLink: lateLink }, { headers: { 'Cache-Control': 'no-store' } });
    });
    const { user } = renderAt('/members');
    const first = await openRowAction(user, LEE.name, '비밀번호 초기화');
    await user.click(confirmButton(first));
    await sent('POST', `${ACCOUNTS}/${String(LEE.id)}/password-reset`);
    await user.click(within(first).getByRole('button', { name: '취소' }));
    await closed();

    const done = answered('POST', `${ACCOUNTS}/${String(LEE.id)}/password-reset`);
    release.open();
    await done;
    await settle();
    expect(leftOnPage('rst-LateAnswerA0001'), '닫힌 뒤 온 링크').toBe(false);

    const second = await openRowAction(user, JUNG.name, '비밀번호 초기화');
    expect(leftOnPage('rst-LateAnswerA0001'), 'A의 재설정 링크가 B의 대화상자에 나옴').toBe(false);
    expect(text(second)).toContain('지금 비밀번호를 못 쓰게 하고 재설정 메일을 보냅니다.');
    expect(callsTo('POST', `${ACCOUNTS}/${String(JUNG.id)}/password-reset`), 'B는 확인 전').toHaveLength(0);
  });

  test('C-10 axe (1a): 필터, 줄 메뉴가 있는 목록, 열린 정지 확인 대화상자에 접근성 위반이 없다', async () => {
    seed();
    const { user } = renderAt('/members');
    await search();
    await openRowAction(user, LEE.name, '정지');

    await expectNoAxeViolations();
  });
});
