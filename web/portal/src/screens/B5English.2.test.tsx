// b5 묶음 ② 몫(acceptance.md 4절 7): 포털 P-3, P-4, P-5, P-7, P-8을 영어로 연다(B5-AC-05).
// 화면마다 상태 여러 개(목록 있음·빔, 상세, 대화상자, 오류 띠, 토스트)를 열고 한글이 없는지 본다.
// 영어 글은 구현 탭이 짓는다(4절 6). 그래서 영어 문구로 찾지 않는다: 칸과 단추는 꼴(type, form, role)로,
// 데이터는 영어 견본으로 찾고, 단추 이름이 꼭 필요하면 "지금 한국어 또는 용어표의 영어"로 찾는다.
import { HttpResponse } from 'msw/http';
import { describe, expect, test } from 'vitest';
import { MB, errors, eventually, file, found, liveRegions, on, paged, renderAt, screen, sent, state, text, within, type Issue } from '../__tests__/portal';
import { rejected, route, session } from '../__tests__/portal1a';
import { membersPath, people, person, type Member } from '../__tests__/portal2';
import { CLOSED_AT, RESOLVED_AT } from '../__tests__/portal3';
import { SUPPORT_ACTOR, deletedComment, notification, s4, type Notification } from '../__tests__/portal4';
import '../__tests__/portal5';
import {
  ACME_EN,
  BETA_EN,
  COLLEAGUE_EN,
  ISSUE_TITLE_EN,
  ME_EN,
  ME_PERSON_EN,
  attachmentEn,
  commentEn,
  expectEnglishPage,
  expectNoHangul,
  issueEn,
  openInEnglish,
  summaryEn,
} from '../__tests__/portalB5';

type User = ReturnType<typeof renderAt>['user'];

const SUPPORT_TEAM_PERSON = { accountId: null, kind: 'SUPPORT_TEAM', name: null, photoUpdatedAt: null } as const;

/** An issue in `status` with the times that go with it, as the server gives it (portal3.ts `issueIn`, in English). */
function issueInEn(status: Issue['status'], over: Partial<Issue> = {}): Issue {
  return issueEn({
    status,
    resolvedAt: status === 'RESOLVED' ? RESOLVED_AT : null,
    closedAt: status === 'CLOSED' ? CLOSED_AT : null,
    reopenable: status === 'RESOLVED' || status === 'CLOSED',
    ...over,
  });
}

/** Waits until `query` gives at least `count` elements and returns them. */
async function atLeast<T extends Element>(query: () => T[], count: number, what: string): Promise<T[]> {
  let got: T[] = [];
  await eventually(() => {
    got = query();
    expect(got.length, what).toBeGreaterThanOrEqual(count);
  });
  return got;
}

const main = () => found(() => screen.queryByRole('main'), '본문(main)');
const dialogOpen = () => found(() => screen.queryByRole('dialog') ?? screen.queryByRole('alertdialog'), '대화상자');
const anyAlert = () => found(() => screen.queryAllByRole('alert').find((el) => text(el) !== ''), '오류 띠');
const anyToast = () => found(() => liveRegions().find((el) => el.getAttribute('role') === 'status' && text(el) !== ''), '토스트');
const submitOf = (root: ParentNode) => found(() => root.querySelector<HTMLButtonElement>('button[type="submit"]'), '보내기 단추');
/** A button found by its Korean name today or its English name of the glossary (screens.md 3절). */
const buttonNamed = (name: RegExp) => found(() => screen.queryAllByRole('button', { name })[0], `단추 ${String(name)}`);

// ---------------------------------------------------------------------------
// P-3

