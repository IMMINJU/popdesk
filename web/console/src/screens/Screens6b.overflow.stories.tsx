// 6b 묶음이 바꾼 콘솔 화면(C-4 문의 상세의 작업 구역, C-15 내 작업)을 상태마다 360·820·1280 폭, 밝게·어둡게에서
// 그리고, play에서 테마가 그것인지, 페이지와 바뀐 부품이 가로로 넘치지 않는지, axe(대비 포함) 위반이 없는지 본다
// (screens.md 5절, S6B-AC-90). 화면은 가짜 fetch(__tests__/storyFetch.ts와 storyFetch6b.ts)의 답으로 그린다. C-15의
// 표는 안에서 옆으로 스크롤해도 되므로 표 자체는 재지 않는다(screens.md 2절 좁은 폭).
//
// 화면은 앱 전체를 그 경로에서 그린다: C-15의 화면 파일 이름은 구현이 정한다.
//
// The fake fetch comes first: src/api.ts keeps the fetch it finds when it is first imported.
import { ADMIN, company, issue, scenario, setScenario, summary, type Scenario } from '../__tests__/storyFetch';
import { CHOI, LEE, LONG_TITLE, myTask, note, setTaskScenario, task, type TaskScenario } from '../__tests__/storyFetch6b';
import type { ReactElement } from 'react';
import '@popdesk/ui/tokens.css';
import '@popdesk/ui/styles.css';
import '../console.css';
import { at, buttonNamed, expectDrawnRight, namedPart, termValue, textOf, waitFor, type Theme, type Width } from '../../../packages/ui/src/__tests__/overflow';
import { App } from '../App';

interface PlayContext {
  canvasElement: HTMLElement;
}

interface Story {
  render: () => ReactElement;
  play: (context: PlayContext) => Promise<void>;
  parameters: ReturnType<typeof at>['parameters'] & { layout: 'fullscreen' };
  globals: ReturnType<typeof at>['globals'];
}

const meta = { title: 'Overflow/6b 콘솔 화면' };
export default meta;

type Data = { screen: Scenario; tasks: TaskScenario };
type Found = { el: Element; what: string }[];

const OPEN_ISSUE = issue();
const CLOSED_ISSUE = issue({ status: 'CLOSED', nextStatuses: [], closedAt: '2026-10-09T03:15:00Z' });

function data(tasks: Partial<TaskScenario>, one = OPEN_ISSUE): Data {
  return { screen: scenario({ me: ADMIN, companies: [company()], issue: one, issues: [summary(one)] }), tasks: { tasks: [], mine: [], ...tasks } };
}

/** Tasks of every kind: overdue with an assignee, done, nobody's, a long title, deleted. */
const MIXED = [
  task({ id: 41, notes: [note(), note({ id: 802, author: CHOI, content: '확인했습니다.', editedAt: '2026-10-09T05:00:00Z' }), note({ id: 803, kind: 'CHANGE', content: '', field: 'DUE_DATE', oldValue: '2026-10-20', newValue: '2026-11-03' })] }),
  task({ id: 42, title: '고객에게 전화하기', assignee: null, dueDate: null, overdue: false }),
  task({ id: 43, title: '재현 절차 정리', status: 'DONE', doneBy: CHOI, doneAt: '2026-10-09T02:00:00Z', overdue: false }),
  task({ id: 44, title: LONG_TITLE, assignee: CHOI, dueDate: '2099-12-31', overdue: false }),
  task({ id: 45, title: '', assignee: null, dueDate: null, overdue: false, createdBy: LEE, deleted: true, deletedBy: LEE, deletedAt: '2026-10-10T04:05:06Z' }),
];

const NONE = data({ tasks: [] });
const MANY = data({ tasks: MIXED });
const FULL = data({ tasks: Array.from({ length: 30 }, (_, i) => task({ id: 100 + i, title: `${LONG_TITLE.slice(0, 60)} ${String(i + 1).padStart(2, '0')}`, assignee: i % 2 === 0 ? LEE : null })) });
const LONG = data({ tasks: [task({ id: 41, title: LONG_TITLE, notes: [note({ content: LONG_TITLE.replace(/ /g, '') })] })] });
const TRACES = data({
  tasks: [
    task({ id: 41, notes: [note({ id: 801, content: '', deleted: true, deletedBy: CHOI, deletedAt: '2026-10-10T04:05:06Z' }), note({ id: 802 })] }),
    MIXED[4] ?? task(),
  ],
});
const CLOSED = data({ tasks: MIXED }, CLOSED_ISSUE);

