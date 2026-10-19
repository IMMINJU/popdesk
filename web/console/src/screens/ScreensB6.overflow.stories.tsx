// b6 묶음 screens.md 1절·2절: 콘솔 C-9 소속 고객 표와 C-11 휴지통 표를 820·1280 폭, 밝게·어둡게, 한국어·영어 관리자로 그리고,
// play에서 먼저 두 가지를 함께 보고 실패 글에 둘 다 적는다: 표 틀(.pd-table-wrap)이 가로로 밀리지 않는지(scrollWidth <= clientWidth,
// B6-AC-01, B6-AC-03), C-9 이름 칸의 "삭제된 사용자"(B6-AC-02)와 두 표 동작 칸의 단추 글이 줄 하나인지. 그 뒤 페이지와 화면 본문이
// 넘치지 않는지, 테마와 axe를 본다(expectDrawnRight).
// 줄 수는 넘침 도움 overflow.ts의 linesOf로 센다. 단추 글은 화면에 그려진 글만 센다(.pd-sr-only의 문의 키는 빼고).
// 견본 C-9: 67자 이메일, 오십 자 이름의 담당자, 담당자 아닌 고객, 익명화한 계정(이름 "삭제된 사용자", 배지 "익명화됨").
// 견본 C-11: 긴 고객사 이름과 긴 제목, 정리 작업이 지운 줄(deletedBy null)과 사람이 지운 줄.
// 화면은 가짜 fetch(__tests__/storyFetch.ts, storyFetch7a.ts, storyFetchB6.ts)의 답으로 그린다. 화면 문구로 찾지 않고 견본 데이터로 기다린다.
//
// The fake fetch comes first: src/api.ts keeps the fetch it finds when it is first imported.
import { ADMIN, company, issue, scenario, setScenario, summary } from '../__tests__/storyFetch';
import { setTaskScenario } from '../__tests__/storyFetch6b';
import { setLinkScenario } from '../__tests__/storyFetch6c';
import { CHOI, setTrashScenario, trashed } from '../__tests__/storyFetch7a';
import { LONG_PERSON } from '../__tests__/storyFetch10a';
import { ADMIN_EN, ANONYMIZED_NAME, EMAIL_67, anonymizedCustomer, customer, setCustomers } from '../__tests__/storyFetchB6';
import type { ReactElement } from 'react';
import '@popdesk/ui/tokens.css';
import '@popdesk/ui/styles.css';
import '../console.css';
import { at, expectDrawnRight, linesOf, textOf, waitFor, type Theme, type Width } from '../../../packages/ui/src/__tests__/overflow';
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

const meta = { title: 'Overflow/b6 콘솔 표' };
export default meta;

type Language = 'ko' | 'en';

/** Fifty characters: the longest name an account takes, in English words. */
const LONG_PERSON_EN = 'Alexandra Montgomery-Fitzgerald Wellington-Smythe'.padEnd(50, 'e').slice(0, 50);
const LONG_COMPANY = { ko: '에이크미 글로벌 엔터프라이즈 홀딩스 아시아 태평양 고객 지원 본부 주식회사', en: 'Acme Global Enterprise Holdings Asia Pacific Customer Support Division Ltd.' } as const;
const LONG_TITLE = {
  ko: '10월 배포 뒤 아시아 태평양 지역 고객에게 결제 확인 메일이 가지 않고 일부는 스팸함에도 보이지 않습니다',
  en: 'Payment confirmation emails are not delivered to customers in the Asia Pacific region after the October release',
} as const;

const LONG_NAME = { ko: LONG_PERSON, en: LONG_PERSON_EN } as const;
const ANONYMIZED_ID = 13;

function mainOf(canvas: HTMLElement): HTMLElement | null {
  return canvas.querySelector('main') ?? document.querySelector('main');
}

