// 8a 묶음이 더하거나 바꾼 콘솔 화면을 상태마다 360·820·1280 폭, 밝게·어둡게에서 그리고, play에서 테마가 그것인지, 페이지와
// 화면 본문(main)이 가로로 넘치지 않는지, axe(대비 포함) 위반이 없는지 본다(screens.md 5절):
// C-13 SLA 설정(꺼짐, 켜짐, 쉬는 날 500개, 409), C-3 문의 목록(SLA 칸 셋 상태와 긴 넘김 글), C-4 문의 상세(목표 다섯 상태와
// 목표 없음, 한 화면에 둘씩). 360에서 C-13 목표 표와 C-3 표는 안에서 옆으로 스크롤하므로, 표가 옆으로 스크롤하는 틀
// (overflow-x auto 또는 scroll) 안에 있는지 본다. 화면은 가짜 fetch(__tests__/storyFetch.ts, storyFetch8a.ts)의 답으로 그린다.
//
// 409 줄은 play가 목표 칸 하나를 고치고 "저장"을 눌러 만든다.
//
// The fake fetch comes first: src/api.ts keeps the fetch it finds when it is first imported.
import { ADMIN, company, issue, scenario, setScenario, summary, type Scenario } from '../__tests__/storyFetch';
import { LONG_TITLE, setTaskScenario } from '../__tests__/storyFetch6b';
import { LONG_COMPANY, setLinkScenario } from '../__tests__/storyFetch6c';
import { goal, manyHolidays, offPolicy, onPolicy, setPolicyScenario, type PolicyScenario } from '../__tests__/storyFetch8a';
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

const meta = { title: 'Overflow/8a 콘솔 화면' };
export default meta;

type Data = { screen: Scenario; policy: PolicyScenario; path: string };
type Found = { el: Element; what: string }[];

function data(over: { screen?: Partial<Scenario>; policy?: Partial<PolicyScenario>; path: string }): Data {
  const one = issue();
  return {
    screen: scenario({ me: ADMIN, companies: [company()], issue: one, issues: [summary(one)], ...over.screen }),
    policy: { policy: offPolicy(), conflict: false, ...over.policy },
    path: over.path,
  };
}

// C-13
const POLICY_OFF = data({ path: '/sla' });
const POLICY_ON = data({ path: '/sla', policy: { policy: onPolicy() } });
const POLICY_FULL = data({ path: '/sla', policy: { policy: onPolicy({ holidays: manyHolidays(500) }) } });
const POLICY_CONFLICT = data({ path: '/sla', policy: { policy: onPolicy(), conflict: true } });

// C-3: 빈 칸, 괜찮음, 곧 넘김, 넘김(1년 넘게 넘긴 긴 글), 긴 제목과 고객사
const SLA_ROWS = [
  summary(issue({ key: 'ACME-1', sla: null })),
  summary(issue({ key: 'ACME-2', sla: { firstResponse: goal('OK', 200), resolution: goal(null) } })),
  summary(issue({ key: 'ACME-3', sla: { firstResponse: goal('MET'), resolution: goal('WARNING', 50) } })),
  summary(issue({ key: 'ACME-4', sla: { firstResponse: goal('BREACHED', -1560), resolution: goal('WARNING', 5) } })),
  summary(issue({ key: 'ACME-12345', title: LONG_TITLE, companyName: LONG_COMPANY, sla: { firstResponse: goal('MISSED'), resolution: goal('BREACHED', -1051199) } })),
];
const LIST = data({ path: '/issues', screen: { issues: SLA_ROWS } });

// C-4: 목표 다섯 상태와 목표 없음, 한 화면에 둘씩
function detail(firstResponse: ReturnType<typeof goal>, resolution: ReturnType<typeof goal>): Data {
  const one = issue({ sla: { firstResponse, resolution }, companyName: LONG_COMPANY });
  return data({ path: '/issues/ACME-7', screen: { issue: one, issues: [summary(one)] } });
}
const DETAIL_OK_WARNING = detail(goal('OK', 200), goal('WARNING', 50));
const DETAIL_BREACHED_MET = detail(goal('MET'), goal('BREACHED', -1051199));
const DETAIL_MISSED_NONE = detail(goal('MISSED'), goal(null));

