// 6b 묶음 screens.md 2절 C-15 내 작업(/tasks): 옆 메뉴, 두 보기와 주소, 표의 열과 링크, "완료"와 실패, 빈 문구 둘,
// 쪽 넘기기, 주소로 열기. 보기를 바꾼 뒤 온 옛 목록은 버린다(S6B-AC-87, S6B-AC-89).
import { waitFor } from '@testing-library/react';
import { describe, expect, test } from 'vitest';
import {
  AGENT,
  callsTo,
  errors,
  eventually,
  expectNoAxeViolations,
  found,
  gate,
  heading1,
  hrefPath,
  menuLinks,
  pageText,
  renderAt,
  screen,
  sent,
  state,
  text,
  within,
} from '../__tests__/console';
import { answered, route, settle } from '../__tests__/console1a';
import { longDate } from '../__tests__/console6a';
import { MY_TASKS, doneTask, lastBody, lastMine, myTask, myTasksPage, s6b, taskPath, taskResponse, type MyTask } from '../__tests__/console6b';

const URL_TASKS = '/tasks';
const FAILED = '작업을 바꾸지 못했습니다. 다시 해 보세요.';

const LOGS = myTask({ id: 41, title: '로그 모으기', dueDate: '2026-10-01', overdue: true });
const CALL = myTask({
  id: 52,
  title: '고객에게 전화하기',
  dueDate: '2099-10-20',
  issue: { key: 'BETA-3', title: '결제가 두 번 됩니다', status: 'CONFIRMED' },
  companyCode: 'BETA',
  companyName: '베타상사',
});
const NO_DUE = myTask({ id: 63, title: '재현 절차 정리' });
const FINISHED = myTask({ id: 44, title: '끝낸 정리', status: 'DONE', doneAt: '2026-10-09T02:00:00Z' });

const views = ['할 일', '끝낸 일'] as const;
type View = (typeof views)[number];

/** A view of C-15: a link inside a `nav` (the 보기 꼴 of C-3). */
function view(name: View): Promise<HTMLAnchorElement> {
  return found(() => screen.queryAllByRole<HTMLAnchorElement>('link', { name }).find((a) => a.closest('nav, [role="navigation"]') !== null), `보기 "${name}"`);
}

function currentViews(): View[] {
  return views.filter((name) =>
    screen.queryAllByRole('link', { name }).some((a) => a.closest('nav, [role="navigation"]') !== null && a.hasAttribute('aria-current') && a.getAttribute('aria-current') !== 'false'),
  );
}

/** The table of C-15, by its caption "내 작업, n건", waited for generously the first time. */
async function table(count?: number): Promise<HTMLElement> {
  let el: HTMLElement | null = null;
  await waitFor(
    () => {
      el = screen.queryByRole('table', { name: count === undefined ? /^내 작업, \d+건$/ : `내 작업, ${String(count)}건` });
      expect(el, `표 "내 작업, ${count === undefined ? 'n' : String(count)}건"`).not.toBeNull();
    },
    { timeout: 5000 },
  );
  return el as unknown as HTMLElement;
}

/** The body row of the table that holds `part`. */
function rowWith(part: string): Promise<HTMLElement> {
  return found(
    () => screen.queryAllByRole('row').find((r) => within(r).queryAllByRole('cell').length > 0 && text(r).includes(part)),
    `"${part}" 줄`,
  );
}

const headers = (t: HTMLElement) => within(t).queryAllByRole('columnheader').map((h) => text(h));
const address = () => new URLSearchParams(window.location.search);

function seed(items: MyTask[] = [LOGS, CALL, NO_DUE, FINISHED]) {
  s6b.mine = items;
}