/** Draws the console at `path` for an admin in `language`, after `setUp` set the data of that screen. */
function screenStory(width: Width, theme: Theme, language: Language, path: string, setUp: () => void, check: (canvas: HTMLElement) => Promise<void>): Story {
  const { parameters, globals } = at(width, theme, 'console');
  return {
    parameters: { ...parameters, layout: 'fullscreen' },
    globals,
    render: () => {
      const one = issue();
      setScenario(scenario({ me: language === 'en' ? ADMIN_EN : ADMIN, companies: [company({ name: LONG_COMPANY[language] })], issue: one, issues: [summary(one)] }));
      setTaskScenario({ tasks: [], mine: [] });
      setLinkScenario({ links: [], others: [], linkError: null });
      setUp();
      window.history.replaceState(null, '', path);
      return <App />;
    },
    play: async ({ canvasElement }) => {
      await check(canvasElement);
    },
  };
}

/** Waits until `root` holds every one of `words` (sample data, not screen copy). */
async function holding(root: HTMLElement, what: string, words: readonly string[]): Promise<void> {
  for (const word of words) {
    await waitFor(() => (textOf(root).includes(word) ? true : null), `${what}의 견본 "${word}"`, 8000);
  }
}

/** The body rows of the table inside `root`, waiting until there are `count`. */
async function rowsOf(root: HTMLElement, what: string, count: number): Promise<HTMLTableRowElement[]> {
  return waitFor(() => {
    const rows = Array.from(root.querySelectorAll<HTMLTableRowElement>('table tbody tr'));
    return rows.length >= count ? rows : null;
  }, `${what}의 줄 ${String(count)}개`, 8000);
}

/** "<what>: n줄" for every button in the last cell of each row whose seen text is not one line. */
function buttonsNotOneLine(rows: readonly HTMLTableRowElement[], what: string): string[] {
  const wrapped: string[] = [];
  for (const row of rows) {
    const cell = row.lastElementChild;
    const buttons = Array.from(cell?.querySelectorAll('button') ?? []);
    if (buttons.length === 0) wrapped.push(`${what} 동작 칸에 단추가 없다 (줄 "${textOf(row)}")`);
    for (const button of buttons) {
      const lines = linesOf(button, true);
      if (lines !== 1) wrapped.push(`${what} 동작 칸 단추 "${textOf(button)}": ${String(lines)}줄`);
    }
  }
  return wrapped;
}

/** Throws, naming both, when the frame scrolls sideways or a text is not one line; the frame first, so one run tells both. */
function fitsAndOneLineOrThrow(frame: Element, what: string, wrapped: readonly string[]): void {
  const found: string[] = [];
  if (frame.scrollWidth > frame.clientWidth) found.push(`${what}: 가로로 넘친다 (scrollWidth ${String(frame.scrollWidth)} > clientWidth ${String(frame.clientWidth)})`);
  if (wrapped.length > 0) found.push(`한 줄이 아닌 글 ${String(wrapped.length)}개: ${wrapped.join(' / ')}`);
  if (found.length > 0) throw new Error(`${String(window.innerWidth)} 폭: ${found.join(' // ')}`);
}

/** The table frame inside `root`. */
function frameOf(root: ParentNode, what: string): HTMLElement {
  const frame = root.querySelector<HTMLElement>('.pd-table-wrap');
  if (!frame) throw new Error(`${what}의 표 틀 .pd-table-wrap이 없다`);
  return frame;
}

// C-9 소속 고객 (B6-AC-01, B6-AC-02)

function c9Customers(language: Language) {
  return [
    customer({ accountId: 11, name: LONG_NAME[language], email: EMAIL_67, manager: true }),
    customer({ accountId: 12, name: language === 'en' ? 'Park Colleague' : '박동료', email: 'park@acme.test' }),
    anonymizedCustomer(ANONYMIZED_ID),
  ];
}

