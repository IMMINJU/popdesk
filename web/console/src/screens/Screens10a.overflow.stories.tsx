// 10a 묶음이 바꾼 콘솔 화면(C-10 구성원 목록의 익명 계정 줄, 익명화 대화상자)을 상태마다 360·820·1280 폭, 밝게·어둡게에서 그리고,
// play에서 테마가 그것인지, 페이지와 바뀐 부분이 가로로 넘치지 않는지, axe(대비 포함) 위반이 없는지 본다(screens.md).
// C-10: 익명 계정 줄과 긴 이메일 줄. 익명화 대화상자: 긴 이름·이메일, 담당 수 있음, 오류 있음.
// C-12: "감사 사슬 다시 계산" 줄. 바뀐 값의 "마지막 hash"는 64자 hash 둘을 줄이지 않고 보인다(design.md 0절 13, C-7 웹 점검 D5).
// 360에서 구성원 표는 안에서 옆으로 스크롤하므로 표의 스크롤 틀은 부품으로 넘기지 않고, 페이지와 화면 본문(main)을 본다.
// 화면은 가짜 fetch(__tests__/storyFetch.ts, storyFetch10a.ts)의 답으로 그린다.
//
// 대화상자는 play가 그 줄의 메뉴를 열어 "익명화"를 눌러 연다. 오류 줄은 play가 이메일을 쳐 넣고 "익명화"를 눌러 만든다.
//
// The fake fetch comes first: src/api.ts keeps the fetch it finds when it is first imported.
import { ADMIN, company, issue, scenario, setScenario, summary } from '../__tests__/storyFetch';
import { setTaskScenario } from '../__tests__/storyFetch6b';
import { setLinkScenario } from '../__tests__/storyFetch6c';
import { LONG_EMAIL, LONG_PERSON, RECHAINED, anonymized, hashOf, member, setAuditScenario, setMemberScenario, type MemberScenario } from '../__tests__/storyFetch10a';
import type { ReactElement } from 'react';
import '@popdesk/ui/tokens.css';
import '@popdesk/ui/styles.css';
import '../console.css';
import { at, buttonNamed, expectDrawnRight, textOf, waitFor, type Theme, type Width } from '../../../packages/ui/src/__tests__/overflow';
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

const meta = { title: 'Overflow/10a 콘솔 화면' };
export default meta;

type Found = { el: Element; what: string }[];

const ME = member({ id: 1, email: 'admin@popdesk.test', name: '최관리', role: 'ADMIN' });
const LONG = member({ id: 31, email: LONG_EMAIL, name: LONG_PERSON, role: 'AGENT' });
const ANON = anonymized(9);

function members(over: Partial<MemberScenario>): MemberScenario {
  return { accounts: [ME, member(), ANON, LONG, member({ id: 4, email: 'han@popdesk.test', name: '한정지', status: 'SUSPENDED' })], open: { issues: 0, tasks: 0 }, refuse: null, ...over };
}

const LIST = members({});
const WITH_COUNT = members({ open: { issues: 4, tasks: 2 } });
const REFUSED = members({ open: { issues: 4, tasks: 0 }, refuse: { code: 'LAST_ADMIN', status: 400 } });

function screenStory(width: Width, theme: Theme, given: MemberScenario, parts: (canvas: HTMLElement) => Promise<Found>, path = '/members'): Story {
  const { parameters, globals } = at(width, theme, 'console');
  return {
    parameters: { ...parameters, layout: 'fullscreen' },
    globals,
    render: () => {
      const one = issue();
      setScenario(scenario({ me: ADMIN, companies: [company()], issue: one, issues: [summary(one)] }));
      setTaskScenario({ tasks: [], mine: [] });
      setLinkScenario({ links: [], others: [], linkError: null });
      setMemberScenario(given);
      setAuditScenario([RECHAINED]);
      window.history.replaceState(null, '', path);
      return <App />;
    },
    play: async ({ canvasElement }) => {
      await expectDrawnRight(width, theme, await parts(canvasElement));
    },
  };
}

function mainOf(canvas: HTMLElement): HTMLElement | null {
  return canvas.querySelector('main') ?? document.querySelector('main');
}

/** C-10 drawn: its h1 "구성원 목록" and the rows of the anonymized account and the long email. */
async function listShows(canvas: HTMLElement): Promise<{ main: HTMLElement; found: Found }> {
  await waitFor(() => Array.from(document.querySelectorAll('h1')).find((h) => textOf(h) === '구성원 목록'), 'C-10의 h1 "구성원 목록"', 8000);
  const main = await waitFor(() => mainOf(canvas), 'C-10의 main');
  await waitFor(() => (textOf(main).includes(LONG_EMAIL) ? true : null), 'C-10의 긴 이메일 줄');
  const anon = await waitFor(
    () => Array.from(main.querySelectorAll('tr')).find((r) => textOf(r).includes('삭제된 사용자') && textOf(r).includes('익명화됨')),
    'C-10의 익명 계정 줄("익명화됨")',
  );
  return { main, found: [{ el: main, what: 'C-10의 main' }, { el: anon, what: 'C-10의 익명 계정 줄' }] };
}

const list = async (canvas: HTMLElement) => (await listShows(canvas)).found;

/** Opens the row menu of `name` and presses "익명화"; returns the dialog. */
async function openAnonymize(name: string): Promise<HTMLElement> {
  const trigger = await waitFor(() => buttonNamed(document, `${name} 동작`), `"${name} 동작" 메뉴 단추`);
  trigger.click();
  const item = await waitFor(
    () => Array.from(document.querySelectorAll<HTMLElement>('[role="menuitem"]')).find((i) => textOf(i) === '익명화'),
    '줄 메뉴의 "익명화"',
  );
  item.click();
  return waitFor(
    () => Array.from(document.querySelectorAll<HTMLElement>('dialog[open], [role="dialog"], [role="alertdialog"]')).find((d) => textOf(d).includes('님을 익명화할까요?')) ?? null,
    '대화상자 "… 님을 익명화할까요?"',
  );
}

