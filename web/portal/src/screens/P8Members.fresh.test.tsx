// 2번 묶음 screens.md 6절 P-8 우리 회사 사람: P-8을 열 때 내 정보를 다시 읽는다(S2-AC-45).
// 포털을 연 뒤에 담당자가 되거나 담당자 지정이 풀린 사람의 메뉴와 화면이 다시 읽은 값을 따른다.
import { act } from '@testing-library/react';
import { describe, expect, test } from 'vitest';
import { atPath, button, eventually, hrefPath, link, renderAt, screen, sent } from '../__tests__/portal';
import { ACME_MANAGED, COLLEAGUE, belongTo, forbiddenScreen, rowOf } from '../__tests__/portal2';

const URL_PEOPLE = '/members';
const ME_PATH = '/api/portal/me';
const ACME_PLAIN = { ...ACME_MANAGED, manager: false };
const peopleLinks = () => screen.queryAllByRole('link', { name: '우리 회사 사람' });

/** Moves inside the open app as the address bar's back and forward do: there is no menu to click for someone who is not a manager. */
async function moveTo(path: string) {
  await act(async () => {
    window.history.pushState(null, '', path);
    window.dispatchEvent(new PopStateEvent('popstate', { state: null }));
    await Promise.resolve();
  });
  await atPath(path);
}

describe('P-8 우리 회사 사람: 포털을 연 뒤 바뀐 담당자 지정', () => {
  test('S2-AC-45 P-8 방금 담당자가 됨: 처음 읽은 내 정보에서는 메뉴가 없고, /members로 가면 내 정보를 다시 읽어 표와 "초대" 버튼이 보이고 메뉴에 "우리 회사 사람"이 생긴다', async () => {
    belongTo(ACME_PLAIN);
    renderAt('/issues');
    await link(/로그인이 안 됩니다/);
    expect(peopleLinks(), '담당자가 되기 전의 메뉴').toHaveLength(0);
    const readBefore = (await sent('GET', ME_PATH)).length;

    // An admin made me a manager of ACME: the fake server says so from now on.
    belongTo(ACME_MANAGED);
    await moveTo(URL_PEOPLE);

    await rowOf(COLLEAGUE.email);
    await button(/^초대$/);
    const peopleLink = await link('우리 회사 사람');
    expect(hrefPath(peopleLink)).toBe(URL_PEOPLE);
    expect(peopleLink.closest('nav'), '메뉴 안').not.toBeNull();
    await sent('GET', ME_PATH, readBefore + 1);
  });

  test('S2-AC-45 P-8 방금 담당자 지정이 풀림: 처음에는 메뉴가 있고, /members로 갈 때 다시 읽은 내 정보에서 담당자가 아니면 403 상태이고 메뉴에서 "우리 회사 사람"이 사라진다', async () => {
    belongTo(ACME_MANAGED);
    const { user } = renderAt('/issues');
    const peopleLink = await link('우리 회사 사람');

    // An admin took the manager mark away: my info says so and the member list answers 403 from now on.
    belongTo(ACME_PLAIN);
    await user.click(peopleLink);
    await atPath(URL_PEOPLE);

    await forbiddenScreen();
    await eventually(() => {
      expect(peopleLinks(), '담당자 지정이 풀린 뒤의 메뉴').toHaveLength(0);
    });
  });
});