describe('C-15 내 작업 (6b 묶음)', () => {
  test.each([
    { who: '관리자', me: () => state.me },
    { who: '상담원', me: () => ({ ...AGENT, companies: [{ code: 'ACME', name: '에이크미' }] }) },
  ])('S6B-AC-87 C-15 옆 메뉴($who): "문의" 다음이 "내 작업"이고 /tasks로 간다. 제목은 "내 작업"이다', async ({ me }) => {
    state.me = me();
    seed();
    const { user } = renderAt('/issues');
    await waitFor(
      () => {
        expect(menuLinks()).toContain('문의');
      },
      { timeout: 5000 },
    );

    const menu = menuLinks();
    expect(menu.slice(menu.indexOf('문의'), menu.indexOf('문의') + 2)).toEqual(['문의', '내 작업']);
    const mine = await found(() => screen.queryAllByRole('link', { name: '내 작업' }).find((a) => a.closest('nav') !== null), '메뉴 "내 작업"');
    expect(hrefPath(mine)).toBe(URL_TASKS);
    await user.click(mine);

    await eventually(() => {
      expect(window.location.pathname).toBe(URL_TASKS);
    });
    expect(text(await heading1())).toBe('내 작업');
  });

  test('S6B-AC-87 C-15 할 일(기본): status=OPEN으로 받고(또는 빼고), 표의 열은 작업·문의·고객사·마감일 차례이며 "할 일"이 지금 보기다', async () => {
    seed();
    renderAt(URL_TASKS);

    const t = await table(3);
    await sent('GET', MY_TASKS);
    expect(['OPEN', null]).toContain(lastMine().get('status'));
    const order = headers(t);
    const at = (name: string) => order.findIndex((h) => h.startsWith(name));
    for (const name of ['작업', '문의', '고객사', '마감일']) expect(at(name), `열 "${name}"`).toBeGreaterThanOrEqual(0);
    const places = ['작업', '문의', '고객사', '마감일'].map(at);
    expect(places, '열의 차례').toEqual([...places].sort((a, b) => a - b));
    expect(currentViews()).toEqual(['할 일']);
    expect(text(t), '끝낸 일의 작업').not.toContain(FINISHED.title);
    await expectNoAxeViolations();
  });

  test('S6B-AC-87 C-15 줄: 작업은 /issues/{key}#task-{id}로, 문의(키와 제목)는 그 문의로 간다. 고객사 이름, 마감일과 "지남"(서버의 overdue만 본다)이 있다', async () => {
    seed();
    renderAt(URL_TASKS);
    await table(3);

    const logs = await rowWith(LOGS.title);
    const taskLink = within(logs).getByRole<HTMLAnchorElement>('link', { name: LOGS.title });
    expect(new URL(taskLink.href).pathname + new URL(taskLink.href).hash).toBe('/issues/ACME-7#task-41');
    const issueLink = within(logs)
      .queryAllByRole<HTMLAnchorElement>('link')
      .find((a) => text(a).includes('ACME-7'));
    expect(issueLink, '문의 링크').toBeDefined();
    expect(hrefPath(issueLink as HTMLAnchorElement)).toBe('/issues/ACME-7');
    expect(new URL((issueLink as HTMLAnchorElement).href).hash, '문의 링크는 작업으로 가지 않는다').toBe('');
    expect(text(logs)).toContain('로그인이 안 됩니다');
    expect(text(logs)).toContain('에이크미');
    expect(text(logs)).toContain(longDate('2026-10-01'));
    expect(text(logs)).toContain('지남');

    const call = await rowWith(CALL.title);
    expect(new URL(within(call).getByRole<HTMLAnchorElement>('link', { name: CALL.title }).href).hash).toBe('#task-52');
    expect(text(call)).toContain('BETA-3');
    expect(text(call)).toContain('베타상사');
    expect(text(call)).toContain(longDate('2099-10-20'));
    expect(text(call)).not.toContain('지남');
  });

  test('S6B-AC-87 C-15 "완료": "{제목} 완료"가 작업 고치기와 같은 PATCH(status DONE)를 그 문의 경로로 보내고 줄이 "완료했습니다"가 된다', async () => {
    seed();
    route('patch', taskPath(41), () => {
      const done = { ...LOGS, status: 'DONE' as const, doneAt: '2026-10-09T02:00:00Z', overdue: false };
      s6b.mine = s6b.mine.map((t) => (t.id === 41 ? done : t));
      return taskResponse(doneTask({ id: 41, title: LOGS.title, dueDate: LOGS.dueDate }));
    });
    const { user } = renderAt(URL_TASKS);
    await table(3);

    const row = await rowWith(LOGS.title);
    await user.click(within(row).getByRole('button', { name: `${LOGS.title} 완료` }));

    await sent('PATCH', taskPath(41));
    expect(await lastBody('PATCH', taskPath(41))).toEqual({ status: 'DONE' });
    const after = await rowWith(LOGS.title);
    await eventually(() => {
      expect(text(after)).toContain('완료했습니다');
    });
    expect(callsTo('PATCH', /\/tasks\//).map((c) => c.path), '다른 줄').toEqual([taskPath(41)]);
  });

  test('S6B-AC-87 C-15 "완료" 실패: 그 줄 아래 role="alert" "작업을 바꾸지 못했습니다. 다시 해 보세요."이고 "완료"는 남는다', async () => {
    seed();
    route('patch', taskPath(41), () => errors.internal());
    const { user } = renderAt(URL_TASKS);
    await table(3);

    const row = await rowWith(LOGS.title);
    await user.click(within(row).getByRole('button', { name: `${LOGS.title} 완료` }));

    await sent('PATCH', taskPath(41));
    await found(() => screen.queryAllByRole('alert').find((el) => text(el).includes(FAILED)), `role="alert" "${FAILED}"`);
    expect(within(await rowWith(LOGS.title)).queryByRole('button', { name: `${LOGS.title} 완료` })).not.toBeNull();
    expect(pageText()).not.toContain('완료했습니다');
  });

  test('S6B-AC-87 C-15 끝낸 일: "끝낸 일"을 누르면 status=DONE으로 받고 주소에 남는다. 줄에 "완료" 단추가 없고 끝낸 시각 열이 있다', async () => {
    seed();
    const { user } = renderAt(URL_TASKS);
    await table(3);

    await user.click(await view('끝낸 일'));

    await eventually(() => {
      expect(lastMine().get('status')).toBe('DONE');
    });
    expect(address().get('status')).toBe('DONE');
    const t = await table(1);
    await rowWith(FINISHED.title);
    expect(currentViews()).toEqual(['끝낸 일']);
    expect(within(t).queryAllByRole('button', { name: /완료$/ }), '끝낸 일의 "완료"').toEqual([]);
    expect(headers(t).some((h) => h.includes('끝낸')), '끝낸 시각 열').toBe(true);

    await user.click(await view('할 일'));
    await table(3);
    expect(address().get('status'), '할 일의 주소').toBeNull();
  });

  test.each([
    { what: '할 일', path: URL_TASKS, says: '맡은 작업이 없습니다.' },
    { what: '끝낸 일', path: `${URL_TASKS}?status=DONE`, says: '끝낸 작업이 없습니다.' },
  ])('S6B-AC-87 C-15 비었을 때($what): "$says"', async ({ path, says }) => {
    seed([]);
    renderAt(path);

    await waitFor(
      () => {
        expect(pageText()).toContain(says);
      },
      { timeout: 5000 },
    );
    await sent('GET', MY_TASKS);
  });

  test('S6B-AC-87 C-15 쪽 넘기기: 2쪽을 누르면 page=2로 받고 주소에 page=2가 남는다', async () => {
    const many = Array.from({ length: 25 }, (_, i) => myTask({ id: 100 + i, title: `작업 ${String(i + 1).padStart(2, '0')}번` }));
    seed(many);
    const { user } = renderAt(URL_TASKS);
    await table(25);
    await rowWith('작업 20번');

    await user.click(await found(() => screen.queryByRole('link', { name: /^2(쪽)?$/ }), '2쪽 링크'));

    await rowWith('작업 21번');
    expect(lastMine().get('page')).toBe('2');
    expect(address().get('page')).toBe('2');
  });

  test('S6B-AC-87 C-15 주소로 열기: ?status=DONE&page=2로 열면 첫 요청부터 그대로이고 "끝낸 일"이 지금 보기다', async () => {
    seed(Array.from({ length: 25 }, (_, i) => myTask({ id: 200 + i, title: `끝낸 작업 ${String(i + 1).padStart(2, '0')}`, status: 'DONE', doneAt: '2026-10-09T02:00:00Z' })));
    renderAt(`${URL_TASKS}?status=DONE&page=2`);

    await table(25);
    const first = new URL(callsTo('GET', MY_TASKS)[0]?.url ?? 'http://x/').searchParams;
    expect(first.get('status')).toBe('DONE');
    expect(first.get('page')).toBe('2');
    await rowWith('끝낸 작업 21');
    expect(currentViews()).toEqual(['끝낸 일']);
  });

  test('S6B-AC-89 C-15 보기를 바꾼 뒤 온 옛 목록은 버린다: "끝낸 일"에 할 일의 줄이 섞이지 않는다', async () => {
    seed();
    const release = gate();
    route('get', MY_TASKS, async ({ request }) => {
      const status = new URL(request.url).searchParams.get('status') ?? 'OPEN';
      if (status === 'OPEN') await release.opened;
      const items = s6b.mine.filter((t) => t.status === status);
      return myTasksPage(items);
    });
    const { user } = renderAt(URL_TASKS);
    await sent('GET', MY_TASKS);

    await user.click(await view('끝낸 일'));
    await table(1);
    await rowWith(FINISHED.title);
    const done = answered('GET', MY_TASKS);
    release.open();
    await done;
    await settle();

    const t = await table(1);
    expect(text(t), '옛 목록의 줄').not.toContain(LOGS.title);
    expect(currentViews()).toEqual(['끝낸 일']);
  });
});
