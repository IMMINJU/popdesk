// 6b 묶음 screens.md 1절 C-4 문의 상세의 작업 구역: 줄, 만들기, 상태 단추, 줄 메뉴, 펼치기와 #task-ID,
// 작업 담당자와 마감일, 기록, 시스템 항목의 문장(3절), 흔적, CLOSED, 실패. 그 아래 상태 바꾸기의 열린 작업 묻기
// (S6B-AC-86)와 늦은 답(S6B-AC-89). 같은 글자가 화면 두 곳에 나올 수 있어 찾기는 작업 구역 안으로 좁힌다.
import { waitFor } from '@testing-library/react';
import { describe, expect, test } from 'vitest';
import {
  ADMIN_PERSON,
  AGENT,
  AGENT_PERSON,
  OTHER_AGENT_PERSON,
  callsTo,
  errors,
  eventually,
  expectNoAxeViolations,
  found,
  gate,
  isBlocked,
  openDialog,
  renderAt,
  screen,
  sent,
  state,
  summary,
  text,
  within,
  type Person,
} from '../__tests__/console';
import { answered, closed, route, settle } from '../__tests__/console1a';
import { ISSUES, issueIn } from '../__tests__/console3';
import { longDate, metaValueNow, rejectedWith } from '../__tests__/console6a';
import {
  DONE_AT,
  TASKS_7,
  actionOf,
  actionsOf,
  change,
  deletedNote,
  deletedTask,
  doneTask,
  expand,
  keepsBreak,
  lastBody,
  note,
  noteBoxNow,
  noteLine,
  notePath,
  outerLine,
  ownText,
  regionName,
  s6b,
  sendNoteButton,
  statusButton,
  statusButtonNow,
  task,
  taskDueInputNow,
  taskLine,
  taskPath,
  taskRegion,
  taskRegionNow,
  taskResponse,
  tasksResponse,
  type Task,
} from '../__tests__/console6b';

const URL_7 = '/issues/ACME-7';
const ISSUE_7 = `${ISSUES}/ACME-7`;
const FAILED = '작업을 바꾸지 못했습니다. 다시 해 보세요.';
const NOTE_FAILED = '기록을 남기지 못했습니다. 다시 해 보세요.';
const TASK_LIMIT = '작업은 문의마다 30개까지 만듭니다.';
const NOTE_LIMIT = '기록은 작업마다 100개까지 남깁니다.';
const PAST_TEXT = '오늘이나 그 뒤의 날짜를 고르세요.';
const CLOSE_ASK = '이 문의를 종료할까요? 고객은 14일 안에 다시 열 수 있습니다.';

type User = ReturnType<typeof renderAt>['user'];

const LEE_NOTE = note({ id: 801, author: AGENT_PERSON, content: '고객에게 로그를 받았습니다.' });
const CHOI_NOTE = note({ id: 802, author: ADMIN_PERSON, content: '확인했습니다.' });
const A_CHANGE = change('DUE_DATE', { id: 803, oldValue: null, newValue: '2026-10-20' });

/** 이상담's task with two notes and one system entry, due on a day that has passed. */
const A: Task = task({ id: 41, title: '로그 모으기', assignee: AGENT_PERSON, dueDate: '2026-10-20', overdue: true, notes: [LEE_NOTE, CHOI_NOTE, A_CHANGE] });
/** Nobody's task, no due date, made by 이상담. */
const B: Task = task({ id: 42, title: '고객에게 전화하기', createdBy: AGENT_PERSON, notes: [note({ id: 811, content: '오후에 다시 겁니다.' })] });
/** A done task whose due date has passed: not overdue (S6B-AC-07). */
const C: Task = doneTask({ id: 43, title: '재현 절차 정리', dueDate: '2026-10-01', overdue: false });
const D: Task = deletedTask({ id: 44 });

function seed(tasks: Task[] = [A, B, C, D], key = 'ACME-7') {
  s6b.tasks = { ...s6b.tasks, [key]: tasks };
}

/** Waits generously for C-4 and its 작업 구역: the first wait of a test may meet a busy machine. */
async function opened(): Promise<HTMLElement> {
  await waitFor(
    () => {
      expect(metaValueNow('담당자'), 'C-4 머리의 "담당자"').not.toBeNull();
    },
    { timeout: 5000 },
  );
  return taskRegion();
}

function person(p: Person): string {
  return p.name;
}

/** Goes to another issue as a person would: the menu "문의", then its link in the list. */
async function goToIssue(user: User, title: RegExp, key: string): Promise<void> {
  await user.click(await found(() => screen.queryAllByRole('link', { name: '문의' }).find((a) => a.closest('nav') !== null), '메뉴 "문의"'));
  await user.click(await found(() => screen.queryByRole('link', { name: title }), `${key} 링크`));
  await eventually(() => {
    expect(window.location.pathname).toBe(`/issues/${key}`);
  });
}

const assigneeDialog = () => screen.queryByRole('dialog', { name: '작업 담당자 바꾸기' });

async function openTaskAssignee(user: User, title: string): Promise<HTMLElement> {
  await expand(user, title);
  const region = await taskRegion();
  await user.click(await found(() => within(region).queryByRole('button', { name: '작업 담당자 바꾸기' }), `"${title}"의 "작업 담당자 바꾸기"`));
  return found(assigneeDialog, '대화상자 "작업 담당자 바꾸기"');
}

async function openTaskDue(user: User, title: string): Promise<HTMLInputElement> {
  await expand(user, title);
  const region = await taskRegion();
  await user.click(await found(() => within(region).queryByRole('button', { name: '작업 마감일 바꾸기' }), `"${title}"의 "작업 마감일 바꾸기"`));
  return found(taskDueInputNow, '작업 마감일 고르기의 날짜 입력');
}

/** A button of the form (or the box) around the opened task date input. */
function dueButton(input: HTMLInputElement, name: '정하기' | '지우기'): Promise<HTMLElement> {
  const scope = (input.closest('form') ?? input.parentElement?.parentElement ?? input.parentElement) as HTMLElement;
  return found(() => within(scope).queryByRole('button', { name }), `작업 마감일 고르기의 "${name}"`);
}

/** The titles of the task lines in the order shown. */
function titlesShown(region: HTMLElement, titles: readonly string[]): string[] {
  const all = text(region);
  return titles.filter((t) => all.includes(t)).sort((a, b) => all.indexOf(a) - all.indexOf(b));
}

