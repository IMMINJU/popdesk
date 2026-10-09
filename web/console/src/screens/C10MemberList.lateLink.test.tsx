// verify.md 6절 V-2. 보내는 중에 닫은 대화상자에 늦게 온 링크는 버린다. 남겨 두면 다음에 연
// 다른 계정이나 새 초대에 앞 사람의 초대 링크가 나온다(MAIL-3, 잘못된 사람에게 계정이 넘어감).
import { act } from '@testing-library/react';
import { HttpResponse } from 'msw/http';
import { describe, expect, test } from 'vitest';
import {
  INVITE_LINK,
  account,
  button,
  callsTo,
  dialog,
  eventually,
  found,
  gate,
  on,
  openDialog,
  renderAt,
  screen,
  sent,
  server,
  setSmtp,
  state,
  text,
  within,
} from '../__tests__/console';

/** The link made for account A, answered after its dialog has closed. */
const LATE_TOKEN = 'inv-LateAnswerA0001';
const LATE_LINK = `http://portal.localhost/invite#token=${LATE_TOKEN}`;

const ACCOUNT_A = account({ id: 21, email: 'park@acme.test', name: '박초대', role: 'CUSTOMER', status: 'INVITED' });
const ACCOUNT_B = account({ id: 22, email: 'han@acme.test', name: '한초대', role: 'CUSTOMER', status: 'INVITED' });

/** Resolves when the fake server has answered `method path`. */
function mocked(method: string, path: string): Promise<void> {
  return new Promise((resolve) => {
    const listener = ({ request }: { request: Request }) => {
      if (request.method === method && new URL(request.url).pathname === path) {
        server.events.removeListener('response:mocked', listener);
        resolve();
      }
    };
    server.events.on('response:mocked', listener);
  });
}

/**
 * Lets an answer already released reach the screen code: two more full round
 * trips, inside `act` so React draws what the answer changed before the test looks.
 */
async function settle() {
  await act(async () => {
    for (let i = 0; i < 2; i += 1) {
      await fetch(new URL('/api/public/csrf', window.location.href));
    }
  });
}

async function rowOf(name: string): Promise<HTMLElement> {
  return found(
    () => screen.queryAllByRole('row').find((r) => within(r).queryAllByRole('cell').some((c) => text(c).includes(name))),
    `${name} 줄`,
  );
}

async function copyButtonOf(name: string): Promise<HTMLElement> {
  const row = await rowOf(name);
  return found(() => within(row).queryByRole('button', { name: '초대 링크 복사' }), `${name} 줄의 "초대 링크 복사"`);
}

/** Every place a link could still sit: input values and the page markup. */
function linkLeft(token: string): boolean {
  const values = Array.from(document.querySelectorAll<HTMLInputElement | HTMLTextAreaElement>('input, textarea')).map((el) => el.value);
  return values.some((v) => v.includes(token)) || document.body.innerHTML.includes(token);
}

describe('C-10 늦게 온 초대 링크', () => {
  test('C-10 V-2 초대 링크 복사: A의 새 링크를 만드는 중에 취소한 뒤 답이 와도, B를 열면 A의 링크 없이 확인 단계부터 묻는다', async () => {
    setSmtp(false);
    state.accounts = [account(), ACCOUNT_A, ACCOUNT_B];
    const release = gate();
    on('post', '/api/console/invitations/:accountId/link', async ({ params }) => {
      if (params.accountId === String(ACCOUNT_A.id)) {
        await release.opened;
        return HttpResponse.json({ inviteLink: LATE_LINK }, { headers: { 'Cache-Control': 'no-store' } });
      }
      return HttpResponse.json({ inviteLink: INVITE_LINK }, { headers: { 'Cache-Control': 'no-store' } });
    });
    const { user } = renderAt('/members');

    await user.click(await copyButtonOf(ACCOUNT_A.name));
    await user.click(within(await dialog()).getByRole('button', { name: '새 링크 만들기' }));
    await sent('POST', `/api/console/invitations/${String(ACCOUNT_A.id)}/link`);
    await user.click(within(await dialog()).getByRole('button', { name: '취소' }));
    await eventually(() => {
      expect(openDialog()).toBeNull();
    });

    const answered = mocked('POST', `/api/console/invitations/${String(ACCOUNT_A.id)}/link`);
    release.open();
    await answered;
    await settle();

    await user.click(await copyButtonOf(ACCOUNT_B.name));
    const box = await dialog();
    expect(linkLeft(LATE_TOKEN), 'A의 초대 링크가 화면에 남음').toBe(false);
    expect(text(box)).toContain('새 링크를 만들면 이전 링크는 쓸 수 없게 됩니다.');
    expect(within(box).queryByRole('button', { name: '새 링크 만들기' }), '확인 버튼').not.toBeNull();
    expect(callsTo('POST', `/api/console/invitations/${String(ACCOUNT_B.id)}/link`), 'B는 확인 전').toHaveLength(0);
  });

  test('C-10 V-2 초대: 보내는 중에 Esc로 닫고 답이 오기 전에 다시 열면, 앞 초대의 링크가 나오지 않는다', async () => {
    setSmtp(false);
    const release = gate();
    on('post', '/api/console/invitations', async () => {
      await release.opened;
      return HttpResponse.json({ accountId: 30, inviteLink: LATE_LINK }, { status: 201, headers: { 'Cache-Control': 'no-store' } });
    });
    const { user } = renderAt('/members');

    await user.click(await button(/^초대$/));
    const first = await dialog();
    const email = await found(() => within(first).queryAllByLabelText<HTMLInputElement>(/^이메일/, { selector: 'input' })[0], '이메일 칸');
    await user.type(email, 'choi@popdesk.test');
    await user.type(await found(() => within(first).queryAllByLabelText<HTMLInputElement>(/^이름/, { selector: 'input' })[0], '이름 칸'), '최상담');
    await user.click(within(first).getByRole('radio', { name: '상담원' }));
    await user.click(within(first).getByRole('button', { name: '초대 보내기' }));
    await sent('POST', '/api/console/invitations');
    await user.keyboard('{Escape}');
    await eventually(() => {
      expect(openDialog()).toBeNull();
    });

    await user.click(await button(/^초대$/));
    const again = await dialog();
    const answered = mocked('POST', '/api/console/invitations');
    release.open();
    await answered;
    await settle();

    expect(linkLeft(LATE_TOKEN), '앞 초대의 링크가 다시 연 대화상자에 나옴').toBe(false);
    expect(within(again).queryAllByLabelText(/^이메일/, { selector: 'input' }), '다시 연 대화상자는 빈 초대 칸').toHaveLength(1);
  });
});
