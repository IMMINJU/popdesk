// b1 묶음 screens.md 1절 C-12 감사 로그 표를 820·1280 폭, 밝게·어둡게에서 그리고(B1-AC-01), play에서 "일" 칸과 "대상" 칸의
// 각 칸 글이 한 줄인지, 문서가 창 너비보다 넓지 않은지(표가 넓으면 표 틀 .pd-table-wrap 안에서만 민다), 테마와 axe를 본다.
// 견본: 감사 종류 이름 가운데 가장 긴 것 모두(auditWords.ts에서 센다), "로그인 (회사 계정)", "OIDC 연결 풀기", 긴 이름과 긴 이메일,
// 거를 수 있는 대상(조용한 단추 "고객사 ACME · 계정 2147483647", "고객사 ACME · 문의 ACME-123456").
// 한 줄인지는 칸 안 글 조각(텍스트 노드)의 줄 상자(Range.getClientRects)를 위아래로 겹치는 것끼리 묶어 센다. 묶음이 하나면 한 줄이다.
// 화면은 가짜 fetch(__tests__/storyFetch.ts, storyFetch10a.ts)의 답으로 그린다.
//
// The fake fetch comes first: src/api.ts keeps the fetch it finds when it is first imported.
import { ADMIN, company, issue, scenario, setScenario, summary } from '../__tests__/storyFetch';
import { setTaskScenario } from '../__tests__/storyFetch6b';
import { setLinkScenario } from '../__tests__/storyFetch6c';
import { LONG_EMAIL, LONG_PERSON, hashOf, setAuditScenario, setMemberScenario } from '../__tests__/storyFetch10a';
import type { ReactElement } from 'react';
import type { Schemas } from '../api';
import '@popdesk/ui/tokens.css';
import '@popdesk/ui/styles.css';
import '../console.css';
import { at, expectDrawnRight, textOf, waitFor, type Theme, type Width } from '../../../packages/ui/src/__tests__/overflow';
import { App } from '../App';
import { KIND_GROUPS } from '../app/auditWords';

interface PlayContext {
  canvasElement: HTMLElement;
}

interface Story {
  render: () => ReactElement;
  play: (context: PlayContext) => Promise<void>;
  parameters: ReturnType<typeof at>['parameters'] & { layout: 'fullscreen' };
  globals: ReturnType<typeof at>['globals'];
}

const meta = { title: 'Overflow/b1 콘솔 화면' };
export default meta;

type AuditEntry = Schemas['AuditEntry'];

const INT32_MAX = 2_147_483_647;
const ONE_LINE_COLUMNS = ['일', '대상'] as const;

/**
 * Reads the kind names in Korean. They follow `<html lang>` since b5, and Storybook starts the frame
 * in English, while this story draws the screen for a Korean account.
 */
function inKorean<T>(read: () => T): T {
  const was = document.documentElement.lang;
  document.documentElement.lang = 'ko';
  try {
    return read();
  } finally {
    document.documentElement.lang = was;
  }
}

/** Every kind whose Korean name is the longest there is. */
const LONGEST_KINDS: string[] = inKorean(() => {
  const all = KIND_GROUPS.flatMap((g) => Object.entries(g.kinds));
  const most = Math.max(...all.map(([, word]) => word.length));
  return all.filter(([, word]) => word.length === most).map(([kind]) => kind);
});

const LONG_ACTOR = { accountId: 31, name: LONG_PERSON, email: LONG_EMAIL };

function entry(id: number, action: string, target: Record<string, unknown> | null, changes: Record<string, unknown> | null = null): AuditEntry {
  return {
    id,
    seq: id,
    action,
    actor: LONG_ACTOR,
    target,
    changes,
    occurredAt: '2026-12-31T14:59:59.123456Z',
    hash: hashOf(id),
    prevHash: hashOf(id - 1),
  };
}

const ENTRIES: AuditEntry[] = [
  entry(9401, 'SIGN_IN_SUCCEEDED', { type: 'ACCOUNT', accountId: INT32_MAX }, { method: 'OIDC' }),
  entry(9402, 'OIDC_UNLINKED', { type: 'ACCOUNT', accountId: INT32_MAX }),
  entry(9403, 'ISSUE_PURGED', { type: 'COMPANY', code: 'ACME', issueKey: 'ACME-123456' }),
  ...LONGEST_KINDS.map((kind, i) => entry(9410 + i, kind, { type: 'COMPANY', code: 'ACME', accountId: INT32_MAX })),
];

/** Words the 일 column must show, each in one row. */
const KIND_TEXTS = inKorean(() => ['로그인 (회사 계정)', 'OIDC 연결 풀기', ...LONGEST_KINDS.map((kind) => KIND_GROUPS.map((g) => g.kinds[kind]).find((w) => w !== undefined) ?? kind)]);

