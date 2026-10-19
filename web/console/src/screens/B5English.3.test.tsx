// b5 묶음 ③ 몫(acceptance.md 4절 7): 콘솔 문의 쪽 C-3, C-4, C-7, C-15, C-19를 영어로 연다(B5-AC-05).
// 영어 글은 구현 탭이 짓는다(4절 6). 그래서 영어 문구로 찾지 않는다: 칸과 단추는 꼴(type, form, role)로,
// 데이터는 영어 견본으로 찾고, 단추 이름이 꼭 필요하면 "지금 한국어 또는 용어표(screens.md 3절)의 영어"로 찾는다.
// 견본은 모두 영어다: 앞 도움 파일의 빌더에 영어 값을 넘겨 만들고, 앞 묶음의 상태(s3, s4, s6a, s6b, s6c)를 영어로 덮는다.
import { HttpResponse } from 'msw/http';
import { beforeEach, describe, expect, test } from 'vitest';
import { errors, eventually, found, heading1, on, renderAt, screen, sent, state, text, within } from '../__tests__/console';
import { route } from '../__tests__/console1a';
import { history, issueIn, s3, type CompanyReporter } from '../__tests__/console3';
import { notification, alertItem, s4, type Notification } from '../__tests__/console4';
import { label, refOf, s6a } from '../__tests__/console6a';
import { doneTask, myTask, note, s6b, task } from '../__tests__/console6b';
import { linkTo, s6c, seedLinks } from '../__tests__/console6c';
import { goal, sla, withSla } from '../__tests__/console8a';
import {
  ADMIN_EN,
  ADMIN_PERSON_EN,
  AGENT_EN,
  AGENT_PERSON_EN,
  ACME_EN,
  BETA_EN,
  CUSTOMER_PERSON_EN,
  ISSUE_TITLE_EN,
  OTHER_AGENT_PERSON_EN,
  attachmentEn,
  commentEn,
  expectEnglishPage,
  expectNoHangul,
  issueEn,
  openInEnglish,
  summaryEn,
} from '../__tests__/consoleB5';

type User = ReturnType<typeof renderAt>['user'];

const URL_7 = '/issues/ACME-7';

// ---------------------------------------------------------------------------
// English data for the earlier slices' fake servers

const KIM_EN: CompanyReporter = { accountId: 11, name: 'Kim Customer', email: 'kim@acme.test', photoUpdatedAt: null };
const CHOI_EN: CompanyReporter = { accountId: 12, name: 'Choi Colleague', email: 'choi@acme.test', photoUpdatedAt: null };
const BAE_EN: CompanyReporter = { accountId: 41, name: 'Bae Beta', email: 'bae@beta.test', photoUpdatedAt: null };

const URGENT_EN = label({ id: 61, name: 'Urgent', color: '#C0262D', issueCount: 3 });
const BILLING_EN = label({ id: 62, name: 'Billing', color: '#245FB0', issueCount: 1 });
const BETA_LABEL_EN = label({ id: 71, name: 'Beta only', color: '#1F7A3A', issueCount: 2 });

const BETA_3_EN = issueEn({ key: 'BETA-3', title: 'Charged twice', status: 'IN_PROGRESS', companyCode: 'BETA', companyName: BETA_EN.name, nextStatuses: ['RESOLVED'] });
const ACME_2_EN = issueEn({ key: 'ACME-2', title: 'Reset mail does not arrive', status: 'CLOSED', closedAt: '2026-10-08T03:15:00Z' });

const LOGS_TASK = task({ id: 41, title: 'Collect logs', assignee: AGENT_PERSON_EN, dueDate: '2026-10-01', overdue: true, createdBy: ADMIN_PERSON_EN, notes: [note({ author: AGENT_PERSON_EN, content: 'Got the logs from the customer.' })] });
const CALL_TASK = doneTask({ id: 42, title: 'Call the customer', createdBy: ADMIN_PERSON_EN, doneBy: ADMIN_PERSON_EN });

