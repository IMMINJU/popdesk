// 6c 묶음이 바꾼 콘솔 화면(C-4 문의 상세의 연결 구역과 "문의 잇기" 대화상자)을 상태마다 360·820·1280 폭, 밝게·어둡게에서
// 그리고, play에서 테마가 그것인지, 페이지와 바뀐 부품이 가로로 넘치지 않는지, axe(대비 포함) 위반이 없는지 본다
// (screens.md 4절, S6C-AC-56). 화면은 가짜 fetch(__tests__/storyFetch.ts, storyFetch6b.ts, storyFetch6c.ts)의 답으로 그린다.
//
// 대화상자는 play가 "문의 잇기"를 눌러 열고, 키를 넣어 "찾기"와 "잇기"를 누른다.
//
// The fake fetch comes first: src/api.ts keeps the fetch it finds when it is first imported.
import { ADMIN, company, issue, scenario, setScenario, summary, type Scenario } from '../__tests__/storyFetch';
import { LONG_TITLE, setTaskScenario } from '../__tests__/storyFetch6b';
import { LONG_COMPANY, link, linked, setLinkScenario, type LinkScenario } from '../__tests__/storyFetch6c';
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

const meta = { title: 'Overflow/6c 콘솔 화면' };
export default meta;

type Data = { screen: Scenario; links: LinkScenario };
type Found = { el: Element; what: string }[];

const OPEN_ISSUE = issue();
const CLOSED_ISSUE = issue({ status: 'CLOSED', nextStatuses: [], closedAt: '2026-10-09T03:15:00Z' });

/** The issue "찾기" finds: the long title and the long company name, the widest it draws. */
const FOUND = issue({ key: 'BETA-3', title: LONG_TITLE, status: 'IN_PROGRESS', companyCode: 'BETA', companyName: LONG_COMPANY, assignee: null });

function data(links: Partial<LinkScenario>, one = OPEN_ISSUE): Data {
  return { screen: scenario({ me: ADMIN, companies: [company()], issue: one, issues: [summary(one)] }), links: { links: [], others: [FOUND], linkError: null, ...links } };
}

const MIXED = [
  link({ type: 'DUPLICATE_OF' }),
  link({ type: 'DUPLICATED_BY', issue: linked({ key: 'ACME-2', title: '비밀번호 재설정 메일이 안 옵니다', status: 'CLOSED', companyCode: 'ACME', companyName: '에이크미' }) }),
  link({ type: 'RELATED', issue: linked({ key: 'BETA-1234', title: '영수증이 안 나옵니다', status: 'RECEIVED' }) }),
];

const NONE = data({ links: [] });
const THREE = data({ links: MIXED });
const FIFTY = data({ links: Array.from({ length: 50 }, (_, i) => link({ type: i % 3 === 0 ? 'RELATED' : 'DUPLICATED_BY', issue: linked({ key: `BETA-${String(100 + i)}`, title: `${LONG_TITLE.slice(0, 50)} ${String(i + 1)}` }) })) });
const LONG_NAME = data({ links: [link({ issue: linked({ companyName: LONG_COMPANY }) })] });
const LONG_TITLED = data({ links: [link({ type: 'DUPLICATE_OF', issue: linked({ title: LONG_TITLE }) })] });
const CLOSED = data({ links: MIXED }, CLOSED_ISSUE);
const LINK_FAILS = data({ links: [], linkError: { status: 400, code: 'LINK_DUPLICATE_CHAIN' } });

function screenStory(width: Width, theme: Theme, given: Data, parts: (canvas: HTMLElement) => Promise<Found>): Story {
  const { parameters, globals } = at(width, theme, 'console');
  return {
    parameters: { ...parameters, layout: 'fullscreen' },
    globals,
    render: () => {
      setScenario(given.screen);
      setTaskScenario({ tasks: [], mine: [] });
      setLinkScenario(given.links);
      window.history.replaceState(null, '', '/issues/ACME-7');
      return <App />;
    },
    play: async ({ canvasElement }) => {
      await expectDrawnRight(width, theme, await parts(canvasElement));
    },
  };
}

// C-4 문의 상세의 연결 구역

// The screen drawn at all, by a part that was there before this slice: a failure past this point
// is about the slice, not about the fake server or the address.
const detailDrawn = () => waitFor(() => termValue(document, '담당자'), 'C-4의 칸 "담당자"(화면이 그려짐)', 8000);

async function region(canvas: HTMLElement): Promise<HTMLElement> {
  await detailDrawn();
  return waitFor(() => namedPart(canvas, /^연결된 문의( \d+)?$/), 'C-4의 구역 "연결된 문의"');
}

async function shows(canvas: HTMLElement, part: string): Promise<Found> {
  const r = await region(canvas);
  await waitFor(() => (textOf(r).includes(part) ? true : null), `연결 구역의 "${part}"`);
  return [{ el: r, what: 'C-4의 구역 "연결된 문의"' }];
}

