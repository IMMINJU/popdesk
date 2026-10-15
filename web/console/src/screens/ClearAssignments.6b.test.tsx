// 6b 묶음 screens.md 4절 C-9 배정 빼기와 C-10 계정 정지의 담당 비우기: 열린 작업이 있으면 문의 문장 다음에 작업 문장을
// 더하고 체크 상자를 "이 문의와 작업의 담당을 비웁니다"로 한다. 문의가 0이고 작업만 있으면 작업 문장만. `tasks`가 없거나
// 0이면 지금 그대로다(S6B-AC-88). 수는 계약의 OpenAssignments 꼴로 답한다.
import { waitFor } from '@testing-library/react';
import { HttpResponse } from 'msw/http';
import { describe, expect, test } from 'vitest';
import { account, callsTo, dialog, expectNoAxeViolations, found, member, on, renderAt, sent, state, text, within } from '../__tests__/console';
import { openRowAction, region, rowOf } from '../__tests__/console1a';
import { CLEAR, clearBox, go } from '../__tests__/console3';
import { contract } from '../__tests__/console6a';

const BOTH = '이 문의와 작업의 담당을 비웁니다';
const COUNT_LEE_ACME = '/api/console/companies/ACME/agents/2/open-assignments';
const COUNT_LEE = '/api/console/accounts/2/open-assignments';

const LEE_MEMBER = member({ accountId: 2, email: 'lee@popdesk.test', name: '이상담' });
const LEE = account({ id: 2, email: 'lee@popdesk.test', name: '이상담', role: 'AGENT' });

type User = ReturnType<typeof renderAt>['user'];
type Answer = { issues: number; tasks?: number };

interface Place {
  what: string;
  path: string;
  operationId: string;
  /** "이 고객사에서 " for 배정 빼기, nothing for 정지. */
  where: string;
  open: (user: User) => Promise<HTMLElement>;
  setUp: () => void;
  /** The button that goes on, and the request it sends (its method and path). */
  go: '빼기' | '정지';
  method: string;
  sends: string;
}

const PLACES: Place[] = [
  {
    what: 'C-9 배정 빼기',
    path: COUNT_LEE_ACME,
    operationId: 'countCompanyOpenAssignments',
    where: '이 고객사에서 ',
    setUp: () => {
      state.agents = [LEE_MEMBER];
    },
    open: async (user) => {
      const row = await rowOf(LEE_MEMBER.name, await region(/배정 상담원/));
      await user.click(await found(() => within(row).queryByRole('button', { name: /빼기/ }), '이상담 줄의 "빼기"'));
      return dialog();
    },
    go: '빼기',
    method: 'DELETE',
    sends: '/api/console/companies/ACME/agents/2',
  },
  {
    what: 'C-10 정지',
    path: COUNT_LEE,
    operationId: 'countOpenAssignments',
    where: '',
    setUp: () => {
      state.accounts = [account(), LEE];
    },
    open: (user) => openRowAction(user, LEE.name, '정지'),
    go: '정지',
    method: 'POST',
    sends: '/api/console/accounts/2/suspend',
  },
];

const URL_OF: Record<string, string> = { 'C-9 배정 빼기': '/companies/ACME', 'C-10 정지': '/members' };

/** Renders the screen of `place`, answers the count with `answer`, and opens the dialog. */
async function openWith(place: Place, answer: Answer, checked: boolean): Promise<HTMLElement> {
  return (await openFor(place, answer, checked)).box;
}

/** Like `openWith`, and gives the user too. */
async function openFor(place: Place, answer: Answer, checked: boolean): Promise<{ box: HTMLElement; user: User }> {
  place.setUp();
  on('get', place.path, () => HttpResponse.json(checked ? contract(place.operationId, 200, answer) : answer));
  const { user } = renderAt(URL_OF[place.what] ?? '/');
  await waitFor(
    () => {
      expect(text(document.body), '화면에 그 사람의 줄').toContain('이상담');
    },
    { timeout: 5000 },
  );
  const box = await place.open(user);
  await sent('GET', place.path);
  return { box, user };
}

describe('담당 비우기의 작업 몫 (6b 묶음)', () => {
  describe.each(PLACES)('$what', (place) => {
    test(`S6B-AC-88 ${place.what} 문의 3건과 작업 2건: 두 문장이 차례로 있고 체크 상자는 "${BOTH}"이다`, async () => {
      const box = await openWith(place, { issues: 3, tasks: 2 }, true);

      const issues = `이상담 님이 ${place.where}담당인 열린 문의가 3건 있습니다.`;
      const tasks = `이상담 님이 ${place.where}담당인 열린 작업이 2건 있습니다.`;
      await waitFor(() => {
        expect(text(box)).toContain(tasks);
      });
      expect(text(box)).toContain(issues);
      expect(text(box).indexOf(issues), '문의 문장 다음에 작업 문장').toBeLessThan(text(box).indexOf(tasks));
      const check = await found(() => within(box).queryByRole('checkbox', { name: BOTH }), `체크 상자 "${BOTH}"`);
      expect(check).not.toBeChecked();
      expect(clearBox(box), `옛 이름 "${CLEAR}"`).toBeNull();
      await expectNoAxeViolations();
    });

    test(`S6B-AC-88 ${place.what} 문의 0건과 작업 2건: 작업 문장만 있고 체크 상자는 "${BOTH}"이다`, async () => {
      const box = await openWith(place, { issues: 0, tasks: 2 }, true);

      await waitFor(() => {
        expect(text(box)).toContain(`이상담 님이 ${place.where}담당인 열린 작업이 2건 있습니다.`);
      });
      expect(text(box)).not.toContain('담당인 열린 문의');
      expect(within(box).queryByRole('checkbox', { name: BOTH })).not.toBeNull();
    });

    test(`S6B-AC-88 ${place.what} 문의 0건과 작업 2건에서 "${BOTH}"를 체크하고 ${place.go}: clearAssignments=true로 보낸다`, async () => {
      const { box, user } = await openFor(place, { issues: 0, tasks: 2 }, true);

      await user.click(await found(() => within(box).queryByRole('checkbox', { name: BOTH }), `체크 상자 "${BOTH}"`));
      await user.click(go(box, place.go));

      await sent(place.method, place.sends);
      const query = new URL(callsTo(place.method, place.sends).at(-1)?.url ?? 'http://x/').searchParams;
      expect(query.get('clearAssignments')).toBe('true');
    });

    test.each([
      { what: 'tasks 0', answer: { issues: 3, tasks: 0 }, checked: true },
      { what: 'tasks가 없는 옛 답', answer: { issues: 3 }, checked: false },
    ])(`S6B-AC-88 ${place.what} 문의 3건과 $what: 지금 문장과 지금 체크 상자 그대로다`, async ({ answer, checked }) => {
      const box = await openWith(place, answer, checked);

      await waitFor(() => {
        expect(text(box)).toContain(`이상담 님이 ${place.where}담당인 열린 문의가 3건 있습니다.`);
      });
      expect(text(box)).not.toContain('열린 작업');
      expect(clearBox(box), `체크 상자 "${CLEAR}"`).not.toBeNull();
      expect(within(box).queryByRole('checkbox', { name: BOTH })).toBeNull();
      expect(go(box, place.what === 'C-9 배정 빼기' ? '빼기' : '정지')).not.toBeNull();
    });
  });
});