const MY_LOGS = myTask({ id: 41, title: 'Collect logs', dueDate: '2026-10-01', overdue: true, issue: { key: 'ACME-7', title: ISSUE_TITLE_EN, status: 'IN_PROGRESS' }, companyCode: 'ACME', companyName: ACME_EN.name });
const MY_CALL = myTask({ id: 52, title: 'Call the customer', dueDate: '2099-10-20', issue: { key: 'BETA-3', title: 'Charged twice', status: 'CONFIRMED' }, companyCode: 'BETA', companyName: BETA_EN.name });

beforeEach(() => {
  openInEnglish();
  s3.candidates = [ADMIN_PERSON_EN, AGENT_PERSON_EN, OTHER_AGENT_PERSON_EN];
  s3.reporters = { ACME: [KIM_EN, CHOI_EN], BETA: [BAE_EN] };
  s3.history = [];
  s4.inbox = [];
  s4.candidates = { public: [ADMIN_PERSON_EN, OTHER_AGENT_PERSON_EN, CUSTOMER_PERSON_EN], internal: [ADMIN_PERSON_EN, OTHER_AGENT_PERSON_EN] };
  s6a.labels = { ACME: [URGENT_EN, BILLING_EN], BETA: [BETA_LABEL_EN] };
  s6b.tasks = {};
  s6b.mine = [];
  s6c.links = {};
  s6c.others = [BETA_3_EN, ACME_2_EN];
});

/** ACME-7 in `status`, with the English title, content, reporter and company. */
function enIn(status: Parameters<typeof issueIn>[0], over: Parameters<typeof issueIn>[1] = {}) {
  const { title, content, reporter, companyName } = issueEn();
  return issueIn(status, { title, content, reporter, companyName, ...over });
}

/** A full ACME-7: comments public and internal, files, labels, tasks, a link, history, and an SLA. */
function richIssue() {
  state.issue = enIn('IN_PROGRESS', {
    assignee: AGENT_PERSON_EN,
    labels: [refOf(URGENT_EN), refOf(BILLING_EN)],
    dueDate: '2026-10-20',
    firstResponseAt: '2026-10-08T04:00:00Z',
    sla: sla(goal('MET', null), goal('WARNING', 50, '2026-10-11T03:15:00Z')),
  });
  state.comments = [
    commentEn({ id: 101 }),
    commentEn({ id: 102, author: AGENT_PERSON_EN, content: 'We are looking into it.' }),
    commentEn({ id: 103, author: ADMIN_PERSON_EN, content: 'Check the proxy logs first.', internal: true }),
  ];
  state.attachments = [attachmentEn(), attachmentEn({ id: '0b5e9d42-6f1a-4c3b-8d27-1e9f4a6c2b10', fileName: 'screen.png', contentType: 'image/png', uploadedBy: AGENT_PERSON_EN, commentId: 102 })];
  s6b.tasks = { 'ACME-7': [LOGS_TASK, CALL_TASK] };
  seedLinks([linkTo(BETA_3_EN)]);
  s3.history = [
    history({ id: 1, actor: AGENT_PERSON_EN }),
    history({ id: 2, field: 'ASSIGNEE', oldValue: null, newValue: null, oldPerson: null, newPerson: AGENT_PERSON_EN, actor: ADMIN_PERSON_EN }),
    history({ id: 3, field: 'PRIORITY', oldValue: 'NORMAL', newValue: 'HIGH', actor: ADMIN_PERSON_EN }),
  ];
}

// ---------------------------------------------------------------------------
// Finding things without English words

const detailShown = () => found(() => screen.queryByRole('heading', { level: 1, name: ISSUE_TITLE_EN }), `문의 제목 "${ISSUE_TITLE_EN}"`);

/** Waits until the page holds `part` (English data). */
async function shows(part: string) {
  await eventually(() => {
    expect(text(document.body)).toContain(part);
  });
}

/** The comment form: the form with the public / internal choice and a text area. */
const commentForm = () =>
  found(() => Array.from(document.querySelectorAll('form')).find((f) => f.querySelector('textarea') && f.querySelector('input[type="radio"]')), '댓글 쓰기 form');

/** The submit button of `form`: `type="submit"`, or else its last button. */
const submitOf = (form: HTMLElement) =>
  found(() => form.querySelector<HTMLButtonElement>('button[type="submit"]') ?? within(form).queryAllByRole('button').at(-1), 'form의 보내기 단추');

