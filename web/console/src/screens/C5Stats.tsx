// C-5 통계 (7b-stats screens.md 1절). Issue counts per company the reader sees, in the order the
// API gives them. A company and each count other than 0 lead to the issue list filtered by them.
// Slice 8b: with an SLA policy on, a period to choose and two SLA columns (8b screens.md 2절, 4절).
import { Badge, DataTable, EmptyState, ISSUE_STATUS_WORDS, Select, type DataTableColumn } from '@popdesk/ui';
import { useEffect } from 'react';
import { Link, useSearchParams } from 'react-router';
import { api, type Schemas } from '../api';
import { useMainBusy } from '../app/busy';
import { useLoad } from '../app/hooks';
import { issuesHref, type IssueStatus } from '../app/issueFilters';
import { LoadFailed, PageTitle } from '../app/layout';
import { slaLength } from '../app/messages';
import { call } from '../app/result';
import { useSession } from '../app/sessionContext';

type CompanyStat = Schemas['CompanyStat'];
type SlaGoalStat = Schemas['SlaGoalStat'];

const READ_FAILED = '통계를 읽지 못했습니다. 다시 해 보세요.';

/** A count, with a link to the issues it counts unless it is 0. 전체 filters by the company alone. */
function count(s: CompanyStat, n: number, status: IssueStatus[]) {
  const shown = n.toLocaleString('ko');
  if (n === 0) return shown;
  return <Link to={issuesHref({ companyCode: s.companyCode, status })}>{shown}</Link>;
}

/** A goal's cell: the median over the share kept, rounded down (249/250 is 99%). */
function goalCell(g: SlaGoalStat) {
  const counted = g.met + g.missed;
  if (counted === 0) return '없음';
  const share = Math.floor((g.met * 100) / counted);
  return (
    <span className="pd-two-lines pd-stats-sla">
      <span>{g.medianMinutes === null ? '중앙값 없음' : `중앙값 ${slaLength(g.medianMinutes)}`}</span>
      <span>{`지킴 ${String(share)}% (${g.met.toLocaleString('ko')}/${counted.toLocaleString('ko')})`}</span>
    </span>
  );
}

const PERIODS = [
  { value: '7', label: '최근 7일' },
  { value: '30', label: '최근 30일' },
  { value: '90', label: '최근 90일' },
] as const;
const DEFAULT_DAYS = '30';

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

// Eight columns scroll sideways on a narrow window with the company held in place.
const slaColumns: DataTableColumn<CompanyStat>[] = [
  ...columns.map((c) => (c.key === 'company' ? { ...c, sticky: true } : c)),
  { key: 'firstResponse', header: '첫 응답 SLA', cell: (s) => (s.sla === null ? '없음' : goalCell(s.sla.firstResponse)) },
  { key: 'resolution', header: '해결 SLA', cell: (s) => (s.sla === null ? '없음' : goalCell(s.sla.resolution)) },
];

export function C5Stats() {
  const { me } = useSession();
  // The period lives in the address alone, 30 when it holds none or one the list does not take.
  const [searchParams, setParams] = useSearchParams();
  const asked = searchParams.get('days');
  const days = PERIODS.find((p) => p.value === asked)?.value ?? DEFAULT_DAYS;
  // The answer keeps the period it was read for: an older one on screen means reading again.
  const load = useLoad(async () => {
    const result = await call(api.GET('/api/console/stats', { params: { query: { days: Number(days) } } }));
    return result.ok ? { ...result, data: { days, items: result.data.items } } : result;
  }, days);
  const firstLoad = load.status === 'loading' && load.data === undefined;
  useMainBusy(firstLoad);
  const items = load.data?.items ?? [];
  // The server gives every item its SLA columns or none.
  const slaOn = items.some((s) => s.sla !== null);
  const rereading = load.status !== 'failed' && load.data !== undefined && load.data.days !== days;

  const choose = (next: string, options: { replace?: boolean } = {}) => {
    const params = new URLSearchParams(searchParams);
    if (next === DEFAULT_DAYS) params.delete('days');
    else params.set('days', next);
    setParams(params, options);
  };
  // A 30 or a value the list does not take leaves the address, without a new entry. With the policy
  // off the address stays as it is.
  useEffect(() => {
    if (slaOn && asked !== (days === DEFAULT_DAYS ? null : days)) choose(days, { replace: true });
  });

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
      {/* A failed reading of another period hides the counts of the one before (8b screens.md 2절). */}
      {load.status !== 'failed' && (
        <>
          {slaOn && (
            <div className="pd-filters">
              <Select
                label="SLA 기간"
                options={PERIODS}
                value={days}
                onChange={(next) => {
                  choose(next);
                }}
              />
            </div>
          )}
          <div aria-busy={rereading ? 'true' : undefined}>
            <DataTable
              caption={`고객사별 문의 수, ${String(items.length)}곳`}
              columns={slaOn ? slaColumns : columns}
              rows={items}
              rowKey={(s) => s.companyCode}
              status={firstLoad ? 'loading' : 'ready'}
              skeletonRows={load.slow ? 5 : 0}
              empty={<EmptyState title={me?.role === 'ADMIN' ? '아직 고객사가 없습니다.' : '배정된 고객사가 없습니다.'} />}
            />
          </div>
          <p className="pd-muted">휴지통의 문의는 세지 않습니다.</p>
          {slaOn && <p className="pd-muted">SLA 칸은 고른 기간에 멈춘 목표만 셉니다. 시간은 업무 시간입니다.</p>}
        </>
      )}
    </div>
  );
}
