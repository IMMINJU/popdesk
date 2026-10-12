// 3번 묶음 screens.md 2절 C-4 문의 상세: 고치기(S3-AC-81)와 이력 구역(S3-AC-80).
import { HttpResponse } from 'msw/http';
import { describe, expect, test } from 'vitest';
import {
  ADMIN_PERSON,
  AGENT_PERSON,
  CUSTOMER_PERSON,
  OTHER_AGENT_PERSON,
  alertWith,
  button,
  callsTo,
  dialog,
  errors,
  eventually,
  expectNoAxeViolations,
  field,
  found,
  isBlocked,
  openDialog,
  pageText,
  renderAt,
  screen,
  sent,
  state,
  text,
  within,
} from '../__tests__/console';
import { route } from '../__tests__/console1a';
import { said } from '../__tests__/console2';
import { ISSUES, history, issueIn, nowValues, opener, s3, type IssueHistoryEntry, type UpdateIssueRequest } from '../__tests__/console3';

const URL_7 = '/issues/ACME-7';
const ISSUE_7 = `${ISSUES}/ACME-7`;
const HISTORY = `${ISSUE_7}/history`;
const CONFLICT = '고치는 동안 다른 사람이 이 문의를 바꿨습니다. 아래 지금 값을 보고 다시 저장해 주세요.';

type User = ReturnType<typeof renderAt>['user'];

const titleBox = () => field('제목', 'input');
const contentBox = () => field<HTMLTextAreaElement>('내용', 'textarea');
const priority = (word: string) =>
  found(() => {
    const group = screen.queryByRole('radiogroup', { name: '우선순위' });
    return group && within(group).queryByRole<HTMLInputElement>('radio', { name: word });
  }, `우선순위 "${word}"`);
const save = () => button('저장');
const editing = () => screen.queryAllByLabelText('제목', { selector: 'input' }).length > 0;
const patches = () => callsTo('PATCH', ISSUE_7);
/** The read-only part named 지금 값, waited for. */
const nowShown = () => found(nowValues, '구역 "지금 값"');

async function openEdit(user: User) {
  await user.click(await button('고치기'));
  return titleBox();
}

/** Opens the folded 이력 part. */
async function openHistory(user: User) {
  await user.click(await opener(/^이력/));
}

const historyReads = () => callsTo('GET', HISTORY).length;