/** "담당자 바꾸기" now, "Change" or "Change assignee" in English. */
const ASSIGNEE_OPENER = /^((담당자 )?바꾸기|change( assignee)?)$/i;
/** "종료" now, "Close" in English (용어표: closed). */
const CLOSE_ACTION = /^(종료|close( issue)?)$/i;

async function openDialogBy(user: User, name: RegExp): Promise<HTMLElement> {
  await user.click(await found(() => screen.queryAllByRole('button', { name })[0], `단추 ${String(name)}`));
  return found(() => screen.queryByRole('dialog') ?? screen.queryByRole('alertdialog'), '대화상자');
}

/** The texts an element points to with `aria-describedby`. */
function describedBy(el: Element): string {
  return (el.getAttribute('aria-describedby') ?? '')
    .split(/\s+/)
    .filter(Boolean)
    .map((id) => text(document.getElementById(id)))
    .join(' ');
}

// ---------------------------------------------------------------------------

describe('B5 ③ C-3 문의 목록을 영어로', () => {
  test.each([
    ['관리자', ADMIN_EN],
    ['상담원', AGENT_EN],
  ] as const)('B5-AC-05 C-3 목록 있음(%s): 표, 열 머리, 상태·우선순위 말, 필터 줄, 머리와 옆 메뉴에 한글이 없다', async (_who, me) => {
    openInEnglish(me);
    state.issues = [
      summaryEn({ assignee: AGENT_PERSON_EN, labels: [refOf(URGENT_EN)], dueDate: '2026-10-01', overdue: true, openTaskCount: 2 }),
      summaryEn({ key: 'ACME-8', title: 'Export button does nothing', status: 'RESOLVED', priority: 'URGENT', filedOnBehalf: true }),
      withSla('ACME-9', sla(goal('BREACHED', -30), goal('OK', 300)), { title: 'Slow dashboard', status: 'CONFIRMED', priority: 'HIGH', reporter: CUSTOMER_PERSON_EN, companyName: ACME_EN.name }),
      summaryEn({ key: 'BETA-3', title: 'Charged twice', companyCode: 'BETA', companyName: BETA_EN.name, status: 'IN_PROGRESS', priority: 'LOW', private: true }),
    ];
    renderAt('/issues');
    await found(() => screen.queryByText(ISSUE_TITLE_EN), '견본 문의');
    await shows('Charged twice');

    expectNoHangul('C-3 목록 있음');
    expectEnglishPage('C-3 목록 있음');
  });

  test('B5-AC-05 C-3 빈 목록: 빈 문구에 한글이 없다', async () => {
    state.issues = [];
    renderAt('/issues?view=all');
    await heading1();
    await found(() => (document.querySelector('table tbody tr') ? null : document.querySelector('main')), '빈 목록');
    await eventually(() => {
      expect(text(document.querySelector('main'))).not.toBe('');
    });

    expectNoHangul('C-3 빈 목록');
  });

  test('B5-AC-05 C-3 필터를 고른 상태(고객사 ACME, 상태, 우선순위, 라벨): 필터 줄과 걸린 값에 한글이 없다', async () => {
    renderAt('/issues?view=all&company=ACME&status=IN_PROGRESS&priority=HIGH&label=61&q=sign');
    await found(() => screen.queryByText(ISSUE_TITLE_EN), '견본 문의');
    await shows('Acme');

    expectNoHangul('C-3 필터');
  });

  test('B5-AC-05 C-3 목록을 읽지 못함: 오류 띠에 한글이 없다', async () => {
    on('get', '/api/console/issues', () => errors.internal());
    renderAt('/issues');
    await found(() => screen.queryAllByRole('alert').find((el) => text(el) !== ''), '오류 띠');

    expectNoHangul('C-3 오류 띠');
  });
});

