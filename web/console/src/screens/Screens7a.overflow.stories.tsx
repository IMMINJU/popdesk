// 7a 묶음이 바꾼 콘솔 화면(C-11 휴지통, C-4의 "문의 지우기" 확인)을 상태마다 360·820·1280 폭, 밝게·어둡게에서 그리고,
// play에서 테마가 그것인지, 페이지와 바뀐 부품이 가로로 넘치지 않는지, axe(대비 포함) 위반이 없는지 본다
// (screens.md 4절, S7A-AC-54). 360에서 휴지통 표는 안에서 옆으로 스크롤하므로 표의 스크롤 틀은 부품으로 넘기지 않고,
// 페이지와 화면 본문(main)이 넘치지 않는지 본다. 화면은 가짜 fetch(__tests__/storyFetch.ts, storyFetch7a.ts)의 답으로 그린다.
//
// 복구한 줄과 409 줄은 play가 "{키} 복구"를 눌러 만든다. 지우기 확인은 play가 "문의 지우기"를 눌러 연다.
//
// The fake fetch comes first: src/api.ts keeps the fetch it finds when it is first imported.
import { ADMIN, company, issue, scenario, setScenario, summary, type Scenario } from '../__tests__/storyFetch';
import { LONG_TITLE, setTaskScenario } from '../__tests__/storyFetch6b';
import { LONG_COMPANY, setLinkScenario } from '../__tests__/storyFetch6c';
import { setTrashScenario, trashed, type TrashScenario } from '../__tests__/storyFetch7a';
import type { ReactElement } from 'react';
import '@popdesk/ui/tokens.css';
import '@popdesk/ui/styles.css';
import '../console.css';
import { at, buttonNamed, expectDrawnRight, termValue, textOf, waitFor, type Theme, type Width } from '../../../packages/ui/src/__tests__/overflow';
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

const meta = { title: 'Overflow/7a 콘솔 화면' };
export default meta;

type Data = { screen: Scenario; trash: TrashScenario; path: string };
type Found = { el: Element; what: string }[];

const OPEN_ISSUE = issue();

function data(trash: Partial<TrashScenario>, path = '/trash'): Data {
  return { screen: scenario({ me: ADMIN, companies: [company()], issue: OPEN_ISSUE, issues: [summary(OPEN_ISSUE)] }), trash: { trash: [], conflict: null, ...trash }, path };
}

const MANY = Array.from({ length: 20 }, (_, i) =>
  trashed({ key: `ACME-${String(1200 + i)}`, title: `${LONG_TITLE.slice(0, 40)} ${String(i + 1)}`, status: i % 2 === 0 ? 'IN_PROGRESS' : 'RESOLVED' }),
);

const EMPTY = data({ trash: [] });
const SEVERAL = data({ trash: MANY });
const LONG_TITLED = data({ trash: [trashed({ title: LONG_TITLE })] });
const LONG_NAMED = data({ trash: [trashed({ companyName: LONG_COMPANY })] });
const RESTORABLE = data({ trash: [trashed(), trashed({ key: 'BETA-5', companyCode: 'BETA', companyName: '베타상사' })] });
const CONFLICTED = data({ trash: [trashed(), trashed({ key: 'BETA-5', companyCode: 'BETA', companyName: '베타상사' })], conflict: ['ACME-40', 'BETA-77', 'BETA-1234'] });
const DETAIL = data({}, '/issues/ACME-7');

function screenStory(width: Width, theme: Theme, given: Data, parts: (canvas: HTMLElement) => Promise<Found>): Story {
  const { parameters, globals } = at(width, theme, 'console');
  return {
    parameters: { ...parameters, layout: 'fullscreen' },
    globals,
    render: () => {
      setScenario(given.screen);
      setTaskScenario({ tasks: [], mine: [] });
      setLinkScenario({ links: [], others: [], linkError: null });
      setTrashScenario(given.trash);
      window.history.replaceState(null, '', given.path);
      return <App />;
    },
    play: async ({ canvasElement }) => {
      await expectDrawnRight(width, theme, await parts(canvasElement));
    },
  };
}

// C-11 휴지통

function mainOf(canvas: HTMLElement): HTMLElement | null {
  return canvas.querySelector('main') ?? document.querySelector('main');
}

/** C-11 drawn: its h1 "휴지통", then `part` in its main. */
async function trashShows(canvas: HTMLElement, part: string): Promise<Found> {
  await waitFor(() => Array.from(document.querySelectorAll('h1')).find((h) => textOf(h) === '휴지통'), 'C-11의 h1 "휴지통"', 8000);
  const main = await waitFor(() => mainOf(canvas), 'C-11의 main');
  await waitFor(() => (textOf(main).includes(part) ? true : null), `C-11의 "${part}"`);
  return [{ el: main, what: 'C-11의 main' }];
}

const trashEmpty = (canvas: HTMLElement) => trashShows(canvas, '휴지통이 비었습니다.');
const trashSeveral = (canvas: HTMLElement) => trashShows(canvas, 'ACME-1219');
const trashLongTitle = (canvas: HTMLElement) => trashShows(canvas, LONG_TITLE);
const trashLongCompany = (canvas: HTMLElement) => trashShows(canvas, LONG_COMPANY);

/** Presses "ACME-12 복구" and waits for `part`: the restored row, or the 409 alert under it. */
async function restoreShows(canvas: HTMLElement, part: string): Promise<Found> {
  const found = await trashShows(canvas, 'BETA-5');
  const button = await waitFor(() => buttonNamed(document, 'ACME-12 복구'), '"ACME-12 복구"');
  button.click();
  const main = found[0]?.el as HTMLElement;
  await waitFor(() => (textOf(main).includes(part) ? true : null), `C-11의 "${part}"`);
  const alerts = Array.from(main.querySelectorAll('[role="alert"]')).map((el) => ({ el, what: 'C-11 줄 아래의 role="alert"' }));
  return [...found, ...alerts];
}

