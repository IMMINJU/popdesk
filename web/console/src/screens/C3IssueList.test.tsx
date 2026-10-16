// screens.md 4절 C-3 문의 목록. 표(<table>, 숨긴 <caption> "문의 목록, N건"), 제목 칸이 링크.
import { act } from '@testing-library/react';
import { HttpResponse } from 'msw/http';
import { describe, expect, test, vi } from 'vitest';
import {
  AGENT,
  BETA,
  CUSTOMER_PERSON,
  T0,
  eventually,
  expectNoAxeViolations,
  found,
  gate,
  heading1,
  hrefPath,
  link,
  on,
  paged,
  pageText,
  renderAt,
  screen,
  sent,
  state,
  statusWith,
  summary,
  tabbables,
  text,
  useFakeClock,
  within,
  type IssueSummary,
} from '../__tests__/console';

function many(count: number, from = 1): IssueSummary[] {
  return Array.from({ length: count }, (_, i) => summary({ key: `ACME-${String(from + i)}`, title: `문의 ${String(from + i)}` }));
}

function issueLinks(): HTMLAnchorElement[] {
  return screen.queryAllByRole<HTMLAnchorElement>('link').filter((a) => /^\/issues\/[A-Z0-9]+-\d+$/.test(hrefPath(a)));
}

/** The table row holding the issue `key`. */
async function rowOf(key: string): Promise<HTMLElement> {
  return found(
    () => screen.queryAllByRole('row').find((r) => within(r).queryAllByRole('cell').some((c) => text(c) === key)),
    `${key} 줄`,
  );
}

const pageLink = (n: number) => screen.queryByRole('link', { name: new RegExp(`^${String(n)}(쪽)?$`) });

