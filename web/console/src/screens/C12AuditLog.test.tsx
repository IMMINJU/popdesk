// 1b screens.md 1절 C-12 감사 로그: 표의 칸 다섯, 종류의 한국어 말, 바뀐 값, 줄 펼치기(S1B-AC-01, 36)와
// 화면 상태(처음, 결과 없음 둘, 없는 쪽, 필터가 주소에 실림). 필터는 C12AuditLog.filters.test.tsx,
// 내보내기·무결성 확인·늦게 온 답은 C12AuditLog.actions.test.tsx.
import { describe, expect, test } from 'vitest';
import {
  action,
  calls,
  eventually,
  expectNoAxeViolations,
  found,
  hrefPath,
  link,
  pageText,
  queryAction,
  renderAt,
  screen,
  text,
  within,
} from '../__tests__/console';
import {
  BACKED_UP,
  COLUMNS,
  COMPANY_MADE,
  EXPORTED,
  KIND_GROUPS,
  SIGNED_IN,
  SUSPENDED,
  SYSTEM_ACTOR,
  audit,
  cellOf,
  chosenKind,
  column,
  entry,
  entryRows,
  kindSelect,
  lastList,
  listedKinds,
} from '../__tests__/console1b';

const URL_LOG = '/audit-log';
const FILTERS = ['action', 'actorAccountId', 'targetType', 'targetAccountId', 'targetCode', 'from', 'to'] as const;

/** A kind the screen has no word for, with a field and a value it has no word for, and no target. */
const UNKNOWN = entry({ id: 7700, action: 'LEGACY_THING', target: null, changes: { flavor: 'mint' } });

/** A customer of ACME and an agent assigned to it: the target holds the company and the account. */
const MEMBER_OF_ACME = { type: 'COMPANY', code: 'ACME', accountId: 12 } as const;
const CUSTOMER_ADDED = entry({ id: 7712, action: 'CUSTOMER_ADDED', target: MEMBER_OF_ACME, changes: { member: true } });
const AGENT_ASSIGNED = entry({ id: 7711, action: 'AGENT_ASSIGNED', target: MEMBER_OF_ACME, changes: { assigned: true } });
const RESTORED = entry({
  id: 7710,
  action: 'DATABASE_RESTORED',
  actor: SYSTEM_ACTOR,
  target: { type: 'DATABASE' },
  changes: { file: 'popdesk-20261007.dump' },
});

/** The button that opens a row: the one with `aria-expanded`. The target cell's button has none. */
function expander(row: HTMLElement | undefined): Promise<HTMLElement> {
  return found(
    () => (row ? within(row).queryAllByRole('button').find((b) => b.hasAttribute('aria-expanded')) : null),
    '줄 펼치기 버튼(aria-expanded)',
  );
}