describe('B5 ③ C-4 문의 상세를 영어로', () => {
  test.each([
    ['관리자', ADMIN_EN],
    ['상담원', AGENT_EN],
  ] as const)(
    'B5-AC-05 C-4 다 있는 상세(%s): 머리 값, 댓글·내부 메모, 첨부, 라벨, 작업, 연결, 이력, SLA 배지에 한글이 없다',
    async (_who, me) => {
      openInEnglish(me);
      richIssue();
      renderAt(URL_7);
      await detailShown();
      await shows('Check the proxy logs first.');
      await shows('Collect logs');
      await shows('Charged twice');
      await shows('screen.png');

      expectNoHangul('C-4 상세');
      expectEnglishPage('C-4 상세');
    },
  );

  test('B5-AC-05 C-4 비어 있는 상세(댓글·첨부·작업·연결 없음, 담당 없음): 빈 문구에 한글이 없다', async () => {
    state.issue = enIn('RECEIVED');
    renderAt(URL_7);
    await detailShown();
    await found(() => document.querySelector('form textarea'), '댓글 쓰기 칸');

    expectNoHangul('C-4 빈 상세');
  });

  test('B5-AC-05 C-4 종료된 문의: 띠와 다시 열기에 한글이 없다', async () => {
    state.issue = enIn('CLOSED', { closedAt: '2026-10-08T03:15:00Z', resolvedAt: '2026-10-08T03:00:00Z' });
    renderAt(URL_7);
    await detailShown();
    await found(() => screen.queryAllByRole('status').concat(screen.queryAllByRole('alert')).find((el) => text(el) !== ''), '종료된 문의 띠');

    expectNoHangul('C-4 종료');
  });

  test('B5-AC-05 C-4 담당자 대화상자: 제목, "담당 없음", 후보와 역할, 단추에 한글이 없다', async () => {
    richIssue();
    const { user } = renderAt(URL_7);
    await detailShown();
    const box = await openDialogBy(user, ASSIGNEE_OPENER);
    await sent('GET', '/api/console/companies/ACME/assignees');
    await found(() => within(box).queryAllByRole('radio').find((r) => text((r as HTMLInputElement).labels?.[0]).includes('Jordan Agent')), '후보 Jordan Agent');

    expectNoHangul('C-4 담당자 대화상자', box);
    expectNoHangul('C-4 담당자 대화상자가 열린 화면');
  });

  test('B5-AC-05 C-4 상태 바꾸기 확인(종료): 확인 대화상자에 한글이 없다', async () => {
    state.issue = enIn('RECEIVED');
    const { user } = renderAt(URL_7);
    await detailShown();
    const box = await openDialogBy(user, CLOSE_ACTION);

    expectNoHangul('C-4 종료 확인', box);
  });

  test('B5-AC-05 C-4 상태를 바꾸지 못함(INVALID_TRANSITION): 오류 띠나 토스트에 한글이 없다', async () => {
    state.issue = enIn('IN_PROGRESS');
    route('put', '/api/console/issues/ACME-7/status', () => HttpResponse.json({ code: 'INVALID_TRANSITION', message: 'This move is not allowed.' }, { status: 400 }));
    const { user } = renderAt(URL_7);
    await detailShown();
    const resolve = await found(() => screen.queryAllByRole('button').find((b) => /^(해결|resolve)/i.test(text(b))), '"해결" 단추');
    await user.click(resolve);
    const box = screen.queryByRole('dialog') ?? screen.queryByRole('alertdialog');
    if (box) await user.click(await submitOf(box));

    await sent('PUT', '/api/console/issues/ACME-7/status');
    await found(() => screen.queryAllByRole('alert').concat(screen.queryAllByRole('status')).find((el) => text(el) !== ''), '실패 띠 또는 토스트');
    expectNoHangul('C-4 상태 실패');
  });

  test('B5-AC-05 C-4 문의를 찾지 못함(404): 안내에 한글이 없다', async () => {
    renderAt('/issues/ACME-404');
    await found(() => screen.queryAllByRole('link').find((a) => a.getAttribute('href') === '/issues'), '문의 목록으로 가는 링크');
    await eventually(() => {
      expect(text(document.querySelector('main'))).not.toBe('');
    });

    expectNoHangul('C-4 404');
  });

  test('B5-AC-05 C-4 댓글을 단 뒤: 새 댓글 줄과 토스트에 한글이 없다', async () => {
    state.issue = enIn('IN_PROGRESS');
    const { user } = renderAt(URL_7);
    await detailShown();
    const form = await commentForm();
    await user.type(form.querySelector('textarea') as HTMLTextAreaElement, 'Thanks, fixed now.');
    await user.click(await submitOf(form));

    await sent('POST', '/api/console/issues/ACME-7/comments');
    await shows('Thanks, fixed now.');
    expectNoHangul('C-4 댓글 단 뒤');
  });

  test('B5-AC-05 C-4 댓글을 달지 못함(500): 쓰기 form의 오류에 한글이 없다', async () => {
    state.issue = enIn('IN_PROGRESS');
    on('post', '/api/console/issues/ACME-7/comments', () => errors.internal());
    const { user } = renderAt(URL_7);
    await detailShown();
    const form = await commentForm();
    await user.type(form.querySelector('textarea') as HTMLTextAreaElement, 'Thanks, fixed now.');
    await user.click(await submitOf(form));

    await sent('POST', '/api/console/issues/ACME-7/comments');
    await found(() => screen.queryAllByRole('alert').find((el) => text(el) !== ''), '댓글 실패 띠');
    expectNoHangul('C-4 댓글 실패');
  });
});