const linksNone = (canvas: HTMLElement) => shows(canvas, '연결된 문의가 없습니다.');
const linksThree = (canvas: HTMLElement) => shows(canvas, 'BETA-1234');
const linksFifty = (canvas: HTMLElement) => shows(canvas, 'BETA-149');
const linksLongName = (canvas: HTMLElement) => shows(canvas, LONG_COMPANY);
const linksLongTitle = (canvas: HTMLElement) => shows(canvas, LONG_TITLE);

async function linksClosed(canvas: HTMLElement): Promise<Found> {
  const found = await shows(canvas, 'BETA-1234');
  const r = found[0]?.el as HTMLElement;
  if (buttonNamed(r, '문의 잇기')) throw new Error('CLOSED 문의에 "문의 잇기"가 남았다');
  return found;
}

// "문의 잇기" 대화상자

function dialogNow(): HTMLElement | null {
  return Array.from(document.querySelectorAll<HTMLElement>('dialog[open], [role="dialog"]')).find((d) => textOf(d).includes('문의 잇기')) ?? null;
}

/** Opens "문의 잇기" from the 연결 구역 and returns the dialog. */
async function openDialog(canvas: HTMLElement): Promise<HTMLElement> {
  const r = await region(canvas);
  const opener = await waitFor(() => buttonNamed(r, '문의 잇기'), '"문의 잇기" 단추');
  opener.click();
  return waitFor(dialogNow, '대화상자 "문의 잇기"');
}

function keyInput(dialog: HTMLElement): HTMLInputElement | null {
  return (
    Array.from(dialog.querySelectorAll('input')).find(
      (i) => i.getAttribute('aria-label') === '문의 키' || Array.from(i.labels ?? []).some((l) => textOf(l) === '문의 키'),
    ) ?? null
  );
}

/** Puts `key` into "문의 키" the way typing does (React reads the input event) and presses "찾기". */
async function search(dialog: HTMLElement, key: string): Promise<void> {
  const input = await waitFor(() => keyInput(dialog), '칸 "문의 키"');
  Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value')?.set?.call(input, key);
  input.dispatchEvent(new Event('input', { bubbles: true }));
  const find = await waitFor(() => buttonNamed(dialog, '찾기'), '대화상자의 "찾기"');
  find.click();
}

async function dialogShows(dialog: HTMLElement, part: string): Promise<Found> {
  await waitFor(() => (textOf(dialog).includes(part) ? true : null), `대화상자의 "${part}"`);
  return [{ el: dialog, what: '대화상자 "문의 잇기"' }];
}

async function dialogEmpty(canvas: HTMLElement): Promise<Found> {
  const dialog = await openDialog(canvas);
  await waitFor(() => keyInput(dialog), '칸 "문의 키"');
  return [{ el: dialog, what: '대화상자 "문의 잇기"' }];
}

async function dialogFound(canvas: HTMLElement): Promise<Found> {
  const dialog = await openDialog(canvas);
  await search(dialog, 'BETA-3');
  return dialogShows(dialog, LONG_COMPANY);
}

async function dialogNotFound(canvas: HTMLElement): Promise<Found> {
  const dialog = await openDialog(canvas);
  await search(dialog, 'NOPE-1');
  return dialogShows(dialog, '찾을 수 없는 문의입니다.');
}

async function dialogError(canvas: HTMLElement): Promise<Found> {
  const dialog = await openDialog(canvas);
  await search(dialog, 'BETA-3');
  await dialogShows(dialog, LONG_COMPANY);
  const send = await waitFor(() => buttonNamed(dialog, '잇기'), '대화상자의 "잇기"');
  send.click();
  return dialogShows(dialog, '중복은 한 단계만 이을 수 있습니다.');
}

export const C4LinksNone360Light = screenStory(360, 'light', NONE, linksNone);
export const C4LinksNone360Dark = screenStory(360, 'dark', NONE, linksNone);
export const C4LinksNone820Light = screenStory(820, 'light', NONE, linksNone);
export const C4LinksNone820Dark = screenStory(820, 'dark', NONE, linksNone);
export const C4LinksNone1280Light = screenStory(1280, 'light', NONE, linksNone);
export const C4LinksNone1280Dark = screenStory(1280, 'dark', NONE, linksNone);

export const C4LinksThree360Light = screenStory(360, 'light', THREE, linksThree);
export const C4LinksThree360Dark = screenStory(360, 'dark', THREE, linksThree);
export const C4LinksThree820Light = screenStory(820, 'light', THREE, linksThree);
export const C4LinksThree820Dark = screenStory(820, 'dark', THREE, linksThree);
export const C4LinksThree1280Light = screenStory(1280, 'light', THREE, linksThree);
export const C4LinksThree1280Dark = screenStory(1280, 'dark', THREE, linksThree);