describe('B5 ② 포털 P-3 문의 목록을 영어로', () => {
  test.each<[string, () => void]>([
    [
      '데이터 있음(다섯 상태, 네 우선순위, 동료·지원팀이 올림, 비공개)',
      () => {
        state.issues = [
          summaryEn({ key: 'ACME-7', status: 'RECEIVED', priority: 'LOW' }),
          summaryEn({ key: 'ACME-6', status: 'CONFIRMED', priority: 'NORMAL', reporter: COLLEAGUE_EN }),
          summaryEn({ key: 'ACME-5', status: 'IN_PROGRESS', priority: 'HIGH', filedBySupport: true }),
          summaryEn({ key: 'ACME-4', status: 'RESOLVED', priority: 'URGENT', private: true, resolvedAt: RESOLVED_AT }),
          summaryEn({ key: 'ACME-3', status: 'CLOSED', closedAt: CLOSED_AT }),
        ];
      },
    ],
    [
      '쪽 넘기기가 있는 목록',
      () => {
        on('get', '/api/portal/issues', () => HttpResponse.json(paged([summaryEn()], { page: 1, size: 20, totalElements: 45, totalPages: 3 })));
      },
    ],
  ])('B5-AC-05 P-3 %s: 한글이 없다', async (what, given) => {
    openInEnglish();
    given();
    renderAt('/issues');
    await found(() => screen.queryAllByText(ISSUE_TITLE_EN)[0], '견본 문의');

    await eventually(() => {
      expectNoHangul(`P-3 ${what}`);
    });
    expectEnglishPage(`P-3 ${what}`);
  });

  test('B5-AC-05 P-3 빈 목록: 안내와 "문의 올리기"도 영어다', async () => {
    openInEnglish();
    state.issues = [];
    renderAt('/issues');
    await found(() => document.querySelector('main a[href="/issues/new"]'), '문의 올리기 링크');

    await eventually(() => {
      expectNoHangul('P-3 빈 목록');
    });
  });

  test('B5-AC-05 P-3 소속 없음: 안내도 영어다', async () => {
    openInEnglish({ ...ME_EN, companies: [] });
    state.issues = [];
    renderAt('/issues');
    await main();
    await found(() => screen.queryByRole('heading', { level: 1 }), '화면 제목');

    await eventually(() => {
      expect(text(document.querySelector('main')).length, '안내가 그려짐').toBeGreaterThan(0);
      expectNoHangul('P-3 소속 없음');
    });
  });

  test('B5-AC-05 P-3 목록을 받지 못함(500): 오류 띠와 다시 불러오기도 영어다', async () => {
    openInEnglish();
    on('get', '/api/portal/issues', () => errors.internal());
    renderAt('/issues');
    await anyAlert();

    expectNoHangul('P-3 500');
  });
});

// ---------------------------------------------------------------------------
// P-4

const titleInput = () => found(() => document.querySelector<HTMLInputElement>('main form input[type="text"]'), '제목 칸');
const contentInput = () => found(() => document.querySelector<HTMLTextAreaElement>('main form textarea'), '내용 칸');

async function fillIssue(user: User) {
  await user.type(await titleInput(), 'The printer does not answer');
  await user.type(await contentInput(), 'The office printer stopped answering this morning.');
}