describe('B5 ③ C-7 알림함을 영어로', () => {
  const about = (id: number, over: Partial<Notification>): Notification =>
    notification({ id, issue: { key: 'ACME-7', title: ISSUE_TITLE_EN }, actor: CUSTOMER_PERSON_EN, ...over });

  test.each([
    ['관리자', ADMIN_EN],
    ['상담원', AGENT_EN],
  ] as const)('B5-AC-05 C-7 여러 종류의 알림(%s): 종류마다의 문장, 안 읽음, 시각, 모두 읽음에 한글이 없다', async (_who, me) => {
    openInEnglish(me);
    s4.inbox = [
      about(601, { kind: 'CUSTOMER_COMMENT' }),
      about(602, { kind: 'STAFF_COMMENT', actor: OTHER_AGENT_PERSON_EN, readAt: '2026-10-08T04:00:00Z' }),
      about(603, { kind: 'MENTIONED', actor: ADMIN_PERSON_EN }),
      about(604, { kind: 'ASSIGNED', actor: ADMIN_PERSON_EN, commentId: null }),
      about(605, { kind: 'ISSUE_RESOLVED', actor: AGENT_PERSON_EN, commentId: null }),
      about(606, { kind: 'ISSUE_CLOSED', actor: AGENT_PERSON_EN, commentId: null }),
      about(607, { kind: 'ISSUE_REOPENED', commentId: null }),
      about(608, { kind: 'ISSUE_FILED_ON_BEHALF', actor: AGENT_PERSON_EN, commentId: null }),
      about(609, { kind: 'TASK_ASSIGNED', actor: ADMIN_PERSON_EN, commentId: null, task: { id: 41, title: 'Collect logs' } }),
      about(610, { kind: 'LINKED_ISSUE_RESOLVED', actor: AGENT_PERSON_EN, commentId: null, linkedIssue: { key: 'BETA-3', title: 'Charged twice' } }),
      about(611, { kind: 'SLA_RESPONSE_WARNING', actor: null, commentId: null }),
      about(612, { kind: 'SLA_RESOLUTION_BREACHED', actor: null, commentId: null }),
      alertItem('SLA_RECALCULATED', { id: 613, slaSummary: { becameBreached: 2, becameWarning: 1, released: 3 } }),
      alertItem('MEMBERSHIP_REQUESTED', { id: 614, actor: CUSTOMER_PERSON_EN }),
      alertItem('DELIVERY_FAILED', { id: 615 }),
      alertItem('MAIL_STALLED', { id: 616 }),
      alertItem('ISSUE_RETENTION_ARMED', { id: 617, retentionCount: 4, retentionDays: 90 }),
    ];
    renderAt('/inbox');
    await shows('Charged twice');
    await shows('Collect logs');

    expectNoHangul('C-7 여러 종류');
    expectEnglishPage('C-7 여러 종류');
  });

  test('B5-AC-05 C-7 빈 알림함: 빈 문구에 한글이 없다', async () => {
    renderAt('/inbox');
    await heading1();
    await sent('GET', '/api/console/notifications');
    await eventually(() => {
      expect(text(document.querySelector('main'))).not.toBe(text(screen.queryByRole('heading', { level: 1 })));
    });

    expectNoHangul('C-7 빈 알림함');
  });

  test('B5-AC-05 C-7 알림을 읽지 못함: 오류 띠에 한글이 없다', async () => {
    on('get', '/api/console/notifications', () => errors.internal());
    renderAt('/inbox');
    await found(() => screen.queryAllByRole('alert').find((el) => text(el) !== ''), '오류 띠');

    expectNoHangul('C-7 오류 띠');
  });

  test('B5-AC-05 C-7 여러 쪽: 쪽 넘기기에 한글이 없다', async () => {
    s4.inbox = Array.from({ length: 25 }, (_, i) => about(700 + i, { issue: { key: `ACME-${String(i + 1)}`, title: `Issue number ${String(i + 1)}` } }));
    renderAt('/inbox');
    await shows('Issue number 1');
    await found(() => screen.queryAllByRole('navigation').find((n) => n.closest('main')), '쪽 넘기기');

    expectNoHangul('C-7 여러 쪽');
  });
});

