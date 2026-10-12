// 3번 묶음 screens.md 2절 C-4 문의 상세: 머리의 값 목록, 담당자 대화상자, 동작 줄, 종료된 문의.
import { HttpResponse } from 'msw/http';
import { describe, expect, test } from 'vitest';
import {
  ADMIN_PERSON,
  AGENT,
  AGENT_PERSON,
  OTHER_AGENT_PERSON,
  atPath,
  button,
  callsTo,
  errors,
  eventually,
  expectNoAxeViolations,
  found,
  gate,
  isBlocked,
  on,
  openDialog,
  pageText,
  renderAt,
  screen,
  sent,
  state,
  text,
  within,
  type Issue,
} from '../__tests__/console';
import { answered, closed, confirmButton, rejected, settle } from '../__tests__/console1a';
import { expectNotSaid, said } from '../__tests__/console2';
import { ISSUES, issueIn, metaOf, s3 } from '../__tests__/console3';

const URL_7 = '/issues/ACME-7';
const ISSUE_7 = `${ISSUES}/ACME-7`;
const CANDIDATES = '/api/console/companies/ACME/assignees';
const ACTIONS = ['확인', '처리 시작', '해결', '다시 처리', '종료', '다시 열기'] as const;
const TOO_LATE = '종료한 지 14일이 지나 다시 열 수 없습니다.';
const CLOSED_BAND = '종료된 문의입니다. 댓글과 첨부를 더할 수 없습니다.';
const UNAVAILABLE_HELP = '이 담당자는 지금 이 문의를 볼 수 없습니다. 담당자를 바꿔 주세요.';

type User = ReturnType<typeof renderAt>['user'];

const title = () => found(() => screen.queryByRole('heading', { level: 1 }), '문의 제목');
/** The value of a line of the head, waited for. */
const meta = (term: string) => found(() => metaOf(term), `머리의 "${term}" 줄`);
const changeButton = () => button(/^(담당자 )?바꾸기$/);
/** The action buttons the screen shows now, in the order of ACTIONS. */
const actionsShown = () => ACTIONS.filter((name) => screen.queryByRole('button', { name }) !== null);
const radio = (box: HTMLElement, name: string | RegExp) => found(() => within(box).queryByRole<HTMLInputElement>('radio', { name }), `대화상자의 "${String(name)}"`);
const save = (box: HTMLElement) => found(() => within(box).queryByRole('button', { name: '저장' }), '"저장"');
const issueReads = () => callsTo('GET', ISSUE_7).length;

/** Opens the 담당자 dialog. */
async function openAssignee(user: User): Promise<HTMLElement> {
  await user.click(await changeButton());
  return found(() => screen.queryByRole('dialog', { name: '담당자' }), '대화상자 "담당자"');
}

