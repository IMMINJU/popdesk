// screens.md 4절 C-18 시스템 상태(관리자). 첫 묶음은 읽기만 한다. 상담원이 보는 경우는 adminOnly.test.tsx.
import { act } from '@testing-library/react';
import { HttpResponse } from 'msw/http';
import { describe, expect, test, vi } from 'vitest';
import {
  T0,
  channel,
  eventually,
  expectNoAxeViolations,
  failedDelivery,
  found,
  gate,
  heading1,
  on,
  paged,
  pageText,
  renderAt,
  screen,
  sent,
  state,
  statusWith,
  text,
  useFakeClock,
  within,
  type FailedDelivery,
} from '../__tests__/console';

const LAST_MAIL = '2026-10-08T02:00:00Z';

/** The channel table row whose first cell names the channel. */
async function channelRow(name: string): Promise<HTMLElement> {
  return found(
    () => screen.queryAllByRole('row').find((r) => text(within(r).queryAllByRole('cell')[0] ?? within(r).queryAllByRole('rowheader')[0]).includes(name)),
    `채널 줄 "${name}"`,
  );
}

async function failedTable(): Promise<HTMLElement> {
  return found(
    () =>
      screen
        .queryAllByRole('table')
        .find((t) => within(t).queryAllByRole('columnheader').some((h) => /오류/.test(text(h)))),
    '`failed` 행 표(마지막 오류 종류 열)',
  );
}

function manyFailed(count: number, from = 1): FailedDelivery[] {
  return Array.from({ length: count }, (_, i) => failedDelivery({ id: from + i, recipientAccountId: 1000 + from + i }));
}

