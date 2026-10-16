// 7b 묶음이 더한 콘솔 화면 C-5 통계를 상태마다(여럿, 긴 고객사 이름, 큰 수 123,456, 비었음) 360·820·1280 폭, 밝게·어둡게에서
// 그리고, play에서 테마가 그것인지, 페이지와 화면 본문(main)이 가로로 넘치지 않는지, axe(대비 포함) 위반이 없는지 본다
// (screens.md 2절, S7B-AC-22). 360에서 표는 안에서 옆으로 스크롤하므로 표의 스크롤 틀은 넘침을 보지 않고, 표가 옆으로
// 스크롤하는 틀(overflow-x auto 또는 scroll) 안에 있는지 본다. 화면은 가짜 fetch(__tests__/storyFetch.ts, storyFetch7b.ts)의
// 답으로 그린다.
//
// The fake fetch comes first: src/api.ts keeps the fetch it finds when it is first imported.
import { ADMIN, company, issue, scenario, setScenario, summary, type Scenario } from '../__tests__/storyFetch';
import { setTaskScenario } from '../__tests__/storyFetch6b';
import { LONG_COMPANY, setLinkScenario } from '../__tests__/storyFetch6c';
import { setStatsScenario, stat, type StatsScenario } from '../__tests__/storyFetch7b';
import type { ReactElement } from 'react';
import '@popdesk/ui/tokens.css';
import '@popdesk/ui/styles.css';
import '../console.css';
import { at, expectDrawnRight, textOf, waitFor, type Theme, type Width } from '../../../packages/ui/src/__tests__/overflow';
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

const meta = { title: 'Overflow/7b 콘솔 화면' };
export default meta;

type Data = { screen: Scenario; stats: StatsScenario };
type Found = { el: Element; what: string }[];

const OPEN_ISSUE = issue();

function data(items: StatsScenario['items']): Data {
  return { screen: scenario({ me: ADMIN, companies: [company()], issue: OPEN_ISSUE, issues: [summary(OPEN_ISSUE)] }), stats: { items } };
}

const NAMES = ['에이크미', '베타상사', '감마물산', '델타테크', '엡실론', '제타소프트', '에타로지스', '세타유통', '이오타랩', '카파금융', '람다식품', '뮤헬스'];
// 계약 차례(열림이 많은 순)로 두되 코드는 거꾸로다: 열림이 가장 많은 곳이 C12다.
const MANY = NAMES.map((name, i) =>
  stat({
    companyCode: `C${String(NAMES.length - i).padStart(2, '0')}`,
    companyName: name,
    companyStatus: i === 10 ? 'ARCHIVED' : i === 11 ? 'SUSPENDED' : 'ACTIVE',
    open: 12 - i,
    inProgress: (i * 3) % 7,
    resolved: i % 3 === 0 ? 0 : i,
    closed: i * 11,
  }),
);

const SEVERAL = data(MANY);
const LONG_NAMED = data([stat({ companyName: LONG_COMPANY }), stat({ companyCode: 'OLD', companyName: `${LONG_COMPANY} 옛 법인`, companyStatus: 'ARCHIVED', open: 0, closed: 31 })]);
const BIG = data([stat({ total: 493824, open: 123456, inProgress: 123456, resolved: 123456, closed: 123456 }), stat({ companyCode: 'BETA', companyName: '베타상사', open: 98765, closed: 123456 })]);
const EMPTY = data([]);

function screenStory(width: Width, theme: Theme, given: Data, parts: (canvas: HTMLElement, width: Width) => Promise<Found>): Story {
  const { parameters, globals } = at(width, theme, 'console');
  return {
    parameters: { ...parameters, layout: 'fullscreen' },
    globals,
    render: () => {
      setScenario(given.screen);
      setTaskScenario({ tasks: [], mine: [] });
      setLinkScenario({ links: [], others: [], linkError: null });
      setStatsScenario(given.stats);
      window.history.replaceState(null, '', '/stats');
      return <App />;
    },
    play: async ({ canvasElement }) => {
      await expectDrawnRight(width, theme, await parts(canvasElement, width));
    },
  };
}

