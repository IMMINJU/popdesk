// More test support for slice 7b (design/archive/7b-stats/screens.md): the statistics screen C-5
// and the cleanup alert of C-7. The earlier support files stay as they are; this file adds the
// statistics endpoint to the fake server, in the shape of docs/integrate/openapi-console.json, and
// the helpers the slice 7b screen tests share.
//
// Every answer made here goes through `contract` of console6a.ts: a fixture that does not fit the
// contract file fails the test that used it.
import type { ConsoleComponents } from '@popdesk/api/console';
import { waitFor } from '@testing-library/react';
import { HttpResponse, http } from 'msw/http';
import { beforeEach, expect } from 'vitest';
import { errors, found, screen, server, state, text, within } from './console';
import { contract } from './console6a';

type Schemas = ConsoleComponents['schemas'];
export type CompanyStat = Schemas['CompanyStat'];
export type CompanyStats = Schemas['CompanyStats'];

export const URL_STATS = '/stats';
export const STATS = '/api/console/stats';

/** The column heads of the C-5 table, in order (screens.md 1절). */
export const COLUMNS = ['고객사', '전체', '열림', '처리 중', '해결', '종료'] as const;
export type Column = (typeof COLUMNS)[number];

export const NOTICE = '휴지통의 문의는 세지 않습니다.';
export const EMPTY_ADMIN = '아직 고객사가 없습니다.';
export const EMPTY_AGENT = '배정된 고객사가 없습니다.';
export const READ_FAILED = '통계를 읽지 못했습니다. 다시 해 보세요.';
export const RETRY = '다시 불러오기';
export const ARCHIVED = '보관';

// ---------------------------------------------------------------------------
// Fixtures

/** One company's counts; `total` is the sum of the four others unless given. */
export function stat(over: Partial<CompanyStat> = {}): CompanyStat {
  const base = { companyCode: 'ACME', companyName: '에이크미', companyStatus: 'ACTIVE' as const, open: 5, inProgress: 3, resolved: 0, closed: 4, sla: null, ...over };
  return { ...base, total: over.total ?? base.open + base.inProgress + base.resolved + base.closed };
}

export const ACME_STAT = stat();
export const BETA_STAT = stat({ companyCode: 'BETA', companyName: '베타상사', open: 2, inProgress: 0, resolved: 1, closed: 0 });
/** An archived company: listed with "보관", no open issues. */
export const OLD_STAT = stat({ companyCode: 'OLD', companyName: '옛상사', companyStatus: 'ARCHIVED', open: 0, inProgress: 0, resolved: 0, closed: 7 });
/** A suspended company with the most open issues: its code comes last, so the contract order is not the code order. */
export const ZETA_STAT = stat({ companyCode: 'ZETA', companyName: '제타소프트', companyStatus: 'SUSPENDED', open: 9, inProgress: 1, resolved: 0, closed: 2 });
/** A company with no issues: listed with 0 everywhere (S7B-AC-01). */
export const NEW_STAT = stat({ companyCode: 'NEW', companyName: '새상사', open: 0, inProgress: 0, resolved: 0, closed: 0 });

// ---------------------------------------------------------------------------
// Fake server

/** State the slice 7b handlers read. Tests change it before rendering. */
export const s7b: {
  /** The items `getCompanyStats` gives, in the order the contract lists them. */
  stats: CompanyStat[];
} = { stats: [] };

/** The 200 of `getCompanyStats` for `items`, through the contract check. */
export function statsResponse(items: CompanyStat[] = s7b.stats): Response {
  return HttpResponse.json(contract('getCompanyStats', 200, { items }));
}

const handlers7b = [http.get(`*${STATS}`, () => (state.signedIn ? statsResponse() : errors.unauthenticated()))];

beforeEach(() => {
  s7b.stats = [];
  server.use(...handlers7b);
});

// ---------------------------------------------------------------------------
// Finding things on C-5 통계 (screens.md 1절)

function captionOf(count?: number): string | RegExp {
  return count === undefined ? /^고객사별 문의 수, \d+곳$/ : `고객사별 문의 수, ${String(count)}곳`;
}

/** The table of C-5 by its caption "고객사별 문의 수, {n}곳", or null now. */
export function statsTableNow(count?: number): HTMLElement | null {
  return screen.queryByRole('table', { name: captionOf(count) });
}

/** The table of C-5, waited for generously: the first wait of a test may meet a busy machine. */
export async function statsTable(count?: number): Promise<HTMLElement> {
  let el: HTMLElement | null = null;
  await waitFor(
    () => {
      el = statsTableNow(count);
      expect(el, `표 "고객사별 문의 수, ${count === undefined ? 'n' : String(count)}곳"`).not.toBeNull();
    },
    { timeout: 5000 },
  );
  return el as unknown as HTMLElement;
}

/** The body rows of the C-5 table now (rows with cells). */
export function statsRowsNow(): HTMLElement[] {
  const table = statsTableNow();
  return table ? within(table).queryAllByRole('row').filter((r) => within(r).queryAllByRole('cell').length > 0) : [];
}

/** The body row of the company `code`, or null now. */
export function statsRowNow(code: string): HTMLElement | null {
  return statsRowsNow().find((r) => text(r).includes(code)) ?? null;
}

export function statsRow(code: string): Promise<HTMLElement> {
  return found(() => statsRowNow(code), `통계의 ${code} 줄`);
}

/** The column head `column` of the C-5 table. */
export function headOf(column: Column): HTMLElement {
  const head = screen.queryAllByRole('columnheader').find((h) => text(h) === column);
  expect(head ?? null, `열 머리 "${column}"`).not.toBeNull();
  return head as HTMLElement;
}

/** The cell of `row` under the column whose head is `column`. */
export function cellOf(row: HTMLElement, column: Column): HTMLElement {
  const head = headOf(column);
  const cell = row.children[Array.from(head.parentElement?.children ?? []).indexOf(head)];
  expect(cell ?? null, `"${column}" 칸`).not.toBeNull();
  return cell as HTMLElement;
}

/** The links inside `cell`. */
export function linksIn(cell: HTMLElement): HTMLAnchorElement[] {
  return within(cell).queryAllByRole<HTMLAnchorElement>('link');
}

/** Path and query of a link inside this app: `status` sorted. */
export function target(a: HTMLAnchorElement): { path: string; companyCode: string | null; status: string[]; others: string[] } {
  const url = new URL(a.getAttribute('href') ?? '', window.location.href);
  const others = [...url.searchParams.keys()].filter((k) => k !== 'companyCode' && k !== 'status');
  return { path: url.pathname, companyCode: url.searchParams.get('companyCode'), status: url.searchParams.getAll('status').sort(), others };
}

/** The "통계" link of the side menu, or null now. */
export function statsMenuNow(): HTMLAnchorElement | null {
  return (
    screen
      .queryAllByRole<HTMLAnchorElement>('link', { name: '통계' })
      .find((a) => a.closest('nav') !== null) ?? null
  );
}
