// 6a 묶음이 바꾼 콘솔 화면(C-3 문의 목록, C-4 문의 상세, C-9 고객사 상세)을 바뀐 상태마다 360·820·1280 폭,
// 밝게·어둡게에서 그리고, play에서 테마가 그것인지, 페이지와 바뀐 부품이 가로로 넘치지 않는지, axe(대비 포함)
// 위반이 없는지 본다(screens.md 6절, S6A-AC-85, S6A-AC-91). 화면은 가짜 fetch(__tests__/storyFetch.ts)의
// 답으로 그린다. C-3의 표는 안에서 옆으로 스크롤해도 되므로 표 자체는 재지 않는다(screens.md 2절).
//
// The fake fetch comes first: src/api.ts keeps the fetch it finds when it is first imported.
import { ADMIN, WIDE_LABELS, company, issue, manyLabels, scenario, setScenario, summary, type Scenario } from '../__tests__/storyFetch';
import { useState, type ReactElement } from 'react';
import { createMemoryRouter, RouterProvider, type RouteObject } from 'react-router';
import '@popdesk/ui/tokens.css';
import '@popdesk/ui/styles.css';
import '../console.css';
import {
  at,
  buttonNamed,
  expectDrawnRight,
  nameOf,
  namedPart,
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
const LONG_LABEL = WIDE_LABELS.reduce((a, b) => (b.name.length > a.name.length ? b : a));

/** C-4: an issue with ten labels, two of thirty characters, and a due date that has passed. */
const DETAIL: Scenario = scenario({ issue: issue({ labels: refs(10), dueDate: '2026-10-01', overdue: true }) });

/** C-4: the same issue closed: no "바꾸기" for 라벨 and 마감일. */
const DETAIL_CLOSED: Scenario = scenario({
  issue: issue({ status: 'CLOSED', nextStatuses: [], closedAt: '2026-10-09T03:15:00Z', labels: refs(10), dueDate: '2026-10-01', overdue: true }),
});

/** C-3: rows with many labels and due dates, filtered by a company's labels, earliest due date first. */
const LIST: Scenario = scenario({
  issues: [
    summary(issue({ key: 'ACME-7', labels: refs(10), dueDate: '2026-10-01', overdue: true })),
    summary(issue({ key: 'ACME-8', title: '결제가 두 번 됩니다', labels: refs(2), dueDate: '2099-10-20' })),
    summary(issue({ key: 'ACME-9', title: '앱이 멈춥니다', labels: [], dueDate: null })),
  ],
});
const LIST_PATH = '/issues?companyCode=ACME&label=61&label=69&sort=dueDate';
/** C-3 before a company is chosen: no 라벨 filter yet, the 정렬 is there. */
const LIST_NO_COMPANY_PATH = '/issues?sort=dueDate';

/** C-9: an active company with ten labels. */
const COMPANY: Scenario = scenario({ me: ADMIN, companies: [company()] });
/** C-9: no label yet. */
const COMPANY_EMPTY: Scenario = scenario({ me: ADMIN, companies: [company()], labels: { ACME: [] } });
/** C-9: 200 labels, the limit. */
const COMPANY_FULL: Scenario = scenario({ me: ADMIN, companies: [company()], labels: { ACME: manyLabels(200) } });
/** C-9: an archived company with its labels. */
const COMPANY_ARCHIVED: Scenario = scenario({ me: ADMIN, companies: [company({ status: 'ARCHIVED' })] });

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
      await expectDrawnRight(width, theme, await parts(canvasElement));
    },
  };
}

// The screens drawn at all, by a part that was there before this slice: a failure past this
// point is about the slice, not about the fake server.
const detailDrawn = (canvas: HTMLElement) => waitFor(() => termValue(canvas, '담당자'), 'C-4의 칸 "담당자"(화면이 그려짐)', 8000);
const listDrawn = (canvas: HTMLElement) =>
  waitFor(() => Array.from(canvas.querySelectorAll('td')).find((td) => textOf(td) === 'ACME-8'), 'C-3의 ACME-8 줄(화면이 그려짐)', 8000);
