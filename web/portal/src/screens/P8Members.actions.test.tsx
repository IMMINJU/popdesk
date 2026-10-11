// 2번 묶음 screens.md 1절 P-8 우리 회사 사람: 줄 메뉴의 동작(S2-AC-24~31, 47)과 초대 대화상자(S2-AC-22, 46).
// "초대 취소"와 "소속에서 빼기"는 같은 DELETE …/members/{id}다. 가짜 서버가 받은 요청으로 본다.
import { HttpResponse } from 'msw/http';
import { describe, expect, test } from 'vitest';
import {
  button,
  callsTo,
  describedText,
  errors,
  eventually,
  expectFieldError,
  fieldState,
  found,
  gate,
  isBlocked,
  pageText,
  renderAt,
  screen,
  sent,
  text,
  within,
} from '../__tests__/portal';
import { rejected, route } from '../__tests__/portal1a';
import {
  ACME_MANAGED,
  COLLEAGUE,
  INVITED_ROW,
  belongTo,
  cellOf,
  closed,
  confirmButton,
  expectNotSaid,
  membersPath,
  openDialog,
  people,
  rowAction,
  rowOf,
  said,
} from '../__tests__/portal2';

const URL_PEOPLE = '/members';
const LIST = membersPath('ACME');
const INVITE_NOTE = '초대 메일이 가거나, 이미 계정이 있는 분이면 지원팀이 확인한 뒤 추가됩니다.';

type User = ReturnType<typeof renderAt>['user'];

/** Opens the page with ACME's rows in and returns how many times the list was read so far. */
async function open(): Promise<{ user: User; loads: number }> {
  belongTo(ACME_MANAGED);
  const { user } = renderAt(URL_PEOPLE);
  await rowOf(COLLEAGUE.email);
  return { user, loads: callsTo('GET', LIST).length };
}

async function reloaded(loads: number) {
  await eventually(() => {
    expect(callsTo('GET', LIST).length, '목록을 다시 불러옴').toBeGreaterThan(loads);
  });
}

async function openInvite(user: User) {
  await user.click(await button(/^초대$/));
  const box = await found(() => screen.queryByRole('dialog', { name: `${ACME_MANAGED.name}에 초대` }), '대화상자 "에이크미에 초대"');
  const fieldIn = (label: RegExp) => found(() => within(box).queryAllByLabelText<HTMLInputElement>(label, { selector: 'input' })[0], `대화상자 칸 ${String(label)}`);
  return { box, email: await fieldIn(/^이메일/), name: await fieldIn(/^이름/) };
}

async function fillInvite(user: User, d: Awaited<ReturnType<typeof openInvite>>, email = 'new@acme.test', name = '신입') {
  await user.type(d.email, email);
  await user.type(d.name, name);
}