describe('C-4 작업 구역 (6b 묶음)', () => {
  test('S6B-AC-85 C-4 구역: 첨부 다음, 댓글 앞에 "작업 2/3"이다(지운 것은 세지 않는다). 줄은 만든 차례이고 지운 작업은 그 자리에 흔적으로 남는다', async () => {
    seed();
    state.issue = issueIn('IN_PROGRESS');
    renderAt(URL_7);
    const region = await opened();

    await eventually(() => {
      expect(regionName(region)).toBe('작업 2/3');
    });
    expect(titlesShown(region, [A.title, B.title, C.title, '지워진 작업'])).toEqual([A.title, B.title, C.title, '지워진 작업']);
    const comments = await found(() => screen.queryByRole('heading', { name: /^댓글/ }), '제목 "댓글 n개"');
    expect(region.compareDocumentPosition(comments) & Node.DOCUMENT_POSITION_FOLLOWING, '댓글이 작업 구역 뒤').toBeTruthy();
    const attachments = await found(() => screen.queryAllByText('첨부', { exact: true })[0], '"첨부"');
    expect(region.compareDocumentPosition(attachments) & Node.DOCUMENT_POSITION_PRECEDING, '첨부가 작업 구역 앞').toBeTruthy();
  });

  test.each([
    { what: '모두 끝냄', tasks: [doneTask({ id: 43, title: '재현 절차 정리' })], empty: false },
    { what: '작업 없음', tasks: [], empty: true },
  ])('S6B-AC-85 C-4 구역 제목($what): 열린 작업이 없으면 "작업"이다', async ({ tasks, empty }) => {
    seed(tasks);
    state.issue = issueIn('IN_PROGRESS');
    renderAt(URL_7);
    const region = await opened();

    await sent('GET', TASKS_7);
    await settle();
    expect(regionName(region)).toBe('작업');
    expect(text(region).includes('아직 작업이 없습니다.'), '"아직 작업이 없습니다."').toBe(empty);
  });

  test('S6B-AC-85 C-4 줄: 상태 단추의 이름, 담당자 또는 "담당 없음", 마감일과 "지남"(서버의 overdue만 본다), "기록 n"(사람 기록), 끝낸 작업은 "완료"와 끝낸 사람·시각이다', async () => {
    seed();
    state.issue = issueIn('IN_PROGRESS');
    renderAt(URL_7);
    await opened();

    const a = await taskLine(A.title);
    await statusButton(A.title, 'DONE');
    expect(text(a)).toContain(person(AGENT_PERSON));
    expect(text(a)).toContain(longDate('2026-10-20'));
    expect(text(a)).toContain('지남');
    expect(text(a)).toContain('기록 2');

    const b = await taskLine(B.title);
    await statusButton(B.title, 'DONE');
    expect(text(b)).toContain('담당 없음');
    expect(text(b)).not.toContain('지남');
    expect(text(b)).toContain('기록 1');

    const c = await taskLine(C.title);
    await statusButton(C.title, 'OPEN');
    expect(statusButtonNow(C.title, 'DONE'), '끝낸 작업의 "완료" 단추').toBeNull();
    expect(text(c)).toContain('완료');
    expect(text(c)).toContain(person(ADMIN_PERSON));
    expect(c.querySelector(`time[datetime="${DONE_AT}"]`), '끝낸 시각(<time datetime>)').not.toBeNull();
    expect(text(c), '날짜가 지났어도 overdue가 false').not.toContain('지남');
    expect(c.querySelector('s, del, strike'), '제목에 줄을 긋지 않는다').toBeNull();
  });

  test.each([
    { what: '완료', t: A, to: 'DONE' as const, back: 'OPEN' as const },
    { what: '다시 열기', t: C, to: 'OPEN' as const, back: 'DONE' as const },
  ])('S6B-AC-85 C-4 상태 단추($what): PATCH 작업(정수 ID 경로)에 status만 보내고 답으로 줄을 다시 그린다', async ({ t, to, back }) => {
    seed();
    state.issue = issueIn('IN_PROGRESS');
    const { user } = renderAt(URL_7);
    await opened();

    await user.click(await statusButton(t.title, to === 'DONE' ? 'DONE' : 'OPEN'));

    await sent('PATCH', taskPath(t.id));
    expect(await lastBody('PATCH', taskPath(t.id))).toEqual({ status: to });
    await statusButton(t.title, back === 'DONE' ? 'DONE' : 'OPEN');
  });

  test('S6B-AC-85 C-4 만들기: "작업 더하기"를 누르면 줄 안에 "작업 제목" 칸이 생기고, Enter로 제목만 보내며 새 줄이 생긴다', async () => {
    seed([A]);
    state.issue = issueIn('IN_PROGRESS');
    const { user } = renderAt(URL_7);
    const region = await opened();

    await user.click(await found(() => within(region).queryByRole('button', { name: '작업 더하기' }), '"작업 더하기"'));
    const input = await found(() => within(region).queryByRole<HTMLInputElement>('textbox', { name: '작업 제목' }), '칸 "작업 제목"');
    await user.type(input, '서버 로그 확인{Enter}');

    await sent('POST', TASKS_7);
    const body = await lastBody('POST', TASKS_7);
    expect(body['title']).toBe('서버 로그 확인');
    expect(body['assigneeId'] ?? null, '만들 때 담당자를 보내지 않는다').toBeNull();
    expect(body['dueDate'] ?? null, '만들 때 마감일을 보내지 않는다').toBeNull();
    await statusButton('서버 로그 확인', 'DONE');
    expect(titlesShown(region, [A.title, '서버 로그 확인'])).toEqual([A.title, '서버 로그 확인']);
  });

  test('S6B-AC-85 C-4 만들기의 "더하기"와 "취소": "취소"는 보내지 않고 칸을 닫고, "더하기"는 Enter와 같이 보낸다', async () => {
    seed([]);
    state.issue = issueIn('IN_PROGRESS');
    const { user } = renderAt(URL_7);
    const region = await opened();
    const add = () => found(() => within(region).queryByRole('button', { name: '작업 더하기' }), '"작업 더하기"');
    const input = () => found(() => within(region).queryByRole<HTMLInputElement>('textbox', { name: '작업 제목' }), '칸 "작업 제목"');

    await user.click(await add());
    await user.type(await input(), '보내지 않을 작업');
    await user.click(await found(() => within(region).queryByRole('button', { name: '취소' }), '"취소"'));
    await eventually(() => {
      expect(within(region).queryByRole('textbox', { name: '작업 제목' })).toBeNull();
    });
    expect(callsTo('POST', TASKS_7)).toHaveLength(0);

    await user.click(await add());
    await user.type(await input(), '전화 기록 정리');
    await user.click(await found(() => within(region).queryByRole('button', { name: '더하기' }), '"더하기"'));
    await sent('POST', TASKS_7);
    expect((await lastBody('POST', TASKS_7))['title']).toBe('전화 기록 정리');
    await statusButton('전화 기록 정리', 'DONE');
  });

  test.each([
    { what: '400 TASK_LIMIT_EXCEEDED', answer: () => rejectedWith('createTask', 'TASK_LIMIT_EXCEEDED'), says: TASK_LIMIT },
    { what: '500', answer: () => errors.internal(), says: FAILED },
  ])('S6B-AC-85 C-4 만들기 실패($what): "$says"를 보이고 쓴 제목은 그대로 둔다', async ({ answer, says }) => {
    seed([A]);
    state.issue = issueIn('IN_PROGRESS');
    route('post', TASKS_7, () => answer());
    const { user } = renderAt(URL_7);
    const region = await opened();

    await user.click(await found(() => within(region).queryByRole('button', { name: '작업 더하기' }), '"작업 더하기"'));
    const input = await found(() => within(region).queryByRole<HTMLInputElement>('textbox', { name: '작업 제목' }), '칸 "작업 제목"');
    await user.type(input, '서른한 번째 작업{Enter}');

    await sent('POST', TASKS_7);
    await found(() => (text(region).includes(says) ? region : null), `"${says}"`);
    expect(within(region).queryByRole<HTMLInputElement>('textbox', { name: '작업 제목' })?.value).toBe('서른한 번째 작업');
  });

  test('S6B-AC-85 C-4 줄 메뉴: 열린 문의의 작업 줄에는 "고치기"와 "지우기"가 있다', async () => {
    seed();
    state.issue = issueIn('IN_PROGRESS');
    const { user } = renderAt(URL_7);
    await opened();

    expect(await actionsOf(user, await taskLine(A.title))).toEqual(['고치기', '지우기']);
  });

  test('S6B-AC-85 C-4 줄 메뉴(상담원): "지우기"는 그 작업을 만든 사람에게만 있다. 최관리가 만든 작업은 "고치기"만, 자기가 만든 작업은 둘 다다', async () => {
    state.me = { ...AGENT, companies: [{ code: 'ACME', name: '에이크미' }] };
    seed();
    state.issue = issueIn('IN_PROGRESS');
    const { user } = renderAt(URL_7);
    await opened();

    expect(await actionsOf(user, await taskLine(A.title)), '최관리가 만든 A').toEqual(['고치기']);
    expect(await actionsOf(user, await taskLine(B.title)), '이상담이 만든 B').toEqual(['고치기', '지우기']);
  });

  test('S6B-AC-85 C-4 "기록 n"은 지우지 않은 사람 기록만 센다: 지운 기록과 시스템 항목은 빼고, 작업 지우기 확인의 "기록 n개"도 같은 수다', async () => {
    seed([task({ ...A, notes: [LEE_NOTE, deletedNote({ id: 805 }), A_CHANGE] }), B]);
    state.issue = issueIn('IN_PROGRESS');
    const { user } = renderAt(URL_7);
    await opened();

    const a = await taskLine(A.title);
    await eventually(() => {
      expect(/기록 (\d+)/.exec(ownText(a))?.[1], 'A 줄의 "기록 n"').toBe('1');
    });
    await user.click(await actionOf(user, a, '지우기'));
    const box = await found(openDialog, '지우기 확인');
    expect(text(box)).toContain('이 작업을 지울까요? 기록 1개도 함께 지웁니다.');
  });

  const manyTasks = (live: number, deleted: number): Task[] => [
    ...Array.from({ length: live }, (_, i) => (i % 3 === 0 ? doneTask : task)({ id: 100 + i, title: `할 일 ${String(i + 1).padStart(3, '0')}` })),
    ...Array.from({ length: deleted }, (_, i) => deletedTask({ id: 300 + i })),
  ];

  test.each([
    { what: '지우지 않은 작업 30개', live: 30, deleted: 0, blocked: true },
    { what: '지우지 않은 것 29개와 지운 것 71개(모두 100개)', live: 29, deleted: 71, blocked: true },
    { what: '지우지 않은 것 29개와 지운 것 70개(모두 99개)', live: 29, deleted: 70, blocked: false },
  ])('S6B-AC-85 C-4 작업 한도($what): 다 차면 "작업 더하기"를 미리 막고(aria-disabled) "작업은 문의마다 30개까지 만듭니다."를 보인다', async ({ live, deleted, blocked }) => {
    seed(manyTasks(live, deleted));
    state.issue = issueIn('IN_PROGRESS');
    const { user } = renderAt(URL_7);
    const region = await opened();
    await taskLine(`할 일 ${String(live).padStart(3, '0')}`);
    await settle();

    const add = await found(() => within(region).queryByRole('button', { name: '작업 더하기' }), '"작업 더하기"');
    expect(isBlocked(add), '"작업 더하기"가 막힘').toBe(blocked);
    expect(text(region).includes(TASK_LIMIT), `"${TASK_LIMIT}"`).toBe(blocked);
    if (blocked) {
      await user.click(add);
      await settle();
      expect(within(region).queryByRole('textbox', { name: '작업 제목' }), '막힌 "작업 더하기"가 연 칸').toBeNull();
      expect(callsTo('POST', TASKS_7)).toHaveLength(0);
    }
  });

  test('S6B-AC-85 C-4 제목 고치기: 줄 메뉴의 "고치기"가 지금 제목이 든 "작업 제목" 칸을 열고, Enter로 PATCH에 title만 보낸다', async () => {
    seed();
    state.issue = issueIn('IN_PROGRESS');
    const { user } = renderAt(URL_7);
    const region = await opened();

    await user.click(await actionOf(user, await taskLine(A.title), '고치기'));
    const input = await found(() => within(region).queryByRole<HTMLInputElement>('textbox', { name: '작업 제목' }), '칸 "작업 제목"');
    expect(input.value).toBe(A.title);
    await user.clear(input);
    await user.type(input, '로그 모아 보내기{Enter}');

    await sent('PATCH', taskPath(A.id));
    expect(await lastBody('PATCH', taskPath(A.id))).toEqual({ title: '로그 모아 보내기' });
    await statusButton('로그 모아 보내기', 'DONE');
  });

  test('S6B-AC-85 C-4 작업 지우기: 한 번 더 묻는다(기록 수와 되돌릴 수 없음, "지우기"·"그만두기"). "그만두기"는 보내지 않고, "지우기"는 DELETE 뒤 흔적 줄이 된다', async () => {
    seed();
    state.issue = issueIn('IN_PROGRESS');
    const { user } = renderAt(URL_7);
    const region = await opened();

    await user.click(await actionOf(user, await taskLine(A.title), '지우기'));
    const first = await found(openDialog, '지우기 확인');
    expect(text(first)).toContain('이 작업을 지울까요? 기록 2개도 함께 지웁니다. 지운 작업은 되돌릴 수 없습니다.');
    await user.click(within(first).getByRole('button', { name: '그만두기' }));
    await closed();
    expect(callsTo('DELETE', taskPath(A.id))).toHaveLength(0);

    await user.click(await actionOf(user, await taskLine(A.title), '지우기'));
    const second = await found(openDialog, '지우기 확인');
    await user.click(within(second).getByRole('button', { name: '지우기' }));

    await sent('DELETE', taskPath(A.id));
    await eventually(() => {
      expect(text(region)).not.toContain(A.title);
    });
    expect(text(region)).toContain('최관리 님이 지움');
    expect(text(region).split('지워진 작업').length - 1, '흔적 줄 둘(앞의 것과 방금 지운 것)').toBe(2);
  });

  test('S6B-AC-85 C-4 펼치기: 줄은 처음에 닫혀 있고 "기록 n"(또는 줄)을 누르면 그 아래에 기록과 "기록 남기기"가 펼쳐진다', async () => {
    seed();
    state.issue = issueIn('IN_PROGRESS');
    const { user } = renderAt(URL_7);
    const region = await opened();
    await taskLine(A.title);
    expect(text(region), '닫힌 줄의 기록').not.toContain(LEE_NOTE.content);

    await expand(user, A.title);

    await noteLine(LEE_NOTE.content);
    await found(noteBoxNow, '"기록 남기기"');
    expect(text(region), '펼치지 않은 작업의 기록').not.toContain('오후에 다시 겁니다.');
    await expectNoAxeViolations();
  });

  test('S6B-AC-85 C-4 #task-ID: 주소에 있으면 그 작업을 펼치고 그 줄로 스크롤한다. 다른 작업은 닫혀 있다', async () => {
    seed();
    state.issue = issueIn('IN_PROGRESS');
    const scrolled: Element[] = [];
    const had = Object.getOwnPropertyDescriptor(Element.prototype, 'scrollIntoView');
    Object.defineProperty(Element.prototype, 'scrollIntoView', {
      configurable: true,
      writable: true,
      value(this: Element) {
        scrolled.push(this);
      },
    });
    try {
      renderAt(`${URL_7}#task-${String(B.id)}`);
      const region = await opened();

      await noteLine('오후에 다시 겁니다.');
      expect(text(region), '주소에 없는 작업의 기록').not.toContain(LEE_NOTE.content);
      const line = await taskLine(B.title);
      await eventually(() => {
        expect(scrolled.some((el) => line.contains(el)), '그 작업 줄로 스크롤').toBe(true);
      });
    } finally {
      if (had) Object.defineProperty(Element.prototype, 'scrollIntoView', had);
      else Reflect.deleteProperty(Element.prototype, 'scrollIntoView');
    }
  });

  test('S6B-AC-85 C-4 기록: 쓴 사람과 글이 보이고, 줄바꿈은 그대로이며, 고친 기록에만 "고침"이 있다', async () => {
    const twoLines = note({ id: 804, author: OTHER_AGENT_PERSON, content: '첫 줄\n둘째 줄', editedAt: '2026-10-09T05:00:00Z' });
    seed([task({ ...A, notes: [LEE_NOTE, twoLines] })]);
    state.issue = issueIn('IN_PROGRESS');
    const { user } = renderAt(URL_7);
    await opened();
    await expand(user, A.title);

    const lee = await noteLine(LEE_NOTE.content);
    expect(text(lee)).toContain(person(AGENT_PERSON));
    expect(text(lee), '고친 적 없는 기록').not.toContain('고침');
    const edited = await noteLine('둘째 줄');
    expect(text(edited)).toContain(person(OTHER_AGENT_PERSON));
    expect(text(edited)).toContain('고침');
    expect(keepsBreak(edited, '첫 줄', '둘째 줄'), '줄바꿈').toBe(true);
  });

  test.each([
    { who: '관리자', me: () => state.me, own: CHOI_NOTE, others: LEE_NOTE, ownActions: ['고치기', '지우기'], otherActions: ['지우기'] },
    { who: '상담원', me: () => ({ ...AGENT, companies: [{ code: 'ACME', name: '에이크미' }] }), own: LEE_NOTE, others: CHOI_NOTE, ownActions: ['고치기', '지우기'], otherActions: [] },
  ])('S6B-AC-85 C-4 기록의 단추($who): 자기 기록은 "고치기"·"지우기", 남의 기록은 관리자에게만 "지우기", 시스템 항목은 없다', async ({ me, own, others, ownActions, otherActions }) => {
    state.me = me();
    seed();
    state.issue = issueIn('IN_PROGRESS');
    const { user } = renderAt(URL_7);
    await opened();
    await expand(user, A.title);

    expect(await actionsOf(user, await noteLine(own.content))).toEqual(ownActions);
    expect(await actionsOf(user, await noteLine(others.content))).toEqual(otherActions);
    expect(await actionsOf(user, await noteLine('마감일을'))).toEqual([]);
  });

  test('S6B-AC-85 C-4 기록 남기기: 여러 줄 칸에 4,000자 셈이 있고, 보내면 POST 기록에 쓴 그대로(줄바꿈 포함) 가며 새 기록이 보인다', async () => {
    seed();
    state.issue = issueIn('IN_PROGRESS');
    const { user } = renderAt(URL_7);
    const region = await opened();
    await expand(user, A.title);

    const box = await found(noteBoxNow, '"기록 남기기"');
    expect(box.tagName, '여러 줄 칸').toBe('TEXTAREA');
    await user.type(box, '첫 줄{Enter}둘째 줄');
    expect(text(region)).toContain('4,000');
    await user.click(await sendNoteButton());

    await sent('POST', notePath(A.id));
    expect(await lastBody('POST', notePath(A.id))).toEqual({ content: '첫 줄\n둘째 줄' });
    const added = await noteLine('둘째 줄');
    expect(text(added)).toContain('최관리');
  });

  test.each([
    { what: '500', answer: () => errors.internal(), says: NOTE_FAILED },
    { what: '400 TASK_NOTE_LIMIT_EXCEEDED', answer: () => rejectedWith('createTaskNote', 'TASK_NOTE_LIMIT_EXCEEDED'), says: NOTE_LIMIT },
  ])('S6B-AC-85 C-4 기록 실패($what): "$says"를 보이고 쓴 글은 그대로 둔다', async ({ answer, says }) => {
    seed();
    state.issue = issueIn('IN_PROGRESS');
    route('post', notePath(A.id), () => answer());
    const { user } = renderAt(URL_7);
    const region = await opened();
    await expand(user, A.title);

    await user.type(await found(noteBoxNow, '"기록 남기기"'), '남기지 못할 기록');
    await user.click(await sendNoteButton());

    await sent('POST', notePath(A.id));
    await found(() => (text(region).includes(says) ? region : null), `"${says}"`);
    expect(noteBoxNow()?.value).toBe('남기지 못할 기록');
    if (says === NOTE_FAILED) await found(() => within(region).queryAllByRole('alert').find((el) => text(el).includes(says)), `role="alert" "${says}"`);
  });

  test('S6B-AC-85 C-4 기록 고치기: 자기 기록의 "고치기"가 그 글이 든 칸을 열고, "저장"이 PATCH 기록에 content를 보내며 "고침"이 붙는다', async () => {
    seed();
    state.issue = issueIn('IN_PROGRESS');
    const { user } = renderAt(URL_7);
    const region = await opened();
    await expand(user, A.title);

    await user.click(await actionOf(user, await noteLine(CHOI_NOTE.content), '고치기'));
    const box = await found(
      () => Array.from(region.querySelectorAll<HTMLTextAreaElement>('textarea')).find((t) => t.value === CHOI_NOTE.content),
      '그 글이 든 고치기 칸',
    );
    await user.clear(box);
    await user.type(box, '확인했고 고객에게 알렸습니다.');
    await user.click(await found(() => within(region).queryByRole('button', { name: '저장' }), '"저장"'));

    await sent('PATCH', notePath(A.id, CHOI_NOTE.id));
    expect(await lastBody('PATCH', notePath(A.id, CHOI_NOTE.id))).toEqual({ content: '확인했고 고객에게 알렸습니다.' });
    const line = await noteLine('확인했고 고객에게 알렸습니다.');
    expect(text(line)).toContain('고침');
  });

  test('S6B-AC-85 C-4 기록 지우기: "지운 기록은 되돌릴 수 없습니다."로 묻고, "지우기"는 DELETE 뒤 "지워진 기록"·"최관리 님이 지움"이 된다', async () => {
    seed();
    state.issue = issueIn('IN_PROGRESS');
    const { user } = renderAt(URL_7);
    const region = await opened();
    await expand(user, A.title);

    await user.click(await actionOf(user, await noteLine(LEE_NOTE.content), '지우기'));
    const box = await found(openDialog, '지우기 확인');
    expect(text(box)).toContain('지운 기록은 되돌릴 수 없습니다.');
    expect(within(box).queryByRole('button', { name: '그만두기' })).not.toBeNull();
    await user.click(within(box).getByRole('button', { name: '지우기' }));

    await sent('DELETE', notePath(A.id, LEE_NOTE.id));
    await eventually(() => {
      expect(text(region)).not.toContain(LEE_NOTE.content);
    });
    const trace = await noteLine('지워진 기록');
    expect(text(trace)).toContain('최관리 님이 지움');
  });

  test.each([
    { what: 'STATUS → DONE', entry: change('STATUS', { oldValue: 'OPEN', newValue: 'DONE' }), says: ['이상담 님이 작업을 완료했습니다'] },
    { what: 'STATUS → OPEN', entry: change('STATUS', { oldValue: 'DONE', newValue: 'OPEN' }), says: ['이상담 님이 작업을 다시 열었습니다'] },
    { what: 'ASSIGNEE 새 사람', entry: change('ASSIGNEE', { newPerson: OTHER_AGENT_PERSON }), says: ['이상담 님이 담당자를 정상담 님으로 정했습니다'] },
    { what: 'ASSIGNEE 바꿈', entry: change('ASSIGNEE', { oldPerson: ADMIN_PERSON, newPerson: OTHER_AGENT_PERSON }), says: ['이상담 님이 담당자를 최관리 님에서 정상담 님으로 바꿨습니다'] },
    { what: 'ASSIGNEE 비움', entry: change('ASSIGNEE', { oldPerson: ADMIN_PERSON }), says: ['이상담 님이 담당자를 비웠습니다'] },
    { what: 'DUE_DATE 정함', entry: change('DUE_DATE', { newValue: '2026-10-20' }), says: ['이상담', '마감일을 2026년 10월 20일로 정했습니다'] },
    { what: 'DUE_DATE 바꿈', entry: change('DUE_DATE', { oldValue: '2026-10-20', newValue: '2026-11-03' }), says: ['이상담', '마감일을 2026년 10월 20일에서 2026년 11월 3일로 바꿨습니다'] },
    { what: 'DUE_DATE 지움', entry: change('DUE_DATE', { oldValue: '2026-10-20' }), says: ['이상담', '마감일을 지웠습니다'] },
    { what: 'TITLE', entry: change('TITLE', { oldValue: '로그 받기', newValue: '로그 모으기' }), says: ['이상담 님이 제목을 고쳤습니다', '로그 받기 → 로그 모으기'] },
    { what: '모르는 칸', entry: change('PRIORITY', { oldValue: 'LOW', newValue: 'HIGH' }), says: ['이상담 님이 작업을 바꿨습니다'] },
  ])('S6B-AC-85 C-4 시스템 항목($what): 문장으로 보이고 고치기·지우기가 없다', async ({ entry, says }) => {
    seed([task({ ...A, notes: [entry] })]);
    state.issue = issueIn('IN_PROGRESS');
    const { user } = renderAt(URL_7);
    await opened();
    await expand(user, A.title);

    const line = await noteLine(says[says.length - 1] ?? '');
    for (const part of says) expect(text(line)).toContain(part);
    expect(text(line), '날짜를 YYYY-MM-DD 그대로 쓰지 않는다').not.toMatch(/\d{4}-\d{2}-\d{2}/);
    expect(await actionsOf(user, line)).toEqual([]);
  });

  test('S6B-AC-85 C-4 흔적: 지운 기록은 "지워진 기록"·"최관리 님이 지움", 지운 작업은 "지워진 작업"·"이상담 님이 지움"이고 상태 단추도 펼칠 것도 없다', async () => {
    seed([task({ ...A, notes: [deletedNote({ id: 805 }), LEE_NOTE] }), D]);
    state.issue = issueIn('IN_PROGRESS');
    const { user } = renderAt(URL_7);
    const region = await opened();

    const gone = await taskLine('지워진 작업');
    expect(text(gone)).toContain('이상담 님이 지움');
    expect(within(gone).queryAllByRole('button', { name: /완료|다시 열기|^기록 \d+/ }), '지운 작업의 상태 단추와 "기록 n"').toEqual([]);

    await expand(user, A.title);
    const trace = await noteLine('지워진 기록');
    expect(text(trace)).toContain('최관리 님이 지움');
    expect(within(region).queryAllByRole('textbox', { name: '기록 남기기' }), '펼친 것은 A 하나').toHaveLength(1);
  });

  test('S6B-AC-85 C-4 지운 작업은 펼쳐지지 않는다: 줄에 펼침 단추가 없고, 줄과 그 안의 단추를 눌러도 기록이 열리지 않는다', async () => {
    seed([A, D]);
    state.issue = issueIn('IN_PROGRESS');
    const { user } = renderAt(URL_7);
    const region = await opened();
    await taskLine(A.title);
    const gone = await taskLine('지워진 작업');

    const toggles = [gone, ...within(gone).queryAllByRole('button')].filter((el) => el.hasAttribute('aria-expanded'));
    expect(toggles, '지운 작업 줄의 펼침 단추(aria-expanded)').toEqual([]);
    const words = Array.from(gone.querySelectorAll<HTMLElement>('*')).filter((el) => el.children.length === 0 && text(el).includes('지워진 작업'));
    for (const el of [gone, ...words, ...within(gone).queryAllByRole('button')]) await user.click(el);
    await settle();

    expect(noteBoxNow(), '지운 작업의 "기록 남기기"').toBeNull();
    expect(region.querySelector('[aria-expanded="true"]'), '펼친 것').toBeNull();
    expect(text(region), '펼치지 않은 A의 기록').not.toContain(LEE_NOTE.content);
  });

  test('S6B-AC-85 C-4 CLOSED: 상태 단추, "작업 더하기", 바꾸기 둘, "기록 남기기", 기록과 줄 메뉴의 "고치기"가 없다. 작업과 기록의 "지우기"는 남는다', async () => {
    seed();
    state.issue = issueIn('CLOSED');
    const { user } = renderAt(URL_7);
    const region = await opened();
    const a = await taskLine(A.title);
    await taskLine(C.title);

    expect(statusButtonNow(A.title, 'DONE'), 'A의 "완료"').toBeNull();
    expect(statusButtonNow(C.title, 'OPEN'), 'C의 "다시 열기"').toBeNull();
    expect(within(region).queryByRole('button', { name: '작업 더하기' })).toBeNull();
    expect(await actionsOf(user, a)).toEqual(['지우기']);

    await expand(user, A.title);
    await noteLine(CHOI_NOTE.content);
    expect(within(region).queryByRole('button', { name: '작업 담당자 바꾸기' })).toBeNull();
    expect(within(region).queryByRole('button', { name: '작업 마감일 바꾸기' })).toBeNull();
    expect(noteBoxNow(), '"기록 남기기"').toBeNull();
    expect(await actionsOf(user, await noteLine(CHOI_NOTE.content))).toEqual(['지우기']);
  });

  test('S6B-AC-85 C-4 실패 줄: 상태를 바꾸지 못하면 작업 구역에 role="alert" "작업을 바꾸지 못했습니다. 다시 해 보세요."이고 줄은 그대로다', async () => {
    seed();
    state.issue = issueIn('IN_PROGRESS');
    route('patch', taskPath(A.id), () => errors.internal());
    const { user } = renderAt(URL_7);
    const region = await opened();

    await user.click(await statusButton(A.title, 'DONE'));

    await sent('PATCH', taskPath(A.id));
    await found(() => within(region).queryAllByRole('alert').find((el) => text(el).includes(FAILED)), `role="alert" "${FAILED}"`);
    expect(statusButtonNow(A.title, 'DONE'), '그대로인 "완료"').not.toBeNull();
  });

  test('S6B-AC-85 C-4 TASK_DELETED: 다른 창에서 지운 작업을 바꾸려다 400 TASK_DELETED를 받으면 작업 목록을 다시 읽어 흔적 줄을 보인다', async () => {
    seed();
    state.issue = issueIn('IN_PROGRESS');
    route('patch', taskPath(A.id), () => {
      seed([deletedTask({ id: A.id, createdBy: ADMIN_PERSON, deletedBy: ADMIN_PERSON }), B, C, D]);
      return rejectedWith('updateTask', 'TASK_DELETED');
    });
    const { user } = renderAt(URL_7);
    const region = await opened();
    await sent('GET', TASKS_7);
    const reads = callsTo('GET', TASKS_7).length;

    await user.click(await statusButton(A.title, 'DONE'));

    await sent('PATCH', taskPath(A.id));
    await eventually(() => {
      expect(callsTo('GET', TASKS_7).length, '작업 목록을 다시 읽는다').toBeGreaterThan(reads);
    });
    await eventually(() => {
      expect(text(region)).not.toContain(A.title);
    });
  });

  test('S6B-AC-85 C-4 TASK_NOTE_DELETED: 남이 지운 기록을 고치려다 400 TASK_NOTE_DELETED를 받으면 작업 목록을 다시 읽어 "지워진 기록"을 보인다', async () => {
    seed();
    state.issue = issueIn('IN_PROGRESS');
    route('patch', notePath(A.id, CHOI_NOTE.id), () => {
      seed([task({ ...A, notes: [LEE_NOTE, deletedNote({ id: CHOI_NOTE.id, author: ADMIN_PERSON }), A_CHANGE] }), B, C, D]);
      return rejectedWith('updateTaskNote', 'TASK_NOTE_DELETED');
    });
    const { user } = renderAt(URL_7);
    const region = await opened();
    await expand(user, A.title);
    const reads = callsTo('GET', TASKS_7).length;

    await user.click(await actionOf(user, await noteLine(CHOI_NOTE.content), '고치기'));
    const box = await found(() => Array.from(region.querySelectorAll<HTMLTextAreaElement>('textarea')).find((t) => t.value === CHOI_NOTE.content), '고치기 칸');
    await user.type(box, ' 더');
    await user.click(await found(() => within(region).queryByRole('button', { name: '저장' }), '"저장"'));

    await sent('PATCH', notePath(A.id, CHOI_NOTE.id));
    await eventually(() => {
      expect(callsTo('GET', TASKS_7).length, '작업 목록을 다시 읽는다').toBeGreaterThan(reads);
    });
    await noteLine('지워진 기록');
  });

  test.each([
    { what: '다른 사람', pick: '정상담', body: { assigneeId: 3 }, shows: '정상담' },
    { what: '담당 없음', pick: '담당 없음', body: { assigneeId: null }, shows: '담당 없음' },
  ])('S6B-AC-85 C-4 작업 담당자($what): "작업 담당자 바꾸기" 대화상자에서 고르고 "저장"이 PATCH에 assigneeId(정수 또는 null)만 보내며 줄의 담당자 칸이 바뀐다', async ({ pick, body, shows }) => {
    seed();
    state.issue = issueIn('IN_PROGRESS');
    const { user } = renderAt(URL_7);
    await opened();

    const box = await openTaskAssignee(user, A.title);
    expect(screen.queryAllByRole('button', { name: /^(담당자 )?바꾸기$/ }), '이름이 "바꾸기"나 "담당자 바꾸기"인 단추(문의의 것)').toHaveLength(1);
    await user.click(await found(() => within(box).queryByRole('radio', { name: new RegExp(pick) }), `"${pick}"`));
    await user.click(within(box).getByRole('button', { name: '저장' }));

    await sent('PATCH', taskPath(A.id));
    expect(await lastBody('PATCH', taskPath(A.id))).toEqual(body);
    await closed();
    // The opened notes may sit inside the line and now hold a system entry naming both people: the
    // assignee is read from the line itself.
    const line = await taskLine(A.title);
    await eventually(() => {
      expect(ownText(line), '줄의 담당자 칸').toContain(shows);
      expect(ownText(line), '줄의 담당자 칸에 앞 담당자').not.toContain(person(AGENT_PERSON));
    });
  });

  test.each([
    { what: '정하기', act: '정하기' as const, type: '2099-12-01', body: { dueDate: '2099-12-01' }, shows: '2099년 12월 1일' },
    { what: '지우기', act: '지우기' as const, type: null, body: { dueDate: null }, shows: null },
  ])('S6B-AC-85 C-4 작업 마감일($what): "작업 마감일 바꾸기"의 날짜 고르기가 PATCH에 dueDate(날짜 또는 null)만 보내고 줄의 마감일 칸을 다시 그린다', async ({ act, type, body, shows }) => {
    seed();
    state.issue = issueIn('IN_PROGRESS');
    const { user } = renderAt(URL_7);
    await opened();

    const input = await openTaskDue(user, A.title);
    if (type !== null) {
      await user.clear(input);
      await user.type(input, type);
    }
    await user.click(await dueButton(input, act));

    await sent('PATCH', taskPath(A.id));
    expect(await lastBody('PATCH', taskPath(A.id))).toEqual(body);
    // The opened notes show the old date in their system entries ("마감일을 2026년 10월 20일로 정했습니다"
    // and the one this change adds): the date is read from the line itself.
    const line = await taskLine(A.title);
    await eventually(() => {
      if (shows !== null) expect(ownText(line), '줄의 마감일 칸').toContain(shows);
      expect(ownText(line), '줄의 마감일 칸에 앞 날짜').not.toContain(longDate('2026-10-20'));
    });
  });

  test('S6B-AC-85 C-4 작업 마감일 지난 날짜: 서버의 400(OUT_OF_RANGE)은 "오늘이나 그 뒤의 날짜를 고르세요."다. 줄은 그대로다', async () => {
    seed();
    state.issue = issueIn('IN_PROGRESS');
    route('patch', taskPath(A.id), () => rejectedWith('updateTask', 'INVALID_INPUT', 400, [{ field: 'dueDate', code: 'OUT_OF_RANGE' }]));
    const { user } = renderAt(URL_7);
    const region = await opened();

    const input = await openTaskDue(user, A.title);
    await user.clear(input);
    await user.type(input, '2026-01-02');
    await user.click(await dueButton(input, '정하기'));

    await sent('PATCH', taskPath(A.id));
    await found(() => (text(region).includes(PAST_TEXT) ? region : null), `"${PAST_TEXT}"`);
    const line = await taskLine(A.title);
    expect(ownText(line), '줄의 마감일 칸').toContain(longDate('2026-10-20'));
    expect(ownText(line), '미리 바꿔 그린 날짜').not.toContain(longDate('2026-01-02'));
  });
});

