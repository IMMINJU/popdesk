// screens.md 3절 P-3 문의 목록.
import { HttpResponse } from 'msw/http';
import { describe, expect, test } from 'vitest';
import {
  COLLEAGUE,
  SUPPORT_TEAM,
  T0,
  action,
  atPath,
  eventually,
  expectNoAxeViolations,
  found,
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
  type IssueSummary,
} from '../__tests__/portal';

function many(count: number, from = 1): IssueSummary[] {
  return Array.from({ length: count }, (_, i) => summary({ key: `ACME-${String(from + i)}`, title: `문의 ${String(from + i)}` }));
}

function rowLinks(): HTMLAnchorElement[] {
  return screen.queryAllByRole<HTMLAnchorElement>('link').filter((a) => /^\/issues\/[A-Z0-9]+-\d+$/.test(hrefPath(a)));
}

const pageLink = (n: number) => screen.queryByRole('link', { name: new RegExp(`^${String(n)}(쪽)?$`) });

describe('P-3 문의 목록', () => {
  test('P-3 데이터 있음: 줄마다 링크 하나에 키, 제목, 고객사, 상태, 우선순위, 올린 사람(내 것이면 "나"), 올린 시각', async () => {
    renderAt('/issues');

    const row = await link(/ACME-7/);
    expect(hrefPath(row)).toBe('/issues/ACME-7');
    const t = text(row);
    for (const part of ['ACME-7', '로그인이 안 됩니다', '에이크미', '접수됨', '보통', '나']) {
      expect(t, `줄에 "${part}"`).toContain(part);
    }
    expect(t, '내 문의에는 내 이름 대신 "나"').not.toContain('김고객');
    const time = row.querySelector('time');
    expect(time, '올린 시각 <time>').not.toBeNull();
    expect(new Date(time?.getAttribute('datetime') ?? '').toISOString()).toBe(new Date(T0).toISOString());
  });

  test('P-3 데이터 있음: 동료가 올린 문의는 그 이름, 상담 쪽이 올린 문의는 "지원팀", 비공개 표시', async () => {
    state.issues = [
      summary({ key: 'ACME-9', title: '동료 문의', reporter: COLLEAGUE }),
      summary({ key: 'ACME-8', title: '지원팀 문의', reporter: SUPPORT_TEAM }),
      summary({ key: 'ACME-7', title: '내 비공개 문의', private: true }),
    ];
    renderAt('/issues');

    expect(text(await link(/ACME-9/))).toContain('박동료');
    expect(text(await link(/ACME-8/))).toContain('지원팀');
    expect(text(await link(/ACME-7/))).toContain('비공개');
    expect(text(await link(/ACME-9/))).not.toContain('비공개');
  });

  test('P-3 상태 말: 다섯 상태를 고객용 말로 그린다(UX-3)', async () => {
    const words = {
      RECEIVED: '접수됨',
      CONFIRMED: '확인됨',
      IN_PROGRESS: '처리 중',
      RESOLVED: '해결됨',
      CLOSED: '종료됨',
    } as const;
    state.issues = Object.keys(words).map((status, i) =>
      summary({ key: `ACME-${String(i + 1)}`, title: `상태 ${status}`, status: status as keyof typeof words }),
    );
    renderAt('/issues');

    for (const [i, word] of Object.values(words).entries()) {
      expect(text(await link(new RegExp(`ACME-${String(i + 1)}\\b`)))).toContain(word);
    }
  });

  test('P-3 우선순위: 낮음, 보통, 높음, 긴급을 글자로 보인다', async () => {
    const words = { LOW: '낮음', NORMAL: '보통', HIGH: '높음', URGENT: '긴급' } as const;
    state.issues = Object.keys(words).map((priority, i) =>
      summary({ key: `ACME-${String(i + 1)}`, title: `우선순위 ${priority}`, priority: priority as keyof typeof words }),
    );
    renderAt('/issues');

    for (const [i, word] of Object.values(words).entries()) {
      expect(text(await link(new RegExp(`ACME-${String(i + 1)}\\b`)))).toContain(word);
    }
  });

  test('P-3 줄 전체가 링크 하나: 줄 안에 다른 링크·버튼이 없다(Tab은 줄마다 한 번)', async () => {
    state.issues = many(3);
    renderAt('/issues');
    await link(/ACME-3/);

    const rows = rowLinks();
    expect(rows).toHaveLength(3);
    for (const row of rows) {
      expect(row.querySelectorAll('a, button, input, [tabindex]:not([tabindex="-1"])')).toHaveLength(0);
    }
  });

  test('P-3 "문의 올리기"를 누르면 P-4로 간다', async () => {
    const { user } = renderAt('/issues');
    await user.click(await action('문의 올리기'));

    await atPath('/issues/new');
  });

  test('P-3 빈 목록: "아직 올린 문의가 없습니다."와 "문의 올리기"', async () => {
    state.issues = [];
    renderAt('/issues');

    await eventually(() => {
      expect(pageText()).toContain('아직 올린 문의가 없습니다.');
    });
    await action('문의 올리기');
    expect(rowLinks()).toHaveLength(0);
  });

  test('P-3 소속 없음: "문의 올리기"를 숨기고 "소속된 고객사가 없습니다. 지원팀에 문의하세요.", 내 문의 목록은 보인다', async () => {
    state.companies = [];
    renderAt('/issues');

    await eventually(() => {
      expect(pageText()).toContain('소속된 고객사가 없습니다. 지원팀에 문의하세요.');
    });
    await link(/ACME-7/);
    expect(screen.queryByRole('link', { name: '문의 올리기' })).toBeNull();
    expect(screen.queryByRole('button', { name: '문의 올리기' })).toBeNull();
  });

  test('P-3 쪽 넘기기: 1쪽뿐이면 쪽 넘기기를 숨긴다', async () => {
    state.issues = many(5);
    renderAt('/issues');
    await link(/ACME-5/);

    expect(pageLink(1)).toBeNull();
    expect(pageLink(2)).toBeNull();
  });

  test('P-3 쪽 넘기기: 20개씩, 2쪽을 누르면 page=2로 부르고 포커스를 목록 제목으로, "2쪽, 45건 가운데 21~40"을 알린다', async () => {
    on('get', '/api/portal/issues', ({ request }) => {
      const page = Number(new URL(request.url).searchParams.get('page') ?? '1');
      const items = many(page === 3 ? 5 : 20, (page - 1) * 20 + 1);
      return HttpResponse.json(paged(items, { page, size: 20, totalElements: 45, totalPages: 3 }));
    });
    const { user } = renderAt('/issues');
    await link(/ACME-20\b/);
    expect(rowLinks()).toHaveLength(20);

    await user.click(await found(() => pageLink(2), '2쪽 링크'));

    await link(/ACME-21\b/);
    const lists = await sent('GET', '/api/portal/issues', 2);
    expect(new URL(lists[lists.length - 1]?.url ?? '').searchParams.get('page')).toBe('2');
    await statusWith('2쪽, 45건 가운데 21~40');
    await eventually(() => {
      expect(document.activeElement?.tagName).toMatch(/^H[1-6]$/);
    });
    expect(pageLink(2)).toHaveAttribute('aria-current', 'page');
  });

  test('P-3 쪽 바깥: 끝 뒤 쪽은 빈 목록 문구가 아니라 "이 쪽에는 문의가 없습니다"와 1쪽 링크', async () => {
    on('get', '/api/portal/issues', ({ request }) => {
      const page = Number(new URL(request.url).searchParams.get('page') ?? '1');
      return page === 1
        ? HttpResponse.json(paged(many(20), { page: 1, totalElements: 25, totalPages: 2 }))
        : HttpResponse.json(paged([], { page, totalElements: 20, totalPages: 1 }));
    });
    const { user } = renderAt('/issues');
    await link(/ACME-20\b/);
    await user.click(await found(() => pageLink(2), '2쪽 링크'));

    await eventually(() => {
      expect(pageText()).toContain('이 쪽에는 문의가 없습니다');
    });
    expect(pageText()).not.toContain('아직 올린 문의가 없습니다.');
    const firstPage = screen
      .queryAllByRole<HTMLAnchorElement>('link')
      .filter((a) => hrefPath(a) === '/issues' && new URL(a.href).searchParams.get('page') !== '2');
    expect(firstPage.length, '1쪽으로 가는 링크').toBeGreaterThan(0);
  });

  test('P-3 키보드: 문의 올리기 → 줄들 → 쪽 넘기기', async () => {
    on('get', '/api/portal/issues', () => HttpResponse.json(paged(many(20), { totalElements: 45, totalPages: 3 })));
    renderAt('/issues');
    await link(/ACME-20\b/);

    const order = tabbables();
    const create = order.findIndex((el) => text(el) === '문의 올리기');
    const firstRow = order.findIndex((el) => el === rowLinks()[0]);
    const lastRow = order.findIndex((el) => el === rowLinks()[19]);
    const pager = order.findIndex((el) => el === pageLink(2));
    expect(create, '문의 올리기').toBeGreaterThanOrEqual(0);
    expect(create).toBeLessThan(firstRow);
    expect(firstRow).toBeLessThan(lastRow);
    expect(lastRow).toBeLessThan(pager);
  });

  test('P-3 접근성: 처음 포커스는 h1(tabindex=-1), 화면 제목을 document.title에', async () => {
    renderAt('/issues');
    await link(/ACME-7/);

    const h1 = await heading1();
    expect(h1).toHaveAttribute('tabindex', '-1');
    await eventually(() => {
      expect(document.activeElement).toBe(h1);
    });
    expect(document.title).toContain(text(h1));
  });

  test('P-3 axe: 목록과 쪽 넘기기가 있는 화면에 접근성 위반이 없다', async () => {
    on('get', '/api/portal/issues', () =>
      HttpResponse.json(paged([...many(19), summary({ key: 'ACME-99', private: true, reporter: COLLEAGUE })], { totalElements: 45, totalPages: 3 })),
    );
    renderAt('/issues');
    await link(/ACME-99/);
    await found(() => pageLink(2), '2쪽 링크');

    await expectNoAxeViolations();
  });
});