describe('B5 ③ C-15 내 작업을 영어로', () => {
  test.each([
    ['관리자', ADMIN_EN],
    ['상담원', AGENT_EN],
  ] as const)('B5-AC-05 C-15 할 일이 있음(%s): 두 보기, 표의 열, 늦음 표시, 완료 단추에 한글이 없다', async (_who, me) => {
    openInEnglish(me);
    s6b.mine = [MY_LOGS, MY_CALL];
    renderAt('/tasks');
    await shows('Collect logs');
    await shows('Charged twice');

    expectNoHangul('C-15 할 일');
    expectEnglishPage('C-15 할 일');
  });

  test.each([
    ['할 일', '/tasks'],
    ['끝낸 일', '/tasks?status=DONE'],
  ])('B5-AC-05 C-15 %s이 빔: 빈 문구에 한글이 없다', async (_view, path) => {
    renderAt(path);
    await heading1();
    await sent('GET', '/api/console/tasks');
    await eventually(() => {
      expect(text(document.querySelector('main')), '줄이 없다').not.toContain('Collect logs');
      expect(text(document.querySelector('main'))).not.toBe(text(screen.queryByRole('heading', { level: 1 })));
    });

    expectNoHangul('C-15 빔');
  });

  test('B5-AC-05 C-15 완료하지 못함: 오류에 한글이 없다', async () => {
    s6b.mine = [MY_LOGS];
    route('put', '/api/console/issues/ACME-7/tasks/41/status', () => errors.internal());
    route('patch', '/api/console/issues/ACME-7/tasks/41', () => errors.internal());
    const { user } = renderAt('/tasks');
    await shows('Collect logs');
    const row = await found(() => screen.queryAllByRole('row').find((r) => text(r).includes('Collect logs')), '"Collect logs" 줄');
    await user.click(await found(() => within(row).queryAllByRole('button')[0], '줄의 단추'));

    await found(() => screen.queryAllByRole('alert').concat(screen.queryAllByRole('status')).find((el) => text(el) !== ''), '실패 띠 또는 토스트');
    expectNoHangul('C-15 완료 실패');
  });

  test('B5-AC-05 C-15 목록을 읽지 못함: 오류 띠에 한글이 없다', async () => {
    on('get', '/api/console/tasks', () => errors.internal());
    renderAt('/tasks');
    await found(() => screen.queryAllByRole('alert').find((el) => text(el) !== ''), '오류 띠');

    expectNoHangul('C-15 오류 띠');
  });
});

