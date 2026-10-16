// 8a 묶음 screens.md 1절 C-13 SLA 설정(관리자, /sla): 옆 메뉴 자리, 안내, 상태 줄(꺼짐·켜짐·시스템이 바꿈)과 시간대,
// 목표 표와 분 도움말, 업무 시간(업무일, 시각, 자정까지), 쉬는 날(더하기, 빼기, 500개), 저장(바뀐 것 없음, 성공, 버전,
// 보낸 본문), 400의 칸 글, 409와 "다시 불러오기", 읽기 실패, 저장 중 떠났다 돌아온 뒤 늦게 온 답(S8A-AC-01~04).
import { waitFor } from '@testing-library/react';
import { describe, expect, test } from 'vitest';
import {
  AGENT,
  callsTo,
  describedText,
  errors,
  eventually,
  expectNoAxeViolations,
  found,
  gate,
  hrefPath,
  isBlocked,
  menuLinks,
  pageText,
  renderAt,
  screen,
  sent,
  state,
  text,
} from '../__tests__/console';
import { answered, route, settle } from '../__tests__/console1a';
import {
  ALL_DAY,
  BY_SYSTEM,
  CONFLICT,
  DAY_TAKEN,
  END_BEFORE_START,
  HOLIDAY_LIMIT,
  NOTHING_CHANGED,
  NOTICE,
  OFF,
  ON,
  OUT_OF_RANGE,
  PRIORITIES,
  READ_FAILED,
  RELOAD,
  SAVED,
  SLA_POLICY,
  TITLE,
  TOO_SHORT,
  URL_SLA,
  WEEKDAYS,
  dayWords,
  goalFields,
  goalTable,
  holidayField,
  manyHolidays,
  midnightBoxNow,
  offPolicy,
  onPolicy,
  pageShows,
  policyResponse,
  removeHolidayButtons,
  removeHolidayNow,
  s8a,
  savePolicy,
  slaTitle,
  target,
  timeFieldsNow,
  workdayBox,
  zoneLine,
  type ChangeSlaPolicyRequest,
  type SlaPolicy,
} from '../__tests__/console8a';

type User = ReturnType<typeof renderAt>['user'];

/** The save button "저장". */
function saveButton(): Promise<HTMLElement> {
  return found(() => screen.queryByRole('button', { name: '저장' }), '"저장"');
}

/** The body of the last save, in the contract's order of priorities and weekdays, holidays sorted. */
async function lastSaved(): Promise<ChangeSlaPolicyRequest> {
  const call = (await sent('PUT', SLA_POLICY)).at(-1);
  const body = (await call?.body) as ChangeSlaPolicyRequest;
  return {
    version: body.version,
    targets: [...body.targets].sort((a, b) => PRIORITIES.indexOf(a.priority) - PRIORITIES.indexOf(b.priority)),
    hours: [...body.hours].sort((a, b) => WEEKDAYS.indexOf(a.weekday) - WEEKDAYS.indexOf(b.weekday)),
    holidays: [...body.holidays].sort(),
  };
}

/** Clears `field` and types `value` (nothing for an empty field). */
async function retype(user: User, field: HTMLInputElement, value: string): Promise<void> {
  await user.clear(field);
  if (value !== '') await user.type(field, value);
}

/** Renders C-13 and waits until the policy it read is drawn (the goal table). */
async function openPolicy(policy: SlaPolicy): Promise<User> {
  s8a.policy = policy;
  const { user } = renderAt(URL_SLA);
  await slaTitle();
  await goalTable();
  await sent('GET', SLA_POLICY);
  return user;
}

