// 3번 묶음 screens.md 2절 P-5 문의 상세: 머리의 새 줄, 누구에게 어떤 동작이 보이는가, 종료·해결 확인·다시 열기, 늦게 온 답.
import { HttpResponse } from 'msw/http';
import { describe, expect, test } from 'vitest';
import {
  BETA,
  COLLEAGUE,
  alertWith,
  atPath,
  button,
  callsTo,
  errors,
  eventually,
  expectNoAxeViolations,
  found,
  gate,
  hrefPath,
  link,
  on,
  paged,
  pageText,
  renderAt,
  screen,
  sent,
  state,
  text,
  within,
  type Issue,
} from '../__tests__/portal';
import { answered, rejected, route, settle } from '../__tests__/portal1a';
import { closed, confirmButton, expectNotSaid, openDialog, said } from '../__tests__/portal2';
import { CLOSED_AT, ISSUES, RESOLVED_AT, issueIn, metaOf } from '../__tests__/portal3';

const URL_7 = '/issues/ACME-7';
const ISSUE_7 = `${ISSUES}/ACME-7`;
const ACTIONS = ['고치기', '문의 종료', '해결 확인', '다시 열기'] as const;

const SUPPORT_FILED = '(지원팀이 대신 올림)';
const RESOLVED_BAND = '지원팀이 이 문의를 해결했다고 알렸습니다. 해결됐으면 확인해 주세요. 아직이면 다시 열 수 있습니다.';
const TOO_LATE = '종료한 지 14일이 지나 다시 열 수 없습니다. 새 문의를 올려 주세요.';
// The wording of slice 0, which the locked P5IssueDetail.test.tsx looks for.
const LEFT_COMPANY = '이 고객사에서 빠져 댓글을 달 수 없습니다';
const ASK_CLOSE = '이 문의를 종료할까요? 14일 안에는 다시 열 수 있습니다.';
const ASK_REOPEN = '이 문의를 다시 열까요? 지원팀이 다시 확인합니다.';
const CHANGED_ALREADY = '문의 상태가 이미 바뀌었습니다';

const titleShown = (title = '로그인이 안 됩니다') => found(() => screen.queryByRole('heading', { level: 1, name: title }), `문의 제목 "${title}"`);
/** The action buttons of the screen that are there now, in the order of `ACTIONS`. */
const actionsShown = () => ACTIONS.filter((name) => within(screen.getByRole('main')).queryAllByRole('button', { name }).length > 0);
const confirmBox = () => found(() => screen.queryByRole('alertdialog'), '확인 대화상자');
const issueReads = () => callsTo('GET', ISSUE_7).length;