describe('C-4 고치기', () => {
  test('S3-AC-81 C-4 고치기: 제목, 내용, 우선순위가 지금 값이 든 입력칸으로 바뀐다. 바뀐 것이 없으면 "저장"이 막혀 있고 "취소"는 묻지 않고 돌아간다', async () => {
    state.issue = issueIn('RECEIVED', { priority: 'HIGH' });
    const { user } = renderAt(URL_7);

    expect(await openEdit(user)).toHaveValue('로그인이 안 됩니다');
    expect(await contentBox()).toHaveValue('어제부터 **로그인**이 안 됩니다.');
    expect(await priority('높음')).toBeChecked();
    expect(isBlocked(await save()), '바뀐 것이 없으면 "저장"').toBe(true);

    await user.type(await titleBox(), '!');
    expect(isBlocked(await save()), '바꾸면 "저장"').toBe(false);
    await user.type(await titleBox(), '{Backspace}');
    expect(isBlocked(await save()), '되돌리면 "저장"').toBe(true);

    await user.click(await button('취소'));
    await eventually(() => {
      expect(editing(), '보기로 돌아감').toBe(false);
    });
    expect(openDialog()).toBeNull();
    expect(patches()).toHaveLength(0);
  });

  test('S3-AC-81 C-4 고치기 저장: 읽어 온 version과 바뀐 칸만 보낸다. 성공하면 보기로 돌아가 새 값을 보이고 토스트 "문의를 고쳤습니다"', async () => {
    state.issue = issueIn('RECEIVED', { version: 4 });
    const { user } = renderAt(URL_7);
    const title = await openEdit(user);
    await user.clear(title);
    await user.type(title, '로그인 화면이 멈춥니다');
    await user.click(await priority('긴급'));
    await user.click(await save());

    const [patch] = await sent('PATCH', ISSUE_7);
    const body: UpdateIssueRequest = { title: '로그인 화면이 멈춥니다', priority: 'URGENT', version: 4 };
    expect(await patch?.body).toEqual(body);
    await said('문의를 고쳤습니다');
    await eventually(() => {
      expect(editing(), '보기로 돌아감').toBe(false);
      expect(text(screen.getByRole('heading', { level: 1 }))).toBe('로그인 화면이 멈춥니다');
    });
  });

  test('S3-AC-81 C-4 고치기 409: 입력은 그대로 두고 Alert로 알리며 이름이 "지금 값"인 구역 안에 지금의 제목, 우선순위, 상태와 "지금 내용 보기"를 읽기 전용으로 보인다. 다시 저장하면 새 version으로 보낸다', async () => {
    state.issue = issueIn('RECEIVED', { version: 4 });
    let first = true;
    route('patch', ISSUE_7, async ({ request }) => {
      const { version, ...fields } = (await request.json()) as UpdateIssueRequest;
      if (first) {
        first = false;
        // Somebody else saved first.
        state.issue = issueIn('IN_PROGRESS', { title: '남이 고친 제목', content: '남이 고친 내용', priority: 'URGENT', version: 5 });
        return HttpResponse.json({ code: 'VERSION_CONFLICT', message: 'Changed by someone else.' }, { status: 409 });
      }
      if (version !== state.issue.version) return HttpResponse.json({ code: 'VERSION_CONFLICT', message: 'Again.' }, { status: 409 });
      state.issue = { ...state.issue, ...fields, version: 6 };
      return HttpResponse.json(state.issue);
    });
    const { user } = renderAt(URL_7);
    const title = await openEdit(user);
    await user.clear(title);
    await user.type(title, '내가 고친 제목');
    await user.click(await save());

    await alertWith(CONFLICT);
    expect(await titleBox(), '입력은 그대로').toHaveValue('내가 고친 제목');
    // Inside the part, not on the page: "긴급" is a radio of the form and "처리 중" a badge of the head.
    await nowShown();
    await eventually(() => {
      for (const now of ['남이 고친 제목', '긴급', '처리 중']) expect(text(nowValues()), `지금 값 "${now}"`).toContain(now);
    });
    const part = await nowShown();
    for (const role of ['textbox', 'radio', 'checkbox', 'combobox']) expect(within(part).queryAllByRole(role), `읽기 전용: 구역 안의 ${role}`).toHaveLength(0);
    await user.click(await opener(/지금 내용 보기/, nowValues));
    await eventually(() => {
      expect(text(nowValues())).toContain('남이 고친 내용');
    });

    await user.click(await save());
    const [, second] = await sent('PATCH', ISSUE_7, 2);
    expect(await second?.body).toMatchObject({ title: '내가 고친 제목', version: 5 });
    await said('문의를 고쳤습니다');
    await eventually(() => {
      expect(editing()).toBe(false);
    });
  });

  test('S3-AC-81 C-4 고치기 400 ISSUE_CLOSED: Alert "문의가 종료되어 고칠 수 없습니다". 입력은 그대로 두고 "저장"을 막는다', async () => {
    state.issue = issueIn('RECEIVED');
    route('patch', ISSUE_7, () => {
      state.issue = issueIn('CLOSED');
      return errors.issueClosed();
    });
    const { user } = renderAt(URL_7);
    await user.type(await openEdit(user), ' (보탬)');
    await user.click(await save());

    await alertWith('문의가 종료되어 고칠 수 없습니다');
    expect(await titleBox()).toHaveValue('로그인이 안 됩니다 (보탬)');
    await eventually(() => {
      const again = screen.queryByRole('button', { name: '저장' });
      expect(again !== null && isBlocked(again), '"저장"이 막힘').toBe(true);
    });
  });

  test('S3-AC-81 C-4 고치기 취소와 떠나기: 바꾼 글이 있으면 묻고, 그 자리에 글이 남는다', async () => {
    state.issue = issueIn('RECEIVED');
    const { user } = renderAt(URL_7);
    await user.type(await openEdit(user), ' (쓰는 중)');

    await user.click(await button('취소'));
    await dialog();
    await user.keyboard('{Escape}');
    await eventually(() => {
      expect(openDialog()).toBeNull();
    });
    expect(await titleBox(), '취소를 물은 뒤').toHaveValue('로그인이 안 됩니다 (쓰는 중)');

    await user.click(await found(() => screen.queryByRole('link', { name: '고객사' }), '메뉴 "고객사"'));
    await dialog();
    expect(window.location.pathname).toBe(URL_7);
    await user.keyboard('{Escape}');
    expect(await titleBox(), '떠나기를 물은 뒤').toHaveValue('로그인이 안 됩니다 (쓰는 중)');
    expect(patches()).toHaveLength(0);
  });

  test('C-4 axe (고치기): 입력칸으로 바뀐 화면과 409의 지금 값이 보이는 화면에 접근성 위반이 없다', async () => {
    state.issue = issueIn('RECEIVED');
    route('patch', ISSUE_7, () => {
      state.issue = issueIn('IN_PROGRESS', { title: '남이 고친 제목', version: 2 });
      return HttpResponse.json({ code: 'VERSION_CONFLICT', message: 'Changed by someone else.' }, { status: 409 });
    });
    const { user } = renderAt(URL_7);
    await user.type(await openEdit(user), ' (보탬)');
    await expectNoAxeViolations();

    await user.click(await save());
    await alertWith(CONFLICT);
    await nowShown();
    await expectNoAxeViolations();
  });
});