function screenStory(width: Width, theme: Theme): Story {
  const { parameters, globals } = at(width, theme, 'console');
  return {
    parameters: { ...parameters, layout: 'fullscreen' },
    globals,
    render: () => {
      const one = issue();
      setScenario(scenario({ me: ADMIN, companies: [company()], issue: one, issues: [summary(one)] }));
      setTaskScenario({ tasks: [], mine: [] });
      setLinkScenario({ links: [], others: [], linkError: null });
      setMemberScenario({ accounts: [], open: { issues: 0, tasks: 0 }, refuse: null });
      setAuditScenario(ENTRIES);
      window.history.replaceState(null, '', '/audit-log');
      return <App />;
    },
    play: async ({ canvasElement }) => {
      const main = await auditShown(canvasElement);
      expectOneLine(main);
      // The page and the screen body do not scroll sideways; the table frame may (it is not checked here).
      await expectDrawnRight(width, theme, [{ el: main, what: 'C-12의 main' }]);
    },
  };
}

function mainOf(canvas: HTMLElement): HTMLElement | null {
  return canvas.querySelector('main') ?? document.querySelector('main');
}

/** C-12 drawn with every sample row. */
async function auditShown(canvas: HTMLElement): Promise<HTMLElement> {
  await waitFor(() => Array.from(document.querySelectorAll('h1')).find((h) => textOf(h) === '감사 로그'), 'C-12의 h1 "감사 로그"', 8000);
  const main = await waitFor(() => mainOf(canvas), 'C-12의 main');
  for (const word of KIND_TEXTS) {
    await waitFor(() => Array.from(main.querySelectorAll('td')).find((td) => textOf(td) === word), `C-12의 "일" 칸 "${word}"`, 8000);
  }
  await waitFor(() => (textOf(main).includes(LONG_EMAIL) ? true : null), 'C-12의 긴 이메일');
  await waitFor(() => Array.from(main.querySelectorAll('td button')).find((b) => textOf(b) === `고객사 ACME · 계정 ${String(INT32_MAX)}`), 'C-12 "대상" 칸의 조용한 단추');
  return main;
}

/** How many lines the text inside `cell` takes: its text boxes, those that overlap from top to bottom put together. */
function linesOf(cell: Element): number {
  const boxes: DOMRect[] = [];
  const walker = document.createTreeWalker(cell, NodeFilter.SHOW_TEXT);
  for (let node = walker.nextNode(); node !== null; node = walker.nextNode()) {
    if ((node.textContent ?? '').trim() === '') continue;
    const range = document.createRange();
    range.selectNodeContents(node);
    for (const box of Array.from(range.getClientRects())) if (box.width > 0 && box.height > 0) boxes.push(box);
  }
  boxes.sort((a, b) => a.top - b.top);
  let lines = 0;
  let bottom = -Infinity;
  for (const box of boxes) {
    if (box.top >= bottom - 1) {
      lines += 1;
      bottom = box.bottom;
    } else {
      bottom = Math.max(bottom, box.bottom);
    }
  }
  return lines;
}

/** Throws unless every cell of 일 and 대상 holds its text in one line. */
function expectOneLine(main: HTMLElement): void {
  const table = main.querySelector('table');
  if (!table) throw new Error('C-12의 표가 없다');
  const heads = Array.from(table.querySelectorAll('thead tr')[0]?.children ?? []);
  const rows = Array.from(table.querySelectorAll('tbody tr')).filter((r) => r.children.length === heads.length);
  if (rows.length < ENTRIES.length) throw new Error(`C-12의 줄이 ${String(rows.length)}개다 (견본 ${String(ENTRIES.length)}개)`);
  const wrapped: string[] = [];
  for (const column of ONE_LINE_COLUMNS) {
    const at = heads.findIndex((h) => textOf(h) === column);
    if (at < 0) throw new Error(`C-12의 열 "${column}"이 없다`);
    for (const row of rows) {
      const cell = row.children[at];
      if (!cell || textOf(cell) === '') continue;
      const lines = linesOf(cell);
      if (lines !== 1) wrapped.push(`"${column}" 칸 "${textOf(cell)}": ${String(lines)}줄 (높이 ${String(Math.round(cell.getBoundingClientRect().height))}px)`);
    }
  }
  if (wrapped.length > 0) throw new Error(`${String(window.innerWidth)} 폭에서 한 줄이 아닌 칸 ${String(wrapped.length)}개: ${wrapped.join(' / ')}`);
}

export const C12Columns820Light = screenStory(820, 'light');
export const C12Columns820Dark = screenStory(820, 'dark');
export const C12Columns1280Light = screenStory(1280, 'light');
export const C12Columns1280Dark = screenStory(1280, 'dark');
