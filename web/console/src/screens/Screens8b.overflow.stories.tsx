// 8b 묶음이 바꾼 콘솔 화면을 360·820·1280 폭, 밝게·어둡게에서 그리고, play에서 테마가 그것인지, 페이지와 화면 본문(main)이
// 가로로 넘치지 않는지, axe(대비 포함) 위반이 없는지 본다(screens.md 2절·3절): C-5 통계의 정책 켜짐(긴 고객사 이름, 보관 배지,
// 중앙값 "2일 23시간", "없음", "중앙값 없음"), C-7 알림함의 SLA 항목(수가 긴 다시 계산 요약, 긴 제목의 넘김). 360에서 C-5 표는
// 안에서 옆으로 스크롤하므로 표가 옆으로 스크롤하는 틀(overflow-x auto 또는 scroll) 안에 있는지, 고객사 칸이 고정(sticky)인지
// 본다. 화면은 가짜 fetch(__tests__/storyFetch.ts, storyFetch8b.ts)의 답으로 그린다.
//
// The fake fetch comes first: src/api.ts keeps the fetch it finds when it is first imported.
import { ADMIN, company, issue, scenario, setScenario, summary, type Scenario } from '../__tests__/storyFetch';
import { LONG_TITLE, setTaskScenario } from '../__tests__/storyFetch6b';
import { LONG_COMPANY, setLinkScenario } from '../__tests__/storyFetch6c';
import { inboxItem, setScenario8b, slaStat, type Scenario8b } from '../__tests__/storyFetch8b';
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

const meta = { title: 'Overflow/8b 콘솔 화면' };
export default meta;

type Data = { screen: Scenario; given: Scenario8b; path: string };
type Found = { el: Element; what: string }[];

const OPEN_ISSUE = issue();

function data(given: Scenario8b, path: string): Data {
  return { screen: scenario({ me: ADMIN, companies: [company()], issue: OPEN_ISSUE, issues: [summary(OPEN_ISSUE)] }), given, path };
}

// C-5 켜짐: 긴 이름(중앙값 2일 23시간), 보관한 곳(긴 이름과 배지), 셀 것 없음과 중앙값만 없음, 큰 수
const STATS_ON = data(
  {
    stats: [
      slaStat({ firstResponse: { medianMinutes: 4260, met: 249, missed: 1 }, resolution: { medianMinutes: 4260, met: 123456, missed: 98765 } }, { companyName: LONG_COMPANY }),
      slaStat(
        { firstResponse: { medianMinutes: null, met: 2, missed: 1 }, resolution: { medianMinutes: null, met: 0, missed: 0 } },
        { companyCode: 'OLD', companyName: `${LONG_COMPANY} 옛 법인`, companyStatus: 'ARCHIVED', open: 0, inProgress: 0, closed: 31 },
      ),
      slaStat({ firstResponse: { medianMinutes: 95, met: 25, missed: 0 }, resolution: { medianMinutes: 525599, met: 7, missed: 3 } }, { companyCode: 'BETA', companyName: '베타상사', open: 123456 }),
    ],
    inbox: [],
  },
  '/stats',
);

// C-7: 수가 긴 요약과 긴 제목의 넘김
const INBOX_SLA = data(
  {
    stats: [],
    inbox: [
      inboxItem({ id: 901, slaSummary: { becameBreached: 123456, becameWarning: 98765, released: 4321 } }),
      inboxItem({ id: 902, kind: 'SLA_RESOLUTION_BREACHED', issue: { key: 'ACME-12345', title: LONG_TITLE }, companyCode: 'ACME' }),
      inboxItem({ id: 903, kind: 'SLA_RESPONSE_WARNING', issue: { key: 'ACME-7', title: '로그인이 안 됩니다' }, companyCode: 'ACME', readAt: '2026-10-08T04:00:00Z' }),
    ],
  },
  '/inbox',
);

