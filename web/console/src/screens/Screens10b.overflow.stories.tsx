// 10b 묶음이 바꾼 콘솔 화면(C-9 고객사 상세의 "내보내기" 구역과 대화상자)을 360·820·1280 폭, 밝게·어둡게에서 그리고,
// play에서 테마가 그것인지, 페이지와 바뀐 부분이 가로로 넘치지 않는지, axe(대비 포함) 위반이 없는지 본다(screens.md).
// 구역: 긴 고객사 이름, 긴 요청자 이름, 상태가 섞인 줄 다섯(진행 중, 완료, 실패 둘, 기간 지남).
// 대화상자: 긴 고객사 이름의 제목, 선택 상자 둘과 설명, 409 문장이 든 오류.
// 화면은 가짜 fetch(__tests__/storyFetch.ts, storyFetch10b.ts)의 답으로 그린다.
//
// 대화상자는 play가 구역 머리의 "내보내기"를 눌러 열고, 대화상자의 "내보내기"를 눌러 409 문장을 만든다.
//
// The fake fetch comes first: src/api.ts keeps the fetch it finds when it is first imported.
import { ADMIN, company, issue, scenario, setScenario, summary } from '../__tests__/storyFetch';
import { setTaskScenario } from '../__tests__/storyFetch6b';
import { setLinkScenario } from '../__tests__/storyFetch6c';
import { LONG_REQUESTER, companyExport, setExportScenario, type ExportScenario } from '../__tests__/storyFetch10b';
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

const meta = { title: 'Overflow/10b 콘솔 화면' };
export default meta;

type Found = { el: Element; what: string }[];

/** A long company name without a short word to break on early. */
const LONG_COMPANY = '에이크미글로벌엔터프라이즈홀딩스 아시아태평양지역 고객지원본부 주식회사';
const RUNNING_TEXT = '다른 내보내기가 진행 중입니다. 끝난 뒤 다시 시도해 주세요.';

const FIVE = [
  companyExport({ id: '0b7e2d4c-5a61-4f3e-9c2b-000000000005', state: 'RUNNING', finishedAt: null, size: null, requestedBy: { accountId: 31, name: LONG_REQUESTER }, requestedAt: '2026-10-08T03:18:00Z' }),
  companyExport({ id: '0b7e2d4c-5a61-4f3e-9c2b-000000000004', size: 1_234_567_890, requestedBy: { accountId: 1, name: '최관리' } }),
  companyExport({ id: '0b7e2d4c-5a61-4f3e-9c2b-000000000003', state: 'FAILED', failure: 'DISK', size: null, requestedBy: { accountId: 31, name: LONG_REQUESTER } }),
  companyExport({ id: '0b7e2d4c-5a61-4f3e-9c2b-000000000002', state: 'FAILED', failure: 'INTERRUPTED', size: null, requestedBy: { accountId: 5, name: '박관리' } }),
  companyExport({ id: '0b7e2d4c-5a61-4f3e-9c2b-000000000001', state: 'EXPIRED', size: null, requestedBy: { accountId: 6, name: '한관리' } }),
];

const MIXED: ExportScenario = { exports: FIVE, refuse: null };
const REFUSED: ExportScenario = { exports: FIVE, refuse: { code: 'EXPORT_RUNNING', status: 409 } };