describe('B5 ② 포털 P-4 문의 등록을 영어로', () => {
  test.each<[string, () => void]>([
    ['소속이 하나', () => undefined],
    [
      '소속이 둘(고객사 고르기)',
      () => {
        session.me = { ...ME_EN, companies: [ACME_EN, BETA_EN] };
        state.companies = [ACME_EN, BETA_EN];
      },
    ],
  ])('B5-AC-05 P-4 처음(%s): 칸 이름, 우선순위, 비공개 설명, 첨부, 단추에 한글이 없다', async (what, given) => {
    openInEnglish();
    given();
    renderAt('/issues/new');
    await titleInput();
    await contentInput();
    await found(() => document.querySelector('main input[type="file"]'), '첨부 칸');

    await eventually(() => {
      expectNoHangul(`P-4 처음 ${what}`);
    });
    expectEnglishPage(`P-4 처음 ${what}`);
  });

  test('B5-AC-05 P-4 400 INVALID_INPUT(제목·내용이 빔): 칸 오류와 오류 요약도 영어다', async () => {
    openInEnglish();
    on('post', '/api/portal/issues', () =>
      errors.invalid([
        { field: 'title', code: 'REQUIRED' },
        { field: 'content', code: 'REQUIRED' },
      ]),
    );
    const { user } = renderAt('/issues/new');
    const form = (await titleInput()).closest('form') as HTMLFormElement;
    await user.click(await submitOf(form));

    await sent('POST', '/api/portal/issues');
    await found(() => document.querySelector('[aria-invalid="true"]'), '틀린 칸');
    expectNoHangul('P-4 칸 오류');
  });

  test('B5-AC-05 P-4 첨부를 고름: 파일 줄(이름, 크기, 지우기)과 고를 때 막힌 파일의 까닭도 영어다', async () => {
    openInEnglish();
    const { user } = renderAt('/issues/new');
    // 내용 편집칸에도 그림 고르기(accept image/…)가 있다. 첨부 칸은 그 밖의 파일 칸이다.
    const input = await found(
      () => Array.from(document.querySelectorAll<HTMLInputElement>('main input[type="file"]')).find((el) => !(el.getAttribute('accept') ?? '').startsWith('image')),
      '첨부 칸',
    );
    await user.upload(input, [file('log.txt', 3 * 1024), file('huge.zip', 10 * MB + 1, 'application/zip'), file('empty.txt', 0)]);

    await eventually(() => {
      expect(text(document.body)).toContain('huge.zip');
      expect(text(document.body)).toContain('empty.txt');
    });
    expectNoHangul('P-4 첨부 고름');
  });

  test('B5-AC-05 P-4 400 고객사 ACTIVE 아님: 오류 띠도 영어다', async () => {
    openInEnglish();
    on('post', '/api/portal/issues', () => HttpResponse.json({ code: 'COMPANY_NOT_ACTIVE', message: 'The company is not active.' }, { status: 400 }));
    const { user } = renderAt('/issues/new');
    await fillIssue(user);
    await user.click(await submitOf((await titleInput()).closest('form') as HTMLFormElement));

    await anyAlert();
    expectNoHangul('P-4 COMPANY_NOT_ACTIVE');
  });

  test('B5-AC-05 P-4 성공: P-5로 가고 토스트도 영어다', async () => {
    openInEnglish();
    on('post', '/api/portal/issues', () => {
      state.issue = issueEn({ key: 'ACME-8' });
      return HttpResponse.json(state.issue, { status: 201 });
    });
    const { user } = renderAt('/issues/new');
    await fillIssue(user);
    await user.click(await submitOf((await titleInput()).closest('form') as HTMLFormElement));

    await eventually(() => {
      expect(window.location.pathname).toBe('/issues/ACME-8');
    });
    await anyToast();
    await found(() => screen.queryByRole('heading', { level: 1, name: ISSUE_TITLE_EN }), '새 문의의 제목');
    expectNoHangul('P-4 성공 뒤 P-5와 토스트');
  });
});

// ---------------------------------------------------------------------------
// P-5

const URL_7 = '/issues/ACME-7';
const titleShown = () => found(() => screen.queryByRole('heading', { level: 1, name: ISSUE_TITLE_EN }), '문의 제목');