describe('C-12 감사 로그: 표와 상태', () => {
  test('S1B-AC-01 C-12 처음: 필터 없이 1쪽 20건을 받아 받은 순서로 보인다. 칸은 시각, 한 사람, 일, 대상, 바뀐 값이고 로그인 기록도 나온다', async () => {
    audit.entries = [SUSPENDED, SIGNED_IN, COMPANY_MADE];
    renderAt(URL_LOG);

    const rows = await entryRows(3);
    const heads = screen.getAllByRole('columnheader').map((h) => text(h));
    expect(heads.filter((h) => (COLUMNS as readonly string[]).includes(h))).toEqual([...COLUMNS]);
    const time = cellOf(rows[0], '시각')?.querySelector('time');
    expect(time ?? null, '시각 칸의 <time>').not.toBeNull();
    expect(new Date(time?.getAttribute('datetime') ?? '').getTime()).toBe(new Date(SUSPENDED.occurredAt).getTime());
    expect(text(cellOf(rows[0], '한 사람'))).toContain('최관리');
    expect(text(cellOf(rows[0], '한 사람'))).toContain('admin@popdesk.test');
    expect(column('일')).toEqual(['계정 정지', '로그인', '고객사 만들기']);
    expect(column('대상')).toEqual(['계정 12', '계정 2', '고객사 ACME']);

    const query = lastList();
    expect(query.get('page') ?? '1').toBe('1');
    expect(query.get('size')).toBe('20');
    expect(FILTERS.filter((name) => query.has(name)), '처음 요청의 필터').toEqual([]);
  });

  test.each(KIND_GROUPS)('S1B-AC-36 C-12 일의 종류($group): 종류마다 한국어 말로 보인다', async ({ kinds }) => {
    audit.entries = Object.keys(kinds).map((name, i) => entry({ id: 7600 - i, action: name }));
    renderAt(URL_LOG);

    await entryRows(audit.entries.length);
    expect(column('일')).toEqual(Object.values(kinds));
  });

  test.each([
    ['소속', CUSTOMER_ADDED, '고객사 ACME · 계정 12'],
    ['배정', AGENT_ASSIGNED, '고객사 ACME · 계정 12'],
    ['백업', BACKED_UP, '데이터베이스'],
    ['복원', RESTORED, '데이터베이스'],
    ['내보내기', EXPORTED, '감사 로그'],
  ])('S1B-AC-36 C-12 대상 칸(%s): 대상의 종류에 맞는 글자로 보인다', async (_, shown, wanted) => {
    audit.entries = [shown];
    renderAt(URL_LOG);

    const [row] = await entryRows(1);
    expect(text(cellOf(row, '대상'))).toBe(wanted);
  });

  test('S1B-AC-36 C-12 소속·배정의 대상을 누르면 고객사와 계정이 함께 걸린다(targetCode와 targetAccountId)', async () => {
    audit.entries = [CUSTOMER_ADDED, COMPANY_MADE, SUSPENDED];
    const { user } = renderAt(URL_LOG);
    const rows = await entryRows(3);
    const cell = cellOf(rows[0], '대상') as HTMLElement | null;
    const label = '고객사 ACME · 계정 12';
    await user.click(
      await found(() => (cell ? (within(cell).queryByRole('button', { name: label }) ?? within(cell).queryByRole('link', { name: label })) : null), `대상 "${label}"`),
    );

    await eventually(() => {
      expect({ targetCode: lastList().get('targetCode'), targetAccountId: lastList().get('targetAccountId') }).toEqual({
        targetCode: 'ACME',
        targetAccountId: '12',
      });
    });
    await entryRows(1);
    expect(column('일')).toEqual(['소속 더하기']);
  });

  test('S1B-AC-36 C-12 모르는 종류: 이름을 그대로 code 글꼴로 보이고, 모르는 칸과 값도 그대로 보인다. 대상이 없으면 빈칸', async () => {
    audit.entries = [UNKNOWN];
    renderAt(URL_LOG);

    const [row] = await entryRows(1);
    expect(text(cellOf(row, '일'))).toBe('LEGACY_THING');
    expect(text(cellOf(row, '일')?.querySelector('code')), '<code>').toBe('LEGACY_THING');
    expect(text(cellOf(row, '바뀐 값'))).toBe('flavor: mint');
    expect(text(cellOf(row, '대상'))).toBe('');
  });

  test('S1B-AC-36 C-12 바뀐 값: "칸: 전 → 뒤", 전이나 뒤가 없으면 "없음", 전·뒤 꼴이 아니면 "칸: 값", 바뀐 값이 없으면 빈칸', async () => {
    audit.entries = [
      entry({ id: 7705, action: 'ACCOUNT_ROLE_CHANGED', changes: { role: { from: 'AGENT', to: 'ADMIN' } } }),
      entry({ id: 7704, action: 'ACCOUNT_NAME_CHANGED', changes: { name: { from: null, to: '김새이름' } } }),
      entry({ id: 7703, action: 'ACCOUNT_NAME_CHANGED', changes: { name: { from: '김옛이름', to: null } } }),
      COMPANY_MADE,
      SIGNED_IN,
    ];
    renderAt(URL_LOG);

    await entryRows(5);
    const shown = column('바뀐 값');
    expect(shown[0]).toBe('역할: 상담원 → 관리자');
    expect(shown[1]).toBe('이름: 없음 → 김새이름');
    expect(shown[2]).toBe('이름: 김옛이름 → 없음');
    expect(shown[3]).toContain('이름: 에이크미');
    expect(shown[3], '전·뒤 꼴이 아닌 값').not.toContain('→');
    expect(shown[4]).toBe('');
  });

  test('S1B-AC-01 C-12 시스템이 한 일: 한 사람 칸에 "시스템"', async () => {
    audit.entries = [BACKED_UP];
    renderAt(URL_LOG);

    const [row] = await entryRows(1);
    expect(text(cellOf(row, '한 사람'))).toBe('시스템');
    expect(text(cellOf(row, '일'))).toBe('백업');
  });

  test('S1B-AC-01 C-12 줄 펼치기: 평소에는 접혀 있고, 펼치면 그 줄 아래에 항목 ID, 해시, 앞 해시. 필터가 바뀌면 접힌다', async () => {
    audit.entries = [SUSPENDED, SIGNED_IN];
    const { user } = renderAt(URL_LOG);

    const rows = await entryRows(2);
    const open = await expander(rows[0]);
    expect(open).toHaveAttribute('aria-expanded', 'false');
    expect(pageText(), '접힌 줄의 해시').not.toContain(SUSPENDED.hash);
    await user.click(open);

    await eventually(() => {
      expect(open).toHaveAttribute('aria-expanded', 'true');
    });
    const detail = rows[0]?.nextElementSibling;
    expect(text(detail)).toContain(SUSPENDED.hash);
    expect(text(detail)).toContain(SUSPENDED.prevHash);
    expect(text(detail)).toContain(String(SUSPENDED.id));
    expect(pageText(), '펼치지 않은 줄의 해시').not.toContain(SIGNED_IN.hash);

    await user.selectOptions(await kindSelect(), '계정');
    await listedKinds(Object.keys(KIND_GROUPS[1]?.kinds ?? {}));
    await entryRows(1);
    expect(pageText(), '필터가 바뀐 뒤').not.toContain(SUSPENDED.hash);
  });

  test('C-12 결과 없음(필터 있음): "조건에 맞는 항목이 없습니다"와 "조건 지우기". 지우면 필터 없이 다시 받는다', async () => {
    audit.entries = [SIGNED_IN];
    const { user } = renderAt(URL_LOG);
    await entryRows(1);
    const kind = await kindSelect();
    await user.selectOptions(kind, '계정 정지');

    await eventually(() => {
      expect(pageText()).toContain('조건에 맞는 항목이 없습니다');
    });
    await user.click(await action(/조건 지우기/));

    await entryRows(1);
    expect(lastList().getAll('action')).toEqual([]);
    expect(chosenKind(kind)).toBe('전체');
  });

  test('C-12 결과 없음(필터 없음): "감사 항목이 없습니다."이고 "조건 지우기"는 없다', async () => {
    audit.entries = [];
    renderAt(URL_LOG);

    await eventually(() => {
      expect(pageText()).toContain('감사 항목이 없습니다.');
    });
    expect(queryAction(/조건 지우기/)).toBeNull();
  });

  test('C-12 없는 쪽: "이 쪽에는 항목이 없습니다"와 "1쪽으로"', async () => {
    audit.entries = [SUSPENDED, SIGNED_IN];
    renderAt(`${URL_LOG}?page=9`);

    await eventually(() => {
      expect(pageText()).toContain('이 쪽에는 항목이 없습니다');
    });
    expect(lastList().get('page')).toBe('9');
    const first = await link('1쪽으로');
    expect(hrefPath(first)).toBe(URL_LOG);
    expect(new URL(first.href).searchParams.has('page')).toBe(false);
  });

  test('C-12 필터가 바뀜: 1쪽으로 돌아가고, 필터는 주소에 실려 그 주소로 다시 열면 같은 필터로 받는다', async () => {
    audit.entries = [...Array.from({ length: 24 }, (_, i) => entry({ ...SIGNED_IN, id: 7500 - i })), SUSPENDED];
    const { user, unmount } = renderAt(`${URL_LOG}?page=2`);
    await entryRows(5);
    expect(lastList().get('page')).toBe('2');
    await user.selectOptions(await kindSelect(), '계정 정지');

    await listedKinds(['ACCOUNT_SUSPENDED']);
    expect(lastList().get('page') ?? '1').toBe('1');
    await entryRows(1);
    expect(new URLSearchParams(window.location.search).get('page') ?? '1').toBe('1');
    expect(window.location.search, '필터가 주소의 쿼리에').not.toBe('');

    const address = `${window.location.pathname}${window.location.search}`;
    unmount();
    calls.length = 0;
    renderAt(address);

    await entryRows(1);
    expect(lastList().getAll('action')).toEqual(['ACCOUNT_SUSPENDED']);
    expect(chosenKind(await kindSelect())).toBe('계정 정지');
  });

  test('C-12 axe: 필터, 동작 줄, 펼친 줄이 있는 표에 접근성 위반이 없다', async () => {
    audit.entries = [SUSPENDED, SIGNED_IN, COMPANY_MADE, BACKED_UP];
    const { user } = renderAt(URL_LOG);
    const rows = await entryRows(4);
    await kindSelect();
    await user.click(await expander(rows[0]));
    await eventually(() => {
      expect(pageText()).toContain(SUSPENDED.hash);
    });

    await expectNoAxeViolations();
  });
});
