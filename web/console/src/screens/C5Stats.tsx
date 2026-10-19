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
import { formatNumber, t, tf } from '../app/locale';

type CompanyStat = Schemas['CompanyStat'];
type SlaGoalStat = Schemas['SlaGoalStat'];

/** A count, with a link to the issues it counts unless it is 0. 전체 filters by the company alone. */
function count(s: CompanyStat, n: number, status: IssueStatus[]) {
  const shown = formatNumber(n);
  if (n === 0) return shown;
  return <Link to={issuesHref({ companyCode: s.companyCode, status })}>{shown}</Link>;
}

/** A goal's cell: the median over the share kept, rounded down (249/250 is 99%). */
function goalCell(g: SlaGoalStat) {
  const counted = g.met + g.missed;
  if (counted === 0) return t('stats.none');
  const share = Math.floor((g.met * 100) / counted);
  return (
    <span className="pd-two-lines pd-stats-sla">
      <span>{g.medianMinutes === null ? t('stats.noMedian') : tf('stats.median', { length: slaLength(g.medianMinutes) })}</span>
      <span>{tf('stats.kept', { share, met: formatNumber(g.met), counted: formatNumber(counted) })}</span>
    </span>
  );
}

const PERIOD_DAYS = ['7', '30', '90'] as const;
const periods = () => PERIOD_DAYS.map((value) => ({ value, label: tf('stats.lastDays', { days: value }) }));
const DEFAULT_DAYS = '30';

// Built while drawing, so the headers follow the screen language.
const columns = (): DataTableColumn<CompanyStat>[] => [
  {
    key: 'company',
    header: t('stats.company'),
    cell: (s) => (
      <span className="pd-two-lines pd-stats-company">
        <span className="pd-key">{s.companyCode}</span>
        <span className="pd-inline">
          <Link to={issuesHref({ companyCode: s.companyCode })}>{s.companyName}</Link>
          {s.companyStatus === 'ARCHIVED' && <Badge tone="neutral">{t('stats.archived')}</Badge>}
        </span>
      </span>
    ),
  },
  { key: 'total', header: t('stats.total'), cell: (s) => count(s, s.total, []), align: 'end' },
  { key: 'open', header: t('stats.open'), cell: (s) => count(s, s.open, ['RECEIVED', 'CONFIRMED']), align: 'end' },
  { key: 'inProgress', header: ISSUE_STATUS_WORDS.IN_PROGRESS.agent, cell: (s) => count(s, s.inProgress, ['IN_PROGRESS']), align: 'end' },
  { key: 'resolved', header: ISSUE_STATUS_WORDS.RESOLVED.agent, cell: (s) => count(s, s.resolved, ['RESOLVED']), align: 'end' },
  { key: 'closed', header: ISSUE_STATUS_WORDS.CLOSED.agent, cell: (s) => count(s, s.closed, ['CLOSED']), align: 'end' },
];

// Eight columns scroll sideways on a narrow window with the company held in place.
const slaColumns = (): DataTableColumn<CompanyStat>[] => [
  ...columns().map((c) => (c.key === 'company' ? { ...c, sticky: true } : c)),
  { key: 'firstResponse', header: t('stats.firstResponse'), cell: (s) => (s.sla === null ? t('stats.none') : goalCell(s.sla.firstResponse)) },
  { key: 'resolution', header: t('stats.resolution'), cell: (s) => (s.sla === null ? t('stats.none') : goalCell(s.sla.resolution)) },
];

export function C5Stats() {
  const { me } = useSession();
  // The period lives in the address alone, 30 when it holds none or one the list does not take.
  const [searchParams, setParams] = useSearchParams();
  const asked = searchParams.get('days');
  const days = PERIOD_DAYS.find((p) => p === asked) ?? DEFAULT_DAYS;
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
      <PageTitle>{t('stats.title')}</PageTitle>
      {load.status === 'failed' && (
        <LoadFailed
          text={t('stats.readFailed')}
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
                label={t('stats.period')}
                options={periods()}
                value={days}
                onChange={(next) => {
                  choose(next);
                }}
              />
            </div>
          )}
          <div aria-busy={rereading ? 'true' : undefined}>
            <DataTable
              caption={tf('stats.caption', { count: items.length })}
              columns={slaOn ? slaColumns() : columns()}
              rows={items}
              rowKey={(s) => s.companyCode}
              status={firstLoad ? 'loading' : 'ready'}
              skeletonRows={load.slow ? 5 : 0}
              empty={<EmptyState title={me?.role === 'ADMIN' ? t('stats.noCompanies') : t('stats.noAssigned')} />}
            />
          </div>
          <p className="pd-muted">{t('stats.trashNotCounted')}</p>
          {slaOn && <p className="pd-muted">{t('stats.slaNote')}</p>}
        </>
      )}
    </div>
  );
}