describe('C-3 문의 목록', () => {
  test('C-3 데이터 있음: 표에 키, 제목(링크), 고객사, 상태, 우선순위, 올린 사람, 올린 시각', async () => {
    renderAt('/issues');

    const row = await rowOf('ACME-7');
    const t = text(row);
    for (const part of ['ACME-7', '로그인이 안 됩니다', '에이크미', '접수', '보통', '김고객']) {
      expect(t, `줄에 "${part}"`).toContain(part);
    }
    const title = within(row).getByRole('link', { name: /로그인이 안 됩니다/ });
    expect(hrefPath(title)).toBe('/issues/ACME-7');
    const time = row.querySelector('time');
    expect(time, '올린 시각 <time>').not.toBeNull();
    expect(new Date(time?.getAttribute('datetime') ?? '').toISOString()).toBe(new Date(T0).toISOString());
  });

  test('C-3 데이터 있음: <table>에 숨긴 caption "문의 목록, N건"과 열 머리', async () => {
    state.issues = many(3);
    renderAt('/issues');
    await rowOf('ACME-3');

    const table = await found(() => screen.queryByRole('table', { name: /문의 목록, 3건/ }), '표 caption "문의 목록, 3건"');
    expect(table.tagName).toBe('TABLE');
    expect(table.querySelector('caption')).not.toBeNull();
    const headers = within(table).getAllByRole('columnheader').map(text);
    for (const h of ['키', '제목', '고객사', '상태', '우선순위', '올린 사람', '담당자']) {
      expect(headers.some((x) => x.includes(h)), `열 머리 "${h}"`).toBe(true);
    }
    // 8a 묶음이 "SLA" 열을 맨 끝("올린 시각" 다음)에 더했다(8a screens.md 0절·2절).
    expect(headers.at(-1), '맨 끝 열 "SLA"').toBe('SLA');
  });

  test('C-3 상태 말: 다섯 상태를 상담용 말(접수, 확인, 처리 중, 해결, 종료)로 그린다', async () => {
    const words = { RECEIVED: '접수', CONFIRMED: '확인', IN_PROGRESS: '처리 중', RESOLVED: '해결', CLOSED: '종료' } as const;
    state.issues = Object.keys(words).map((status, i) =>
      summary({ key: `ACME-${String(i + 1)}`, title: `상태 ${status}`, status: status as keyof typeof words }),
    );
    renderAt('/issues');

    for (const [i, word] of Object.values(words).entries()) {
      const t = text(await rowOf(`ACME-${String(i + 1)}`));
      expect(t).toContain(word);
      expect(t, '고객용 말("~됨")이 아니다').not.toMatch(/접수됨|확인됨|해결됨|종료됨/);
    }
  });

  test('C-3 우선순위와 비공개: 우선순위를 글자로, 비공개 문의에 비공개 표시', async () => {
    state.issues = [
      summary({ key: 'ACME-1', title: '긴급 문의', priority: 'URGENT', private: true }),
      summary({ key: 'ACME-2', title: '낮은 문의', priority: 'LOW' }),
      summary({ key: 'BETA-1', title: '베타 문의', priority: 'HIGH', companyCode: BETA.code, companyName: BETA.name }),
    ];
    renderAt('/issues');

    expect(text(await rowOf('ACME-1'))).toContain('긴급');
    expect(text(await rowOf('ACME-1'))).toContain('비공개');
    expect(text(await rowOf('ACME-2'))).toContain('낮음');
    expect(text(await rowOf('ACME-2'))).not.toContain('비공개');
    expect(text(await rowOf('BETA-1'))).toContain('높음');
    expect(text(await rowOf('BETA-1'))).toContain('베타상사');
  });

  test('C-3 키보드: 줄마다 Tab은 한 번(제목 링크 하나)', async () => {
    state.issues = many(3);
    renderAt('/issues');
    await rowOf('ACME-3');

    const tbodyRows = screen.queryAllByRole('row').filter((r) => within(r).queryAllByRole('cell').length > 0);
    expect(tbodyRows).toHaveLength(3);
    for (const row of tbodyRows) {
      expect(tabbables(row), `${text(row)} 줄의 Tab 자리`).toHaveLength(1);
    }
    expect(issueLinks()).toHaveLength(3);
  });

  test('C-3 처음 불러옴: 표 머리는 바로, 300ms가 넘으면 aria-hidden 줄 뼈대', async () => {
    useFakeClock();
    const release = gate();
    on('get', '/api/console/issues', async () => {
      await release.opened;
      return HttpResponse.json(paged(state.issues));
    });
    renderAt('/issues', { fakeTimers: true });

    const main = await found(() => screen.queryByRole('main'), '본문 main');
    await found(() => screen.queryByRole('columnheader', { name: /제목/ }), '불러오는 동안의 표 머리');
    act(() => {
      vi.advanceTimersByTime(350);
    });
    await eventually(() => {
      expect(main.querySelectorAll('[aria-hidden="true"]').length, '줄 뼈대').toBeGreaterThan(0);
      expect(main).toHaveAttribute('aria-busy', 'true');
    });
    release.open();

    await rowOf('ACME-7');
    await eventually(() => {
      expect(main).not.toHaveAttribute('aria-busy', 'true');
    });
  });

  test('C-3 빈 목록(관리자): "아직 문의가 없습니다. …" 안내와 고객사 목록 링크', async () => {
    state.issues = [];
    renderAt('/issues');

    await eventually(() => {
      expect(pageText()).toContain('아직 문의가 없습니다. 고객사를 만들고 고객을 초대하면 고객이 포털에서 문의를 올립니다.');
    });
    const toCompanies = screen.queryAllByRole('link').filter((a) => hrefPath(a) === '/companies' && !a.closest('nav'));
    expect(toCompanies.length, '본문의 고객사 목록 링크').toBeGreaterThan(0);
    expect(issueLinks()).toHaveLength(0);
  });

  test('C-3 빈 목록(상담원, 배정 없음): "배정된 고객사가 없습니다. 관리자에게 배정을 요청하세요."', async () => {
    state.me = AGENT;
    state.companies = [];
    state.issues = [];
    renderAt('/issues');

    await eventually(() => {
      expect(pageText()).toContain('배정된 고객사가 없습니다. 관리자에게 배정을 요청하세요.');
    });
    expect(pageText()).not.toContain('아직 문의가 없습니다.');
  });

  test('C-3 빈 목록(상담원, 배정 있음): "배정된 고객사에 문의가 없습니다."', async () => {
    state.me = AGENT;
    state.issues = [];
    renderAt('/issues');

    await eventually(() => {
      expect(pageText()).toContain('배정된 고객사에 문의가 없습니다.');
    });
    expect(pageText()).not.toContain('배정된 고객사가 없습니다.');
  });

  test('C-3 쪽 넘기기: 1쪽뿐이면 숨긴다', async () => {
    state.issues = many(5);
    renderAt('/issues');
    await rowOf('ACME-5');

    expect(pageLink(1)).toBeNull();
    expect(pageLink(2)).toBeNull();
  });

  test('C-3 쪽 넘기기: 2쪽을 누르면 page=2로 부르고 포커스를 목록 제목으로, "2쪽, 45건 가운데 21~40"을 알린다', async () => {
    on('get', '/api/console/issues', ({ request }) => {
      const page = Number(new URL(request.url).searchParams.get('page') ?? '1');
      const items = many(page === 3 ? 5 : 20, (page - 1) * 20 + 1);
      return HttpResponse.json(paged(items, { page, size: 20, totalElements: 45, totalPages: 3 }));
    });
    const { user } = renderAt('/issues');
    await rowOf('ACME-20');
    expect(issueLinks()).toHaveLength(20);

    await user.click(await found(() => pageLink(2), '2쪽 링크'));

    await rowOf('ACME-21');
    const lists = await sent('GET', '/api/console/issues', 2);
    expect(new URL(lists[lists.length - 1]?.url ?? '').searchParams.get('page')).toBe('2');
    await statusWith('2쪽, 45건 가운데 21~40');
    await eventually(() => {
      expect(document.activeElement?.tagName).toMatch(/^H[1-6]$/);
    });
    expect(pageLink(2)).toHaveAttribute('aria-current', 'page');
  });

  test('C-3 쪽 바깥: 끝 뒤 쪽은 빈 목록 문구가 아니라 "이 쪽에는 문의가 없습니다"와 1쪽 링크', async () => {
    on('get', '/api/console/issues', ({ request }) => {
      const page = Number(new URL(request.url).searchParams.get('page') ?? '1');
      return page === 1
        ? HttpResponse.json(paged(many(20), { page: 1, totalElements: 25, totalPages: 2 }))
        : HttpResponse.json(paged([], { page, totalElements: 20, totalPages: 1 }));
    });
    const { user } = renderAt('/issues');
    await rowOf('ACME-20');
    await user.click(await found(() => pageLink(2), '2쪽 링크'));

    await eventually(() => {
      expect(pageText()).toContain('이 쪽에는 문의가 없습니다');
    });
    expect(pageText()).not.toContain('아직 문의가 없습니다.');
    const firstPage = screen
      .queryAllByRole<HTMLAnchorElement>('link')
      .filter((a) => hrefPath(a) === '/issues' && new URL(a.href).searchParams.get('page') !== '2' && !a.closest('nav'));
    expect(firstPage.length, '1쪽으로 가는 링크').toBeGreaterThan(0);
  });

  test('C-3 제목 링크를 누르면 C-4로 간다', async () => {
    const { user } = renderAt('/issues');
    await user.click(await link(/로그인이 안 됩니다/));

    await eventually(() => {
      expect(window.location.pathname).toBe('/issues/ACME-7');
    });
  });

  test('C-3 접근성: 처음 포커스는 h1(tabindex=-1), 화면 제목을 document.title에', async () => {
    renderAt('/issues');
    await rowOf('ACME-7');

    const h1 = await heading1();
    expect(h1).toHaveAttribute('tabindex', '-1');
    await eventually(() => {
      expect(document.activeElement).toBe(h1);
    });
    expect(document.title).toContain(text(h1));
  });

  test('C-3 axe: 표와 쪽 넘기기가 있는 화면에 접근성 위반이 없다', async () => {
    on('get', '/api/console/issues', () =>
      HttpResponse.json(
        paged([...many(19), summary({ key: 'ACME-99', private: true, reporter: CUSTOMER_PERSON, priority: 'URGENT' })], {
          totalElements: 45,
          totalPages: 3,
        }),
      ),
    );
    renderAt('/issues');
    await rowOf('ACME-99');
    await found(() => pageLink(2), '2쪽 링크');

    await expectNoAxeViolations();
  });
});