const companyDrawn = (canvas: HTMLElement) => waitFor(() => namedPart(canvas, /^배정 상담원/), 'C-9의 구역 "배정 상담원"(화면이 그려짐)', 8000);

/** The `label` element whose text is `name` and the box around it. */
const fieldBox = (canvas: HTMLElement, name: string, what: string) =>
  waitFor(() => Array.from(canvas.querySelectorAll('label')).find((l) => textOf(l) === name)?.parentElement, what);

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
  (await waitFor(() => buttonNamed(dd, '라벨 바꾸기'), '"라벨" 칸의 "라벨 바꾸기"')).click();
  const group = await waitFor(() => namedPart(document, '라벨'), '열린 라벨 고르기');
  return [
    { el: group, what: '라벨 고르기' },
    { el: dd.closest('dl') ?? dd, what: 'C-4 머리의 칸들' },
  ];
}

async function detailDuePicker(canvas: HTMLElement): Promise<Found> {
  await detailDrawn(canvas);
  const dd = await waitFor(() => termValue(canvas, '마감일'), 'C-4의 칸 "마감일"');
  (await waitFor(() => buttonNamed(dd, '마감일 바꾸기'), '"마감일" 칸의 "마감일 바꾸기"')).click();
  const input = await waitFor(() => document.querySelector('input[type="date"]'), '열린 마감일 고르기의 날짜 입력');
  return [
    { el: input.parentElement ?? input, what: '마감일 고르기' },
    { el: dd.closest('dl') ?? dd, what: 'C-4 머리의 칸들' },
  ];
}

async function detailClosed(canvas: HTMLElement): Promise<Found> {
  const found = await detailValues(canvas);
  const left = ['라벨', '마감일'].flatMap((term) => Array.from(termValue(canvas, term)?.querySelectorAll('button') ?? []).filter((b) => nameOf(b).includes('바꾸기')));
  if (left.length > 0) throw new Error(`CLOSED 문의에 "바꾸기"가 ${String(left.length)}개 남았다`);
  return found;
}

// C-3 문의 목록

async function listWithLabels(canvas: HTMLElement): Promise<Found> {
  await listDrawn(canvas);
  await waitFor(() => Array.from(canvas.querySelectorAll('th')).find((th) => textOf(th).includes('마감일')), 'C-3의 열 "마감일"');
  const filter = await waitFor(() => namedPart(canvas, '라벨'), 'C-3의 "라벨" 필터(이름이 "라벨"인 체크 상자 묶음)');
  const sort = await fieldBox(canvas, '정렬', 'C-3의 "정렬"');
  return [
    { el: filter.parentElement ?? filter, what: 'C-3 필터 줄' },
    { el: sort, what: 'C-3의 "정렬"' },
  ];
}

async function listBeforeCompany(canvas: HTMLElement): Promise<Found> {
  await listDrawn(canvas);
  await waitFor(() => Array.from(canvas.querySelectorAll('th')).find((th) => textOf(th).includes('마감일')), 'C-3의 열 "마감일"');
  const sort = await fieldBox(canvas, '정렬', 'C-3의 "정렬"');
  const companyField = await fieldBox(canvas, '고객사', 'C-3의 "고객사"');
  return [
    { el: companyField.parentElement ?? companyField, what: 'C-3 필터 줄' },
    { el: sort, what: 'C-3의 "정렬"' },
  ];
}

// C-9 고객사 상세

async function labelSection(canvas: HTMLElement, shows: string): Promise<HTMLElement> {
  await companyDrawn(canvas);
  const section = await waitFor(() => namedPart(canvas, /^라벨/), 'C-9의 구역 "라벨"');
  await waitFor(() => (textOf(section).includes(shows) ? true : null), `C-9 라벨 구역의 "${shows}"`);
  return section;
}

async function companyLabels(canvas: HTMLElement): Promise<Found> {
  return [{ el: await labelSection(canvas, '문의 12건'), what: 'C-9의 구역 "라벨"' }];
}