const MINE_OPEN = data({
  mine: [
    myTask(),
    myTask({ id: 52, title: '고객에게 전화하기', dueDate: null, overdue: false, issue: { key: 'BETA-3', title: '결제가 두 번 됩니다', status: 'CONFIRMED' }, companyCode: 'BETA', companyName: '베타상사' }),
    myTask({ id: 63, title: '재현 절차 정리', dueDate: '2099-12-31', overdue: false }),
  ],
});
const MINE_DONE = data({ mine: [myTask({ id: 44, status: 'DONE', doneAt: '2026-10-09T02:00:00Z', overdue: false })] });
const MINE_EMPTY = data({ mine: [] });
const MINE_LONG = data({ mine: [myTask({ title: LONG_TITLE, issue: { key: 'ACME-1234', title: LONG_TITLE, status: 'IN_PROGRESS' }, companyName: '에이크미 글로벌 결제 서비스 주식회사' })] });

function screenStory(width: Width, theme: Theme, given: Data, path: string, parts: (canvas: HTMLElement) => Promise<Found>): Story {
  const { parameters, globals } = at(width, theme, 'console');
  return {
    parameters: { ...parameters, layout: 'fullscreen' },
    globals,
    render: () => {
      setScenario(given.screen);
      setTaskScenario(given.tasks);
      window.history.replaceState(null, '', path);
      return <App />;
    },
    play: async ({ canvasElement }) => {
      await expectDrawnRight(width, theme, await parts(canvasElement));
    },
  };
}

// C-4 문의 상세의 작업 구역

// The screens drawn at all, by a part that was there before this slice: a failure past this
// point is about the slice, not about the fake server or the address.
const detailDrawn = () => waitFor(() => termValue(document, '담당자'), 'C-4의 칸 "담당자"(화면이 그려짐)', 8000);
const layoutDrawn = () => waitFor(() => document.querySelector('nav#pd-shell-menu a'), '콘솔 옆 메뉴(화면이 그려짐)', 8000);

async function region(canvas: HTMLElement): Promise<HTMLElement> {
  await detailDrawn();
  return waitFor(() => namedPart(canvas, /^작업( \d+\/\d+)?$/), 'C-4의 구역 "작업"');
}

async function shows(canvas: HTMLElement, part: string): Promise<Found> {
  const r = await region(canvas);
  await waitFor(() => (textOf(r).includes(part) ? true : null), `작업 구역의 "${part}"`);
  return [{ el: r, what: 'C-4의 구역 "작업"' }];
}

/** Opens the notes of the first task (its "기록 n" button) and waits for `part` in them. */
async function opened(canvas: HTMLElement, part: string): Promise<Found> {
  const r = await region(canvas);
  const toggle = await waitFor(() => Array.from(r.querySelectorAll('button')).find((b) => /^기록 \d+/.test(textOf(b))), '첫 작업의 "기록 n"');
  toggle.click();
  await waitFor(() => (textOf(r).includes(part) ? true : null), `펼친 기록의 "${part}"`);
  return [{ el: r, what: 'C-4의 구역 "작업"(펼침)' }];
}

const tasksNone = (canvas: HTMLElement) => shows(canvas, '아직 작업이 없습니다.');
const tasksMany = (canvas: HTMLElement) => shows(canvas, '지워진 작업');
const tasksFull = (canvas: HTMLElement) => shows(canvas, '작업 30/30');
const tasksLong = (canvas: HTMLElement) => shows(canvas, LONG_TITLE);
const tasksOpened = (canvas: HTMLElement) => opened(canvas, '확인했습니다.');
const tasksTraces = (canvas: HTMLElement) => opened(canvas, '지워진 기록');

async function tasksClosed(canvas: HTMLElement): Promise<Found> {
  const found = await shows(canvas, '재현 절차 정리');
  const r = found[0]?.el as HTMLElement;
  if (buttonNamed(r, '작업 더하기')) throw new Error('CLOSED 문의에 "작업 더하기"가 남았다');
  return found;
}

