// 6a 묶음이 바꾼 콘솔 화면(C-3 문의 목록, C-4 문의 상세, C-9 고객사 상세)을 360·820·1280 폭,
// 밝게·어둡게에서 그리고, play에서 페이지와 바뀐 부품이 가로로 넘치지 않는지 본다(screens.md 6절,
// S6A-AC-85, S6A-AC-91). 화면은 가짜 fetch(__tests__/storyFetch.ts)의 답으로 그린다. C-3의 표는
// 안에서 옆으로 스크롤해도 되므로 표 자체는 재지 않는다(screens.md 2절).
//
// The fake fetch comes first: src/api.ts keeps the fetch it finds when it is first imported.
import { ADMIN, WIDE_LABELS, company, issue, scenario, setScenario, summary, type Scenario } from '../__tests__/storyFetch';
import { useState, type ReactElement } from 'react';
import { createMemoryRouter, RouterProvider, type RouteObject } from 'react-router';
import '@popdesk/ui/tokens.css';
import '@popdesk/ui/styles.css';
import '../console.css';
import {
  at,
  buttonNamed,
  expectFits,
  expectPageFits,
  namedPart,
  settled,
  termValue,
  textOf,
  waitFor,
  type Theme,
  type Width,
} from '../../../packages/ui/src/__tests__/overflow';
import { AdminOnly, ConsoleLayout } from '../app/layout';
import { SessionProvider } from '../app/session';
import { ToastProvider } from '../app/toast';
import { C3IssueList } from './C3IssueList';
import { C4IssueDetail } from './C4IssueDetail';
import { C9CompanyDetail } from './C9CompanyDetail';

interface PlayContext {
  canvasElement: HTMLElement;
}

interface Story {
  render: () => ReactElement;
  play: (context: PlayContext) => Promise<void>;
  parameters: ReturnType<typeof at>['parameters'] & { layout: 'fullscreen' };
  globals: ReturnType<typeof at>['globals'];
}

const meta = { title: 'Overflow/6a 콘솔 화면' };
export default meta;

const routes: RouteObject[] = [
  {
    element: <ConsoleLayout />,
    children: [
      { path: '/issues', element: <C3IssueList /> },
      { path: '/issues/:issueKey', element: <C4IssueDetail /> },
      { path: '/companies/:code', element: <AdminOnly><C9CompanyDetail /></AdminOnly> },
    ],
  },
];

function Screen(props: { path: string }) {
  const [router] = useState(() => createMemoryRouter(routes, { initialEntries: [props.path] }));
  return (
    <SessionProvider>
      <ToastProvider>
        <RouterProvider router={router} />
      </ToastProvider>
    </SessionProvider>
  );
}

const refs = (n: number) => WIDE_LABELS.slice(0, n).map(({ id, name, color }) => ({ id, name, color }));

/** C-4: an issue with ten labels, two of thirty characters, and a due date that has passed. */
const DETAIL: Scenario = scenario({ issue: issue({ labels: refs(10), dueDate: '2026-10-01', overdue: true }) });

/** C-3: rows with many labels and due dates, filtered by a company's labels, earliest due date first. */
const LIST: Scenario = scenario({
  issues: [
    summary(issue({ key: 'ACME-7', labels: refs(10), dueDate: '2026-10-01', overdue: true })),
    summary(issue({ key: 'ACME-8', title: '결제가 두 번 됩니다', labels: refs(2), dueDate: '2099-10-20' })),
    summary(issue({ key: 'ACME-9', title: '앱이 멈춥니다', labels: [], dueDate: null })),
  ],
});
const LIST_PATH = '/issues?companyCode=ACME&label=61&label=69&sort=dueDate';

/** C-9: an active company with ten labels. */
const COMPANY: Scenario = scenario({ me: ADMIN, companies: [company()] });

type Found = { el: Element; what: string }[];

function screenStory(width: Width, theme: Theme, data: Scenario, path: string, parts: (canvas: HTMLElement) => Promise<Found>): Story {
  const { parameters, globals } = at(width, theme, 'console');
  return {
    parameters: { ...parameters, layout: 'fullscreen' },
    globals,
    render: () => {
      setScenario(data);
      return <Screen path={path} />;
    },
    play: async ({ canvasElement }) => {
      const found = await parts(canvasElement);
      await settled();
      expectPageFits(width);
      for (const { el, what } of found) expectFits(el, what);
    },
  };
}