describe('C-4 상태 바꾸기와 열린 작업 (6b 묶음)', () => {
  test.each([
    { what: '해결', from: 'IN_PROGRESS' as const, press: '해결', status: 'RESOLVED' },
    { what: '종료', from: 'CONFIRMED' as const, press: '종료', status: 'CLOSED' },
  ])('S6B-AC-86 C-4 열린 작업 0개의 "$what": 지금처럼 한다(해결은 묻지 않고 종료는 지금 문구만)', async ({ from, press, status }) => {
    seed([C]);
    state.issue = issueIn(from);
    const { user } = renderAt(URL_7);
    await opened();
    await sent('GET', TASKS_7);
    await settle();

    await user.click(await found(() => screen.queryByRole('button', { name: press }), `"${press}"`));
    if (press === '종료') {
      const box = await found(() => screen.queryByRole('alertdialog'), '확인 대화상자');
      expect(text(box)).toContain(CLOSE_ASK);
      expect(text(box)).not.toContain('끝나지 않은 작업');
      await user.click(within(box).getByRole('button', { name: '종료' }));
    } else {
      expect(openDialog(), '해결은 묻지 않는다').toBeNull();
    }
    await sent('PUT', `${ISSUE_7}/status`);
    expect(await lastBody('PUT', `${ISSUE_7}/status`)).toEqual({ status });
  });

  test('S6B-AC-86 C-4 열린 작업 2개의 "해결": "끝나지 않은 작업이 2개 있습니다. 그래도 해결로 바꿀까요?"로 묻고 "그만두기"는 보내지 않으며 "해결로 바꾸기"가 보낸다', async () => {
    seed();
    state.issue = issueIn('IN_PROGRESS');
    const { user } = renderAt(URL_7);
    await opened();
    await taskLine(A.title);

    await user.click(await found(() => screen.queryByRole('button', { name: '해결' }), '"해결"'));
    const first = await found(openDialog, '확인 대화상자');
    expect(text(first)).toContain('끝나지 않은 작업이 2개 있습니다. 그래도 해결로 바꿀까요?');
    await user.click(within(first).getByRole('button', { name: '그만두기' }));
    await closed();
    expect(callsTo('PUT', `${ISSUE_7}/status`)).toHaveLength(0);

    await user.click(await found(() => screen.queryByRole('button', { name: '해결' }), '"해결"'));
    const second = await found(openDialog, '확인 대화상자');
    await user.click(within(second).getByRole('button', { name: '해결로 바꾸기' }));
    await sent('PUT', `${ISSUE_7}/status`);
    expect(await lastBody('PUT', `${ISSUE_7}/status`)).toEqual({ status: 'RESOLVED' });
  });

  test('S6B-AC-86 C-4 열린 작업 2개의 "종료": 지금 문구 아래에 "끝나지 않은 작업이 2개 있습니다."를 더해 묻는다', async () => {
    seed();
    state.issue = issueIn('RESOLVED');
    const { user } = renderAt(URL_7);
    await opened();
    await taskLine(A.title);

    await user.click(await found(() => screen.queryByRole('button', { name: '종료' }), '"종료"'));
    const box = await found(() => screen.queryByRole('alertdialog'), '확인 대화상자');
    await eventually(() => {
      expect(text(box)).toContain('끝나지 않은 작업이 2개 있습니다.');
    });
    expect(text(box)).toContain(CLOSE_ASK);
    expect(text(box).indexOf(CLOSE_ASK), '지금 문구가 먼저').toBeLessThan(text(box).indexOf('끝나지 않은 작업'));
    await user.click(within(box).getByRole('button', { name: '종료' }));
    await sent('PUT', `${ISSUE_7}/status`);
    expect(await lastBody('PUT', `${ISSUE_7}/status`)).toEqual({ status: 'CLOSED' });
  });

  test('S6B-AC-86 C-4 작업 목록을 받기 전에 누른 "해결": 막지 않고 0개로 다룬다(묻지 않고 보낸다)', async () => {
    seed();
    state.issue = issueIn('IN_PROGRESS');
    const release = gate();
    route('get', TASKS_7, async () => {
      await release.opened;
      return tasksResponse(s6b.tasks['ACME-7'] ?? []);
    });
    const { user } = renderAt(URL_7);
    await waitFor(
      () => {
        expect(metaValueNow('담당자')).not.toBeNull();
      },
      { timeout: 5000 },
    );
    await sent('GET', TASKS_7);

    const resolve = await found(() => screen.queryByRole('button', { name: '해결' }), '"해결"');
    expect(isBlocked(resolve), '받기 전의 "해결"').toBe(false);
    await user.click(resolve);
    await sent('PUT', `${ISSUE_7}/status`);
    expect(screen.queryByRole('alertdialog'), '받기 전 누름은 묻지 않는다').toBeNull();
    release.open();
  });
});