// C-15 내 작업

async function mine(canvas: HTMLElement, part: string): Promise<Found> {
  await layoutDrawn();
  const main = await waitFor(() => canvas.querySelector('main') ?? document.querySelector('main'), 'C-15의 main');
  await waitFor(() => (textOf(main).includes(part) ? true : null), `C-15의 "${part}"`);
  const views = await waitFor(() => Array.from(main.querySelectorAll('nav')).find((n) => textOf(n).includes('끝낸 일')), 'C-15의 보기("할 일", "끝낸 일")');
  return [
    { el: main, what: 'C-15의 main' },
    { el: views, what: 'C-15의 보기' },
  ];
}

const mineOpen = (canvas: HTMLElement) => mine(canvas, '내 작업, 3건');
const mineDone = (canvas: HTMLElement) => mine(canvas, '내 작업, 1건');
const mineEmpty = (canvas: HTMLElement) => mine(canvas, '맡은 작업이 없습니다.');
const mineLong = (canvas: HTMLElement) => mine(canvas, LONG_TITLE);

const URL_7 = '/issues/ACME-7';

export const C4TasksNone360Light = screenStory(360, 'light', NONE, URL_7, tasksNone);
export const C4TasksNone360Dark = screenStory(360, 'dark', NONE, URL_7, tasksNone);
export const C4TasksNone820Light = screenStory(820, 'light', NONE, URL_7, tasksNone);
export const C4TasksNone820Dark = screenStory(820, 'dark', NONE, URL_7, tasksNone);
export const C4TasksNone1280Light = screenStory(1280, 'light', NONE, URL_7, tasksNone);
export const C4TasksNone1280Dark = screenStory(1280, 'dark', NONE, URL_7, tasksNone);

export const C4TasksMany360Light = screenStory(360, 'light', MANY, URL_7, tasksMany);
export const C4TasksMany360Dark = screenStory(360, 'dark', MANY, URL_7, tasksMany);
export const C4TasksMany820Light = screenStory(820, 'light', MANY, URL_7, tasksMany);
export const C4TasksMany820Dark = screenStory(820, 'dark', MANY, URL_7, tasksMany);
export const C4TasksMany1280Light = screenStory(1280, 'light', MANY, URL_7, tasksMany);
export const C4TasksMany1280Dark = screenStory(1280, 'dark', MANY, URL_7, tasksMany);

export const C4TasksFull360Light = screenStory(360, 'light', FULL, URL_7, tasksFull);
export const C4TasksFull360Dark = screenStory(360, 'dark', FULL, URL_7, tasksFull);
export const C4TasksFull820Light = screenStory(820, 'light', FULL, URL_7, tasksFull);
export const C4TasksFull820Dark = screenStory(820, 'dark', FULL, URL_7, tasksFull);
export const C4TasksFull1280Light = screenStory(1280, 'light', FULL, URL_7, tasksFull);
export const C4TasksFull1280Dark = screenStory(1280, 'dark', FULL, URL_7, tasksFull);

export const C4TasksLong360Light = screenStory(360, 'light', LONG, URL_7, tasksLong);
export const C4TasksLong360Dark = screenStory(360, 'dark', LONG, URL_7, tasksLong);
export const C4TasksLong820Light = screenStory(820, 'light', LONG, URL_7, tasksLong);
export const C4TasksLong820Dark = screenStory(820, 'dark', LONG, URL_7, tasksLong);
export const C4TasksLong1280Light = screenStory(1280, 'light', LONG, URL_7, tasksLong);
export const C4TasksLong1280Dark = screenStory(1280, 'dark', LONG, URL_7, tasksLong);

export const C4TasksOpened360Light = screenStory(360, 'light', MANY, URL_7, tasksOpened);
export const C4TasksOpened360Dark = screenStory(360, 'dark', MANY, URL_7, tasksOpened);
export const C4TasksOpened820Light = screenStory(820, 'light', MANY, URL_7, tasksOpened);
export const C4TasksOpened820Dark = screenStory(820, 'dark', MANY, URL_7, tasksOpened);
export const C4TasksOpened1280Light = screenStory(1280, 'light', MANY, URL_7, tasksOpened);
export const C4TasksOpened1280Dark = screenStory(1280, 'dark', MANY, URL_7, tasksOpened);

