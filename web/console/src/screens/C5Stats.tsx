// C-5 통계 (7b-stats screens.md 1절). Issue counts per company the reader sees, in the order the
// API gives them. A company and each count other than 0 lead to the issue list filtered by them.
import { Badge, DataTable, EmptyState, ISSUE_STATUS_WORDS, type DataTableColumn } from '@popdesk/ui';
import { Link } from 'react-router';
import { api, type Schemas } from '../api';
import { useMainBusy } from '../app/busy';
import { useLoad } from '../app/hooks';
import { issuesHref, type IssueStatus } from '../app/issueFilters';
import { LoadFailed, PageTitle } from '../app/layout';
import { call } from '../app/result';
import { useSession } from '../app/sessionContext';

type CompanyStat = Schemas['CompanyStat'];

const READ_FAILED = '통계를 읽지 못했습니다. 다시 해 보세요.';

/** A count, with a link to the issues it counts unless it is 0. 전체 filters by the company alone. */
function count(s: CompanyStat, n: number, status: IssueStatus[]) {
  const shown = n.toLocaleString('ko');
  if (n === 0) return shown;
  return <Link to={issuesHref({ companyCode: s.companyCode, status })}>{shown}</Link>;
}

const columns: DataTableColumn<CompanyStat>[] = [
  {
    key: 'company',
    header: '고객사',
    cell: (s) => (
      <span className="pd-two-lines pd-stats-company">
        <span className="pd-key">{s.companyCode}</span>
        <span className="pd-inline">
          <Link to={issuesHref({ companyCode: s.companyCode })}>{s.companyName}</Link>
          {s.companyStatus === 'ARCHIVED' && <Badge tone="neutral">보관</Badge>}
        </span>
      </span>
    ),
  },
  { key: 'total', header: '전체', cell: (s) => count(s, s.total, []), align: 'end' },
  { key: 'open', header: '열림', cell: (s) => count(s, s.open, ['RECEIVED', 'CONFIRMED']), align: 'end' },
  { key: 'inProgress', header: ISSUE_STATUS_WORDS.IN_PROGRESS.agent, cell: (s) => count(s, s.inProgress, ['IN_PROGRESS']), align: 'end' },
  { key: 'resolved', header: ISSUE_STATUS_WORDS.RESOLVED.agent, cell: (s) => count(s, s.resolved, ['RESOLVED']), align: 'end' },
  { key: 'closed', header: ISSUE_STATUS_WORDS.CLOSED.agent, cell: (s) => count(s, s.closed, ['CLOSED']), align: 'end' },
];

export function C5Stats() {
  const { me } = useSession();
  const load = useLoad(() => call(api.GET('/api/console/stats')), 'stats');
  const firstLoad = load.status === 'loading' && load.data === undefined;
  useMainBusy(firstLoad);
  const items = load.data?.items ?? [];

  return (
    <div className="pd-screen pd-stats">
      <PageTitle>통계</PageTitle>
      {load.status === 'failed' && (
        <LoadFailed
          text={READ_FAILED}
          onRetry={() => {
            void load.reload();
          }}
        />
      )}
      {(load.status !== 'failed' || load.data) && (
        <>
          <DataTable
            caption={`고객사별 문의 수, ${String(items.length)}곳`}
            columns={columns}
            rows={items}
            rowKey={(s) => s.companyCode}
            status={firstLoad ? 'loading' : 'ready'}
            skeletonRows={load.slow ? 5 : 0}
            empty={<EmptyState title={me?.role === 'ADMIN' ? '아직 고객사가 없습니다.' : '배정된 고객사가 없습니다.'} />}
          />
          <p className="pd-muted">휴지통의 문의는 세지 않습니다.</p>
        </>
      )}
    </div>
  );
}