function screenStory(width: Width, theme: Theme, given: Data, parts: (canvas: HTMLElement, width: Width) => Promise<Found>): Story {
  const { parameters, globals } = at(width, theme, 'console');
  return {
    parameters: { ...parameters, layout: 'fullscreen' },
    globals,
    render: () => {
      setScenario(given.screen);
      setTaskScenario({ tasks: [], mine: [] });
      setLinkScenario({ links: [], others: [], linkError: null });
      setPolicyScenario(given.policy);
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

/** Throws unless `table` sits in a box inside `main` that scrolls sideways. */
function expectScrollsInside(main: HTMLElement, table: Element | null, what: string): void {
  if (table === null) throw new Error(`${what}이 없다`);
  for (let el = table.parentElement; el !== null && el !== main; el = el.parentElement) {
    const overflowX = getComputedStyle(el).overflowX;
    if (overflowX === 'auto' || overflowX === 'scroll') return;
  }
  throw new Error(`${what}이 옆으로 스크롤하는 틀 안에 있지 않다`);
}

// C-13 SLA 설정

/** C-13 drawn: its h1 "SLA 설정", then `part` in its main. At 360 the goal table scrolls inside. */
async function policyShows(canvas: HTMLElement, width: Width, part: string): Promise<{ main: HTMLElement; found: Found }> {
  await waitFor(() => Array.from(document.querySelectorAll('h1')).find((h) => textOf(h) === 'SLA 설정'), 'C-13의 h1 "SLA 설정"', 8000);
  const main = await waitFor(() => mainOf(canvas), 'C-13의 main');
  await waitFor(() => (textOf(main).includes(part) ? true : null), `C-13의 "${part}"`);
  if (width === 360) {
    const goals = Array.from(main.querySelectorAll('table')).find((t) => textOf(t).includes('우선순위')) ?? null;
    expectScrollsInside(main, goals, 'C-13의 목표 표');
  }
  return { main, found: [{ el: main, what: 'C-13의 main' }] };
}

const policyOff = async (canvas: HTMLElement, width: Width) => (await policyShows(canvas, width, 'SLA가 꺼져 있습니다.')).found;
const policyOn = async (canvas: HTMLElement, width: Width) => (await policyShows(canvas, width, 'SLA가 켜져 있습니다.')).found;
const policyFull = async (canvas: HTMLElement, width: Width) => (await policyShows(canvas, width, '쉬는 날은 500개까지입니다.')).found;

/** Sets the value of a React-controlled input the way typing does. */
function typeInto(input: HTMLInputElement, value: string): void {
  // The prototype's setter, not React's tracker on the element: React then sees a change.
  Reflect.set(HTMLInputElement.prototype, 'value', value, input);
  input.dispatchEvent(new Event('input', { bubbles: true }));
}

/** Changes the first goal field, presses "저장", and waits for the 409 alert. */
async function policyConflict(canvas: HTMLElement, width: Width): Promise<Found> {
  const { main, found } = await policyShows(canvas, width, 'SLA가 켜져 있습니다.');
  const field = await waitFor(() => main.querySelector<HTMLInputElement>('input[type="number"]'), 'C-13의 목표 칸');
  typeInto(field, '30');
  const save = await waitFor(() => buttonNamed(document, '저장'), '"저장"');
  save.click();
  const text = '다른 관리자가 먼저 바꿨습니다. 다시 읽은 뒤 고쳐 주세요.';
  const alert = await waitFor(() => Array.from(main.querySelectorAll('[role="alert"]')).find((a) => textOf(a).includes(text)), `role="alert" "${text}"`);
  await waitFor(() => buttonNamed(alert, '다시 불러오기') ?? buttonNamed(document, '다시 불러오기'), '"다시 불러오기"');
  return [...found, { el: alert, what: 'C-13의 409 띠' }];
}

// C-3 문의 목록

async function listWithSla(canvas: HTMLElement, width: Width): Promise<Found> {
  const main = await waitFor(() => mainOf(canvas), 'C-3의 main', 8000);
  await waitFor(() => (textOf(main).includes('ACME-12345') ? true : null), 'C-3의 ACME-12345 줄');
  const heads = Array.from(main.querySelectorAll('th')).map(textOf);
  if (heads.at(-1) !== 'SLA') throw new Error(`C-3의 맨 끝 열이 "SLA"가 아니다: ${heads.join(', ')}`);
  // 필터 "SLA"의 선택지도 "넘김"이라 표 칸에서 기다린다: ACME-12345 줄의 SLA 칸(맨 끝)에 긴 넘김 글이 그려질 때까지.
  await waitFor(() => {
    const row = Array.from(main.querySelectorAll('tr')).find((r) => textOf(r).includes('ACME-12345'));
    return row?.lastElementChild && textOf(row.lastElementChild).includes('넘김') ? true : null;
  }, 'C-3 ACME-12345 줄 SLA 칸의 "넘김"');
  if (width === 360) expectScrollsInside(main, main.querySelector('table'), 'C-3의 표');
  return [{ el: main, what: 'C-3의 main' }];
}

// C-4 문의 상세

/** C-4 drawn with its SLA part holding `part`; the side values do not overflow. */
function detailWithSla(part: string) {
  return async (canvas: HTMLElement): Promise<Found> => {
    await waitFor(() => termValue(document, '첫 응답'), 'C-4의 칸 "첫 응답"(화면이 그려짐)', 8000);
    const main = await waitFor(() => mainOf(canvas), 'C-4의 main');
    await waitFor(() => (textOf(main).includes('해결:') && textOf(main).includes(part) ? true : null), `C-4 SLA 구역의 "${part}"`);
    const meta = main.querySelector('dl');
    return [{ el: main, what: 'C-4의 main' }, ...(meta ? [{ el: meta, what: 'C-4의 옆 정보' }] : [])];
  };
}

const detailOkWarning = detailWithSla('곧 넘김');
const detailBreachedMet = detailWithSla('지킴');
const detailMissedNone = detailWithSla('목표 없음');

export const C13Off360Light = screenStory(360, 'light', POLICY_OFF, policyOff);
export const C13Off360Dark = screenStory(360, 'dark', POLICY_OFF, policyOff);
export const C13Off820Light = screenStory(820, 'light', POLICY_OFF, policyOff);
export const C13Off820Dark = screenStory(820, 'dark', POLICY_OFF, policyOff);
export const C13Off1280Light = screenStory(1280, 'light', POLICY_OFF, policyOff);
export const C13Off1280Dark = screenStory(1280, 'dark', POLICY_OFF, policyOff);

export const C13On360Light = screenStory(360, 'light', POLICY_ON, policyOn);
export const C13On360Dark = screenStory(360, 'dark', POLICY_ON, policyOn);
export const C13On820Light = screenStory(820, 'light', POLICY_ON, policyOn);
export const C13On820Dark = screenStory(820, 'dark', POLICY_ON, policyOn);
export const C13On1280Light = screenStory(1280, 'light', POLICY_ON, policyOn);
export const C13On1280Dark = screenStory(1280, 'dark', POLICY_ON, policyOn);

export const C13Holidays500360Light = screenStory(360, 'light', POLICY_FULL, policyFull);
export const C13Holidays500360Dark = screenStory(360, 'dark', POLICY_FULL, policyFull);
export const C13Holidays500820Light = screenStory(820, 'light', POLICY_FULL, policyFull);
export const C13Holidays500820Dark = screenStory(820, 'dark', POLICY_FULL, policyFull);
export const C13Holidays5001280Light = screenStory(1280, 'light', POLICY_FULL, policyFull);
export const C13Holidays5001280Dark = screenStory(1280, 'dark', POLICY_FULL, policyFull);

export const C13Conflict360Light = screenStory(360, 'light', POLICY_CONFLICT, policyConflict);
export const C13Conflict360Dark = screenStory(360, 'dark', POLICY_CONFLICT, policyConflict);
export const C13Conflict820Light = screenStory(820, 'light', POLICY_CONFLICT, policyConflict);
export const C13Conflict820Dark = screenStory(820, 'dark', POLICY_CONFLICT, policyConflict);
export const C13Conflict1280Light = screenStory(1280, 'light', POLICY_CONFLICT, policyConflict);
export const C13Conflict1280Dark = screenStory(1280, 'dark', POLICY_CONFLICT, policyConflict);

export const C3Sla360Light = screenStory(360, 'light', LIST, listWithSla);
export const C3Sla360Dark = screenStory(360, 'dark', LIST, listWithSla);
export const C3Sla820Light = screenStory(820, 'light', LIST, listWithSla);
export const C3Sla820Dark = screenStory(820, 'dark', LIST, listWithSla);
export const C3Sla1280Light = screenStory(1280, 'light', LIST, listWithSla);
export const C3Sla1280Dark = screenStory(1280, 'dark', LIST, listWithSla);

export const C4SlaOkWarning360Light = screenStory(360, 'light', DETAIL_OK_WARNING, detailOkWarning);
export const C4SlaOkWarning360Dark = screenStory(360, 'dark', DETAIL_OK_WARNING, detailOkWarning);
export const C4SlaOkWarning820Light = screenStory(820, 'light', DETAIL_OK_WARNING, detailOkWarning);
export const C4SlaOkWarning820Dark = screenStory(820, 'dark', DETAIL_OK_WARNING, detailOkWarning);
export const C4SlaOkWarning1280Light = screenStory(1280, 'light', DETAIL_OK_WARNING, detailOkWarning);
export const C4SlaOkWarning1280Dark = screenStory(1280, 'dark', DETAIL_OK_WARNING, detailOkWarning);

export const C4SlaBreachedMet360Light = screenStory(360, 'light', DETAIL_BREACHED_MET, detailBreachedMet);
export const C4SlaBreachedMet360Dark = screenStory(360, 'dark', DETAIL_BREACHED_MET, detailBreachedMet);
export const C4SlaBreachedMet820Light = screenStory(820, 'light', DETAIL_BREACHED_MET, detailBreachedMet);
export const C4SlaBreachedMet820Dark = screenStory(820, 'dark', DETAIL_BREACHED_MET, detailBreachedMet);
export const C4SlaBreachedMet1280Light = screenStory(1280, 'light', DETAIL_BREACHED_MET, detailBreachedMet);
export const C4SlaBreachedMet1280Dark = screenStory(1280, 'dark', DETAIL_BREACHED_MET, detailBreachedMet);

export const C4SlaMissedNone360Light = screenStory(360, 'light', DETAIL_MISSED_NONE, detailMissedNone);
export const C4SlaMissedNone360Dark = screenStory(360, 'dark', DETAIL_MISSED_NONE, detailMissedNone);
export const C4SlaMissedNone820Light = screenStory(820, 'light', DETAIL_MISSED_NONE, detailMissedNone);
export const C4SlaMissedNone820Dark = screenStory(820, 'dark', DETAIL_MISSED_NONE, detailMissedNone);
export const C4SlaMissedNone1280Light = screenStory(1280, 'light', DETAIL_MISSED_NONE, detailMissedNone);
export const C4SlaMissedNone1280Dark = screenStory(1280, 'dark', DETAIL_MISSED_NONE, detailMissedNone);