export const C4TasksTraces360Light = screenStory(360, 'light', TRACES, URL_7, tasksTraces);
export const C4TasksTraces360Dark = screenStory(360, 'dark', TRACES, URL_7, tasksTraces);
export const C4TasksTraces820Light = screenStory(820, 'light', TRACES, URL_7, tasksTraces);
export const C4TasksTraces820Dark = screenStory(820, 'dark', TRACES, URL_7, tasksTraces);
export const C4TasksTraces1280Light = screenStory(1280, 'light', TRACES, URL_7, tasksTraces);
export const C4TasksTraces1280Dark = screenStory(1280, 'dark', TRACES, URL_7, tasksTraces);

export const C4TasksClosed360Light = screenStory(360, 'light', CLOSED, URL_7, tasksClosed);
export const C4TasksClosed360Dark = screenStory(360, 'dark', CLOSED, URL_7, tasksClosed);
export const C4TasksClosed820Light = screenStory(820, 'light', CLOSED, URL_7, tasksClosed);
export const C4TasksClosed820Dark = screenStory(820, 'dark', CLOSED, URL_7, tasksClosed);
export const C4TasksClosed1280Light = screenStory(1280, 'light', CLOSED, URL_7, tasksClosed);
export const C4TasksClosed1280Dark = screenStory(1280, 'dark', CLOSED, URL_7, tasksClosed);

export const C15Open360Light = screenStory(360, 'light', MINE_OPEN, '/tasks', mineOpen);
export const C15Open360Dark = screenStory(360, 'dark', MINE_OPEN, '/tasks', mineOpen);
export const C15Open820Light = screenStory(820, 'light', MINE_OPEN, '/tasks', mineOpen);
export const C15Open820Dark = screenStory(820, 'dark', MINE_OPEN, '/tasks', mineOpen);
export const C15Open1280Light = screenStory(1280, 'light', MINE_OPEN, '/tasks', mineOpen);
export const C15Open1280Dark = screenStory(1280, 'dark', MINE_OPEN, '/tasks', mineOpen);

export const C15Done360Light = screenStory(360, 'light', MINE_DONE, '/tasks?status=DONE', mineDone);
export const C15Done360Dark = screenStory(360, 'dark', MINE_DONE, '/tasks?status=DONE', mineDone);
export const C15Done820Light = screenStory(820, 'light', MINE_DONE, '/tasks?status=DONE', mineDone);
export const C15Done820Dark = screenStory(820, 'dark', MINE_DONE, '/tasks?status=DONE', mineDone);
export const C15Done1280Light = screenStory(1280, 'light', MINE_DONE, '/tasks?status=DONE', mineDone);
export const C15Done1280Dark = screenStory(1280, 'dark', MINE_DONE, '/tasks?status=DONE', mineDone);

export const C15Empty360Light = screenStory(360, 'light', MINE_EMPTY, '/tasks', mineEmpty);
export const C15Empty360Dark = screenStory(360, 'dark', MINE_EMPTY, '/tasks', mineEmpty);
export const C15Empty820Light = screenStory(820, 'light', MINE_EMPTY, '/tasks', mineEmpty);
export const C15Empty820Dark = screenStory(820, 'dark', MINE_EMPTY, '/tasks', mineEmpty);
export const C15Empty1280Light = screenStory(1280, 'light', MINE_EMPTY, '/tasks', mineEmpty);
export const C15Empty1280Dark = screenStory(1280, 'dark', MINE_EMPTY, '/tasks', mineEmpty);

export const C15Long360Light = screenStory(360, 'light', MINE_LONG, '/tasks', mineLong);
export const C15Long360Dark = screenStory(360, 'dark', MINE_LONG, '/tasks', mineLong);
export const C15Long820Light = screenStory(820, 'light', MINE_LONG, '/tasks', mineLong);
export const C15Long820Dark = screenStory(820, 'dark', MINE_LONG, '/tasks', mineLong);
export const C15Long1280Light = screenStory(1280, 'light', MINE_LONG, '/tasks', mineLong);
export const C15Long1280Dark = screenStory(1280, 'dark', MINE_LONG, '/tasks', mineLong);