describe('C-13 SLA 설정 자리와 권한 (8a 묶음)', () => {
  test('S8A-AC-01 C-13 관리자 옆 메뉴의 "SLA 설정"은 "휴지통"과 "감사 로그" 사이에 있고, 누르면 /sla의 h1 "SLA 설정"이다', async () => {
    const { user } = renderAt('/issues');
    const menu = await found(
      () => screen.queryAllByRole('link', { name: TITLE }).find((a) => a.closest('nav') !== null),
      `메뉴 "${TITLE}"`,
    );
    const links = menuLinks();
    expect(links.indexOf(TITLE), '"휴지통" 뒤').toBe(links.indexOf('휴지통') + 1);
    expect(links.indexOf('감사 로그'), '"감사 로그" 앞').toBe(links.indexOf(TITLE) + 1);
    expect(hrefPath(menu)).toBe(URL_SLA);

    await user.click(menu);

    await eventually(() => {
      expect(window.location.pathname).toBe(URL_SLA);
    });
    await slaTitle();
    await sent('GET', SLA_POLICY);
  });

  test('S8A-AC-01 C-13 상담원: 메뉴에 "SLA 설정"이 없고, 주소로 오면 화면 전체가 "권한이 없습니다"이며 정책을 부르지 않는다', async () => {
    state.me = AGENT;
    renderAt(URL_SLA);

    await waitFor(
      () => {
        expect(pageText()).toContain('권한이 없습니다');
      },
      { timeout: 5000 },
    );
    expect(menuLinks()).not.toContain(TITLE);
    expect(callsTo('GET', SLA_POLICY)).toHaveLength(0);
  });
});

describe('C-13 SLA 설정 읽기 (8a 묶음)', () => {
  test('S8A-AC-01 C-13 한 번도 정하지 않은 정책: 안내 한 줄, "SLA가 꺼져 있습니다.", 시간대 줄, 목표 칸은 모두 비고 업무일은 하나도 없다', async () => {
    await openPolicy(offPolicy());

    await pageShows(OFF);
    expect(pageText()).toContain(NOTICE);
    expect(pageText()).toMatch(zoneLine('Asia/Seoul'));
    expect(pageText()).not.toContain(ON);
    expect(pageText()).toContain(ALL_DAY);
    for (const p of PRIORITIES) {
      const [first, resolution] = await goalFields(p);
      expect(first.value, `${p} 첫 응답`).toBe('');
      expect(resolution.value, `${p} 해결`).toBe('');
    }
    for (const d of WEEKDAYS) {
      expect(await workdayBox(d), `${d} 업무일`).not.toBeChecked();
      expect(timeFieldsNow(d), `${d}의 시각 칸(업무일이 아니면 감춤)`).toHaveLength(0);
    }
    expect(removeHolidayButtons()).toHaveLength(0);
    await expectNoAxeViolations();
  });

  test('S8A-AC-01 C-13 켜진 정책: 상태 줄에 버전과 바꾼 사람, 목표 칸의 값과 분 도움말, 업무일과 시각, 쉬는 날 목록(오름차순)', async () => {
    await openPolicy(onPolicy({ targets: [target('LOW', 480, 3120), target('NORMAL', 240, 1440), target('HIGH', 60, 480), target('URGENT', 20, 240)], holidays: ['2026-10-09', '2026-12-25'] }));

    await pageShows(/SLA가 켜져 있습니다\. 버전 3, .+최관리(이|가) 바꿈/);
    expect(pageText()).not.toContain(OFF);
    const [lowFirst, lowResolution] = await goalFields('LOW');
    expect(lowFirst.value).toBe('480');
    expect(lowResolution.value).toBe('3120');
    expect(lowFirst.type).toBe('number');
    expect(describedText(lowFirst), '480분의 도움말').toContain('= 8시간');
    expect(describedText(lowResolution), '3120분의 도움말').toContain('= 2일 4시간');
    const [urgentFirst] = await goalFields('URGENT');
    expect(urgentFirst.value).toBe('20');

    expect(await workdayBox('MONDAY')).toBeChecked();
    expect(await workdayBox('SATURDAY')).not.toBeChecked();
    expect(timeFieldsNow('MONDAY').map((f) => f.value), '월요일 시작·끝').toEqual(['09:00', '18:00']);
    expect(timeFieldsNow('SATURDAY')).toHaveLength(0);
    expect(midnightBoxNow('MONDAY'), '월요일 자정까지').not.toBeNull();
    expect(midnightBoxNow('MONDAY')).not.toBeChecked();
    expect(midnightBoxNow('SATURDAY'), '업무일이 아닌 날의 자정까지').toBeNull();

    const removes = removeHolidayButtons().map((b) => b.getAttribute('aria-label') ?? text(b));
    expect(removes, '쉬는 날 두 줄').toHaveLength(2);
    expect(removes[0]).toMatch(new RegExp(dayWords('2026-10-09')));
    expect(removes[1]).toMatch(new RegExp(dayWords('2026-12-25')));
    await expectNoAxeViolations();
  });

  test('S8A-AC-01 C-13 시간대가 바뀌어 시스템이 만든 버전: 상태 줄이 "시간대가 바뀌어 시스템이 바꿈"이고 시간대 줄은 새 시간대다', async () => {
    await openPolicy(onPolicy({ version: 4, changedBy: null, timeZone: 'America/New_York' }));

    await pageShows(BY_SYSTEM);
    expect(pageText()).toMatch(/SLA가 켜져 있습니다\. 버전 4/);
    expect(pageText()).toMatch(zoneLine('America/New_York'));
  });

  test('S8A-AC-01 C-13 끝이 24:00인 요일: "자정까지"가 켜져 있고 끝 칸이 감춰져 있다', async () => {
    await openPolicy(onPolicy({ hours: WEEKDAYS.map((d) => ({ weekday: d, opens: d === 'FRIDAY' ? '13:00' : null, closes: d === 'FRIDAY' ? '24:00' : null })) }));

    const friday = await found(() => midnightBoxNow('FRIDAY'), '"금 자정까지"');
    expect(friday).toBeChecked();
    expect(timeFieldsNow('FRIDAY').map((f) => f.value), '시작 칸만 남는다').toEqual(['13:00']);
  });

  test('S8A-AC-01 C-13 읽기 실패(500): "SLA 정책을 읽지 못했습니다. 다시 해 보세요."와 "다시 불러오기". 누르면 다시 불러 표가 나온다', async () => {
    s8a.policy = onPolicy();
    let fail = true;
    route('get', SLA_POLICY, () => (fail ? errors.internal() : policyResponse()));
    const { user } = renderAt(URL_SLA);

    await waitFor(
      () => {
        expect(screen.queryAllByRole('alert').some((a) => text(a).includes(READ_FAILED)), `role="alert" "${READ_FAILED}"`).toBe(true);
      },
      { timeout: 5000 },
    );
    expect(pageText(), '실패를 꺼진 정책으로 보이지 않는다').not.toContain(OFF);
    const retry = await found(() => screen.queryByRole('button', { name: RELOAD }), `"${RELOAD}"`);
    const before = callsTo('GET', SLA_POLICY).length;
    fail = false;

    await user.click(retry);

    await eventually(() => {
      expect(callsTo('GET', SLA_POLICY).length, '다시 읽기').toBeGreaterThan(before);
    });
    await goalTable();
    await pageShows(/버전 3/);
  });
});