describe('B5 ② 포털 P-5 문의 상세를 영어로', () => {
  test.each<[string, Issue]>([
    ['접수된 내 문의', issueInEn('RECEIVED')],
    ['대리 등록한 해결된 내 문의(해결 띠, 해결 확인·다시 열기)', issueInEn('RESOLVED', { filedBySupport: true, priority: 'URGENT' })],
    ['종료된 동료의 비공개가 아닌 문의', issueInEn('CLOSED', { reporter: COLLEAGUE_EN, priority: 'LOW' })],
    ['처리 중인 비공개 문의', issueInEn('IN_PROGRESS', { private: true, priority: 'HIGH' })],
  ])('B5-AC-05 P-5 %s: 머리, 동작, 댓글 쓰기에 한글이 없다', async (what, given) => {
    openInEnglish();
    state.issue = given;
    renderAt(URL_7);
    await titleShown();
    await found(() => document.querySelector('textarea') ?? (given.status === 'CLOSED' ? document.querySelector('main') : null), '댓글 쓰기 또는 본문');

    await eventually(() => {
      expectNoHangul(`P-5 ${what}`);
    });
    expectEnglishPage(`P-5 ${what}`);
  });

  test('B5-AC-05 P-5 댓글과 첨부가 있음: 지원팀 댓글, 내 댓글(고침), 지운 댓글, 첨부 목록과 지운 첨부도 영어다', async () => {
    openInEnglish();
    state.comments = [
      commentEn({ id: 11, author: SUPPORT_TEAM_PERSON, content: 'We are looking into it.' }),
      commentEn({ id: 12, content: 'Thanks, it still fails.', editedAt: '2026-10-09T01:02:03Z' }),
      deletedComment({ id: 13, author: ME_PERSON_EN }),
    ];
    state.attachments = [
      attachmentEn({ id: '7f1c2a9e-0c4b-4d8e-9a51-3b2f6c1d0e01', fileName: 'log.txt', size: 2048 }),
      attachmentEn({ id: '7f1c2a9e-0c4b-4d8e-9a51-3b2f6c1d0e02', fileName: 'screen.png', contentType: 'image/png', size: 3 * 1024 * 1024 }),
      attachmentEn({ id: '7f1c2a9e-0c4b-4d8e-9a51-3b2f6c1d0e03', fileName: null, deleted: true, deletedAt: '2026-10-10T04:05:06Z', deletedBy: SUPPORT_TEAM_PERSON }),
    ];
    renderAt(URL_7);
    await titleShown();
    await found(() => screen.queryAllByText(/We are looking into it/)[0], '지원팀 댓글');
    await found(() => screen.queryAllByText(/screen\.png/)[0], '첨부 줄');

    await eventually(() => {
      expectNoHangul('P-5 댓글과 첨부');
    });
  });

  test('B5-AC-05 P-5 종료 확인 대화상자: 묻는 글과 단추도 영어다', async () => {
    openInEnglish();
    state.issue = issueInEn('RECEIVED');
    const { user } = renderAt(URL_7);
    await titleShown();
    await user.click(await buttonNamed(/^(문의 종료|close)/i));

    const box = await dialogOpen();
    expectNoHangul('P-5 종료 확인', box);
    expectNoHangul('P-5 종료 확인(화면 전체)');
  });

  test('B5-AC-05 P-5 다시 열기 뒤 토스트: 영어다', async () => {
    openInEnglish();
    state.issue = issueInEn('RESOLVED');
    const { user } = renderAt(URL_7);
    await titleShown();
    await user.click(await buttonNamed(/^(다시 열기|reopen)/i));
    const box = await dialogOpen();
    const go = within(box)
      .queryAllByRole('button')
      .find((b) => !/^(취소|닫기|cancel|close)$/i.test(b.getAttribute('aria-label') ?? text(b)));
    expect(go ?? null, '대화상자의 확인 단추').not.toBeNull();
    await user.click(go as HTMLElement);

    await sent('POST', '/api/portal/issues/ACME-7/reopen');
    await anyToast();
    expectNoHangul('P-5 다시 열기 토스트');
  });

  test('B5-AC-05 P-5 댓글을 달지 못함(500): 오류 띠도 영어다', async () => {
    openInEnglish();
    route('post', '/api/portal/issues/ACME-7/comments', () => errors.internal());
    const { user } = renderAt(URL_7);
    await titleShown();
    const box = await found(() => document.querySelector<HTMLTextAreaElement>('main form textarea'), '댓글 칸');
    await user.type(box, 'Any news on this?');
    await user.click(await submitOf(box.closest('form') as HTMLFormElement));

    await anyAlert();
    expectNoHangul('P-5 댓글 500');
  });

  test('B5-AC-05 P-5 없는 문의(404): 화면 상태도 영어다', async () => {
    openInEnglish();
    renderAt('/issues/ACME-404');
    await found(() => screen.queryByRole('heading', { level: 1 }), '화면 제목');
    await found(() => document.querySelector('main a[href="/issues"]'), '목록으로 가는 링크');

    expectNoHangul('P-5 404');
  });
});

// ---------------------------------------------------------------------------
// P-7

const ABOUT_7 = { key: 'ACME-7', title: ISSUE_TITLE_EN };
const COLLEAGUE_ACTOR = { accountId: COLLEAGUE_EN.accountId, kind: 'CUSTOMER', name: COLLEAGUE_EN.name, photoUpdatedAt: null } as const;

function item(id: number, over: Partial<Notification>): Notification {
  return notification({ id, issue: ABOUT_7, actor: SUPPORT_ACTOR, ...over });
}

