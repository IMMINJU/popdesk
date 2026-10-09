// C-3 문의 목록 (screens.md 4절). Newest first, 20 a page. The first slice has
// no assignee, label, due date or SLA columns.
import { Badge, DataTable, EmptyState, InboxIcon, LockIcon, PriorityLabel, RelativeTime, StatusBadge, type DataTableColumn } from '@popdesk/ui';
import { useRef, type ReactNode } from 'react';
import { Link } from 'react-router';
import { api, type Schemas } from '../api';
import { useMainBusy } from '../app/busy';
import { useLoad } from '../app/hooks';
import { LoadFailed, PageTitle } from '../app/layout';
import { PageNav } from '../app/PageNav';
import { PAGE_SIZE, usePageMove, usePageParam } from '../app/paging';
import { call, type Result } from '../app/result';
import { useSession } from '../app/sessionContext';

type IssueSummary = Schemas['IssueSummary'];

interface ListData {
  issues: Schemas['PagedIssueSummary'];
  /** Agents only, when the list is empty: tells 배정 없음 from 배정 있음. */
  hasCompanies: boolean | null;
}

const columns: DataTableColumn<IssueSummary>[] = [
  { key: 'key', header: '키', cell: (i) => <span className="pd-key">{i.key}</span>, sticky: true, width: '6.5rem' },
  {
    key: 'title',
    header: '제목',
    sticky: true,
    width: '22rem',
    cell: (i) => (
      <span className="pd-title-cell">
        <Link to={`/issues/${encodeURIComponent(i.key)}`}>{i.title}</Link>
        {i.private && (
          <Badge tone="neutral" icon={<LockIcon size={12} />}>
            비공개
          </Badge>
        )}
      </span>
    ),
  },
  { key: 'company', header: '고객사', cell: (i) => i.companyName },
  { key: 'status', header: '상태', cell: (i) => <StatusBadge kind="issue" status={i.status} audience="agent" /> },
  { key: 'priority', header: '우선순위', cell: (i) => <PriorityLabel priority={i.priority} /> },
  { key: 'reporter', header: '올린 사람', cell: (i) => i.reporter.name },
  { key: 'created', header: '올린 시각', cell: (i) => <RelativeTime dateTime={i.createdAt} focusable={false} />, align: 'end' },
];

export function C3IssueList() {
  const { me } = useSession();
  const page = usePageParam();
  const heading = useRef<HTMLHeadingElement>(null);

  const load = useLoad(async (): Promise<Result<ListData>> => {
    const issues = await call(api.GET('/api/console/issues', { params: { query: { page, size: PAGE_SIZE } } }));
    if (!issues.ok) return issues;
    let hasCompanies: boolean | null = null;
    if (me?.role === 'AGENT' && issues.data.totalElements === 0) {
      const companies = await call(api.GET('/api/console/companies', { params: { query: { size: 1 } } }));
      if (companies.ok) hasCompanies = companies.data.totalElements > 0;
    }
    return { ok: true, data: { issues: issues.data, hasCompanies }, response: issues.response };
  }, String(page));

  const firstLoad = load.status === 'loading' && load.data === undefined;
  useMainBusy(firstLoad);
  const move = usePageMove(load.data?.issues, heading);

  const data = load.data?.issues;
  const outOfRange = data !== undefined && data.items.length === 0 && page > 1;

  let empty: ReactNode;
  if (outOfRange) {
    empty = <EmptyState title="이 쪽에는 문의가 없습니다" action={<Link to="/issues">1쪽으로</Link>} />;
  } else if (me?.role === 'ADMIN') {
    empty = (
      <EmptyState
        icon={<InboxIcon size={20} />}
        title="아직 문의가 없습니다. 고객사를 만들고 고객을 초대하면 고객이 포털에서 문의를 올립니다."
        action={<Link to="/companies">고객사 목록</Link>}
      />
    );
  } else if (load.data?.hasCompanies === false) {
    empty = <EmptyState icon={<InboxIcon size={20} />} title="배정된 고객사가 없습니다. 관리자에게 배정을 요청하세요." />;
  } else {
    empty = <EmptyState icon={<InboxIcon size={20} />} title="배정된 고객사에 문의가 없습니다." />;
  }

  return (
    <div className="pd-screen">
      <div className="pd-screen-head">
        <PageTitle titleRef={heading}>문의 목록</PageTitle>
      </div>
      {load.status === 'failed' && (
        <LoadFailed
          onRetry={() => {
            void load.reload();
          }}
        />
      )}
      {load.status !== 'failed' || data ? (
        <DataTable
          caption={`문의 목록, ${String(data?.totalElements ?? 0)}건`}
          columns={columns}
          rows={data?.items ?? []}
          rowKey={(i) => i.key}
          status={firstLoad ? 'loading' : 'ready'}
          skeletonRows={load.slow ? 10 : 0}
          empty={empty}
          density="compact"
        />
      ) : null}
      {data && <PageNav path="/issues" page={data.page} pageCount={data.totalPages} onPageChange={move.onPageChange} />}
      <p role="status" className="pd-sr-only">
        {move.announcement}
      </p>
    </div>
  );
}
