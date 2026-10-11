// 1b screens.md 1절 C-12 감사 로그의 필터: 종류(전체, 묶음, 낱개, "로그인 기록 빼고"), 한 사람, 대상, 기간(S1B-AC-03~06).
// 기간은 브라우저 시간대의 날짜로 받는다. 기대하는 시각도 같은 시간대의 0시로 만들어 어느 시간대에서 돌아도 같다.
import { describe, expect, test } from 'vitest';
import {
  account,
  action,
  alertWith,
  button,
  callsTo,
  errors,
  eventually,
  expectFieldError,
  found,
  on,
  openDialog,
  pageText,
  renderAt,
  screen,
  state,
  text,
  within,
} from '../__tests__/console';
import { settle } from '../__tests__/console1a';
import {
  ALL_KINDS,
  AUDIT,
  BACKED_UP,
  COMPANY_MADE,
  SIGNED_IN,
  SUSPENDED,
  audit,
  cellOf,
  chosenKind,
  column,
  dateField,
  entry,
  entryRows,
  kindSelect,
  kindsOf,
  lastList,
  listedKinds,
} from '../__tests__/console1b';

const URL_LOG = '/audit-log';
const DAY = 24 * 60 * 60 * 1000;

type User = ReturnType<typeof renderAt>['user'];

/** The search box of the 한 사람 filter: on the screen, or behind a button named for the filter. */
async function actorSearch(user: User): Promise<HTMLElement> {
  if (!screen.queryByRole('searchbox')) await user.click(await button(/한 사람/));
  return found(() => screen.queryByRole('searchbox'), '한 사람 찾기 칸');
}

/** A choice of the 한 사람 filter by name, whatever control draws it. */
function actorChoice(name: string): Promise<HTMLElement> {
  const wanted = new RegExp(name);
  return found(
    () =>
      screen.queryByRole('radio', { name: wanted }) ??
      screen.queryByRole('option', { name: wanted }) ??
      screen.queryAllByRole('button', { name: wanted }).find((b) => !b.closest('table')),
    `한 사람 선택지 "${name}"`,
  );
}

/** Picks a choice; when it sits in a dialog with a button that goes on, presses that too. */
async function pickActor(user: User, name: string) {
  const choice = await actorChoice(name);
  await user.click(choice);
  const go = openDialog()
    ? within(openDialog() as HTMLElement)
        .queryAllByRole('button')
        .find((b) => b !== choice && !/^(취소|닫기)$/.test(b.getAttribute('aria-label') ?? text(b)))
    : undefined;
  if (go) await user.click(go);
}

/** The × of the 대상 filter: a button outside the table, named for 대상 or sitting next to its text. */
function targetClear(label: string): Promise<HTMLElement> {
  return found(
    () =>
      screen
        .queryAllByRole('button')
        .filter((b) => !b.closest('table'))
        .find((b) => (b.getAttribute('aria-label') ?? '').includes('대상') || text(b.parentElement).includes(`대상: ${label}`)),
    `"대상: ${label}"의 × 버튼`,
  );
}