describe('B5 ② 포털 P-7 알림함을 영어로', () => {
  test('B5-AC-05 P-7 항목 여러 종류(답변, 해결, 종료, 다시 열림, 대리 등록, 언급, 동료가 종료, 모르는 종류, 읽은 것): 한글이 없다', async () => {
    openInEnglish();
    s4.inbox = [
      item(1, { kind: 'STAFF_COMMENT', commentId: 12 }),
      item(2, { kind: 'ISSUE_RESOLVED' }),
      item(3, { kind: 'ISSUE_CLOSED' }),
      item(4, { kind: 'ISSUE_REOPENED' }),
      item(5, { kind: 'ISSUE_FILED_ON_BEHALF' }),
      item(6, { kind: 'MENTIONED', commentId: 12 }),
      item(7, { kind: 'ISSUE_CLOSED', actor: COLLEAGUE_ACTOR }),
      item(8, { kind: 'LATER_KIND' as Notification['kind'] }),
      item(9, { kind: 'ISSUE_RESOLVED', readAt: '2026-10-09T01:02:03Z' }),
    ];
    renderAt('/inbox');
    await atLeast(() => screen.queryAllByText(new RegExp(ISSUE_TITLE_EN)), 9, '항목 아홉');

    await eventually(() => {
      expectNoHangul('P-7 항목 여러 종류');
    });
    expectEnglishPage('P-7');
  });

  test('B5-AC-05 P-7 빈 알림함: 안내도 영어다', async () => {
    openInEnglish();
    s4.inbox = [];
    renderAt('/inbox');
    await found(() => screen.queryByRole('heading', { level: 1 }), '화면 제목');
    await sent('GET', '/api/portal/notifications');

    await eventually(() => {
      expect(text(document.querySelector('main')).length, '안내가 그려짐').toBeGreaterThan(0);
      expectNoHangul('P-7 빈 알림함');
    });
  });

  test('B5-AC-05 P-7 알림함을 받지 못함(500): 오류 띠도 영어다', async () => {
    openInEnglish();
    route('get', '/api/portal/notifications', () => errors.internal());
    renderAt('/inbox');
    await anyAlert();

    expectNoHangul('P-7 500');
  });
});

// ---------------------------------------------------------------------------
// P-8

const MANAGED_ACME = { ...ACME_EN, manager: true };
const MANAGED_BETA = { ...BETA_EN, manager: true };
const MY_ROW_EN = person({ id: '0b6f3d52-7c1e-4a55-9f0e-5d2a4c1b7e03', name: ME_EN.name, email: ME_EN.email, manager: true });
const ROWS_EN: Member[] = [
  person({ id: '2d8b5f74-9e3a-4c77-9b2a-7f4c6e3d9a25', name: 'Choi Colleague', email: 'choi@acme.test' }),
  person({ id: '1c7a4e63-8d2f-4b66-8a1f-6e3b5d2c8f14', name: 'Han Manager', email: 'han@acme.test', manager: true }),
  MY_ROW_EN,
  person({ id: '3e9c6a85-af4b-4d88-8c3b-8a5d7f4e0b36', name: 'Oh Suspended', email: 'oh@acme.test', status: 'SUSPENDED' }),
  person({ id: '4fad7b96-b05c-4e99-9d4c-9b6e8a5f1c47', name: 'Park Invitee', email: 'park@acme.test', status: 'INVITED' }),
];
const INVITED_EN = ROWS_EN[4] as Member;
const COLLEAGUE_ROW_EN = ROWS_EN[0] as Member;

function openMembers(...companies: (typeof MANAGED_ACME)[]) {
  openInEnglish({ ...ME_EN, companies });
  people.rows = { ACME: ROWS_EN, BETA: [MY_ROW_EN] };
}

async function rowOf(email: string): Promise<HTMLElement> {
  return found(() => screen.queryAllByRole('row').find((r) => within(r).queryAllByRole('cell').some((c) => text(c).includes(email))), `${email} 줄`);
}

/** One action of a row: a button in the row, or an item of the row's menu, by its Korean name today or its English name. */
async function rowAction(user: User, row: HTMLElement, name: RegExp): Promise<HTMLElement> {
  const direct = within(row).queryAllByRole('button', { name })[0];
  if (direct) return direct;
  const trigger = within(row)
    .queryAllByRole('button')
    .find((b) => b.hasAttribute('aria-haspopup'));
  expect(trigger ?? null, `줄의 ${String(name)} 단추 또는 줄 메뉴`).not.toBeNull();
  if (trigger && trigger.getAttribute('aria-expanded') !== 'true') await user.click(trigger);
  return found(() => screen.queryAllByRole('menuitem', { name })[0], `줄 메뉴의 ${String(name)}`);
}