// The screens drawn at all, by a part that was there before this slice: a failure past this
// point is about the slice, not about the fake server.
const detailDrawn = (canvas: HTMLElement) => waitFor(() => termValue(canvas, '담당자'), 'C-4의 칸 "담당자"(화면이 그려짐)', 8000);
const listDrawn = (canvas: HTMLElement) =>
  waitFor(() => Array.from(canvas.querySelectorAll('td')).find((td) => textOf(td) === 'ACME-8'), 'C-3의 ACME-8 줄(화면이 그려짐)', 8000);
const companyDrawn = (canvas: HTMLElement) => waitFor(() => namedPart(canvas, /^배정 상담원/), 'C-9의 구역 "배정 상담원"(화면이 그려짐)', 8000);

// C-4 문의 상세

async function detailValues(canvas: HTMLElement): Promise<Found> {
  await detailDrawn(canvas);
  const labels = await waitFor(() => termValue(canvas, '라벨'), 'C-4의 칸 "라벨"');
  await waitFor(() => (textOf(termValue(canvas, '마감일')).includes('지남') ? true : null), 'C-4의 칸 "마감일"의 "지남"');
  return [
    { el: labels.closest('dl') ?? labels, what: 'C-4 머리의 칸들' },
    { el: labels, what: 'C-4의 칸 "라벨"' },
  ];
}

async function detailLabelPicker(canvas: HTMLElement): Promise<Found> {
  await detailDrawn(canvas);
  const dd = await waitFor(() => termValue(canvas, '라벨'), 'C-4의 칸 "라벨"');
  (await waitFor(() => buttonNamed(dd, '바꾸기'), '"라벨" 칸의 "바꾸기"')).click();
  const group = await waitFor(() => namedPart(document, '라벨'), '열린 라벨 고르기');
  return [
    { el: group, what: '라벨 고르기' },
    { el: dd.closest('dl') ?? dd, what: 'C-4 머리의 칸들' },
  ];
}

async function detailDuePicker(canvas: HTMLElement): Promise<Found> {
  await detailDrawn(canvas);
  const dd = await waitFor(() => termValue(canvas, '마감일'), 'C-4의 칸 "마감일"');
  (await waitFor(() => buttonNamed(dd, '바꾸기'), '"마감일" 칸의 "바꾸기"')).click();
  const input = await waitFor(() => document.querySelector('input[type="date"]'), '열린 마감일 고르기의 날짜 입력');
  return [
    { el: input.parentElement ?? input, what: '마감일 고르기' },
    { el: dd.closest('dl') ?? dd, what: 'C-4 머리의 칸들' },
  ];
}

// C-3 문의 목록

async function listWithLabels(canvas: HTMLElement): Promise<Found> {
  await listDrawn(canvas);
  await waitFor(() => Array.from(canvas.querySelectorAll('th')).find((th) => textOf(th).includes('마감일')), 'C-3의 열 "마감일"');
  const filter = await waitFor(
    () =>
      namedPart(canvas, '라벨') ??
      Array.from(canvas.querySelectorAll('button[aria-expanded]')).find((b) => /라벨/.test(b.getAttribute('aria-label') ?? textOf(b))),
    'C-3의 "라벨" 필터',
  );
  const sort = await waitFor(() => Array.from(canvas.querySelectorAll('label')).find((l) => textOf(l) === '정렬'), 'C-3의 "정렬"');
  return [
    { el: filter.parentElement ?? filter, what: 'C-3 필터 줄' },
    { el: sort.parentElement ?? sort, what: 'C-3의 "정렬"' },
  ];
}

// C-9 고객사 상세

async function companyLabels(canvas: HTMLElement): Promise<Found> {
  await companyDrawn(canvas);
  const section = await waitFor(() => namedPart(canvas, /^라벨/), 'C-9의 구역 "라벨"');
  await waitFor(() => (textOf(section).includes('문의 12건') ? true : null), 'C-9 라벨 목록의 "문의 12건"');
  return [{ el: section, what: 'C-9의 구역 "라벨"' }];
}

async function companyLabelDialog(canvas: HTMLElement): Promise<Found> {
  await companyDrawn(canvas);
  const section = await waitFor(() => namedPart(canvas, /^라벨/), 'C-9의 구역 "라벨"');
  (await waitFor(() => buttonNamed(section, '라벨 만들기'), '"라벨 만들기"')).click();
  const box = await waitFor(() => document.querySelector('[role="dialog"]'), '라벨 만들기 대화상자');
  await waitFor(() => box.querySelector('[role="radiogroup"]'), '색 견본 라디오 그룹');
  return [{ el: box, what: '라벨 만들기 대화상자' }];
}