describe('C-12 감사 로그: 필터', () => {
  test.each([
    ['묶음 "계정": 그 묶음의 종류를 모두 보낸다', '계정', kindsOf('계정'), ['계정 정지']],
    ['묶음 "로그인 기록"', '로그인 기록', kindsOf('로그인 기록'), ['로그인']],
    ['낱개 "고객사 만들기"', '고객사 만들기', ['COMPANY_CREATED'], ['고객사 만들기']],
    [
      '"로그인 기록 빼고": 로그인 기록 셋을 뺀 종류를 모두 보낸다',
      '로그인 기록 빼고',
      ALL_KINDS.filter((k) => !kindsOf('로그인 기록').includes(k)),
      ['계정 정지', '고객사 만들기'],
    ],
  ])('S1B-AC-03 C-12 종류 필터 %s', async (_, choice, kinds, left) => {
    audit.entries = [SUSPENDED, SIGNED_IN, COMPANY_MADE];
    const { user } = renderAt(URL_LOG);
    await entryRows(3);
    const kind = await kindSelect();
    await user.selectOptions(kind, choice);

    await listedKinds(kinds);
    await entryRows(left.length);
    expect(column('일')).toEqual(left);
    expect(chosenKind(kind)).toBe(choice);
  });

  test('S1B-AC-03 C-12 종류 필터 "전체": 맨 위 선택지이고, 고르면 action 없이 받는다', async () => {
    audit.entries = [SUSPENDED, SIGNED_IN];
    const { user } = renderAt(URL_LOG);
    await entryRows(2);
    const kind = await kindSelect();
    expect(text(kind.options[0])).toBe('전체');
    await user.selectOptions(kind, '계정 정지');
    await entryRows(1);
    await user.selectOptions(kind, '전체');

    await entryRows(2);
    expect(lastList().getAll('action')).toEqual([]);
  });

  test('S1B-AC-04 C-12 한 사람 필터: 이름·이메일로 계정을 찾아 고르면 그 계정 ID로 받는다', async () => {
    state.accounts = [account(), account({ id: 2, email: 'lee@popdesk.test', name: '이상담', role: 'AGENT' })];
    audit.entries = [SUSPENDED, SIGNED_IN];
    const { user } = renderAt(URL_LOG);
    await entryRows(2);
    await user.type(await actorSearch(user), 'lee');

    await pickActor(user, '이상담');
    await eventually(() => {
      expect(lastList().get('actorAccountId')).toBe('2');
    });
    expect(new URL(callsTo('GET', '/api/console/accounts').at(-1)?.url ?? 'http://x/').searchParams.get('q')).toBe('lee');
    await entryRows(1);
    expect(column('일')).toEqual(['로그인']);
  });

  test('S1B-AC-04 C-12 한 사람 필터 "시스템": 선택지에 "시스템"이 있고 고르면 계정 ID 0으로 받는다', async () => {
    audit.entries = [SUSPENDED, BACKED_UP];
    const { user } = renderAt(URL_LOG);
    await entryRows(2);
    await actorSearch(user);

    await pickActor(user, '시스템');
    await eventually(() => {
      expect(lastList().get('actorAccountId')).toBe('0');
    });
    await entryRows(1);
    expect(column('한 사람')).toEqual(['시스템']);
  });

  test.each([
    ['계정', [SUSPENDED, COMPANY_MADE], '계정 12', { targetType: 'ACCOUNT', targetAccountId: '12', targetCode: null }],
    ['고객사', [COMPANY_MADE, SUSPENDED], '고객사 ACME', { targetType: 'COMPANY', targetAccountId: null, targetCode: 'ACME' }],
  ])('S1B-AC-05 C-12 대상 필터(%s): 표의 대상을 누르면 걸리고 필터 줄에 "대상: …"로 보인다. ×로 푼다', async (_, entries, label, wanted) => {
    audit.entries = entries;
    const { user } = renderAt(URL_LOG);
    const rows = await entryRows(2);
    const cell = cellOf(rows[0], '대상') as HTMLElement | null;
    await user.click(
      await found(() => (cell ? (within(cell).queryByRole('button', { name: label }) ?? within(cell).queryByRole('link', { name: label })) : null), `대상 "${label}"`),
    );

    await eventually(() => {
      const query = lastList();
      expect({ targetType: query.get('targetType'), targetAccountId: query.get('targetAccountId'), targetCode: query.get('targetCode') }).toEqual(wanted);
    });
    await entryRows(1);
    expect(pageText()).toContain(`대상: ${label}`);

    await user.click(await targetClear(label));
    await entryRows(2);
    expect(lastList().has('targetType')).toBe(false);
    expect(pageText()).not.toContain(`대상: ${label}`);
  });

  test('S1B-AC-06 C-12 기간 필터: 부터는 그 날 0시, 까지는 그 날을 포함하게 다음 날 0시로 보낸다', async () => {
    audit.entries = [
      entry({ id: 7803, occurredAt: new Date(2026, 9, 8, 0, 0, 1).toISOString() }),
      entry({ id: 7802, occurredAt: new Date(2026, 9, 7, 23, 59, 0).toISOString() }),
      entry({ id: 7801, occurredAt: new Date(2026, 9, 5, 0, 0, 0).toISOString() }),
      entry({ id: 7800, occurredAt: new Date(2026, 9, 4, 23, 59, 59).toISOString() }),
    ];
    const { user } = renderAt(URL_LOG);
    await entryRows(4);
    await user.type(await dateField('부터'), '2026-10-05');
    await eventually(() => {
      expect(lastList().has('from')).toBe(true);
    });
    expect(lastList().has('to'), '까지를 넣기 전').toBe(false);
    await user.type(await dateField('까지'), '2026-10-07');

    await eventually(() => {
      expect(lastList().has('to')).toBe(true);
    });
    const from = new Date(lastList().get('from') ?? '').getTime();
    const to = new Date(lastList().get('to') ?? '').getTime();
    expect(from, '부터: 10월 5일 0시').toBe(new Date(2026, 9, 5).getTime());
    expect(to, '까지: 10월 8일 0시').toBe(new Date(2026, 9, 8).getTime());
    expect(to - from).toBe(3 * DAY);
    const rows = await entryRows(2);
    expect(rows.map((r) => new Date(cellOf(r, '시각')?.querySelector('time')?.getAttribute('datetime') ?? '').getTime())).toEqual([
      new Date(2026, 9, 7, 23, 59, 0).getTime(),
      new Date(2026, 9, 5).getTime(),
    ]);
  });

  test('S1B-AC-06 C-12 기간 필터 거꾸로: 부터가 까지보다 뒤면 "까지" 칸 아래 오류를 보이고 보내지 않는다', async () => {
    const { user } = renderAt(URL_LOG);
    await entryRows(2);
    await user.type(await dateField('부터'), '2026-10-07');
    await eventually(() => {
      expect(lastList().has('from')).toBe(true);
    });
    const until = await dateField('까지');
    await user.type(until, '2026-10-05');

    await expectFieldError(until);
    await settle();
    expect(callsTo('GET', AUDIT).filter((c) => new URL(c.url).searchParams.has('to')), '거꾸로인 기간으로 보낸 요청').toHaveLength(0);
  });

  test('S1B-AC-03 C-12 400 UNKNOWN_VALUE: 오류 띠와 "조건 지우기". 지우면 필터 없이 다시 받는다', async () => {
    on('get', AUDIT, ({ request }) =>
      new URL(request.url).searchParams.has('action') ? errors.invalid([{ field: 'action', code: 'UNKNOWN_VALUE' }]) : undefined,
    );
    const { user } = renderAt(URL_LOG);
    await entryRows(2);
    await user.selectOptions(await kindSelect(), '계정 정지');

    await alertWith(/\S/);
    await user.click(await action(/조건 지우기/));
    await entryRows(2);
    expect(lastList().getAll('action')).toEqual([]);
  });
});