describe('B5 ② 포털 P-8 구성원을 영어로', () => {
  test.each<[string, (typeof MANAGED_ACME)[]]>([
    ['한 고객사의 매니저', [MANAGED_ACME]],
    ['두 고객사의 매니저(고객사 고르기)', [MANAGED_ACME, MANAGED_BETA]],
  ])('B5-AC-05 P-8 목록(%s): 열 머리, 상태(활성, 정지, 초대됨), 담당자 표시, 줄 메뉴에 한글이 없다', async (what, companies) => {
    openMembers(...companies);
    const { user } = renderAt('/members');
    const row = await rowOf(COLLEAGUE_ROW_EN.email);
    await rowOf(INVITED_EN.email);
    const trigger = within(row)
      .queryAllByRole('button')
      .find((b) => b.hasAttribute('aria-haspopup'));
    if (trigger) await user.click(trigger);

    await eventually(() => {
      expectNoHangul(`P-8 ${what}`);
    });
    expectEnglishPage(`P-8 ${what}`);
  });

  test('B5-AC-05 P-8 초대 대화상자: 제목, 칸, 안내, 빈 칸 오류도 영어다', async () => {
    openMembers(MANAGED_ACME);
    const { user } = renderAt('/members');
    await rowOf(COLLEAGUE_ROW_EN.email);
    await user.click(await buttonNamed(/^(초대|invite)/i));
    const box = await dialogOpen();
    const go = within(box)
      .queryAllByRole('button')
      .find((b) => !/^(취소|닫기|cancel|close)$/i.test(b.getAttribute('aria-label') ?? text(b)));
    expect(go ?? null, '대화상자의 초대 단추').not.toBeNull();
    await user.click(go as HTMLElement);

    await found(() => box.querySelector('[aria-invalid="true"]'), '빈 칸 오류');
    expectNoHangul('P-8 초대 대화상자', box);
    expectNoHangul('P-8 초대 대화상자(화면 전체)');
  });

  test('B5-AC-05 P-8 소속에서 빼기 확인: 묻는 글과 단추도 영어다', async () => {
    openMembers(MANAGED_ACME);
    const { user } = renderAt('/members');
    await user.click(await rowAction(user, await rowOf(COLLEAGUE_ROW_EN.email), /^(소속에서 빼기|remove)/i));

    const box = await dialogOpen();
    expectNoHangul('P-8 빼기 확인', box);
  });

  test('B5-AC-05 P-8 초대 다시 보내기: 토스트도 영어다', async () => {
    openMembers(MANAGED_ACME);
    const { user } = renderAt('/members');
    await user.click(await rowAction(user, await rowOf(INVITED_EN.email), /^(초대 다시 보내기|resend)/i));

    await sent('POST', `${membersPath('ACME')}/${INVITED_EN.id}/resend`);
    await anyToast();
    expectNoHangul('P-8 다시 보냄 토스트');
  });

  test('B5-AC-05 P-8 다시 보낼 수 없음(400): 오류 토스트나 띠도 영어다', async () => {
    openMembers(MANAGED_ACME);
    route('post', `${membersPath('ACME')}/:id/resend`, () => rejected('INVITATION_NOT_RESENDABLE'));
    const { user } = renderAt('/members');
    await user.click(await rowAction(user, await rowOf(INVITED_EN.email), /^(초대 다시 보내기|resend)/i));

    await sent('POST', `${membersPath('ACME')}/${INVITED_EN.id}/resend`);
    await found(() => liveRegions().find((el) => text(el) !== ''), '토스트나 띠');
    expectNoHangul('P-8 다시 보낼 수 없음');
  });

  test('B5-AC-05 P-8 매니저가 아님(403 화면): 안내도 영어다', async () => {
    openInEnglish();
    renderAt('/members');
    await found(() => screen.queryByRole('heading', { level: 1 }), '화면 제목');
    await main();

    await eventually(() => {
      expect(screen.queryAllByRole('table'), '표 없음').toHaveLength(0);
      expectNoHangul('P-8 403');
    });
  });
});