export const C4LinksFifty360Light = screenStory(360, 'light', FIFTY, linksFifty);
export const C4LinksFifty360Dark = screenStory(360, 'dark', FIFTY, linksFifty);
export const C4LinksFifty820Light = screenStory(820, 'light', FIFTY, linksFifty);
export const C4LinksFifty820Dark = screenStory(820, 'dark', FIFTY, linksFifty);
export const C4LinksFifty1280Light = screenStory(1280, 'light', FIFTY, linksFifty);
export const C4LinksFifty1280Dark = screenStory(1280, 'dark', FIFTY, linksFifty);

export const C4LinksLongCompany360Light = screenStory(360, 'light', LONG_NAME, linksLongName);
export const C4LinksLongCompany360Dark = screenStory(360, 'dark', LONG_NAME, linksLongName);
export const C4LinksLongCompany820Light = screenStory(820, 'light', LONG_NAME, linksLongName);
export const C4LinksLongCompany820Dark = screenStory(820, 'dark', LONG_NAME, linksLongName);
export const C4LinksLongCompany1280Light = screenStory(1280, 'light', LONG_NAME, linksLongName);
export const C4LinksLongCompany1280Dark = screenStory(1280, 'dark', LONG_NAME, linksLongName);

export const C4LinksLongTitle360Light = screenStory(360, 'light', LONG_TITLED, linksLongTitle);
export const C4LinksLongTitle360Dark = screenStory(360, 'dark', LONG_TITLED, linksLongTitle);
export const C4LinksLongTitle820Light = screenStory(820, 'light', LONG_TITLED, linksLongTitle);
export const C4LinksLongTitle820Dark = screenStory(820, 'dark', LONG_TITLED, linksLongTitle);
export const C4LinksLongTitle1280Light = screenStory(1280, 'light', LONG_TITLED, linksLongTitle);
export const C4LinksLongTitle1280Dark = screenStory(1280, 'dark', LONG_TITLED, linksLongTitle);

export const C4LinksClosed360Light = screenStory(360, 'light', CLOSED, linksClosed);
export const C4LinksClosed360Dark = screenStory(360, 'dark', CLOSED, linksClosed);
export const C4LinksClosed820Light = screenStory(820, 'light', CLOSED, linksClosed);
export const C4LinksClosed820Dark = screenStory(820, 'dark', CLOSED, linksClosed);
export const C4LinksClosed1280Light = screenStory(1280, 'light', CLOSED, linksClosed);
export const C4LinksClosed1280Dark = screenStory(1280, 'dark', CLOSED, linksClosed);

export const C4LinkDialogEmpty360Light = screenStory(360, 'light', NONE, dialogEmpty);
export const C4LinkDialogEmpty360Dark = screenStory(360, 'dark', NONE, dialogEmpty);
export const C4LinkDialogEmpty820Light = screenStory(820, 'light', NONE, dialogEmpty);
export const C4LinkDialogEmpty820Dark = screenStory(820, 'dark', NONE, dialogEmpty);
export const C4LinkDialogEmpty1280Light = screenStory(1280, 'light', NONE, dialogEmpty);
export const C4LinkDialogEmpty1280Dark = screenStory(1280, 'dark', NONE, dialogEmpty);

export const C4LinkDialogFound360Light = screenStory(360, 'light', NONE, dialogFound);
export const C4LinkDialogFound360Dark = screenStory(360, 'dark', NONE, dialogFound);
export const C4LinkDialogFound820Light = screenStory(820, 'light', NONE, dialogFound);
export const C4LinkDialogFound820Dark = screenStory(820, 'dark', NONE, dialogFound);
export const C4LinkDialogFound1280Light = screenStory(1280, 'light', NONE, dialogFound);
export const C4LinkDialogFound1280Dark = screenStory(1280, 'dark', NONE, dialogFound);

export const C4LinkDialogNotFound360Light = screenStory(360, 'light', NONE, dialogNotFound);
export const C4LinkDialogNotFound360Dark = screenStory(360, 'dark', NONE, dialogNotFound);
export const C4LinkDialogNotFound820Light = screenStory(820, 'light', NONE, dialogNotFound);
export const C4LinkDialogNotFound820Dark = screenStory(820, 'dark', NONE, dialogNotFound);
export const C4LinkDialogNotFound1280Light = screenStory(1280, 'light', NONE, dialogNotFound);
export const C4LinkDialogNotFound1280Dark = screenStory(1280, 'dark', NONE, dialogNotFound);

export const C4LinkDialogError360Light = screenStory(360, 'light', LINK_FAILS, dialogError);
export const C4LinkDialogError360Dark = screenStory(360, 'dark', LINK_FAILS, dialogError);
export const C4LinkDialogError820Light = screenStory(820, 'light', LINK_FAILS, dialogError);
export const C4LinkDialogError820Dark = screenStory(820, 'dark', LINK_FAILS, dialogError);
export const C4LinkDialogError1280Light = screenStory(1280, 'light', LINK_FAILS, dialogError);
export const C4LinkDialogError1280Dark = screenStory(1280, 'dark', LINK_FAILS, dialogError);