function c9Story(width: Width, theme: Theme, language: Language): Story {
  const rows = c9Customers(language);
  return screenStory(
    width,
    theme,
    language,
    '/companies/ACME',
    () => {
      setCustomers(rows);
    },
    async (canvas) => {
      const main = await waitFor(() => mainOf(canvas), 'C-9의 main', 8000);
      const section = await waitFor(() => main.querySelector<HTMLElement>('section[aria-labelledby="customers-title"]'), 'C-9 소속 고객 구역', 8000);
      await holding(section, 'C-9 소속 고객', [EMAIL_67, LONG_NAME[language], ANONYMIZED_NAME]);
      const drawn = await rowsOf(section, 'C-9 소속 고객', rows.length);
      const name = section.querySelector(`#member-${String(ANONYMIZED_ID)}`);
      if (!name || textOf(name) !== ANONYMIZED_NAME) throw new Error(`C-9 이름 칸의 "${ANONYMIZED_NAME}"이 없다`);
      const wrapped = buttonsNotOneLine(drawn, 'C-9');
      const nameLines = linesOf(name);
      if (nameLines !== 1) wrapped.unshift(`C-9 이름 칸 "${ANONYMIZED_NAME}": ${String(nameLines)}줄`);
      const frame = frameOf(section, 'C-9 소속 고객');
      fitsAndOneLineOrThrow(frame, 'C-9 소속 고객 표 틀', wrapped);
      await expectDrawnRight(width, theme, [
        { el: main, what: 'C-9의 main' },
        { el: frame, what: 'C-9 소속 고객 표 틀' },
      ]);
    },
  );
}

// C-11 휴지통 (B6-AC-03)

function c11Story(width: Width, theme: Theme, language: Language): Story {
  const trash = [
    trashed({ key: 'ACME-123456', title: LONG_TITLE[language], companyName: LONG_COMPANY[language], deletedBy: { ...CHOI, name: LONG_NAME[language] } }),
    trashed({ key: 'ACME-123457', title: LONG_TITLE[language], companyName: LONG_COMPANY[language], status: 'CLOSED', deletedBy: null }),
  ];
  return screenStory(
    width,
    theme,
    language,
    '/trash',
    () => {
      setTrashScenario({ trash, conflict: null });
    },
    async (canvas) => {
      const main = await waitFor(() => mainOf(canvas), 'C-11의 main', 8000);
      await holding(main, 'C-11', ['ACME-123456', 'ACME-123457', LONG_TITLE[language], LONG_COMPANY[language]]);
      const drawn = await rowsOf(main, 'C-11', trash.length);
      const frame = frameOf(main, 'C-11');
      fitsAndOneLineOrThrow(frame, 'C-11 휴지통 표 틀', buttonsNotOneLine(drawn, 'C-11'));
      await expectDrawnRight(width, theme, [
        { el: main, what: 'C-11의 main' },
        { el: frame, what: 'C-11 휴지통 표 틀' },
      ]);
    },
  );
}

export const C9Customers820LightKo = c9Story(820, 'light', 'ko');
export const C9Customers820DarkKo = c9Story(820, 'dark', 'ko');
export const C9Customers1280LightKo = c9Story(1280, 'light', 'ko');
export const C9Customers1280DarkKo = c9Story(1280, 'dark', 'ko');
export const C9Customers820LightEn = c9Story(820, 'light', 'en');
export const C9Customers820DarkEn = c9Story(820, 'dark', 'en');
export const C9Customers1280LightEn = c9Story(1280, 'light', 'en');
export const C9Customers1280DarkEn = c9Story(1280, 'dark', 'en');

export const C11Trash820LightKo = c11Story(820, 'light', 'ko');
export const C11Trash820DarkKo = c11Story(820, 'dark', 'ko');
export const C11Trash1280LightKo = c11Story(1280, 'light', 'ko');
export const C11Trash1280DarkKo = c11Story(1280, 'dark', 'ko');
export const C11Trash820LightEn = c11Story(820, 'light', 'en');
export const C11Trash820DarkEn = c11Story(820, 'dark', 'en');
export const C11Trash1280LightEn = c11Story(1280, 'light', 'en');
export const C11Trash1280DarkEn = c11Story(1280, 'dark', 'en');