function mainOf(canvas: HTMLElement): HTMLElement | null {
  return canvas.querySelector('main') ?? document.querySelector('main');
}

/** C-5 drawn: its h1 "통계", then `part` in its main. */
async function statsShows(canvas: HTMLElement, part: string): Promise<{ main: HTMLElement; found: Found }> {
  await waitFor(() => Array.from(document.querySelectorAll('h1')).find((h) => textOf(h) === '통계'), 'C-5의 h1 "통계"', 8000);
  const main = await waitFor(() => mainOf(canvas), 'C-5의 main');
  await waitFor(() => (textOf(main).includes(part) ? true : null), `C-5의 "${part}"`);
  return { main, found: [{ el: main, what: 'C-5의 main' }] };
}

/** Throws unless the table of `main` sits in a box inside `main` that scrolls sideways. */
function expectTableScrollsInside(main: HTMLElement): void {
  const table = main.querySelector('table');
  if (table === null) throw new Error('C-5의 표가 없다');
  for (let el = table.parentElement; el !== null && el !== main; el = el.parentElement) {
    const overflowX = getComputedStyle(el).overflowX;
    if (overflowX === 'auto' || overflowX === 'scroll') return;
  }
  throw new Error('C-5의 표가 옆으로 스크롤하는 틀 안에 있지 않다');
}

function withTable(part: string) {
  return async (canvas: HTMLElement, width: Width): Promise<Found> => {
    const { main, found } = await statsShows(canvas, part);
    if (width === 360) expectTableScrollsInside(main);
    return found;
  };
}

const statsSeveral = withTable('뮤헬스');
const statsLongCompany = withTable(LONG_COMPANY);
const statsBig = withTable('123,456');
const statsEmpty = async (canvas: HTMLElement): Promise<Found> => (await statsShows(canvas, '아직 고객사가 없습니다.')).found;

export const C5Several360Light = screenStory(360, 'light', SEVERAL, statsSeveral);
export const C5Several360Dark = screenStory(360, 'dark', SEVERAL, statsSeveral);
export const C5Several820Light = screenStory(820, 'light', SEVERAL, statsSeveral);
export const C5Several820Dark = screenStory(820, 'dark', SEVERAL, statsSeveral);
export const C5Several1280Light = screenStory(1280, 'light', SEVERAL, statsSeveral);
export const C5Several1280Dark = screenStory(1280, 'dark', SEVERAL, statsSeveral);

export const C5LongCompany360Light = screenStory(360, 'light', LONG_NAMED, statsLongCompany);
export const C5LongCompany360Dark = screenStory(360, 'dark', LONG_NAMED, statsLongCompany);
export const C5LongCompany820Light = screenStory(820, 'light', LONG_NAMED, statsLongCompany);
export const C5LongCompany820Dark = screenStory(820, 'dark', LONG_NAMED, statsLongCompany);
export const C5LongCompany1280Light = screenStory(1280, 'light', LONG_NAMED, statsLongCompany);
export const C5LongCompany1280Dark = screenStory(1280, 'dark', LONG_NAMED, statsLongCompany);

export const C5BigCount360Light = screenStory(360, 'light', BIG, statsBig);
export const C5BigCount360Dark = screenStory(360, 'dark', BIG, statsBig);
export const C5BigCount820Light = screenStory(820, 'light', BIG, statsBig);
export const C5BigCount820Dark = screenStory(820, 'dark', BIG, statsBig);
export const C5BigCount1280Light = screenStory(1280, 'light', BIG, statsBig);
export const C5BigCount1280Dark = screenStory(1280, 'dark', BIG, statsBig);

export const C5Empty360Light = screenStory(360, 'light', EMPTY, statsEmpty);
export const C5Empty360Dark = screenStory(360, 'dark', EMPTY, statsEmpty);
export const C5Empty820Light = screenStory(820, 'light', EMPTY, statsEmpty);
export const C5Empty820Dark = screenStory(820, 'dark', EMPTY, statsEmpty);
export const C5Empty1280Light = screenStory(1280, 'light', EMPTY, statsEmpty);
export const C5Empty1280Dark = screenStory(1280, 'dark', EMPTY, statsEmpty);
