// 3번 묶음 screens.md 2절 C-9 고객사 상세: 배정 빼기 대화상자의 담당인 열린 문의 수와 담당 비우기(S3-AC-85).
import { HttpResponse } from 'msw/http';
import { describe, expect, test } from 'vitest';
import {
  callsTo,
  dialog,
  errors,
  eventually,
  expectNoAxeViolations,
  found,
  gate,
  isBlocked,
  member,
  on,
  renderAt,
  screen,
  sent,
  state,
  text,
  within,
} from '../__tests__/console';
import { answered, closed, region, rowOf, settle } from '../__tests__/console1a';
import { expectNotSaid, said } from '../__tests__/console2';
import { s3 } from '../__tests__/console3';

const URL_ACME = '/companies/ACME';
const AGENTS = '/api/console/companies/ACME/agents';
const COUNT_LEE = `${AGENTS}/2/open-assignments`;
const COUNT_JUNG = `${AGENTS}/3/open-assignments`;
const NOW_TEXT = '이상담 님은 이 고객사의 문의를 더 보지 못합니다.';
const CLEAR = '이 문의들의 담당을 비웁니다';
const KEEP_HELP = "비우지 않으면 담당자로 남지만 문의를 볼 수 없습니다. 문의 목록의 '담당자가 볼 수 없음'으로 찾을 수 있습니다.";

const LEE = member({ accountId: 2, email: 'lee@popdesk.test', name: '이상담' });
const JUNG = member({ accountId: 3, email: 'jung@popdesk.test', name: '정상담' });
const KIM = member({ accountId: 11, email: 'kim@acme.test', name: '김고객' });

type User = ReturnType<typeof renderAt>['user'];

/** Opens 빼기 of one row of a member list and returns the confirm dialog. */
async function openRemove(user: User, list: RegExp, name: string): Promise<HTMLElement> {
  const row = await rowOf(name, await region(list));
  await user.click(await found(() => within(row).queryByRole('button', { name: /빼기/ }), `${name} 줄의 "빼기"`));
  return dialog();
}

const openUnassign = (user: User, name = LEE.name) => openRemove(user, /배정 상담원/, name);
/** The button that takes the agent out. */
const go = (box: HTMLElement) => within(box).getByRole('button', { name: '빼기' });
const clearBox = (box: HTMLElement) => within(box).queryByRole<HTMLInputElement>('checkbox', { name: CLEAR });
const lastDelete = (path: string) => new URL(callsTo('DELETE', path).at(-1)?.url ?? 'http://x/').searchParams;