export const C4Labels360Light = screenStory(360, 'light', DETAIL, '/issues/ACME-7', detailValues);
export const C4Labels360Dark = screenStory(360, 'dark', DETAIL, '/issues/ACME-7', detailValues);
export const C4Labels820Light = screenStory(820, 'light', DETAIL, '/issues/ACME-7', detailValues);
export const C4Labels820Dark = screenStory(820, 'dark', DETAIL, '/issues/ACME-7', detailValues);
export const C4Labels1280Light = screenStory(1280, 'light', DETAIL, '/issues/ACME-7', detailValues);
export const C4Labels1280Dark = screenStory(1280, 'dark', DETAIL, '/issues/ACME-7', detailValues);

export const C4LabelPicker360Light = screenStory(360, 'light', DETAIL, '/issues/ACME-7', detailLabelPicker);
export const C4LabelPicker360Dark = screenStory(360, 'dark', DETAIL, '/issues/ACME-7', detailLabelPicker);
export const C4LabelPicker820Light = screenStory(820, 'light', DETAIL, '/issues/ACME-7', detailLabelPicker);
export const C4LabelPicker820Dark = screenStory(820, 'dark', DETAIL, '/issues/ACME-7', detailLabelPicker);
export const C4LabelPicker1280Light = screenStory(1280, 'light', DETAIL, '/issues/ACME-7', detailLabelPicker);
export const C4LabelPicker1280Dark = screenStory(1280, 'dark', DETAIL, '/issues/ACME-7', detailLabelPicker);

export const C4DuePicker360Light = screenStory(360, 'light', DETAIL, '/issues/ACME-7', detailDuePicker);
export const C4DuePicker360Dark = screenStory(360, 'dark', DETAIL, '/issues/ACME-7', detailDuePicker);
export const C4DuePicker820Light = screenStory(820, 'light', DETAIL, '/issues/ACME-7', detailDuePicker);
export const C4DuePicker820Dark = screenStory(820, 'dark', DETAIL, '/issues/ACME-7', detailDuePicker);
export const C4DuePicker1280Light = screenStory(1280, 'light', DETAIL, '/issues/ACME-7', detailDuePicker);
export const C4DuePicker1280Dark = screenStory(1280, 'dark', DETAIL, '/issues/ACME-7', detailDuePicker);

export const C3Labels360Light = screenStory(360, 'light', LIST, LIST_PATH, listWithLabels);
export const C3Labels360Dark = screenStory(360, 'dark', LIST, LIST_PATH, listWithLabels);
export const C3Labels820Light = screenStory(820, 'light', LIST, LIST_PATH, listWithLabels);
export const C3Labels820Dark = screenStory(820, 'dark', LIST, LIST_PATH, listWithLabels);
export const C3Labels1280Light = screenStory(1280, 'light', LIST, LIST_PATH, listWithLabels);
export const C3Labels1280Dark = screenStory(1280, 'dark', LIST, LIST_PATH, listWithLabels);

export const C9Labels360Light = screenStory(360, 'light', COMPANY, '/companies/ACME', companyLabels);
export const C9Labels360Dark = screenStory(360, 'dark', COMPANY, '/companies/ACME', companyLabels);
export const C9Labels820Light = screenStory(820, 'light', COMPANY, '/companies/ACME', companyLabels);
export const C9Labels820Dark = screenStory(820, 'dark', COMPANY, '/companies/ACME', companyLabels);
export const C9Labels1280Light = screenStory(1280, 'light', COMPANY, '/companies/ACME', companyLabels);
export const C9Labels1280Dark = screenStory(1280, 'dark', COMPANY, '/companies/ACME', companyLabels);

export const C9LabelDialog360Light = screenStory(360, 'light', COMPANY, '/companies/ACME', companyLabelDialog);
export const C9LabelDialog360Dark = screenStory(360, 'dark', COMPANY, '/companies/ACME', companyLabelDialog);
export const C9LabelDialog820Light = screenStory(820, 'light', COMPANY, '/companies/ACME', companyLabelDialog);
export const C9LabelDialog820Dark = screenStory(820, 'dark', COMPANY, '/companies/ACME', companyLabelDialog);
export const C9LabelDialog1280Light = screenStory(1280, 'light', COMPANY, '/companies/ACME', companyLabelDialog);
export const C9LabelDialog1280Dark = screenStory(1280, 'dark', COMPANY, '/companies/ACME', companyLabelDialog);