/** Sets the value of a React-controlled input the way typing does. */
function typeInto(input: HTMLInputElement, value: string): void {
  Reflect.set(HTMLInputElement.prototype, 'value', value, input);
  input.dispatchEvent(new Event('input', { bubbles: true }));
}

/** The dialog of the long-named agent with the open issues and tasks. */
async function dialogWithCount(canvas: HTMLElement): Promise<Found> {
  const { found } = await listShows(canvas);
  const box = await openAnonymize(LONG_PERSON);
  await waitFor(() => (textOf(box).includes('담당인 열린 문의가 4건') ? true : null), '대화상자의 담당 수');
  await waitFor(() => (textOf(box).includes(LONG_EMAIL) ? true : null), '대화상자의 이메일');
  return [...found, { el: box, what: '익명화 대화상자' }];
}

/** The same dialog after the server refused with 400 LAST_ADMIN. */
async function dialogRefused(canvas: HTMLElement): Promise<Found> {
  const { found } = await listShows(canvas);
  const box = await openAnonymize(LONG_PERSON);
  const field = await waitFor(() => Array.from(box.querySelectorAll<HTMLInputElement>('input')).find((i) => i.type !== 'checkbox') ?? null, '대화상자의 이메일 칸');
  typeInto(field, LONG_EMAIL);
  const go = await waitFor(() => {
    const b = buttonNamed(box, '익명화');
    return b && b.getAttribute('aria-disabled') !== 'true' && !b.disabled ? b : null;
  }, '눌리는 "익명화" 단추');
  go.click();
  await waitFor(() => (textOf(box).includes('사용 중인 관리자가 한 명은 남아야 합니다.') ? true : null), '대화상자 안의 오류');
  return [...found, { el: box, what: '오류가 있는 익명화 대화상자' }];
}

/** C-12 with the row of AUDIT_RECHAINED: its changed values hold two whole hashes of 64 characters. */
async function rechainedRow(canvas: HTMLElement): Promise<Found> {
  await waitFor(() => Array.from(document.querySelectorAll('h1')).find((h) => textOf(h) === '감사 로그'), 'C-12의 h1 "감사 로그"', 8000);
  const main = await waitFor(() => mainOf(canvas), 'C-12의 main');
  const row = await waitFor(
    () => Array.from(main.querySelectorAll('tr')).find((r) => textOf(r).includes('감사 사슬 다시 계산')),
    'C-12의 줄 "감사 사슬 다시 계산"',
  );
  const cell = await waitFor(
    () => Array.from(row.querySelectorAll('td')).find((c) => textOf(c).includes(`마지막 hash: ${hashOf(4410)}`) && textOf(c).includes('ab'.repeat(32))),
    'C-12 줄의 바뀐 값 "마지막 hash: 앞 → 뒤"(64자 둘)',
  );
  return [
    { el: main, what: 'C-12의 main' },
    { el: cell, what: 'C-12 감사 사슬 다시 계산의 바뀐 값 칸' },
  ];
}

export const C10Anonymous360Light =screenStory(360, 'light', LIST, list);
export const C10Anonymous360Dark = screenStory(360, 'dark', LIST, list);
export const C10Anonymous820Light = screenStory(820, 'light', LIST, list);
export const C10Anonymous820Dark = screenStory(820, 'dark', LIST, list);
export const C10Anonymous1280Light = screenStory(1280, 'light', LIST, list);
export const C10Anonymous1280Dark = screenStory(1280, 'dark', LIST, list);

export const AnonymizeCount360Light = screenStory(360, 'light', WITH_COUNT, dialogWithCount);
export const AnonymizeCount360Dark = screenStory(360, 'dark', WITH_COUNT, dialogWithCount);
export const AnonymizeCount820Light = screenStory(820, 'light', WITH_COUNT, dialogWithCount);
export const AnonymizeCount820Dark = screenStory(820, 'dark', WITH_COUNT, dialogWithCount);
export const AnonymizeCount1280Light = screenStory(1280, 'light', WITH_COUNT, dialogWithCount);
export const AnonymizeCount1280Dark = screenStory(1280, 'dark', WITH_COUNT, dialogWithCount);

export const AnonymizeRefused360Light = screenStory(360, 'light', REFUSED, dialogRefused);
export const AnonymizeRefused360Dark = screenStory(360, 'dark', REFUSED, dialogRefused);
export const AnonymizeRefused820Light = screenStory(820, 'light', REFUSED, dialogRefused);
export const AnonymizeRefused820Dark = screenStory(820, 'dark', REFUSED, dialogRefused);
export const AnonymizeRefused1280Light = screenStory(1280, 'light', REFUSED, dialogRefused);
export const AnonymizeRefused1280Dark = screenStory(1280, 'dark', REFUSED, dialogRefused);

export const C12Rechained360Light = screenStory(360, 'light', LIST, rechainedRow, '/audit-log');
export const C12Rechained360Dark = screenStory(360, 'dark', LIST, rechainedRow, '/audit-log');
export const C12Rechained820Light = screenStory(820, 'light', LIST, rechainedRow, '/audit-log');
export const C12Rechained820Dark = screenStory(820, 'dark', LIST, rechainedRow, '/audit-log');
export const C12Rechained1280Light = screenStory(1280, 'light', LIST, rechainedRow, '/audit-log');
export const C12Rechained1280Dark = screenStory(1280, 'dark', LIST, rechainedRow, '/audit-log');