describe('C-4 문의 상세 (3번 묶음)', () => {
  test.each([
    { what: '이름', over: { assignee: AGENT_PERSON }, shows: ['이상담'], hides: ['담당 없음', '볼 수 없음'] },
    { what: '"담당 없음"', over: {}, shows: ['담당 없음'], hides: ['볼 수 없음'] },
    { what: '"볼 수 없음"과 안내', over: { assignee: OTHER_AGENT_PERSON, assigneeUnavailable: true }, shows: ['정상담', '볼 수 없음'], hides: ['담당 없음'] },
  ] satisfies { what: string; over: Partial<Issue>; shows: string[]; hides: string[] }[])(
    'S3-AC-80 C-4 머리의 담당자 줄($what)과 "바꾸기" 버튼',
    async ({ over, shows, hides }) => {
      state.issue = issueIn('RECEIVED', over);
      renderAt(URL_7);

      const line = text(await meta('담당자'));
      for (const word of shows) expect(line).toContain(word);
      for (const word of hides) expect(line).not.toContain(word);
      expect(pageText().includes(UNAVAILABLE_HELP), '볼 수 없다는 안내 한 줄').toBe(over.assigneeUnavailable === true);
      await changeButton();
    },
  );

  test.each([
    ['PORTAL', '포털'],
    ['PHONE', '전화'],
    ['OTHER', '그 밖'],
  ] as const)('S3-AC-80 C-4 머리의 받은 경로 %s: "%s"', async (channel, word) => {
    state.issue = issueIn('RECEIVED', { channel });
    renderAt(URL_7);

    expect(text(await meta('받은 경로'))).toBe(word);
  });

  test('S3-AC-80 C-4 머리(값이 있음): "대리 등록: 이름", 첫 응답 시각, 해결 시각과 종료 시각', async () => {
    const at = { firstResponseAt: '2026-10-08T04:00:00Z', resolvedAt: '2026-10-09T05:00:00Z', closedAt: '2026-10-10T06:00:00Z' };
    state.issue = issueIn('CLOSED', { channel: 'PHONE', createdBy: AGENT_PERSON, ...at });
    renderAt(URL_7);

    const times = async (term: string) => Array.from((await meta(term)).querySelectorAll('time')).map((t) => Date.parse(t.getAttribute('datetime') ?? ''));
    expect(await times('첫 응답')).toEqual([Date.parse(at.firstResponseAt)]);
    expect(await times('해결 시각')).toEqual([Date.parse(at.resolvedAt)]);
    expect(await times('종료 시각')).toEqual([Date.parse(at.closedAt)]);
    expect(pageText()).toMatch(/대리 등록:? ?이상담/);
  });

  test('S3-AC-76 C-4 머리의 올린 사람 줄: 링크 "이 사람이 올린 문의 보기"가 그 사람으로 거른 문의 목록으로 간다', async () => {
    state.issue = issueIn('RECEIVED');
    renderAt(URL_7);

    const toList = await found(
      () => (metaOf('올린 사람') ? within(metaOf('올린 사람') as HTMLElement).queryByRole<HTMLAnchorElement>('link', { name: '이 사람이 올린 문의 보기' }) : null),
      '올린 사람 줄의 링크',
    );
    const target = new URL(toList.href);
    expect(target.pathname).toBe('/issues');
    expect(Array.from(target.searchParams.entries())).toEqual([['reporter', '11']]);
  });

  test('S3-AC-80 C-4 머리(값이 없음): 첫 응답은 "아직 없음", 해결·종료 시각 줄과 대리 등록 줄은 없다', async () => {
    state.issue = issueIn('RECEIVED');
    renderAt(URL_7);

    expect(text(await meta('첫 응답'))).toBe('아직 없음');
    expect(metaOf('해결 시각')).toBeNull();
    expect(metaOf('종료 시각')).toBeNull();
    expect(pageText()).not.toContain('대리 등록');
  });

  test('S3-AC-80 C-4 담당자 대화상자: 열면 후보를 부르고 맨 위 "담당 없음"과 후보(이름과 역할)를 보인다. 지금 담당자가 골라져 있고 같은 사람이면 "저장"이 막혀 있다', async () => {
    state.issue = issueIn('RECEIVED', { assignee: AGENT_PERSON });
    const { user } = renderAt(URL_7);
    await title();
    expect(callsTo('GET', CANDIDATES), '열기 전').toHaveLength(0);

    const box = await openAssignee(user);
    await sent('GET', CANDIDATES);
    const mine = await radio(box, /이상담/);
    const names = within(box).getAllByRole('radio').map((r) => text((r as HTMLInputElement).labels?.[0]));
    expect(names[0]).toBe('담당 없음');
    expect(names).toHaveLength(4);
    expect(text(box)).toContain('관리자');
    expect(text(box)).toContain('상담원');
    expect(mine).toBeChecked();
    expect(isBlocked(await save(box)), '같은 사람이면 "저장"').toBe(true);

    await user.click(await radio(box, /정상담/));
    expect(isBlocked(await save(box)), '다른 사람을 고르면 "저장"').toBe(false);
    await user.click(mine);
    expect(isBlocked(await save(box)), '다시 같은 사람').toBe(true);
  });

  test.each([
    { what: '저장', pick: /정상담/, method: 'PUT', body: { accountId: 3 }, toast: '담당자를 정상담 님으로 바꿨습니다', shows: '정상담' },
    { what: '비우기', pick: '담당 없음', method: 'DELETE', body: undefined, toast: '담당자를 비웠습니다', shows: '담당 없음' },
  ])('S3-AC-80 C-4 담당자 $what: $method …/assignee를 보내고 대화상자를 닫는다. 머리를 다시 불러오고 토스트 "$toast"', async ({ pick, method, body, toast, shows }) => {
    state.issue = issueIn('RECEIVED', { assignee: AGENT_PERSON });
    const { user } = renderAt(URL_7);
    const box = await openAssignee(user);
    await user.click(await radio(box, pick));
    const reads = issueReads();
    await user.click(await save(box));

    const [call] = await sent(method, `${ISSUE_7}/assignee`);
    expect(await call?.body).toEqual(body);
    await closed();
    await said(toast);
    await eventually(() => {
      expect(issueReads(), '문의를 다시 읽음').toBeGreaterThan(reads);
      expect(text(metaOf('담당자'))).toContain(shows);
    });
  });

  test('S3-AC-30 C-4 종료된 문의: 띠는 그대로이고 담당자 "바꾸기"로 담당자를 정할 수 있다. "고치기"는 없다', async () => {
    state.issue = issueIn('CLOSED', { reopenable: false });
    const { user } = renderAt(URL_7);
    await eventually(() => {
      expect(pageText()).toContain(CLOSED_BAND);
    });

    const box = await openAssignee(user);
    await user.click(await radio(box, /정상담/));
    await user.click(await save(box));

    await sent('PUT', `${ISSUE_7}/assignee`);
    await said('담당자를 정상담 님으로 바꿨습니다');
    expect(pageText()).toContain(CLOSED_BAND);
    expect(screen.queryByRole('button', { name: '고치기' })).toBeNull();
  });

  test('S3-AC-80 C-4 "나에게 맡기기": 내가 후보일 때만 있고, 누르면 "저장" 없이 바로 나를 담당자로 보낸다', async () => {
    state.me = AGENT;
    const { user } = renderAt(URL_7);
    const box = await openAssignee(user);
    await radio(box, /정상담/);

    await user.click(await found(() => within(box).queryByRole('button', { name: '나에게 맡기기' }), '"나에게 맡기기"'));

    const [call] = await sent('PUT', `${ISSUE_7}/assignee`);
    expect(await call?.body).toEqual({ accountId: AGENT.id });
    await closed();
    await said('담당자를 이상담 님으로 바꿨습니다');
  });

  test('S3-AC-80 C-4 "나에게 맡기기": 내가 후보가 아니면 없다', async () => {
    state.me = AGENT;
    s3.candidates = [ADMIN_PERSON, OTHER_AGENT_PERSON];
    const { user } = renderAt(URL_7);
    const box = await openAssignee(user);
    await radio(box, /정상담/);

    expect(within(box).queryByRole('button', { name: '나에게 맡기기' })).toBeNull();
  });

  test('S3-AC-80 C-4 담당자 400(UNKNOWN_VALUE): 대화상자 안에 "이 사람은 지금 담당자가 될 수 없습니다"를 보이고 후보를 다시 부른다', async () => {
    on('put', `${ISSUE_7}/assignee`, () => errors.invalid([{ field: 'accountId', code: 'UNKNOWN_VALUE' }]));
    const { user } = renderAt(URL_7);
    const box = await openAssignee(user);
    await user.click(await radio(box, /정상담/));
    await user.click(await save(box));

    await found(
      () => within(box).queryAllByRole('alert').find((a) => text(a).includes('이 사람은 지금 담당자가 될 수 없습니다')),
      '대화상자 안의 Alert',
    );
    await sent('GET', CANDIDATES, 2);
    expect(openDialog(), '대화상자는 열려 있다').not.toBeNull();
  });

  test('S3-AC-80 C-4 담당자 후보를 불러오지 못함: 대화상자 안에 띠와 "다시 불러오기". 누르면 후보가 나온다', async () => {
    let broken = true;
    on('get', CANDIDATES, () => (broken ? errors.internal() : HttpResponse.json({ items: s3.candidates })));
    const { user } = renderAt(URL_7);
    const box = await openAssignee(user);

    await found(() => within(box).queryAllByRole('alert')[0], '대화상자 안의 띠');
    expect(within(box).queryByRole('radio', { name: /정상담/ })).toBeNull();
    broken = false;
    await user.click(await found(() => within(box).queryByRole('button', { name: '다시 불러오기' }), '"다시 불러오기"'));

    await radio(box, /정상담/);
    expect(within(box).queryAllByRole('alert')).toHaveLength(0);
  });

  test('S3-AC-87 C-4 닫힌 뒤 온 답(담당자): 저장 중에 닫은 뒤 온 실패는 버린다. 다시 열면 오류 없이 처음부터 보인다', async () => {
    const release = gate();
    on('put', `${ISSUE_7}/assignee`, async () => {
      await release.opened;
      return errors.invalid([{ field: 'accountId', code: 'UNKNOWN_VALUE' }]);
    });
    const { user } = renderAt(URL_7);
    const first = await openAssignee(user);
    await user.click(await radio(first, /정상담/));
    await user.click(await save(first));
    await sent('PUT', `${ISSUE_7}/assignee`);
    await user.keyboard('{Escape}');
    await closed();

    const done = answered('PUT', `${ISSUE_7}/assignee`);
    release.open();
    await done;
    await settle();
    expect(openDialog(), '늦은 답이 대화상자를 다시 열지 않는다').toBeNull();

    const second = await openAssignee(user);
    await radio(second, /정상담/);
    expect(within(second).queryAllByRole('alert'), '앞 요청의 오류').toHaveLength(0);
    expect(await radio(second, '담당 없음')).toBeChecked();
  });

  test.each([
    { now: '접수', issue: issueIn('RECEIVED'), buttons: ['확인', '처리 시작', '종료'] },
    { now: '확인', issue: issueIn('CONFIRMED'), buttons: ['처리 시작', '종료'] },
    { now: '처리 중', issue: issueIn('IN_PROGRESS'), buttons: ['해결'] },
    { now: '해결', issue: issueIn('RESOLVED'), buttons: ['다시 처리', '종료', '다시 열기'] },
    { now: '종료(14일 안)', issue: issueIn('CLOSED'), buttons: ['다시 열기'] },
    { now: '종료(14일 지남)', issue: issueIn('CLOSED', { reopenable: false }), buttons: [] },
    // Not in the table of the rules: the screen draws what the server gave, nothing of its own.
    { now: '접수인데 서버가 "해결"만 줌', issue: issueIn('RECEIVED', { nextStatuses: ['RESOLVED'] }), buttons: ['해결'] },
  ])('S3-AC-79 C-4 동작 줄($now): 서버가 준 전이와 재오픈 여부로 버튼 $buttons 를 그린다', async ({ issue, buttons }) => {
    state.issue = issue;
    renderAt(URL_7);
    await title();

    await eventually(() => {
      expect(actionsShown()).toEqual(buttons);
    });
    const closedLate = issue.status === 'CLOSED' && !issue.reopenable;
    expect(pageText().includes(TOO_LATE), `"${TOO_LATE}"`).toBe(closedLate);
    expect(screen.queryByRole('button', { name: '고치기' }) !== null, '"고치기"').toBe(issue.status !== 'CLOSED');
  });

  test.each([
    { from: issueIn('RECEIVED'), press: '확인', asks: null, method: 'PUT', path: 'status', body: { status: 'CONFIRMED' }, word: '확인', after: ['처리 시작', '종료'] },
    { from: issueIn('RECEIVED'), press: '처리 시작', asks: null, method: 'PUT', path: 'status', body: { status: 'IN_PROGRESS' }, word: '처리 중', after: ['해결'] },
    { from: issueIn('IN_PROGRESS'), press: '해결', asks: null, method: 'PUT', path: 'status', body: { status: 'RESOLVED' }, word: '해결', after: ['다시 처리', '종료', '다시 열기'] },
    { from: issueIn('RESOLVED'), press: '다시 처리', asks: null, method: 'PUT', path: 'status', body: { status: 'IN_PROGRESS' }, word: '처리 중', after: ['해결'] },
    {
      from: issueIn('CONFIRMED'),
      press: '종료',
      asks: '이 문의를 종료할까요? 고객은 14일 안에 다시 열 수 있습니다.',
      method: 'PUT',
      path: 'status',
      body: { status: 'CLOSED' },
      word: '종료',
      after: ['다시 열기'],
    },
    {
      from: issueIn('RESOLVED'),
      press: '다시 열기',
      asks: '이 문의를 다시 열까요? 상태가 접수로 돌아갑니다.',
      method: 'POST',
      path: 'reopen',
      body: undefined,
      word: '접수',
      after: ['확인', '처리 시작', '종료'],
    },
  ])(
    'S3-AC-79 C-4 "$press": $method …/$path 를 보내고(확인 대화상자: $asks) 토스트로 알린 뒤 문의를 다시 불러와 버튼이 바뀐다',
    async ({ from, press, asks, method, path, body, word, after }) => {
      state.issue = from;
      const { user } = renderAt(URL_7);
      await user.click(await button(press));

      if (asks !== null) {
        const box = await found(() => screen.queryByRole('alertdialog'), '확인 대화상자');
        expect(text(box)).toContain(asks);
        expect(callsTo(method, `${ISSUE_7}/${path}`), '확인 전').toHaveLength(0);
        await user.click(confirmButton(box));
      }
      const [call] = await sent(method, `${ISSUE_7}/${path}`);
      expect(await call?.body).toEqual(body);
      expect(openDialog(), asks === null ? '묻지 않고 바로 한다' : '대화상자가 닫힘').toBeNull();
      await said(`상태를 바꿨습니다: ${word}`);
      await eventually(() => {
        expect(actionsShown()).toEqual(after);
      });
    },
  );

  test.each([
    { code: 'INVALID_TRANSITION', from: issueIn('RECEIVED'), press: '확인', asks: false, path: 'status', method: 'put' as const, now: issueIn('IN_PROGRESS'), after: ['해결'] },
    { code: 'ISSUE_CLOSED', from: issueIn('IN_PROGRESS'), press: '해결', asks: false, path: 'status', method: 'put' as const, now: issueIn('CLOSED'), after: ['다시 열기'] },
    { code: 'NOT_REOPENABLE', from: issueIn('RESOLVED'), press: '다시 열기', asks: true, path: 'reopen', method: 'post' as const, now: issueIn('CONFIRMED'), after: ['처리 시작', '종료'] },
  ])('S3-AC-79 C-4 400 $code: 토스트 "상태가 이미 바뀌었습니다"와 함께 문의를 다시 불러와 버튼이 새 상태에 맞게 바뀐다', async ({ code, from, press, asks, path, method, now, after }) => {
    state.issue = from;
    on(method, `${ISSUE_7}/${path}`, () => {
      // Somebody else changed it first.
      state.issue = now;
      return rejected(code);
    });
    const { user } = renderAt(URL_7);
    await user.click(await button(press));
    if (asks) await user.click(confirmButton(await found(() => screen.queryByRole('alertdialog'), '확인 대화상자')));

    await said('상태가 이미 바뀌었습니다');
    await eventually(() => {
      expect(actionsShown()).toEqual(after);
    });
    expect(openDialog()).toBeNull();
  });

  test('S3-AC-87 C-4 떠난 뒤 온 답(상태): 바꾸는 중에 목록으로 옮긴 뒤 온 실패는 토스트를 띄우지 않고 주소도 그대로다', async () => {
    state.issue = issueIn('RECEIVED');
    const release = gate();
    on('put', `${ISSUE_7}/status`, async () => {
      await release.opened;
      return rejected('INVALID_TRANSITION');
    });
    const { user } = renderAt(URL_7);
    await user.click(await button('확인'));
    await sent('PUT', `${ISSUE_7}/status`);
    await user.click(await found(() => screen.queryByRole('link', { name: '고객사' }), '메뉴 "고객사"'));
    await atPath('/companies');

    const reads = issueReads();
    const done = answered('PUT', `${ISSUE_7}/status`);
    release.open();
    await done;
    await settle();

    expectNotSaid('상태가 이미 바뀌었습니다');
    expect(window.location.pathname).toBe('/companies');
    expect(issueReads(), '떠난 화면이 문의를 다시 읽지 않는다').toBe(reads);
  });

  test('C-4 axe (3번 묶음): 담당자 줄, 동작 줄이 있는 화면과 열린 담당자 대화상자에 접근성 위반이 없다', async () => {
    state.issue = issueIn('RESOLVED', {
      assignee: OTHER_AGENT_PERSON,
      assigneeUnavailable: true,
      createdBy: AGENT_PERSON,
      channel: 'PHONE',
      firstResponseAt: '2026-10-08T04:00:00Z',
      resolvedAt: '2026-10-09T05:00:00Z',
    });
    const { user } = renderAt(URL_7);
    await eventually(() => {
      expect(actionsShown()).toEqual(['다시 처리', '종료', '다시 열기']);
    });
    await expectNoAxeViolations();

    const box = await openAssignee(user);
    await radio(box, /이상담/);
    await expectNoAxeViolations();
  });
});