function screenStory(width: Width, theme: Theme, given: ExportScenario, parts: (canvas: HTMLElement) => Promise<Found>): Story {
  const { parameters, globals } = at(width, theme, 'console');
  return {
    parameters: { ...parameters, layout: 'fullscreen' },
    globals,
    render: () => {
      const one = issue();
      setScenario(scenario({ me: ADMIN, companies: [company({ name: LONG_COMPANY })], issue: one, issues: [summary(one)], labels: { ACME: [] } }));
      setTaskScenario({ tasks: [], mine: [] });
      setLinkScenario({ links: [], others: [], linkError: null });
      setExportScenario(given);
      window.history.replaceState(null, '', '/companies/ACME');
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

/** The name a region gets from `aria-label` or from the elements `aria-labelledby` names. */
function regionName(el: HTMLElement): string {
  const label = el.getAttribute('aria-label');
  if (label) return label.trim();
  const ids = (el.getAttribute('aria-labelledby') ?? '').split(/\s+/).filter((id) => id !== '');
  return ids.map((id) => textOf(document.getElementById(id))).join(' ').trim();
}

/**
 * The 구역 the vitest tests find (C9CompanyDetail.10b.test.tsx): a region named from "내보내기" (a named
 * `<section>` or `role="region"`) whose h2, the level of the other C-9 구역, is "내보내기".
 */
function exportSection(main: HTMLElement): HTMLElement | null {
  const regions = Array.from(main.querySelectorAll<HTMLElement>('section[aria-label], section[aria-labelledby], [role="region"]'));
  return regions.find((s) => regionName(s).startsWith('내보내기') && Array.from(s.querySelectorAll('h2')).some((h) => textOf(h) === '내보내기')) ?? null;
}

/** C-9 drawn with the 내보내기 구역 and its five lines. */
async function sectionShows(canvas: HTMLElement): Promise<{ section: HTMLElement; found: Found }> {
  await waitFor(() => Array.from(document.querySelectorAll('h1')).find((h) => textOf(h).includes(LONG_COMPANY)), 'C-9의 h1(긴 고객사 이름)', 8000);
  const main = await waitFor(() => mainOf(canvas), 'C-9의 main');
  const section = await waitFor(() => exportSection(main), 'C-9의 구역 "내보내기"', 8000);
  await waitFor(() => (textOf(section).includes(LONG_REQUESTER) ? true : null), '구역의 긴 요청자 이름');
  await waitFor(() => (textOf(section).includes('내보내기 파일을 쓸 공간이 없습니다.') ? true : null), '구역의 실패 까닭 문장');
  await waitFor(() => (textOf(section).includes('기간 지남') ? true : null), '구역의 "기간 지남" 줄');
  return {
    section,
    found: [
      { el: main, what: 'C-9의 main' },
      { el: section, what: 'C-9의 구역 "내보내기"' },
    ],
  };
}

const exportsList = async (canvas: HTMLElement) => (await sectionShows(canvas)).found;

/** The dialog opened from the 구역 head, after the server refused with 409 EXPORT_RUNNING. */
async function dialogRefused(canvas: HTMLElement): Promise<Found> {
  const { section, found } = await sectionShows(canvas);
  (await waitFor(() => buttonNamed(section, '내보내기'), '구역 머리의 "내보내기"')).click();
  const box = await waitFor(
    () => Array.from(document.querySelectorAll<HTMLElement>('[role="dialog"]')).find((d) => textOf(d).includes(`${LONG_COMPANY} 내보내기`)) ?? null,
    `대화상자 "${LONG_COMPANY} 내보내기"`,
  );
  await waitFor(() => (textOf(box).includes('비공개 문의는 같은 고객사의 다른 고객도 보지 못하는 문의입니다.') ? true : null), '선택 상자의 설명');
  const go = await waitFor(() => {
    const b = buttonNamed(box, '내보내기');
    return b && b.getAttribute('aria-disabled') !== 'true' && !b.disabled ? b : null;
  }, '대화상자의 "내보내기"');
  go.click();
  await waitFor(() => (textOf(box).includes(RUNNING_TEXT) ? true : null), '대화상자 안의 409 문장');
  return [...found, { el: box, what: '오류가 있는 내보내기 대화상자' }];
}

export const C9Exports360Light = screenStory(360, 'light', MIXED, exportsList);
export const C9Exports360Dark = screenStory(360, 'dark', MIXED, exportsList);
export const C9Exports820Light = screenStory(820, 'light', MIXED, exportsList);
export const C9Exports820Dark = screenStory(820, 'dark', MIXED, exportsList);
export const C9Exports1280Light = screenStory(1280, 'light', MIXED, exportsList);
export const C9Exports1280Dark = screenStory(1280, 'dark', MIXED, exportsList);

export const ExportRefused360Light = screenStory(360, 'light', REFUSED, dialogRefused);
export const ExportRefused360Dark = screenStory(360, 'dark', REFUSED, dialogRefused);
export const ExportRefused820Light = screenStory(820, 'light', REFUSED, dialogRefused);
export const ExportRefused820Dark = screenStory(820, 'dark', REFUSED, dialogRefused);
export const ExportRefused1280Light = screenStory(1280, 'light', REFUSED, dialogRefused);
export const ExportRefused1280Dark = screenStory(1280, 'dark', REFUSED, dialogRefused);