describe('B5 ③ C-19 문의 등록(대리 등록)을 영어로', () => {
  const companySelect = () => found(() => document.querySelector<HTMLSelectElement>('main select'), '고객사 칸');
  const titleBox = () => found(() => document.querySelector<HTMLInputElement>('main form input[type="text"]:not([role="searchbox"]):not([type="search"])'), '제목 칸');

  test.each([
    ['관리자', ADMIN_EN],
    ['상담원', AGENT_EN],
  ] as const)('B5-AC-05 C-19 처음(%s): 칸 이름, 받은 경로, 우선순위, 비공개, 등록 단추에 한글이 없다', async (_who, me) => {
    openInEnglish(me);
    renderAt('/issues/new');
    await companySelect();
    await found(() => document.querySelector('main form textarea'), '내용 칸');
    await found(() => document.querySelector('main input[type="radio"]'), '받은 경로·우선순위');

    expectNoHangul('C-19 처음');
    expectEnglishPage('C-19 처음');
  });

  test('B5-AC-05 C-19 고객 찾기: 고객사를 고르면 고객 줄과 대리 등록 안내에 한글이 없다', async () => {
    const { user } = renderAt('/issues/new');
    await user.selectOptions(await companySelect(), 'ACME');
    await sent('GET', '/api/console/companies/ACME/reporters');
    const kim = await found(
      () =>
        screen.queryAllByRole<HTMLInputElement>('radio').find((r) => text(r.labels?.[0]).includes('Kim Customer')) ??
        screen.queryAllByRole('option').find((o) => text(o).includes('Kim Customer')),
      '고객 Kim Customer',
    );
    await user.click(kim);
    await user.type(await found(() => screen.queryByRole<HTMLInputElement>('searchbox'), '고객 찾기 칸'), 'Kim');
    await shows('kim@acme.test');

    expectNoHangul('C-19 고객 찾기');
  });

  test('B5-AC-05 C-19 고객을 찾지 못함(빈 결과): 빈 문구에 한글이 없다', async () => {
    s3.reporters = { ACME: [], BETA: [BAE_EN] };
    const { user } = renderAt('/issues/new');
    await user.selectOptions(await companySelect(), 'ACME');
    await sent('GET', '/api/console/companies/ACME/reporters');
    await eventually(() => {
      expect(text(document.querySelector('main'))).not.toContain('Kim Customer');
    });

    expectNoHangul('C-19 고객 없음');
  });

  test('B5-AC-05 C-19 칸 오류: 고객을 고르지 않고 보내면 칸 아래 오류와 오류 요약에 한글이 없다', async () => {
    const { user } = renderAt('/issues/new');
    await user.selectOptions(await companySelect(), 'ACME');
    await user.type(await titleBox(), 'Printer offline');
    const said = () => Array.from(document.querySelectorAll('main [aria-describedby]')).map(describedBy).join(' ');
    const before = said();
    const form = await found(() => document.querySelector<HTMLElement>('main form'), '등록 form');
    await user.click(await submitOf(form));

    await eventually(() => {
      expect(said(), '칸 아래의 오류').not.toBe(before);
    });
    expectNoHangul('C-19 칸 오류');
  });

  test('B5-AC-05 C-19 400 칸 오류(서버): 제목이 너무 김을 칸 아래에 보일 때 한글이 없다', async () => {
    on('post', '/api/console/issues', () => errors.invalid([{ field: 'title', code: 'TOO_LONG' }], 'Some fields are invalid.'));
    const { user } = renderAt('/issues/new');
    await user.selectOptions(await companySelect(), 'ACME');
    await user.click(await found(() => screen.queryAllByRole<HTMLInputElement>('radio').find((r) => text(r.labels?.[0]).includes('Kim Customer')) ?? screen.queryAllByRole('option').find((o) => text(o).includes('Kim Customer')), '고객 Kim Customer'));
    await user.type(await titleBox(), 'Printer offline');
    const form = await found(() => document.querySelector<HTMLElement>('main form'), '등록 form');
    await user.click(await submitOf(form));

    await sent('POST', '/api/console/issues');
    await found(() => document.querySelector('[aria-invalid="true"]'), '틀린 칸');
    expectNoHangul('C-19 서버 칸 오류');
  });

  test('B5-AC-05 C-19 등록 실패(500): 오류 띠에 한글이 없다', async () => {
    on('post', '/api/console/issues', () => errors.internal());
    const { user } = renderAt('/issues/new');
    await user.selectOptions(await companySelect(), 'ACME');
    await user.click(await found(() => screen.queryAllByRole<HTMLInputElement>('radio').find((r) => text(r.labels?.[0]).includes('Kim Customer')) ?? screen.queryAllByRole('option').find((o) => text(o).includes('Kim Customer')), '고객 Kim Customer'));
    await user.type(await titleBox(), 'Printer offline');
    const form = await found(() => document.querySelector<HTMLElement>('main form'), '등록 form');
    await user.click(await submitOf(form));

    await sent('POST', '/api/console/issues');
    await found(() => screen.queryAllByRole('alert').find((el) => text(el) !== ''), '오류 띠');
    expectNoHangul('C-19 등록 실패');
  });
});