describe('C-13 SLA 설정 고치기와 저장 (8a 묶음)', () => {
  test('S8A-AC-02 C-13 바뀐 것 없이 "저장": 요청을 보내지 않고 "바뀐 것이 없습니다."', async () => {
    const user = await openPolicy(onPolicy());

    await user.click(await saveButton());

    await pageShows(NOTHING_CHANGED);
    expect(callsTo('PUT', SLA_POLICY)).toHaveLength(0);
  });

  test('S8A-AC-02 C-13 저장: 보낸 본문은 ChangeSlaPolicyRequest 꼴 그대로(우선순위 넷, 요일 일곱, 업무일이 아니면 시각 둘 다 null, 자정까지면 24:00, 쉬는 날), 성공 알림과 오른 버전', async () => {
    const user = await openPolicy(onPolicy());

    // 목표: 보통 첫 응답 240 → 120, 낮음 해결 비움(목표 없음)
    const [normalFirst] = await goalFields('NORMAL');
    await retype(user, normalFirst, '120');
    const [, lowResolution] = await goalFields('LOW');
    await retype(user, lowResolution, '');
    await eventually(() => {
      expect(describedText(normalFirst), '고친 값의 도움말').toContain('= 2시간');
    });
    // 업무 시간: 수요일을 쉬는 요일로, 토요일을 10:00부터 자정까지
    await user.click(await workdayBox('WEDNESDAY'));
    await eventually(() => {
      expect(timeFieldsNow('WEDNESDAY'), '수요일 시각 칸을 감춤').toHaveLength(0);
    });
    await user.click(await workdayBox('SATURDAY'));
    const saturdayOpens = await found(() => (timeFieldsNow('SATURDAY').length === 2 ? timeFieldsNow('SATURDAY')[0] : null), '토요일 시작·끝');
    await retype(user, saturdayOpens, '10:00');
    await user.click(await found(() => midnightBoxNow('SATURDAY'), '"토 자정까지"'));
    await eventually(() => {
      expect(timeFieldsNow('SATURDAY'), '자정까지면 끝 칸을 감춤').toHaveLength(1);
    });
    // 쉬는 날: 2027-01-01 더함, 2026-12-25 뺌
    await user.type(await holidayField(), '2027-01-01');
    await user.click(await found(() => screen.queryByRole('button', { name: '더하기' }), '"더하기"'));
    await found(() => removeHolidayNow('2027-01-01'), '더한 쉬는 날 2027-01-01');
    await user.click(await found(() => removeHolidayNow('2026-12-25'), '"2026-12-25 빼기"'));
    await eventually(() => {
      expect(removeHolidayNow('2026-12-25')).toBeNull();
    });

    await user.click(await saveButton());

    expect(await lastSaved()).toEqual({
      version: 3,
      targets: [target('LOW', 480, null), target('NORMAL', 120, 1440), target('HIGH', 60, 480), target('URGENT', 20, 240)],
      hours: [
        { weekday: 'MONDAY', opens: '09:00', closes: '18:00' },
        { weekday: 'TUESDAY', opens: '09:00', closes: '18:00' },
        { weekday: 'WEDNESDAY', opens: null, closes: null },
        { weekday: 'THURSDAY', opens: '09:00', closes: '18:00' },
        { weekday: 'FRIDAY', opens: '09:00', closes: '18:00' },
        { weekday: 'SATURDAY', opens: '10:00', closes: '24:00' },
        { weekday: 'SUNDAY', opens: null, closes: null },
      ],
      holidays: ['2027-01-01'],
    });
    await pageShows(SAVED);
    await pageShows(/SLA가 켜져 있습니다\. 버전 4/);
    expect(s8a.policy.version).toBe(4);
  });

  test('S8A-AC-02 C-13 목표를 처음 정하면 꺼짐이 켜짐으로 바뀌고, 업무일 없이 보낸 시각은 모두 null이다', async () => {
    const user = await openPolicy(offPolicy());
    await pageShows(OFF);

    const [urgentFirst, urgentResolution] = await goalFields('URGENT');
    await retype(user, urgentFirst, '30');
    await retype(user, urgentResolution, '600');
    await user.click(await saveButton());

    const body = await lastSaved();
    expect(body.version).toBe(1);
    expect(body.targets).toEqual([target('LOW', null, null), target('NORMAL', null, null), target('HIGH', null, null), target('URGENT', 30, 600)]);
    expect(body.hours).toEqual(WEEKDAYS.map((d) => ({ weekday: d, opens: null, closes: null })));
    expect(body.holidays).toEqual([]);
    await pageShows(/SLA가 켜져 있습니다\. 버전 2/);
    expect(pageText()).not.toContain(OFF);
  });

  test('S8A-AC-02 C-13 쉬는 날 500개: "더하기"가 막혀 있고 "쉬는 날은 500개까지입니다."', async () => {
    await openPolicy(onPolicy({ holidays: manyHolidays(500) }));

    const add = await found(() => screen.queryByRole('button', { name: '더하기' }), '"더하기"');
    await pageShows(HOLIDAY_LIMIT);
    expect(isBlocked(add), '"더하기" 막힘').toBe(true);
    expect(removeHolidayButtons()).toHaveLength(500);
  });

  test('S8A-AC-04 C-13 이미 있는 날짜를 더하면 "이미 있는 날짜입니다."이고 목록은 그대로다', async () => {
    const user = await openPolicy(onPolicy());

    await user.type(await holidayField(), '2026-12-25');
    await user.click(await found(() => screen.queryByRole('button', { name: '더하기' }), '"더하기"'));

    await pageShows(DAY_TAKEN);
    expect(removeHolidayButtons(), '쉬는 날 줄').toHaveLength(1);
  });
});