describe('C-9 배정 빼기와 담당 비우기', () => {
  test('S3-AC-85 C-9 배정 빼기 0건: 대화상자가 열리며 수를 부른다. 문구는 지금 그대로이고 체크 상자가 없다. 빼면 clearAssignments 없이 보낸다', async () => {
    state.agents = [LEE, JUNG];
    const { user } = renderAt(URL_ACME);
    const box = await openUnassign(user);

    const done = answered('GET', COUNT_LEE);
    await sent('GET', COUNT_LEE);
    await done;
    await settle();
    expect(text(box)).toContain(NOW_TEXT);
    expect(text(box)).not.toContain('담당인 열린 문의');
    expect(clearBox(box)).toBeNull();

    await user.click(go(box));
    await sent('DELETE', `${AGENTS}/2`);
    expect(lastDelete(`${AGENTS}/2`).has('clearAssignments')).toBe(false);
    await said('이상담 님을 배정에서 뺐습니다');
    expectNotSaid(/담당 \d+건을 비웠습니다/);
  });

  test('S3-AC-85 C-9 배정 빼기 수를 받는 중: 대화상자는 바로 열리고 수 자리만 비어 있다. "빼기"는 막지 않는다', async () => {
    state.agents = [LEE];
    s3.openAssignments = 2;
    const release = gate();
    on('get', COUNT_LEE, async () => {
      await release.opened;
      return HttpResponse.json({ issues: 2 });
    });
    const { user } = renderAt(URL_ACME);
    const box = await openUnassign(user);

    await sent('GET', COUNT_LEE);
    expect(text(box)).toContain(NOW_TEXT);
    expect(isBlocked(go(box)), '수를 받는 중의 "빼기"').toBe(false);
    expect(text(box)).not.toMatch(/\d+건/);

    release.open();
    await eventually(() => {
      expect(text(box)).toContain('담당인 열린 문의가 2건 있습니다.');
    });
    expect(isBlocked(go(box)), '수가 온 뒤의 "빼기"').toBe(false);
  });

  test('S3-AC-85 C-9 배정 빼기 1건 이상: 한 줄과 꺼진 체크 상자, 도움말, 거른 문의 목록으로 가는 새 탭 링크. 체크하지 않고 빼면 쿼리가 없고 토스트에 비운 수가 없다', async () => {
    state.agents = [LEE, JUNG];
    s3.openAssignments = 3;
    const { user } = renderAt(URL_ACME);
    const box = await openUnassign(user);

    await eventually(() => {
      expect(text(box)).toContain('이상담 님이 이 고객사에서 담당인 열린 문의가 3건 있습니다.');
    });
    expect(text(box)).toContain(NOW_TEXT);
    expect(text(box)).toContain(KEEP_HELP);
    const check = await found(() => clearBox(box), `체크 상자 "${CLEAR}"`);
    expect(check).not.toBeChecked();
    const toList = await found(() => within(box).queryByRole<HTMLAnchorElement>('link', { name: /3건/ }), '"3건" 링크');
    const target = new URL(toList.href);
    expect(target.pathname).toBe('/issues');
    expect(target.searchParams.get('companyCode')).toBe('ACME');
    expect(target.searchParams.get('assignee')).toBe('2');
    expect(toList).toHaveAttribute('target', '_blank');

    await user.click(go(box));
    await sent('DELETE', `${AGENTS}/2`);
    expect(lastDelete(`${AGENTS}/2`).has('clearAssignments')).toBe(false);
    await said('이상담 님을 배정에서 뺐습니다');
    expectNotSaid(/담당 \d+건을 비웠습니다/);
  });

  test('S3-AC-85 C-9 담당 비우기: 체크하고 빼면 clearAssignments=true로 보내고, 토스트에 응답이 준 수로 "담당 N건을 비웠습니다"를 붙인다', async () => {
    state.agents = [LEE, JUNG];
    s3.openAssignments = 3;
    // One was closed meanwhile: the answer says two.
    s3.cleared = 2;
    const { user } = renderAt(URL_ACME);
    const box = await openUnassign(user);
    await user.click(await found(() => clearBox(box), `체크 상자 "${CLEAR}"`));
    await user.click(go(box));

    await sent('DELETE', `${AGENTS}/2`);
    expect(lastDelete(`${AGENTS}/2`).get('clearAssignments')).toBe('true');
    await closed();
    await said('이상담 님을 배정에서 뺐습니다');
    await said('담당 2건을 비웠습니다');
  });

  test('S3-AC-85 C-9 수를 받지 못함: "담당인 문의 수를 확인하지 못했습니다."와 수 없는 체크 상자. 체크하고 빼면 clearAssignments=true로 보낸다', async () => {
    state.agents = [LEE];
    s3.openAssignments = 4;
    on('get', COUNT_LEE, () => errors.internal());
    const { user } = renderAt(URL_ACME);
    const box = await openUnassign(user);

    await eventually(() => {
      expect(text(box)).toContain('담당인 문의 수를 확인하지 못했습니다.');
    });
    expect(text(box)).not.toMatch(/\d+건/);
    expect(within(box).queryByRole('button', { name: '다시 확인' }), '"다시 확인"은 두지 않는다').toBeNull();

    await user.click(await found(() => clearBox(box), '수 없이 보이는 체크 상자'));
    await user.click(go(box));
    await sent('DELETE', `${AGENTS}/2`);
    expect(lastDelete(`${AGENTS}/2`).get('clearAssignments')).toBe('true');
    await said('담당 4건을 비웠습니다');
  });

  test('S3-AC-85 C-9 소속 고객 빼기: 담당인 문의 수를 부르지 않는다', async () => {
    state.customers = [KIM];
    state.agents = [LEE];
    s3.openAssignments = 3;
    const { user } = renderAt(URL_ACME);
    // The agent's dialog first: this is what the customer's dialog must not do.
    const first = await openUnassign(user);
    await sent('GET', COUNT_LEE);
    await user.click(within(first).getByRole('button', { name: '취소' }));
    await closed();
    const asked = callsTo('GET', /\/open-assignments$/).length;

    const box = await openRemove(user, /소속 고객/, KIM.name);
    await settle();

    expect(callsTo('GET', /\/open-assignments$/)).toHaveLength(asked);
    expect(clearBox(box)).toBeNull();
    expect(text(box)).not.toContain('담당인 열린 문의');
  });

  test('S3-AC-87 C-9 닫힌 뒤 온 수: 이상담의 수를 받는 중에 닫고 정상담으로 열면, 뒤에 온 이상담의 수를 정상담의 대화상자에 보이지 않는다', async () => {
    state.agents = [LEE, JUNG];
    const release = gate();
    on('get', COUNT_LEE, async () => {
      await release.opened;
      return HttpResponse.json({ issues: 5 });
    });
    const { user } = renderAt(URL_ACME);
    const first = await openUnassign(user);
    await sent('GET', COUNT_LEE);
    await user.click(within(first).getByRole('button', { name: '취소' }));
    await closed();

    const second = await openUnassign(user, JUNG.name);
    await sent('GET', COUNT_JUNG);
    const done = answered('GET', COUNT_LEE);
    release.open();
    await done;
    await settle();

    expect(text(second)).toContain('정상담 님은 이 고객사의 문의를 더 보지 못합니다.');
    expect(text(second)).not.toContain('5건');
    expect(clearBox(second)).toBeNull();
    expect(screen.queryAllByRole('alertdialog').length + screen.queryAllByRole('dialog').length).toBe(1);
  });

  test('C-9 axe (3번 묶음): 수와 체크 상자가 있는 배정 빼기 대화상자에 접근성 위반이 없다', async () => {
    state.agents = [LEE];
    s3.openAssignments = 3;
    const { user } = renderAt(URL_ACME);
    const box = await openUnassign(user);
    await found(() => clearBox(box), `체크 상자 "${CLEAR}"`);

    await expectNoAxeViolations();
  });
});