async function companyEmpty(canvas: HTMLElement): Promise<Found> {
  return [{ el: await labelSection(canvas, '아직 라벨이 없습니다.'), what: 'C-9의 구역 "라벨"(빈 상태)' }];
}

async function companyFull(canvas: HTMLElement): Promise<Found> {
  return [{ el: await labelSection(canvas, '라벨은 고객사마다 200개까지 만듭니다.'), what: 'C-9의 구역 "라벨"(200개)' }];
}

async function companyLabelDialog(canvas: HTMLElement): Promise<Found> {
  const section = await labelSection(canvas, '문의 12건');
  (await waitFor(() => buttonNamed(section, '라벨 만들기'), '"라벨 만들기"')).click();
  const box = await waitFor(() => document.querySelector('[role="dialog"]'), '라벨 만들기 대화상자');
  await waitFor(() => box.querySelector('[role="radiogroup"]'), '색 견본 라디오 그룹');
  return [{ el: box, what: '라벨 만들기 대화상자' }];
}

/** C-9: the 지우기 confirmation of the label with the longest name, from its line or its line menu. */
async function companyDeleteConfirm(canvas: HTMLElement): Promise<Found> {
  const section = await labelSection(canvas, '문의 12건');
  const line = await waitFor(
    () => Array.from(section.querySelectorAll('tr, li')).find((l) => textOf(l).includes(LONG_LABEL.name)),
    `"${LONG_LABEL.name}" 줄`,
  );
  const direct = buttonNamed(line, '지우기');
  if (direct) direct.click();
  else {
    (await waitFor(() => line.querySelector<HTMLButtonElement>('button[aria-haspopup]'), `"${LONG_LABEL.name}" 줄의 메뉴`)).click();
    (await waitFor(() => Array.from(document.querySelectorAll<HTMLElement>('[role="menuitem"]')).find((m) => nameOf(m) === '지우기'), '줄 메뉴의 "지우기"')).click();
  }
  const box = await waitFor(() => document.querySelector('[role="dialog"], [role="alertdialog"]'), '지우기 확인 대화상자');
  await waitFor(() => (textOf(box).includes('문의 7건') ? true : null), '지우기 확인의 "문의 7건"');
  return [{ el: box, what: '라벨 지우기 확인' }];
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

export const C4Closed360Light = screenStory(360, 'light', DETAIL_CLOSED, '/issues/ACME-7', detailClosed);
export const C4Closed360Dark = screenStory(360, 'dark', DETAIL_CLOSED, '/issues/ACME-7', detailClosed);
export const C4Closed820Light = screenStory(820, 'light', DETAIL_CLOSED, '/issues/ACME-7', detailClosed);
export const C4Closed820Dark = screenStory(820, 'dark', DETAIL_CLOSED, '/issues/ACME-7', detailClosed);
export const C4Closed1280Light = screenStory(1280, 'light', DETAIL_CLOSED, '/issues/ACME-7', detailClosed);
export const C4Closed1280Dark = screenStory(1280, 'dark', DETAIL_CLOSED, '/issues/ACME-7', detailClosed);

export const C3Labels360Light = screenStory(360, 'light', LIST, LIST_PATH, listWithLabels);
export const C3Labels360Dark = screenStory(360, 'dark', LIST, LIST_PATH, listWithLabels);
export const C3Labels820Light = screenStory(820, 'light', LIST, LIST_PATH, listWithLabels);
export const C3Labels820Dark = screenStory(820, 'dark', LIST, LIST_PATH, listWithLabels);
export const C3Labels1280Light = screenStory(1280, 'light', LIST, LIST_PATH, listWithLabels);
export const C3Labels1280Dark = screenStory(1280, 'dark', LIST, LIST_PATH, listWithLabels);

export const C3NoCompany360Light = screenStory(360, 'light', LIST, LIST_NO_COMPANY_PATH, listBeforeCompany);
export const C3NoCompany360Dark = screenStory(360, 'dark', LIST, LIST_NO_COMPANY_PATH, listBeforeCompany);
export const C3NoCompany820Light = screenStory(820, 'light', LIST, LIST_NO_COMPANY_PATH, listBeforeCompany);
export const C3NoCompany820Dark = screenStory(820, 'dark', LIST, LIST_NO_COMPANY_PATH, listBeforeCompany);
export const C3NoCompany1280Light = screenStory(1280, 'light', LIST, LIST_NO_COMPANY_PATH, listBeforeCompany);
export const C3NoCompany1280Dark = screenStory(1280, 'dark', LIST, LIST_NO_COMPANY_PATH, listBeforeCompany);

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

export const C9LabelsEmpty360Light = screenStory(360, 'light', COMPANY_EMPTY, '/companies/ACME', companyEmpty);
export const C9LabelsEmpty360Dark = screenStory(360, 'dark', COMPANY_EMPTY, '/companies/ACME', companyEmpty);
export const C9LabelsEmpty820Light = screenStory(820, 'light', COMPANY_EMPTY, '/companies/ACME', companyEmpty);
export const C9LabelsEmpty820Dark = screenStory(820, 'dark', COMPANY_EMPTY, '/companies/ACME', companyEmpty);
export const C9LabelsEmpty1280Light = screenStory(1280, 'light', COMPANY_EMPTY, '/companies/ACME', companyEmpty);
export const C9LabelsEmpty1280Dark = screenStory(1280, 'dark', COMPANY_EMPTY, '/companies/ACME', companyEmpty);

export const C9LabelsFull360Light = screenStory(360, 'light', COMPANY_FULL, '/companies/ACME', companyFull);
export const C9LabelsFull360Dark = screenStory(360, 'dark', COMPANY_FULL, '/companies/ACME', companyFull);
export const C9LabelsFull820Light = screenStory(820, 'light', COMPANY_FULL, '/companies/ACME', companyFull);
export const C9LabelsFull820Dark = screenStory(820, 'dark', COMPANY_FULL, '/companies/ACME', companyFull);
export const C9LabelsFull1280Light = screenStory(1280, 'light', COMPANY_FULL, '/companies/ACME', companyFull);
export const C9LabelsFull1280Dark = screenStory(1280, 'dark', COMPANY_FULL, '/companies/ACME', companyFull);

export const C9LabelsArchived360Light = screenStory(360, 'light', COMPANY_ARCHIVED, '/companies/ACME', companyLabels);
export const C9LabelsArchived360Dark = screenStory(360, 'dark', COMPANY_ARCHIVED, '/companies/ACME', companyLabels);
export const C9LabelsArchived820Light = screenStory(820, 'light', COMPANY_ARCHIVED, '/companies/ACME', companyLabels);
export const C9LabelsArchived820Dark = screenStory(820, 'dark', COMPANY_ARCHIVED, '/companies/ACME', companyLabels);
export const C9LabelsArchived1280Light = screenStory(1280, 'light', COMPANY_ARCHIVED, '/companies/ACME', companyLabels);
export const C9LabelsArchived1280Dark = screenStory(1280, 'dark', COMPANY_ARCHIVED, '/companies/ACME', companyLabels);

export const C9LabelDelete360Light = screenStory(360, 'light', COMPANY, '/companies/ACME', companyDeleteConfirm);
export const C9LabelDelete360Dark = screenStory(360, 'dark', COMPANY, '/companies/ACME', companyDeleteConfirm);
export const C9LabelDelete820Light = screenStory(820, 'light', COMPANY, '/companies/ACME', companyDeleteConfirm);
export const C9LabelDelete820Dark = screenStory(820, 'dark', COMPANY, '/companies/ACME', companyDeleteConfirm);
export const C9LabelDelete1280Light = screenStory(1280, 'light', COMPANY, '/companies/ACME', companyDeleteConfirm);
export const C9LabelDelete1280Dark = screenStory(1280, 'dark', COMPANY, '/companies/ACME', companyDeleteConfirm);