describe('C-13 SLA 설정 오류 (8a 묶음)', () => {
  test.each([
    {
      what: '목표가 범위 밖(0분)',
      words: OUT_OF_RANGE,
      edit: async (user: User) => {
        const [first] = await goalFields('NORMAL');
        await retype(user, first, '0');
        return first;
      },
    },
    {
      what: '해결이 첫 응답보다 짧음',
      words: TOO_SHORT,
      edit: async (user: User) => {
        const [, resolution] = await goalFields('NORMAL');
        await retype(user, resolution, '100');
        return resolution;
      },
    },
    {
      what: '끝이 시작보다 이름',
      words: END_BEFORE_START,
      edit: async (user: User) => {
        const [, closes] = timeFieldsNow('MONDAY');
        await retype(user, closes as HTMLInputElement, '08:00');
        return closes as HTMLInputElement;
      },
    },
  ])('S8A-AC-04 C-13 400($what): 오류 요약과 그 칸의 글 "$words", 칸은 aria-invalid. 아무것도 바뀌지 않는다', async ({ words, edit }) => {
    const user = await openPolicy(onPolicy());

    const field = await edit(user);
    await user.click(await saveButton());

    await eventually(() => {
      expect(field).toHaveAttribute('aria-invalid', 'true');
      expect(describedText(field), '칸의 글').toContain(words);
    });
    expect(
      screen.queryAllByRole('alert').some((a) => a.getAttribute('data-pd') === 'FormErrorSummary'),
      '오류 요약(FormErrorSummary)',
    ).toBe(true);
    expect(s8a.policy.version, '서버의 정책 그대로').toBe(3);
    expect(pageText()).not.toContain(SAVED);
    await expectNoAxeViolations();
  });

  test('S8A-AC-03 C-13 409: role="alert" "다른 관리자가 먼저 바꿨습니다. 다시 읽은 뒤 고쳐 주세요."와 "다시 불러오기". 누르면 다시 읽고 고친 것은 버린다', async () => {
    const user = await openPolicy(onPolicy());
    // 다른 관리자가 먼저 저장했다: 서버는 버전 4, 보통 첫 응답 90.
    s8a.policy = onPolicy({ version: 4, targets: [target('LOW', 480, 2880), target('NORMAL', 90, 1440), target('HIGH', 60, 480), target('URGENT', 20, 240)] });
    const [normalFirst] = await goalFields('NORMAL');
    await retype(user, normalFirst, '120');

    await user.click(await saveButton());

    const alert = await found(() => screen.queryAllByRole('alert').find((a) => text(a).includes(CONFLICT)), `role="alert" "${CONFLICT}"`);
    const reload = await found(() => screen.queryByRole('button', { name: RELOAD }), `"${RELOAD}"`);
    expect(alert).toBeTruthy();
    expect(s8a.policy.version).toBe(4);
    const reads = callsTo('GET', SLA_POLICY).length;

    await user.click(reload);

    await eventually(() => {
      expect(callsTo('GET', SLA_POLICY).length, '다시 읽기').toBeGreaterThan(reads);
    });
    await pageShows(/SLA가 켜져 있습니다\. 버전 4/);
    const [again] = await goalFields('NORMAL');
    await eventually(() => {
      expect(again.value, '고친 120을 버리고 읽은 90').toBe('90');
    });
    expect(screen.queryAllByRole('alert').some((a) => text(a).includes(CONFLICT)), '다시 읽은 뒤의 409 띠').toBe(false);
  });

  test('S8A-AC-02 C-13 저장 중 떠났다 돌아온 뒤 늦게 온 저장 답은 버린다: 다시 읽은 버전 3과 값이 그대로이고 성공 알림이 없다', async () => {
    const release = gate();
    route('put', SLA_POLICY, async ({ request }) => {
      await release.opened;
      return savePolicy(request);
    });
    const user = await openPolicy(onPolicy());
    const [normalFirst] = await goalFields('NORMAL');
    await retype(user, normalFirst, '120');
    await user.click(await saveButton());
    await sent('PUT', SLA_POLICY);

    await user.click(await found(() => screen.queryAllByRole('link', { name: '휴지통' }).find((a) => a.closest('nav') !== null), '메뉴 "휴지통"'));
    await eventually(() => {
      expect(window.location.pathname).not.toBe(URL_SLA);
    });
    await user.click(await found(() => screen.queryAllByRole('link', { name: TITLE }).find((a) => a.closest('nav') !== null), `메뉴 "${TITLE}"`));
    await slaTitle();
    await pageShows(/버전 3/);
    const [shown] = await goalFields('NORMAL');
    await eventually(() => {
      expect(shown.value, '다시 읽은 값').toBe('240');
    });

    const done = answered('PUT', SLA_POLICY);
    release.open();
    await done;
    await settle();

    expect(pageText(), '늦게 온 답의 버전').not.toMatch(/버전 4/);
    expect(pageText()).toMatch(/버전 3/);
    expect(shown.value).toBe('240');
    expect(pageText(), '떠난 화면의 성공 알림').not.toContain(SAVED);
  });
});
