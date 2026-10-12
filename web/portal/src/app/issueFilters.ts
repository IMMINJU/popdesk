// The filters of the issue list (3-issues screens.md P-3 필터 줄). They live in the
// address under the names the API takes (`q`, `status`, `companyCode`, `mine`), so a
// reload or 뒤로 가기 shows the same list.
import type { Schemas } from '../api';

export type IssueStatus = Schemas['Issue']['status'];

export const ISSUE_STATUSES: readonly IssueStatus[] = ['RECEIVED', 'CONFIRMED', 'IN_PROGRESS', 'RESOLVED', 'CLOSED'];

export interface IssueFilters {
  /** Empty: no search. */
  q: string;
  /** In the order of `ISSUE_STATUSES`. Empty: every status. */
  status: IssueStatus[];
  /** Empty: every company the reader belongs to. */
  companyCode: string;
  /** Only the issues the reader filed. */
  mine: boolean;
}

export const NO_FILTERS: IssueFilters = { q: '', status: [], companyCode: '', mine: false };

/** The filters of an address. A value the list cannot take (an unknown status, a `mine` that is not `true`) is left out alone. */
export function readIssueFilters(params: URLSearchParams): IssueFilters {
  const given = params.getAll('status');
  return {
    q: (params.get('q') ?? '').trim().slice(0, 100),
    status: ISSUE_STATUSES.filter((s) => given.includes(s)),
    companyCode: (params.get('companyCode') ?? '').trim(),
    mine: params.get('mine') === 'true',
  };
}

export function writeIssueFilters(filters: IssueFilters): URLSearchParams {
  const params = new URLSearchParams();
  if (filters.q !== '') params.set('q', filters.q);
  if (filters.companyCode !== '') params.set('companyCode', filters.companyCode);
  for (const status of filters.status) params.append('status', status);
  if (filters.mine) params.set('mine', 'true');
  return params;
}

/** The address of the issue list with these filters, on the first page. */
export function issuesHref(filters: IssueFilters = NO_FILTERS): string {
  const query = writeIssueFilters(filters).toString();
  return query === '' ? '/issues' : `/issues?${query}`;
}

/** One text for a set of filters: equal filters, equal text. */
export function filterKey(filters: IssueFilters): string {
  return writeIssueFilters(filters).toString();
}
