// 2번 묶음 screens.md "콘솔 알림함" 절: 관리자의 옆 메뉴 "고객사"에 걸려 있는 소속 요청의 합을 숫자로 붙인다. 0이면 없다(S2-AC-48).
// 링크의 접근 가능한 이름은 글자 뒤에 무엇의 수인지가 붙는다("고객사 소속 요청 3건"). 보이는 숫자는 aria-hidden이다.
// 합은 GET /api/console/membership-requests?size=1의 totalElements로 얻는다. 관리자일 때만 부르고, 고객사 목록을 화면마다 부르지 않는다(screens.md 4절).
import { describe, expect, test } from 'vitest';
import { ACME, AGENT, BETA, callsTo, found, hrefPath, link, renderAt, screen, sent, state, text, within } from '../__tests__/console';
import { settle } from '../__tests__/console1a';
import { REQUESTS, pending, request } from '../__tests__/console2';

const IN_BETA = { code: BETA.code, name: BETA.name };

/** The digits a sighted user sees in the link; the screen reader gets them from the link's name. */
function shownCount(a: HTMLElement): string[] {
  return Array.from(a.querySelectorAll('[aria-hidden="true"]'))
    .map((el) => text(el))
    .filter((t) => /^\d+$/.test(t));
}

describe('콘솔 옆 메뉴: 고객사의 소속 요청 수 (2번 묶음)', () => {
  test('S2-AC-48 옆 메뉴(관리자): "고객사"에 걸려 있는 요청의 합이 붙고, 링크의 이름은 "고객사 소속 요청 3건"', async () => {
    state.companies = [ACME, BETA];
    pending.requests = [request({ id: 301 }), request({ id: 302 }), request({ id: 303, company: IN_BETA })];
    // A screen that does not ask for the companies itself: what is counted below is the frame's own requests.
    renderAt('/me');

    // One wait for each step of the first screen: on a busy machine the session and then the count take more than one wait together.
    await sent('GET', '/api/console/me');
    await sent('GET', REQUESTS);
    const companies = await link('고객사 소속 요청 3건');
    expect(hrefPath(companies)).toBe('/companies');
    expect(companies.closest('nav'), '메뉴 안').not.toBeNull();
    expect(shownCount(companies)).toEqual(['3']);
    expect(shownCount(await link('구성원')), '다른 항목').toEqual([]);
    const queries = callsTo('GET', REQUESTS).map((c) => new URL(c.url).searchParams);
    expect(queries.length, '합을 묻는 요청').toBeGreaterThan(0);
    expect(queries.filter((q) => q.get('size') !== '1'), 'size가 1이 아닌 요청').toHaveLength(0);
    expect(queries.filter((q) => q.has('companyCode')), '고객사로 거른 요청').toHaveLength(0);
    expect(callsTo('GET', '/api/console/companies'), '고객사 목록은 부르지 않는다').toHaveLength(0);
  });

  test('S2-AC-48 옆 메뉴(관리자): 걸려 있는 요청이 없으면 숫자가 없고 이름은 "고객사"뿐이다', async () => {
    state.companies = [ACME, BETA];
    renderAt('/issues');

    await sent('GET', REQUESTS);
    await settle();
    const companies = await link('고객사');
    expect(text(companies)).toBe('고객사');
    expect(shownCount(companies)).toEqual([]);
  });

  test('S2-AC-48 옆 메뉴(상담원): "고객사" 항목도 숫자도 없고, 합을 묻지 않는다', async () => {
    state.me = AGENT;
    pending.requests = [request({ id: 301 }), request({ id: 302 })];
    renderAt('/issues');

    const nav = await found(() => screen.queryByRole('navigation', { name: '메뉴' }), '메뉴');
    await link('문의');
    await settle();
    expect(within(nav).queryAllByRole('link', { name: /고객사|소속 요청/ })).toHaveLength(0);
    expect(text(nav)).not.toMatch(/\d/);
    expect(callsTo('GET', REQUESTS), '상담원은 소속 요청을 부르지 않는다').toHaveLength(0);
  });
});