describe('C-4 이력', () => {
  test.each([
    { what: '올림', issue: {}, entry: history({ kind: 'CREATED', field: null, oldValue: null, newValue: null, actor: CUSTOMER_PERSON }), says: /김고객 ?\(고객\) 님이 문의를 올렸습니다/ },
    {
      what: '대리 등록',
      issue: { createdBy: AGENT_PERSON, channel: 'PHONE' as const },
      entry: history({ kind: 'CREATED', field: null, oldValue: null, newValue: null }),
      says: /이상담 님이 대리 등록했습니다/,
    },
    { what: '상태', issue: {}, entry: history(), says: /이상담 님이 상태를 접수에서 처리 중으로 바꿨습니다/ },
    {
      what: '담당자 정함',
      issue: {},
      entry: history({ field: 'ASSIGNEE', oldValue: null, newValue: '3', newPerson: OTHER_AGENT_PERSON, actor: ADMIN_PERSON }),
      says: /최관리 님이 담당자를 정상담 님으로 정했습니다/,
    },
    {
      what: '담당자 바꿈',
      issue: {},
      entry: history({ field: 'ASSIGNEE', oldValue: '2', newValue: '3', oldPerson: AGENT_PERSON, newPerson: OTHER_AGENT_PERSON, actor: ADMIN_PERSON }),
      says: /최관리 님이 담당자를 이상담 님에서 정상담 님으로 바꿨습니다/,
    },
    {
      what: '담당자 비움',
      issue: {},
      entry: history({ field: 'ASSIGNEE', oldValue: '2', newValue: null, oldPerson: AGENT_PERSON, actor: ADMIN_PERSON }),
      says: /최관리 님이 담당자를 비웠습니다/,
    },
    { what: '우선순위', issue: {}, entry: history({ field: 'PRIORITY', oldValue: 'NORMAL', newValue: 'URGENT' }), says: /이상담 님이 우선순위를 보통에서 긴급으로 바꿨습니다/ },
    {
      what: '제목',
      issue: {},
      entry: history({ field: 'TITLE', oldValue: '옛 제목입니다', newValue: '새 제목입니다', actor: CUSTOMER_PERSON }),
      says: /김고객 ?\(고객\) 님이 제목을 고쳤습니다.*옛 제목입니다.*새 제목입니다/,
    },
    { what: '내용', issue: {}, entry: history({ field: 'CONTENT', oldValue: '옛 내용', newValue: '새 내용' }), says: /이상담 님이 내용을 고쳤습니다.*전 내용 보기/ },
    { what: '모르는 칸', issue: {}, entry: history({ field: 'DUE_DATE', oldValue: null, newValue: '2026-11-01' }), says: /이상담 님이 문의를 바꿨습니다/ },
  ])('S3-AC-80 C-4 이력의 글($what): $says', async ({ issue, entry, says }) => {
    state.issue = issueIn('RECEIVED', issue);
    const at = '2026-10-09T01:02:03Z';
    s3.history = [{ ...entry, createdAt: at }];
    const { user } = renderAt(URL_7);
    await openHistory(user);

    await eventually(() => {
      expect(pageText()).toMatch(says);
    });
    const times = Array.from(document.querySelectorAll('time')).map((t) => Date.parse(t.getAttribute('datetime') ?? ''));
    expect(times, '줄의 시각').toContain(Date.parse(at));
  });

  test('S3-AC-80 C-4 이력: 접혀 있고 펼칠 때 20줄을 부른다. 받은 순서(오래된 것부터)로 보이고 "더 보기"로 다음 20줄을 잇는다', async () => {
    state.issue = issueIn('RECEIVED');
    s3.history = Array.from({ length: 25 }, (_, i): IssueHistoryEntry => history({ id: i + 1, field: 'TITLE', oldValue: `제목 ${String(i)}판`, newValue: `제목 ${String(i + 1)}판` }));
    const { user } = renderAt(URL_7);
    await button('고치기');
    expect(historyReads(), '펼치기 전').toBe(0);

    await openHistory(user);
    const [first] = await sent('GET', HISTORY);
    const query = new URL(first?.url ?? '').searchParams;
    expect(Number(query.get('page') ?? '1')).toBe(1);
    expect(query.get('size')).toBe('20');
    await eventually(() => {
      expect(pageText()).toContain('제목 20판');
    });
    expect(pageText()).not.toContain('제목 21판');
    expect(pageText().indexOf('제목 2판'), '오래된 것부터').toBeLessThan(pageText().indexOf('제목 19판'));

    await user.click(await button('더 보기'));
    await eventually(() => {
      expect(pageText()).toContain('제목 25판');
    });
    expect(new URL(callsTo('GET', HISTORY).at(-1)?.url ?? '').searchParams.get('page')).toBe('2');
    expect(pageText(), '앞의 줄이 남아 있다').toContain('제목 1판');
    expect(screen.queryByRole('button', { name: '더 보기' }), '끝까지 본 뒤').toBeNull();
  });

  test('S3-AC-80 C-4 이력의 내용 전 값: "전 내용 보기"를 펼치면 글 그대로 보인다. 마크다운으로 그리지 않는다', async () => {
    const before = '**굵게** 쓴 [링크](https://old.example.test/a)';
    state.issue = issueIn('RECEIVED', { content: '지금 내용' });
    s3.history = [history({ field: 'CONTENT', oldValue: before, newValue: '지금 내용' })];
    const { user } = renderAt(URL_7);
    await openHistory(user);

    await user.click(await opener(/전 내용 보기/));
    await eventually(() => {
      expect(pageText()).toContain(before);
    });
    expect(screen.queryByRole('link', { name: '링크' }), '링크로 그리지 않는다').toBeNull();
    expect(document.querySelector('a[href*="old.example.test"]')).toBeNull();
  });

  test('S3-AC-80 C-4 이력 다시 부르기: 펼쳐 둔 채 상태를 바꾸면 이력을 다시 부르고 새 줄이 보인다', async () => {
    state.issue = issueIn('RECEIVED');
    s3.history = [history({ kind: 'CREATED', field: null, oldValue: null, newValue: null, actor: CUSTOMER_PERSON })];
    const { user } = renderAt(URL_7);
    await openHistory(user);
    await eventually(() => {
      expect(pageText()).toMatch(/님이 문의를 올렸습니다/);
    });
    const reads = historyReads();

    s3.history = [...s3.history, history({ id: 2, oldValue: 'RECEIVED', newValue: 'CONFIRMED' })];
    await user.click(await button('확인'));

    await sent('PUT', `${ISSUE_7}/status`);
    await eventually(() => {
      expect(historyReads(), '이력을 다시 부름').toBeGreaterThan(reads);
      expect(pageText()).toContain('이상담 님이 상태를 접수에서 확인으로 바꿨습니다');
    });
  });

  test('S3-AC-80 C-4 이력 다시 부르기(담당자): 펼쳐 둔 채 담당자를 바꾸면 이력을 다시 부르고 새 줄이 보인다', async () => {
    state.issue = issueIn('RECEIVED');
    s3.history = [history({ kind: 'CREATED', field: null, oldValue: null, newValue: null, actor: CUSTOMER_PERSON })];
    const { user } = renderAt(URL_7);
    await openHistory(user);
    await eventually(() => {
      expect(pageText()).toMatch(/님이 문의를 올렸습니다/);
    });
    const reads = historyReads();

    s3.history = [...s3.history, history({ id: 2, field: 'ASSIGNEE', oldValue: null, newValue: '3', newPerson: OTHER_AGENT_PERSON, actor: ADMIN_PERSON })];
    await user.click(await button(/^(담당자 )?바꾸기$/));
    const box = await found(() => screen.queryByRole('dialog', { name: '담당자' }), '대화상자 "담당자"');
    await user.click(await found(() => within(box).queryByRole('radio', { name: /정상담/ }), '대화상자의 "정상담"'));
    await user.click(await found(() => within(box).queryByRole('button', { name: '저장' }), '"저장"'));

    await sent('PUT', `${ISSUE_7}/assignee`);
    await eventually(() => {
      expect(historyReads(), '이력을 다시 부름').toBeGreaterThan(reads);
      expect(pageText()).toContain('최관리 님이 담당자를 정상담 님으로 정했습니다');
    });
  });

  test('C-4 axe (이력): 펼친 이력 구역에 접근성 위반이 없다', async () => {
    state.issue = issueIn('RECEIVED');
    s3.history = [
      history({ kind: 'CREATED', field: null, oldValue: null, newValue: null, actor: CUSTOMER_PERSON }),
      history({ id: 2, field: 'CONTENT', oldValue: '옛 내용', newValue: '새 내용' }),
      history({ id: 3, field: 'TITLE', oldValue: '옛 제목입니다', newValue: '새 제목입니다' }),
    ];
    const { user } = renderAt(URL_7);
    await openHistory(user);
    await eventually(() => {
      expect(pageText()).toContain('옛 제목입니다');
    });

    await expectNoAxeViolations();
  });
});
