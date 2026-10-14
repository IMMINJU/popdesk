// The filters of the issue list (3-issues screens.md C-3 필터 줄). They live in the
// address under the names the API takes (`q`, `status`, `companyCode`, `assignee`,
// `reporter`), so a reload or 뒤로 가기 shows the same list.
// Slice 6a adds `label` (given once for each label, only with `companyCode`) and `sort`. The sort
// is not a filter: it does not make a list "filtered", and clearing the filters keeps it.
import type { Schemas } from '../api';

export type IssueStatus = Schemas['Issue']['status'];

export const ISSUE_STATUSES: readonly IssueStatus[] = ['RECEIVED', 'CONFIRMED', 'IN_PROGRESS', 'RESOLVED', 'CLOSED'];
/** The statuses of the 내 담당 and 담당 없음 views: all but 종료. */
const OPEN_STATUSES = ISSUE_STATUSES.filter((s) => s !== 'CLOSED');

export interface IssueFilters {
  /** Empty: no search. */
  q: string;
  /** In the order of `ISSUE_STATUSES`. Empty: every status. */
  status: IssueStatus[];
  /** Empty: every company the reader sees. */
  companyCode: string;
  /** `me`, `none`, `unavailable`, an account ID, or empty for everyone. */
  assignee: string;
  reporter: number | null;
  /** Label IDs of the chosen company. Empty without `companyCode`: a label belongs to one company. */
  label: number[];
}

export const NO_FILTERS: IssueFilters = { q: '', status: [], companyCode: '', assignee: '', reporter: null, label: [] };

export type IssueSort = 'createdAt' | 'dueDate';
export const DEFAULT_SORT: IssueSort = 'createdAt';

const ACCOUNT_ID = /^[1-9]\d{0,14}$/;

/** True when `assignee` names one person by account ID. */
export function isPerson(assignee: string): boolean {
  return ACCOUNT_ID.test(assignee);
}

/** The filters of an address. A value the list cannot take (an unknown status, a wrong ID) is left out alone. */
export function readIssueFilters(params: URLSearchParams): IssueFilters {
  const given = params.getAll('status');
  const assignee = params.get('assignee') ?? '';
  const reporter = params.get('reporter') ?? '';
  const companyCode = (params.get('companyCode') ?? '').trim();
  const labels = companyCode === '' ? [] : params.getAll('label').filter((l) => ACCOUNT_ID.test(l)).map(Number);
  return {
    q: (params.get('q') ?? '').trim().slice(0, 100),
    status: ISSUE_STATUSES.filter((s) => given.includes(s)),
    companyCode,
    assignee: assignee === 'me' || assignee === 'none' || assignee === 'unavailable' || isPerson(assignee) ? assignee : '',
    reporter: ACCOUNT_ID.test(reporter) ? Number(reporter) : null,
    label: [...new Set(labels)],
  };
}

/** The sort of an address; an unknown value is the default. */
export function readIssueSort(params: URLSearchParams): IssueSort {
  return params.get('sort') === 'dueDate' ? 'dueDate' : DEFAULT_SORT;
}

export function writeIssueFilters(filters: IssueFilters, sort: IssueSort = DEFAULT_SORT): URLSearchParams {
  const params = new URLSearchParams();
  if (filters.q !== '') params.set('q', filters.q);
  if (filters.companyCode !== '') params.set('companyCode', filters.companyCode);
  if (filters.companyCode !== '') for (const id of filters.label) params.append('label', String(id));
  if (filters.assignee !== '') params.set('assignee', filters.assignee);
  for (const status of filters.status) params.append('status', status);
  if (filters.reporter !== null) params.set('reporter', String(filters.reporter));
  if (sort !== DEFAULT_SORT) params.set('sort', sort);
  return params;
}

/** The address of the issue list with these filters, on the first page. */
export function issuesHref(filters: Partial<IssueFilters> = {}, sort: IssueSort = DEFAULT_SORT): string {
  const query = writeIssueFilters({ ...NO_FILTERS, ...filters }, sort).toString();
  return query === '' ? '/issues' : `/issues?${query}`;
}

/** 보기: filters set in advance. 전체 is the list without a query. */
export const ISSUE_VIEWS = [
  { name: '내 담당', filters: { ...NO_FILTERS, assignee: 'me', status: OPEN_STATUSES } },
  { name: '담당 없음', filters: { ...NO_FILTERS, assignee: 'none', status: OPEN_STATUSES } },
  { name: '전체', filters: NO_FILTERS },
] as const;

/** Where the console opens and where 문의 in the side menu goes: 내 담당. */
export const MY_ISSUES_HREF = issuesHref(ISSUE_VIEWS[0].filters);

/** One text for a set of filters: equal filters, equal text. */
export function filterKey(filters: IssueFilters): string {
  return writeIssueFilters(filters).toString();
}