describe('C-18 시스템 상태', () => {
  test('C-18 데이터 있음: 채널 표에 메일 보내기·웹훅, 켜짐, 마지막 성공 시각, 실패 수', async () => {
    state.status = { mail: channel({ enabled: true, failed: 3, lastSuccessAt: LAST_MAIL }), webhook: channel({ enabled: true, failed: 0 }) };
    renderAt('/system');

    const mail = await channelRow('메일 보내기');
    expect(text(mail)).toContain('켜짐');
    expect(text(mail)).toContain('3');
    const time = mail.querySelector('time');
    expect(time, '마지막 성공 시각 <time>').not.toBeNull();
    expect(new Date(time?.getAttribute('datetime') ?? '').getTime()).toBe(new Date(LAST_MAIL).getTime());
    const hook = await channelRow('웹훅');
    expect(text(hook)).toContain('켜짐');
    expect(text(hook)).toContain('0');
  });

  test('C-18 꺼진 채널: "설정 없음" 배지와 운영 문서 링크', async () => {
    state.status = { mail: channel(), webhook: channel({ enabled: false, lastSuccessAt: null }) };
    renderAt('/system');

    const hook = await channelRow('웹훅');
    expect(text(hook)).toContain('설정 없음');
    expect(within(hook).queryAllByRole('link').length, '어떤 환경변수를 넣는지 운영 문서 링크').toBeGreaterThan(0);
    expect(text(await channelRow('메일 보내기'))).not.toContain('설정 없음');
  });

  test('S0-AC-81 C-18 비밀값 없음: 웹훅 주소·SMTP 비밀번호 칸과 값이 화면에 없다', async () => {
    state.status = { mail: channel({ enabled: true }), webhook: channel({ enabled: true }) };
    state.failed = [failedDelivery({ channel: 'WEBHOOK', recipientAccountId: null })];
    renderAt('/system');

    await channelRow('웹훅');
    await failedTable();
    expect(screen.queryAllByLabelText(/주소|URL|비밀번호|password/i), '웹훅 주소·SMTP 비밀번호 칸').toHaveLength(0);
    expect(screen.queryAllByRole('textbox'), '입력칸').toHaveLength(0);
    expect(document.querySelectorAll('input[type="password"]')).toHaveLength(0);
    const shown = text(screen.getByRole('main'));
    expect(shown, '본문에 보이는 주소').not.toMatch(/https?:\/\//);
    expect(shown).not.toMatch(/비밀번호|password|smtp\.|hooks\./i);
  });

  test('S0-AC-81 C-18 읽기만: `failed` 다시 보내기와 시험 메일 버튼이 없다(4번 묶음)', async () => {
    state.failed = [failedDelivery()];
    renderAt('/system');

    await failedTable();
    expect(screen.queryByRole('button', { name: /다시 보내기|재전송|시험 메일/ })).toBeNull();
  });

  test('S0-AC-81 C-18 `failed` 표: 채널, 만든 시각, 마지막 오류 종류, 받는 계정 ID', async () => {
    state.status = { mail: channel({ failed: 1 }), webhook: channel({ failed: 1 }) };
    state.failed = [
      failedDelivery({ id: 1, channel: 'MAIL', lastErrorKind: 'CONNECTION_REFUSED', recipientAccountId: 11 }),
      failedDelivery({ id: 2, channel: 'WEBHOOK', lastErrorKind: 'HTTP_500', recipientAccountId: null }),
    ];
    renderAt('/system');

    const table = await failedTable();
    const headers = within(table).getAllByRole('columnheader').map(text);
    for (const h of ['채널', '만든 시각', '오류', '계정']) expect(headers.some((x) => x.includes(h)), `열 머리 "${h}"`).toBe(true);
    const rows = within(table).queryAllByRole('row').filter((r) => within(r).queryAllByRole('cell').length > 0);
    expect(rows).toHaveLength(2);
    expect(text(rows[0])).toContain('CONNECTION_REFUSED');
    expect(text(rows[0])).toContain('11');
    expect(text(rows[1])).toContain('HTTP_500');
    const time = rows[0]?.querySelector('time');
    expect(new Date(time?.getAttribute('datetime') ?? '').getTime()).toBe(new Date(T0).getTime());
  });

  test('C-18 `failed` 없음: "보내지 못한 항목이 없습니다."', async () => {
    state.failed = [];
    renderAt('/system');

    await channelRow('메일 보내기');
    await eventually(() => {
      expect(pageText()).toContain('보내지 못한 항목이 없습니다.');
    });
  });

  test('C-18 `failed` 쪽 넘기기: 2쪽을 page=2로 부르고 "2쪽, 25건 가운데 21~25"를 알린다', async () => {
    on('get', '/api/console/system/failed-deliveries', ({ request }) => {
      const page = Number(new URL(request.url).searchParams.get('page') ?? '1');
      return HttpResponse.json(paged(page === 2 ? manyFailed(5, 21) : manyFailed(20), { page, totalElements: 25, totalPages: 2 }));
    });
    const { user } = renderAt('/system');
    await failedTable();
    await user.click(await found(() => screen.queryByRole('link', { name: /^2(쪽)?$/ }), '2쪽 링크'));

    const lists = await sent('GET', '/api/console/system/failed-deliveries', 2);
    expect(new URL(lists[lists.length - 1]?.url ?? '').searchParams.get('page')).toBe('2');
    await statusWith('2쪽, 25건 가운데 21~25');
  });

  test('C-18 처음 불러옴: 300ms가 넘으면 aria-hidden 줄 뼈대, main은 aria-busy', async () => {
    useFakeClock();
    const release = gate();
    on('get', '/api/console/system/status', async () => {
      await release.opened;
      return HttpResponse.json(state.status);
    });
    renderAt('/system', { fakeTimers: true });

    const main = await found(() => screen.queryByRole('main'), '본문 main');
    act(() => {
      vi.advanceTimersByTime(350);
    });
    await eventually(() => {
      expect(main).toHaveAttribute('aria-busy', 'true');
      expect(main.querySelectorAll('[aria-hidden="true"]').length, '줄 뼈대').toBeGreaterThan(0);
    });
    release.open();
    await channelRow('메일 보내기');
  });

  test('C-18 접근성: 처음 포커스는 h1(tabindex=-1), 화면 제목을 document.title에', async () => {
    renderAt('/system');
    await channelRow('메일 보내기');

    const h1 = await heading1();
    expect(h1).toHaveAttribute('tabindex', '-1');
    await eventually(() => {
      expect(document.activeElement).toBe(h1);
    });
    expect(document.title).toContain(text(h1));
  });

  test('C-18 axe: 채널 표와 `failed` 표가 있는 화면에 접근성 위반이 없다', async () => {
    state.status = { mail: channel({ failed: 2 }), webhook: channel({ enabled: false, lastSuccessAt: null }) };
    state.failed = manyFailed(2);
    renderAt('/system');
    await failedTable();
    await channelRow('웹훅');

    await expectNoAxeViolations();
  });
});