describe('P-5 문의 상세 (3번 묶음)', () => {
  const heads: { what: string; issue: Issue; reporter: string; times: Record<string, string> }[] = [
    { what: '대리 등록한 해결된 내 문의', issue: issueIn('RESOLVED', { filedBySupport: true }), reporter: `나 ${SUPPORT_FILED}`, times: { '해결 시각': RESOLVED_AT } },
    { what: '스스로 올린 종료된 내 문의', issue: issueIn('CLOSED'), reporter: '나', times: { '종료 시각': CLOSED_AT } },
    { what: '대리 등록한 열린 동료의 문의', issue: issueIn('RECEIVED', { filedBySupport: true, reporter: COLLEAGUE }), reporter: `박동료 ${SUPPORT_FILED}`, times: {} },
  ];
  test.each(heads)('S3-AC-83 P-5 머리($what): 올린 사람은 "$reporter", 해결·종료 시각 줄은 값이 있을 때만. 담당자, 받은 경로, 이력은 없다', async ({ issue, reporter, times }) => {
    state.issue = issue;
    renderAt(URL_7);
    await titleShown();

    await eventually(() => {
      expect(text(metaOf('올린 사람'))).toContain(reporter);
    });
    expect(text(metaOf('올린 사람')).includes(SUPPORT_FILED), `"${SUPPORT_FILED}"`).toBe(issue.filedBySupport);
    for (const line of ['해결 시각', '종료 시각']) {
      const at = times[line];
      const shown = metaOf(line)?.querySelector('time')?.getAttribute('datetime') ?? null;
      expect(metaOf(line) !== null, `"${line}" 줄이 있는가`).toBe(at !== undefined);
      expect(shown === null ? null : Date.parse(shown), `"${line}" 줄의 시각`).toBe(at === undefined ? null : Date.parse(at));
    }
    for (const staffOnly of ['담당자', '받은 경로', '이력']) expect(text(screen.getByRole('main')), `"${staffOnly}"`).not.toContain(staffOnly);
  });

  const viewers: { who: string; issue: Issue; member: boolean; shows: string[]; says: string[] }[] = [
    { who: '내 열린 문의', issue: issueIn('IN_PROGRESS'), member: true, shows: ['고치기', '문의 종료'], says: [] },
    { who: '내 해결된 문의', issue: issueIn('RESOLVED'), member: true, shows: ['고치기', '해결 확인', '다시 열기'], says: [RESOLVED_BAND] },
    { who: '내 종료된 문의(다시 열 수 있음)', issue: issueIn('CLOSED'), member: true, shows: ['다시 열기'], says: [] },
    { who: '내 종료된 문의(14일 지남)', issue: issueIn('CLOSED', { reopenable: false }), member: true, shows: [], says: [TOO_LATE] },
    { who: '동료의 열린 문의', issue: issueIn('IN_PROGRESS', { reporter: COLLEAGUE }), member: true, shows: [], says: [] },
    { who: '동료의 해결된 문의', issue: issueIn('RESOLVED', { reporter: COLLEAGUE }), member: true, shows: [], says: [] },
    { who: '동료의 종료된 문의(reopenable이 참)', issue: issueIn('CLOSED', { reporter: COLLEAGUE }), member: true, shows: [], says: [] },
    { who: '소속에서 빠진 내 열린 문의', issue: issueIn('RECEIVED'), member: false, shows: ['문의 종료'], says: [LEFT_COMPANY] },
  ];
  test.each(viewers)('S3-AC-82 P-5 동작($who): 버튼은 $shows', async ({ issue, member, shows, says }) => {
    state.issue = issue;
    if (!member) state.companies = [BETA];
    renderAt(URL_7);
    await titleShown();

    await eventually(() => {
      expect(actionsShown()).toEqual(shows);
    });
    for (const line of [RESOLVED_BAND, TOO_LATE, LEFT_COMPANY]) expect(pageText().includes(line), `"${line}"`).toBe(says.includes(line));
  });

  test.each([
    { now: '접수됨', from: issueIn('RECEIVED'), press: '문의 종료', asks: ASK_CLOSE, path: 'close', toast: '문의를 종료했습니다', not: '확인했습니다', after: ['다시 열기'] },
    { now: '해결됨', from: issueIn('RESOLVED'), press: '해결 확인', asks: null, path: 'close', toast: '확인했습니다. 문의를 종료했습니다', not: null, after: ['다시 열기'] },
    { now: '해결됨', from: issueIn('RESOLVED'), press: '다시 열기', asks: ASK_REOPEN, path: 'reopen', toast: '문의를 다시 열었습니다', not: null, after: ['고치기', '문의 종료'] },
    { now: '종료됨', from: issueIn('CLOSED'), press: '다시 열기', asks: ASK_REOPEN, path: 'reopen', toast: '문의를 다시 열었습니다', not: null, after: ['고치기', '문의 종료'] },
  ])(
    'S3-AC-82 P-5 $now 문의의 "$press": POST …/$path 를 보내고(확인 대화상자: $asks) 토스트 "$toast"로 알린 뒤 문의를 다시 불러와 버튼이 바뀐다',
    async ({ from, press, asks, path, toast, not, after }) => {
      state.issue = from;
      const { user } = renderAt(URL_7);
      await user.click(await button(press));

      if (asks !== null) {
        const box = await confirmBox();
        expect(text(box)).toContain(asks);
        expect(callsTo('POST', `${ISSUE_7}/${path}`), '확인 전').toHaveLength(0);
        await user.click(confirmButton(box));
      }
      const [call] = await sent('POST', `${ISSUE_7}/${path}`);
      expect(await call?.body, '본문 없음').toBeUndefined();
      // A confirm dialog may stay open, blocked, until the answer comes.
      if (asks === null) expect(openDialog(), '묻지 않고 바로 한다').toBeNull();
      else await closed();
      await said(toast);
      if (not !== null) expectNotSaid(not);
      await eventually(() => {
        expect(actionsShown()).toEqual(after);
      });
    },
  );

  const changed: { code: string; from: Issue; press: string; path: string; now: Issue; after: string[] }[] = [
    { code: 'ISSUE_CLOSED', from: issueIn('RECEIVED'), press: '문의 종료', path: 'close', now: issueIn('CLOSED'), after: ['다시 열기'] },
    { code: 'NOT_REOPENABLE', from: issueIn('CLOSED'), press: '다시 열기', path: 'reopen', now: issueIn('CLOSED', { reopenable: false }), after: [] },
  ];
  test.each(changed)('S3-AC-82 P-5 400 $code: 토스트 "문의 상태가 이미 바뀌었습니다"와 함께 문의를 다시 불러와 버튼이 새 상태에 맞게 바뀐다', async ({ code, from, press, path, now, after }) => {
    state.issue = from;
    on('post', `${ISSUE_7}/${path}`, () => {
      // Somebody else changed it first.
      state.issue = now;
      return rejected(code);
    });
    const { user } = renderAt(URL_7);
    await user.click(await button(press));
    await user.click(confirmButton(await confirmBox()));

    await said(CHANGED_ALREADY);
    await eventually(() => {
      expect(actionsShown()).toEqual(after);
    });
    await closed();
  });

  test('S3-AC-82 P-5 403: 보는 사이 소속에서 빠졌으면 권한 띠를 보이고 문의를 다시 불러와 동작이 줄어든다', async () => {
    state.issue = issueIn('CLOSED');
    on('post', `${ISSUE_7}/reopen`, () => {
      state.companies = [BETA];
      return errors.forbidden();
    });
    const { user } = renderAt(URL_7);
    await user.click(await button('다시 열기'));
    const reads = issueReads();
    await user.click(confirmButton(await confirmBox()));

    await alertWith(/권한이 없습니다/);
    await eventually(() => {
      expect(issueReads(), '문의를 다시 부름').toBeGreaterThan(reads);
      expect(actionsShown()).toEqual([]);
    });
    expectNotSaid('문의를 다시 열었습니다');
    await closed();
  });

  test('S3-AC-87 P-5 늦게 온 답: 다시 여는 중에 다른 문의로 옮기면 대화상자가 닫히고, 뒤에 온 답은 토스트를 띄우지 않으며 옮겨 간 문의가 그대로 보인다', async () => {
    state.issue = issueIn('RESOLVED');
    const other = issueIn('RECEIVED', { key: 'ACME-9', title: '다른 문의' });
    route('get', `${ISSUES}/ACME-9`, () => HttpResponse.json(other));
    route('get', `${ISSUES}/ACME-9/comments`, () => HttpResponse.json(paged([])));
    route('get', `${ISSUES}/ACME-9/attachments`, () => HttpResponse.json(paged([])));
    const release = gate();
    route('post', `${ISSUE_7}/reopen`, async () => {
      await release.opened;
      return rejected('NOT_REOPENABLE');
    });
    // ACME-9 → 목록 → ACME-7, so that two steps back is the other issue.
    const { user } = renderAt('/issues/ACME-9');
    await titleShown('다른 문의');
    await user.click(await found(() => screen.queryAllByRole('link').find((a) => hrefPath(a) === '/issues'), '문의 목록으로 가는 링크'));
    await user.click(await link(/ACME-7/));
    await titleShown();

    await user.click(await button('다시 열기'));
    await user.click(confirmButton(await confirmBox()));
    await sent('POST', `${ISSUE_7}/reopen`);
    window.history.go(-2);
    await atPath('/issues/ACME-9');
    await titleShown('다른 문의');
    await closed();

    const done = answered('POST', `${ISSUE_7}/reopen`);
    release.open();
    await done;
    await settle();

    expectNotSaid(CHANGED_ALREADY);
    expect(openDialog()).toBeNull();
    expect(window.location.pathname).toBe('/issues/ACME-9');
    expect(text(screen.getByRole('heading', { level: 1 }))).toBe('다른 문의');
    expect(actionsShown(), '옮겨 간 문의의 동작').toEqual(['고치기', '문의 종료']);
  });

  test('P-5 axe (3번 묶음): 해결 띠와 동작이 있는 화면, 열린 확인 대화상자에 접근성 위반이 없다', async () => {
    state.issue = issueIn('RESOLVED', { filedBySupport: true });
    const { user } = renderAt(URL_7);
    await button('해결 확인');
    await expectNoAxeViolations();

    await user.click(await button('다시 열기'));
    await confirmBox();
    await expectNoAxeViolations();
  });
});