describe('P-8 우리 회사 사람: 줄 메뉴의 동작', () => {
  test('S2-AC-29 S2-AC-47 P-8 초대 다시 보내기: 확인 없이 바로 보내고 토스트 "초대를 다시 보냈습니다"', async () => {
    const { user } = await open();
    await user.click(await rowAction(user, await rowOf(INVITED_ROW.email), '초대 다시 보내기'));

    await sent('POST', `${LIST}/${INVITED_ROW.id}/resend`);
    await said('초대를 다시 보냈습니다');
    expect(openDialog(), '확인 대화상자').toBeNull();
  });

  test.each([
    ['400 INVITATION_NOT_RESENDABLE이면 까닭을 가르지 않는 토스트', () => rejected('INVITATION_NOT_RESENDABLE'), '이 초대는 다시 보낼 수 없습니다. 지원팀에 문의하세요'],
    ['429이면 공통 상태', () => errors.rateLimited(30), '요청이 많습니다. 30초 뒤 다시 하세요.'],
  ])('S2-AC-31 P-8 초대 다시 보내기: %s', async (_, answer, wanted) => {
    route('post', `${LIST}/:id/resend`, answer);
    const { user } = await open();
    await user.click(await rowAction(user, await rowOf(INVITED_ROW.email), '초대 다시 보내기'));

    await said(wanted);
    expectNotSaid('초대를 다시 보냈습니다');
    await rowOf(INVITED_ROW.email);
  });

  test.each([
    { what: '초대 취소', row: INVITED_ROW, asks: ['박초대 님의 초대를 취소할까요?'], toast: '초대를 취소했습니다' },
    {
      what: '소속에서 빼기',
      row: COLLEAGUE,
      asks: ['최동료 님을 에이크미에서 뺄까요?', '이 사람은 자기가 올린 문의만 계속 볼 수 있습니다.'],
      toast: '소속에서 뺐습니다',
    },
  ])('S2-AC-24 S2-AC-25 S2-AC-47 P-8 $what: 확인 대화상자의 문구, 확인하면 DELETE …/members/{id}를 부르고 행이 사라진다', async ({ what, row, asks, toast }) => {
    const path = `${LIST}/${row.id}`;
    const { user } = await open();
    await user.click(await rowAction(user, await rowOf(row.email), what));

    const box = await found(() => openDialog(), `"${what}" 확인 대화상자`);
    for (const ask of asks) expect(text(box)).toContain(ask);
    expect(callsTo('DELETE', path), '확인 전').toHaveLength(0);
    await user.click(confirmButton(box));

    await sent('DELETE', path);
    await eventually(() => {
      expect(pageText()).not.toContain(row.email);
    });
    await said(toast);
    await closed();
  });

  test.each([
    {
      what: '404(다른 사람이 먼저 뺌)',
      answer: () => {
        people.rows = { ...people.rows, ACME: (people.rows.ACME ?? []).filter((m) => m.id !== COLLEAGUE.id) };
        return errors.notFound();
      },
      toast: '이미 목록에 없는 사람입니다',
      after: null,
    },
    {
      what: '400 MEMBER_IS_MANAGER(보는 사이 담당자가 됨)',
      answer: () => {
        people.rows = { ...people.rows, ACME: (people.rows.ACME ?? []).map((m) => (m.id === COLLEAGUE.id ? { ...m, manager: true } : m)) };
        return rejected('MEMBER_IS_MANAGER');
      },
      toast: '담당자는 뺄 수 없습니다',
      after: '담당자',
    },
  ])('S2-AC-28 P-8 소속에서 빼기가 $what: 토스트와 함께 목록을 다시 불러온다', async ({ answer, toast, after }) => {
    route('delete', `${LIST}/:id`, answer);
    const { user, loads } = await open();
    await user.click(await rowAction(user, await rowOf(COLLEAGUE.email), '소속에서 빼기'));
    await user.click(confirmButton(await found(() => openDialog(), '확인 대화상자')));

    await said(toast);
    await reloaded(loads);
    expectNotSaid('소속에서 뺐습니다');
    if (after === null) {
      await eventually(() => {
        expect(pageText()).not.toContain(COLLEAGUE.email);
      });
    } else {
      await eventually(() => {
        const row = screen.queryAllByRole('row').find((r) => text(r).includes(COLLEAGUE.email));
        expect(row ? text(cellOf(row, '담당자')) : null).toBe(after);
      });
    }
  });
});