const trashRestored = (canvas: HTMLElement) => restoreShows(canvas, '복구했습니다.');
const trashConflict = (canvas: HTMLElement) => restoreShows(canvas, '연결 규칙에 걸려 복구하지 못했습니다:');

// C-4 "문의 지우기" 확인

function deleteDialogNow(): HTMLElement | null {
  return Array.from(document.querySelectorAll<HTMLElement>('dialog[open], [role="dialog"], [role="alertdialog"]')).find((d) => textOf(d).includes('이 문의를 지울까요?')) ?? null;
}

async function deleteConfirm(canvas: HTMLElement): Promise<Found> {
  await waitFor(() => termValue(document, '담당자'), 'C-4의 칸 "담당자"(화면이 그려짐)', 8000);
  const opener = await waitFor(() => buttonNamed(canvas.ownerDocument, '문의 지우기'), '"문의 지우기" 단추');
  opener.click();
  const dialog = await waitFor(deleteDialogNow, '대화상자 "이 문의를 지울까요?"');
  await waitFor(() => buttonNamed(dialog, '그만두기'), '대화상자의 "그만두기"');
  return [{ el: dialog, what: '대화상자 "이 문의를 지울까요?"' }];
}

export const C11Empty360Light = screenStory(360, 'light', EMPTY, trashEmpty);
export const C11Empty360Dark = screenStory(360, 'dark', EMPTY, trashEmpty);
export const C11Empty820Light = screenStory(820, 'light', EMPTY, trashEmpty);
export const C11Empty820Dark = screenStory(820, 'dark', EMPTY, trashEmpty);
export const C11Empty1280Light = screenStory(1280, 'light', EMPTY, trashEmpty);
export const C11Empty1280Dark = screenStory(1280, 'dark', EMPTY, trashEmpty);

export const C11Several360Light = screenStory(360, 'light', SEVERAL, trashSeveral);
export const C11Several360Dark = screenStory(360, 'dark', SEVERAL, trashSeveral);
export const C11Several820Light = screenStory(820, 'light', SEVERAL, trashSeveral);
export const C11Several820Dark = screenStory(820, 'dark', SEVERAL, trashSeveral);
export const C11Several1280Light = screenStory(1280, 'light', SEVERAL, trashSeveral);
export const C11Several1280Dark = screenStory(1280, 'dark', SEVERAL, trashSeveral);

export const C11LongTitle360Light = screenStory(360, 'light', LONG_TITLED, trashLongTitle);
export const C11LongTitle360Dark = screenStory(360, 'dark', LONG_TITLED, trashLongTitle);
export const C11LongTitle820Light = screenStory(820, 'light', LONG_TITLED, trashLongTitle);
export const C11LongTitle820Dark = screenStory(820, 'dark', LONG_TITLED, trashLongTitle);
export const C11LongTitle1280Light = screenStory(1280, 'light', LONG_TITLED, trashLongTitle);
export const C11LongTitle1280Dark = screenStory(1280, 'dark', LONG_TITLED, trashLongTitle);

export const C11LongCompany360Light = screenStory(360, 'light', LONG_NAMED, trashLongCompany);
export const C11LongCompany360Dark = screenStory(360, 'dark', LONG_NAMED, trashLongCompany);
export const C11LongCompany820Light = screenStory(820, 'light', LONG_NAMED, trashLongCompany);
export const C11LongCompany820Dark = screenStory(820, 'dark', LONG_NAMED, trashLongCompany);
export const C11LongCompany1280Light = screenStory(1280, 'light', LONG_NAMED, trashLongCompany);
export const C11LongCompany1280Dark = screenStory(1280, 'dark', LONG_NAMED, trashLongCompany);

export const C11Restored360Light = screenStory(360, 'light', RESTORABLE, trashRestored);
export const C11Restored360Dark = screenStory(360, 'dark', RESTORABLE, trashRestored);
export const C11Restored820Light = screenStory(820, 'light', RESTORABLE, trashRestored);
export const C11Restored820Dark = screenStory(820, 'dark', RESTORABLE, trashRestored);
export const C11Restored1280Light = screenStory(1280, 'light', RESTORABLE, trashRestored);
export const C11Restored1280Dark = screenStory(1280, 'dark', RESTORABLE, trashRestored);

export const C11Conflict360Light = screenStory(360, 'light', CONFLICTED, trashConflict);
export const C11Conflict360Dark = screenStory(360, 'dark', CONFLICTED, trashConflict);
export const C11Conflict820Light = screenStory(820, 'light', CONFLICTED, trashConflict);
export const C11Conflict820Dark = screenStory(820, 'dark', CONFLICTED, trashConflict);
export const C11Conflict1280Light = screenStory(1280, 'light', CONFLICTED, trashConflict);
export const C11Conflict1280Dark = screenStory(1280, 'dark', CONFLICTED, trashConflict);

export const C4DeleteConfirm360Light = screenStory(360, 'light', DETAIL, deleteConfirm);
export const C4DeleteConfirm360Dark = screenStory(360, 'dark', DETAIL, deleteConfirm);
export const C4DeleteConfirm820Light = screenStory(820, 'light', DETAIL, deleteConfirm);
export const C4DeleteConfirm820Dark = screenStory(820, 'dark', DETAIL, deleteConfirm);
export const C4DeleteConfirm1280Light = screenStory(1280, 'light', DETAIL, deleteConfirm);
export const C4DeleteConfirm1280Dark = screenStory(1280, 'dark', DETAIL, deleteConfirm);