function screenStory(width: Width, theme: Theme, given: Data, parts: (canvas: HTMLElement, width: Width) => Promise<Found>): Story {
  const { parameters, globals } = at(width, theme, 'console');
  return {
    parameters: { ...parameters, layout: 'fullscreen' },
    globals,
    render: () => {
      setScenario(given.screen);
      setTaskScenario({ tasks: [], mine: [] });
      setLinkScenario({ links: [], others: [], linkError: null });
      setScenario8b(given.given);
      window.history.replaceState(null, '', given.path);
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

/** The screen drawn: its h1 `title`, then `part` in its main. */
async function shows(canvas: HTMLElement, title: string, part: string): Promise<HTMLElement> {
  await waitFor(() => Array.from(document.querySelectorAll('h1')).find((h) => textOf(h) === title), `h1 "${title}"`, 8000);
  const main = await waitFor(() => mainOf(canvas), `${title}의 main`);
  await waitFor(() => (textOf(main).includes(part) ? true : null), `${title}의 "${part}"`);
  return main;
}

/** Throws unless the table of `main` sits in a box inside `main` that scrolls sideways, with the company column fixed. */
function expectTableScrollsInside(main: HTMLElement): void {
  const table = main.querySelector('table');
  if (table === null) throw new Error('C-5의 표가 없다');
  let scrolls = false;
  for (let el = table.parentElement; el !== null && el !== main; el = el.parentElement) {
    const overflowX = getComputedStyle(el).overflowX;
    if (overflowX === 'auto' || overflowX === 'scroll') scrolls = true;
  }
  if (!scrolls) throw new Error('C-5의 표가 옆으로 스크롤하는 틀 안에 있지 않다');
  const companyCell = table.querySelector('tbody tr td, tbody tr th');
  if (companyCell === null || getComputedStyle(companyCell).position !== 'sticky') throw new Error('C-5의 고객사 칸이 고정(sticky)이 아니다');
}

async function statsOn(canvas: HTMLElement, width: Width): Promise<Found> {
  const main = await shows(canvas, '통계', '중앙값 2일 23시간');
  await waitFor(() => (textOf(main).includes('중앙값 없음') ? true : null), 'C-5의 "중앙값 없음"');
  if (width === 360) expectTableScrollsInside(main);
  return [{ el: main, what: 'C-5의 main' }];
}

async function inboxSla(canvas: HTMLElement): Promise<Found> {
  const main = await shows(canvas, '알림함', '새로 넘김 123,456건');
  await waitFor(() => (textOf(main).includes('해결 기한을 넘겼습니다') ? true : null), 'C-7의 "해결 기한을 넘겼습니다"');
  return [{ el: main, what: 'C-7의 main' }];
}

export const C5SlaOn360Light = screenStory(360, 'light', STATS_ON, statsOn);
export const C5SlaOn360Dark = screenStory(360, 'dark', STATS_ON, statsOn);
export const C5SlaOn820Light = screenStory(820, 'light', STATS_ON, statsOn);
export const C5SlaOn820Dark = screenStory(820, 'dark', STATS_ON, statsOn);
export const C5SlaOn1280Light = screenStory(1280, 'light', STATS_ON, statsOn);
export const C5SlaOn1280Dark = screenStory(1280, 'dark', STATS_ON, statsOn);

export const C7SlaItems360Light = screenStory(360, 'light', INBOX_SLA, inboxSla);
export const C7SlaItems360Dark = screenStory(360, 'dark', INBOX_SLA, inboxSla);
export const C7SlaItems820Light = screenStory(820, 'light', INBOX_SLA, inboxSla);
export const C7SlaItems820Dark = screenStory(820, 'dark', INBOX_SLA, inboxSla);
export const C7SlaItems1280Light = screenStory(1280, 'light', INBOX_SLA, inboxSla);
export const C7SlaItems1280Dark = screenStory(1280, 'dark', INBOX_SLA, inboxSla);