describe('P-8 우리 회사 사람: 초대 대화상자', () => {
  test('S2-AC-22 P-8 초대 처음: 제목 "에이크미에 초대", 칸은 이메일과 이름 둘뿐(역할·고객사 칸 없음), 아래의 안내 한 줄. 빈 채로 누르면 빈 칸에 오류', async () => {
    const { user } = await open();
    const d = await openInvite(user);

    expect(d.email).toHaveValue('');
    expect(d.name).toHaveValue('');
    expect(within(d.box).queryAllByRole('textbox')).toHaveLength(2);
    for (const role of ['radio', 'checkbox', 'combobox'] as const) expect(within(d.box).queryAllByRole(role), `${role} 칸`).toHaveLength(0);
    expect(text(d.box)).toContain(INVITE_NOTE);
    const submit = confirmButton(d.box);
    expect(text(submit)).toBe('초대');
    expect(isBlocked(submit)).toBe(false);

    const before = { email: fieldState(d.email), name: fieldState(d.name) };
    await user.click(submit);

    await expectFieldError(d.email, before.email);
    await expectFieldError(d.name, before.name);
    expect(openDialog()).toBe(d.box);
  });

  test('P-8 초대 보내는 중: 버튼을 막고 "보내는 중"', async () => {
    const release = gate();
    route('post', LIST, async () => {
      await release.opened;
      return new HttpResponse(null, { status: 204 });
    });
    const { user } = await open();
    const d = await openInvite(user);
    await fillInvite(user, d);
    await user.click(confirmButton(d.box));

    await sent('POST', LIST);
    await eventually(() => {
      const submit = confirmButton(d.box);
      expect(isBlocked(submit)).toBe(true);
      expect(text(submit)).toContain('보내는 중');
    });
    release.open();
    await closed();
  });

  test('S2-AC-22 S2-AC-46 P-8 초대 성공: 이메일과 이름만 보내고, 닫히고, 토스트 "요청을 처리했습니다"(새 이메일이든 있는 이메일이든 같다), 목록을 다시 불러온다', async () => {
    const { user, loads } = await open();
    const d = await openInvite(user);
    await fillInvite(user, d);
    await user.click(confirmButton(d.box));

    const [post] = await sent('POST', LIST);
    expect(await post?.body).toEqual({ email: 'new@acme.test', name: '신입' });
    await closed();
    await said('요청을 처리했습니다');
    await reloaded(loads);
    expect(text(cellOf(await rowOf('new@acme.test'), '상태'))).toBe('수락 전');
  });

  test.each([
    ['400(칸 오류): 문구는 콘솔 초대 대화상자와 같다', 'bad', () => errors.invalid([{ field: 'email', code: 'INVALID_FORMAT' }]), '이메일 모양이 아닙니다.'],
    ['409 ALREADY_LISTED', COLLEAGUE.email, () => rejected('ALREADY_LISTED', 409), '이미 목록에 있는 이메일입니다'],
  ])('P-8 초대 %s: 이메일 칸 아래에 오류', async (_, email, answer, wanted) => {
    route('post', LIST, answer);
    const { user } = await open();
    const d = await openInvite(user);
    await fillInvite(user, d, email);
    const before = fieldState(d.email);
    await user.click(confirmButton(d.box));

    await expectFieldError(d.email, before);
    expect(describedText(d.email)).toContain(wanted);
    expect(d.name).not.toHaveAttribute('aria-invalid', 'true');
    expect(openDialog()).toBe(d.box);
    expectNotSaid('요청을 처리했습니다');
  });

  test.each([
    ['409 TOO_MANY_PENDING', () => rejected('TOO_MANY_PENDING', 409), '수락을 기다리는 초대가 너무 많습니다. 기다리는 초대를 정리한 뒤 다시 해 주세요'],
    ['400 COMPANY_NOT_ACTIVE', () => rejected('COMPANY_NOT_ACTIVE'), '보관된 고객사에는 초대할 수 없습니다'],
    ['429', () => errors.rateLimited(30), '요청이 많습니다. 30초 뒤 다시 하세요.'],
  ])('P-8 초대 %s: 대화상자 안의 Alert, 넣은 값은 그대로', async (_, answer, wanted) => {
    route('post', LIST, answer);
    const { user } = await open();
    const d = await openInvite(user);
    await fillInvite(user, d);
    await user.click(confirmButton(d.box));

    await found(() => within(d.box).queryAllByRole('alert').find((el) => text(el).includes(wanted)), `대화상자 안의 띠: ${wanted}`);
    expect(d.email).toHaveValue('new@acme.test');
    expect(d.name).toHaveValue('신입');
    expect(d.email).not.toHaveAttribute('aria-invalid', 'true');
    expect(openDialog()).toBe(d.box);
  });
});