describe('C-4 작업의 늦은 답 (6b 묶음)', () => {
  test('S6B-AC-89 C-4 작업 담당자 대화상자를 닫은 뒤 온 성공은 그 작업을 다시 그린다', async () => {
    seed();
    state.issue = issueIn('IN_PROGRESS');
    const release = gate();
    route('patch', taskPath(A.id), async () => {
      await release.opened;
      const saved = { ...A, assignee: OTHER_AGENT_PERSON };
      seed([saved, B, C, D]);
      return taskResponse(saved);
    });
    const { user } = renderAt(URL_7);
    await opened();
    const box = await openTaskAssignee(user, A.title);
    await user.click(await found(() => within(box).queryByRole('radio', { name: /정상담/ }), '"정상담"'));
    await user.click(within(box).getByRole('button', { name: '저장' }));
    await sent('PATCH', taskPath(A.id));

    await user.keyboard('{Escape}');
    await closed();
    const done = answered('PATCH', taskPath(A.id));
    release.open();
    await done;
    await settle();

    const line = await taskLine(A.title);
    await eventually(() => {
      expect(ownText(line), '닫힌 뒤 온 성공').toContain('정상담');
    });
    expect(openDialog(), '늦은 답이 대화상자를 다시 열지 않는다').toBeNull();
  });

  test('S6B-AC-89 C-4 작업 담당자 대화상자를 닫은 뒤 온 실패는 버린다: 오류가 없고 다시 열면 처음부터다', async () => {
    seed();
    state.issue = issueIn('IN_PROGRESS');
    const release = gate();
    route('patch', taskPath(A.id), async () => {
      await release.opened;
      return errors.internal();
    });
    const { user } = renderAt(URL_7);
    const region = await opened();
    const box = await openTaskAssignee(user, A.title);
    await user.click(await found(() => within(box).queryByRole('radio', { name: /정상담/ }), '"정상담"'));
    await user.click(within(box).getByRole('button', { name: '저장' }));
    await sent('PATCH', taskPath(A.id));

    await user.keyboard('{Escape}');
    await closed();
    const done = answered('PATCH', taskPath(A.id));
    release.open();
    await done;
    await settle();

    expect(text(region), '닫힌 뒤 온 실패').not.toContain(FAILED);
    const line = await taskLine(A.title);
    await eventually(() => {
      expect(ownText(line), '줄의 담당자 칸').toContain('이상담');
      expect(ownText(line), '미리 바꿔 그린 담당자가 되돌아갔다').not.toContain('정상담');
    });
    await user.click(await found(() => within(region).queryByRole('button', { name: '작업 담당자 바꾸기' }), '"작업 담당자 바꾸기"'));
    const again = await found(assigneeDialog, '다시 연 대화상자');
    expect(within(again).queryAllByRole('alert'), '앞 요청의 오류').toHaveLength(0);
  });

  test.each([
    { what: '성공', ok: true },
    { what: '실패', ok: false },
  ])('S6B-AC-89 C-4 작업 마감일 고르기를 닫은 뒤 온 답($what): 성공은 줄을 다시 그리고 실패는 버린다', async ({ ok }) => {
    seed();
    state.issue = issueIn('IN_PROGRESS');
    const release = gate();
    route('patch', taskPath(A.id), async () => {
      await release.opened;
      if (!ok) return rejectedWith('updateTask', 'INVALID_INPUT', 400, [{ field: 'dueDate', code: 'OUT_OF_RANGE' }]);
      const saved = { ...A, dueDate: '2099-12-01', overdue: false };
      seed([saved, B, C, D]);
      return taskResponse(saved);
    });
    const { user } = renderAt(URL_7);
    const region = await opened();
    const input = await openTaskDue(user, A.title);
    await user.clear(input);
    await user.type(input, '2099-12-01');
    await user.click(await dueButton(input, '정하기'));
    await sent('PATCH', taskPath(A.id));

    await user.keyboard('{Escape}');
    await eventually(() => {
      expect(taskDueInputNow(), '닫힌 고르기').toBeNull();
    });
    const done = answered('PATCH', taskPath(A.id));
    release.open();
    await done;
    await settle();

    const line = await taskLine(A.title);
    if (ok) {
      await eventually(() => {
        expect(ownText(line), '닫힌 뒤 온 성공').toContain('2099년 12월 1일');
      });
    } else {
      expect(text(region), '닫힌 뒤 온 400').not.toContain(PAST_TEXT);
      await eventually(() => {
        expect(ownText(line), '줄의 마감일 칸').toContain(longDate('2026-10-20'));
        expect(ownText(line), '미리 바꿔 그린 날짜가 되돌아갔다').not.toContain('2099년 12월 1일');
      });
    }
  });

  test('S6B-AC-89 C-4 다른 문의로 옮긴 뒤 온 작업 목록은 버린다: 새 문의의 작업만 있다', async () => {
    seed();
    seed([task({ id: 61, title: '다른 문의의 작업' })], 'ACME-8');
    state.issue = issueIn('IN_PROGRESS');
    state.issues = [summary({ key: 'ACME-8', title: '다른 문의입니다' })];
    const release = gate();
    route('get', TASKS_7, async () => {
      await release.opened;
      return tasksResponse(s6b.tasks['ACME-7'] ?? []);
    });
    const { user } = renderAt(URL_7);
    await waitFor(
      () => {
        expect(metaValueNow('담당자')).not.toBeNull();
      },
      { timeout: 5000 },
    );
    await sent('GET', TASKS_7);

    state.issue = issueIn('IN_PROGRESS', { key: 'ACME-8', title: '다른 문의입니다' });
    await goToIssue(user, /다른 문의입니다/, 'ACME-8');
    await taskLine('다른 문의의 작업');
    const done = answered('GET', TASKS_7);
    release.open();
    await done;
    await settle();

    const region = await taskRegion();
    expect(text(region), 'ACME-7의 작업').not.toContain(A.title);
    expect(text(region)).toContain('다른 문의의 작업');
    expect(regionName(region)).toBe('작업 1/1');
  });

  test.each([
    { what: '만들기 성공', method: 'post' as const, path: TASKS_7, start: 'create', answer: () => taskResponse(task({ id: 77, title: '늦게 온 작업' }), 'createTask', 201) },
    { what: '만들기 실패', method: 'post' as const, path: TASKS_7, start: 'create', answer: () => errors.internal() },
    { what: '고치기 성공', method: 'patch' as const, path: taskPath(41), start: 'status', answer: () => taskResponse(doneTask({ ...A, status: 'DONE', doneBy: ADMIN_PERSON, doneAt: '2026-10-09T02:00:00Z', overdue: false })) },
    { what: '고치기 실패', method: 'patch' as const, path: taskPath(41), start: 'status', answer: () => errors.internal() },
  ])('S6B-AC-89 C-4 다른 문의로 옮긴 뒤 온 $what 답은 버린다: 새 문의의 작업 구역이 그대로다', async ({ method, path, start, answer }) => {
    seed();
    seed([task({ id: 61, title: '다른 문의의 작업' })], 'ACME-8');
    state.issue = issueIn('IN_PROGRESS');
    state.issues = [summary({ key: 'ACME-8', title: '다른 문의입니다' })];
    const release = gate();
    route(method, path, async () => {
      await release.opened;
      return answer();
    });
    const { user } = renderAt(URL_7);
    const region7 = await opened();
    if (start === 'create') {
      await user.click(await found(() => within(region7).queryByRole('button', { name: '작업 더하기' }), '"작업 더하기"'));
      await user.type(await found(() => within(region7).queryByRole('textbox', { name: '작업 제목' }), '칸 "작업 제목"'), '늦게 온 작업{Enter}');
    } else {
      await user.click(await statusButton(A.title, 'DONE'));
    }
    await sent(method.toUpperCase(), path);

    state.issue = issueIn('IN_PROGRESS', { key: 'ACME-8', title: '다른 문의입니다' });
    await goToIssue(user, /다른 문의입니다/, 'ACME-8');
    await taskLine('다른 문의의 작업');
    const done = answered(method.toUpperCase(), path);
    release.open();
    await done;
    await settle();

    const region = await taskRegion();
    expect(text(region), 'ACME-7의 답').not.toContain('늦게 온 작업');
    expect(text(region), 'ACME-7의 작업').not.toContain(A.title);
    expect(text(region), '옮긴 뒤 온 실패').not.toContain(FAILED);
    expect(outerLine(region, '다른 문의의 작업'), 'ACME-8의 줄').not.toBeNull();
    expect(statusButtonNow('다른 문의의 작업', 'DONE'), 'ACME-8 작업의 "완료"').not.toBeNull();
    expect(taskRegionNow()).not.toBeNull();
  });
});
