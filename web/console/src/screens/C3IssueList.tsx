// C-3 문의 목록 (screens.md 4절, 3-issues screens.md 2절). Newest first, 20 a page.
// 보기 고르기 and the filter row keep their state in the address (app/issueFilters.ts).
// No label, due date or SLA columns yet.
import {
  Alert,
  Badge,
  Button,
  CheckboxGroup,
  CloseIcon,
  DataTable,
  EmptyState,
  IconButton,
  InboxIcon,
  ISSUE_STATUS_WORDS,
  LockIcon,
  PriorityLabel,
  RelativeTime,
  SearchField,
  SearchOffIcon,
  Select,
  StatusBadge,
  type DataTableColumn,
  type SelectOption,
} from '@popdesk/ui';
import { useEffect, useRef, useState, type ReactNode } from 'react';
import { Link, useSearchParams } from 'react-router';
import { api, type Schemas } from '../api';
import { RateLimitBand } from '../app/bands';
import { useMainBusy } from '../app/busy';
import { AssigneeName } from '../app/AssigneeName';
import { useCooldown, useLoad } from '../app/hooks';
import {
  filterKey,
  isPerson,
  ISSUE_STATUSES,
  ISSUE_VIEWS,
  issuesHref,
  readIssueFilters,
  writeIssueFilters,
  type IssueFilters,
} from '../app/issueFilters';
import { LoadFailed, PageTitle } from '../app/layout';
import { t } from '../app/locale';
import { rateLimitedText } from '../app/messages';
import { useLiveNavigate } from '../app/navigate';
import { PageNav } from '../app/PageNav';
import { PAGE_SIZE, usePageMove, usePageParam } from '../app/paging';
import { call } from '../app/result';
import { useSession } from '../app/sessionContext';

type IssueSummary = Schemas['IssueSummary'];
type Person = Schemas['Person'];

const STATUS_OPTIONS = ISSUE_STATUSES.map((value) => ({ value, label: ISSUE_STATUS_WORDS[value].agent }));
const ASSIGNEE_OPTIONS: SelectOption[] = [
  { value: 'me', label: '나' },
  { value: 'none', label: '담당 없음' },
  { value: 'unavailable', label: '담당자가 볼 수 없음' },
];

const columns: DataTableColumn<IssueSummary>[] = [
  { key: 'key', header: '키', cell: (i) => <span className="pd-key">{i.key}</span>, sticky: true, width: '6.5rem' },
  {
    key: 'title',
    header: '제목',
    sticky: true,
    width: '14rem',
    cell: (i) => (
      <span className="pd-title-cell">
        <Link to={`/issues/${encodeURIComponent(i.key)}`}>{i.title}</Link>
        {i.private && (
          <Badge tone="neutral" icon={<LockIcon size={12} />}>
            비공개
          </Badge>
        )}
        {i.filedOnBehalf && <Badge tone="neutral">대리 등록</Badge>}
      </span>
    ),
  },
  { key: 'company', header: '고객사', cell: (i) => i.companyName },
  { key: 'status', header: '상태', cell: (i) => <StatusBadge kind="issue" status={i.status} audience="agent" /> },
  { key: 'priority', header: '우선순위', cell: (i) => <PriorityLabel priority={i.priority} /> },
  { key: 'reporter', header: '올린 사람', cell: (i) => i.reporter.name },
  { key: 'assignee', header: '담당자', cell: (i) => <AssigneeName person={i.assignee} unavailable={i.assigneeUnavailable} /> },
  { key: 'created', header: '올린 시각', cell: (i) => <RelativeTime dateTime={i.createdAt} focusable={false} />, align: 'end' },
];

/** Who can be the assignee in one company, for the 담당자 filter. An answer for a company left behind is dropped (S3-AC-87). */
function useCandidates(companyCode: string): Person[] {
  const [answer, setAnswer] = useState<{ code: string; items: Person[] } | null>(null);
  useEffect(() => {
    if (companyCode === '') return undefined;
    let current = true;
    void call(api.GET('/api/console/companies/{code}/assignees', { params: { path: { code: companyCode } } })).then((result) => {
      // A failed answer leaves the filter without people; the list itself reports what is wrong.
      if (current && result.ok) setAnswer({ code: companyCode, items: result.data.items });
    });
    return () => {
      current = false;
    };
  }, [companyCode]);
  return answer?.code === companyCode ? answer.items : [];
}

export function C3IssueList() {
  const { me } = useSession();
  const page = usePageParam();
  const [params, setParams] = useSearchParams();
  const navigate = useLiveNavigate();
  const heading = useRef<HTMLHeadingElement>(null);
  const cooldown = useCooldown();

  const filters = readIssueFilters(params);
  const key = filterKey(filters);
  const filtered = key !== '';
  const view = ISSUE_VIEWS.find((v) => filterKey(v.filters) === key)?.name ?? null;

  const load = useLoad(
    () =>
      call(
        api.GET('/api/console/issues', {
          params: {
            query: {
              page,
              size: PAGE_SIZE,
              ...(filters.q === '' ? {} : { q: filters.q }),
              ...(filters.status.length === 0 ? {} : { status: filters.status }),
              ...(filters.companyCode === '' ? {} : { companyCode: filters.companyCode }),
              ...(filters.assignee === '' ? {} : { assignee: filters.assignee }),
              ...(filters.reporter === null ? {} : { reporter: filters.reporter }),
            },
          },
        }),
      ),
    `${String(page)}\n${key}`,
  );
  // The companies the reader sees: the choices of the 고객사 filter, and for an agent whether any is assigned.
  const companies = useLoad(() => call(api.GET('/api/console/companies', { params: { query: { size: 100 } } })), 'companies');
  const candidates = useCandidates(filters.companyCode);

  // 429 (the search has its own limit): the band counts down, then the same list is asked again.
  const limited = load.failure?.kind === 'rateLimited' ? load.failure.seconds : null;
  const { start, ended, clearEnded } = cooldown;
  const reload = load.reload;
  useEffect(() => {
    if (limited !== null) start(limited);
  }, [limited, load.failure, start]);
  useEffect(() => {
    if (!ended) return;
    clearEnded();
    void reload();
  }, [ended, clearEnded, reload]);

  const data = load.data;
  const rows = data?.items ?? [];
  const hasCompanies = companies.data ? companies.data.totalElements > 0 : null;
  // An agent's empty list reads differently with no company assigned: wait for the companies before saying which.
  const waitingForCompanies = !filtered && me?.role === 'AGENT' && rows.length === 0 && companies.status === 'loading';
  const firstLoad = (load.status === 'loading' && data === undefined) || waitingForCompanies;
  useMainBusy(firstLoad);
  const move = usePageMove(data, heading);
  const outOfRange = data !== undefined && rows.length === 0 && page > 1;

  const change = (next: IssueFilters, options: { replace?: boolean } = {}) => {
    // Without `page`: a new filter starts on the first page.
    setParams(writeIssueFilters(next), options);
  };

  const companyOptions: SelectOption[] = (companies.data?.items ?? []).map((c) => ({ value: c.code, label: c.name }));
  if (filters.companyCode !== '' && !companyOptions.some((o) => o.value === filters.companyCode)) {
    // A company the choices do not hold (an old link, more than a page of companies): shown as it was asked.
    const row = rows.find((i) => i.companyCode === filters.companyCode);
    companyOptions.push({ value: filters.companyCode, label: row?.companyName ?? filters.companyCode });
  }

  const people = candidates.filter((p) => p.accountId !== me?.id);
  const assigneeOptions: SelectOption[] = [...ASSIGNEE_OPTIONS, ...people.map((p) => ({ value: String(p.accountId), label: p.name }))];
  if (isPerson(filters.assignee) && !assigneeOptions.some((o) => o.value === filters.assignee)) {
    // A person who is not a choice now: no company chosen, or no longer a candidate. The name comes with the rows.
    const id = Number(filters.assignee);
    const name = id === me?.id ? me.name : rows.find((i) => i.assignee?.accountId === id)?.assignee?.name;
    assigneeOptions.push({ value: filters.assignee, label: name ?? '다른 사람' });
  }
  const reporterName = filters.reporter === null ? null : (rows.find((i) => i.reporter.accountId === filters.reporter)?.reporter.name ?? '한 명');

  let empty: ReactNode;
  if (outOfRange) {
    empty = <EmptyState title="이 쪽에는 문의가 없습니다" action={<Link to={issuesHref(filters)}>1쪽으로</Link>} />;
  } else if (view === '내 담당') {
    empty = (
      <EmptyState
        icon={<InboxIcon size={20} />}
        title="담당인 열린 문의가 없습니다"
        action={<Link to={issuesHref(ISSUE_VIEWS[1].filters)}>담당 없음 보기</Link>}
      />
    );
  } else if (filtered) {
    empty = <EmptyState icon={<SearchOffIcon size={20} />} title={t('issueSearch.none')} action={<Link to="/issues">{t('issueSearch.clear')}</Link>} />;
  } else if (me?.role === 'ADMIN') {
    empty = (
      <EmptyState
        icon={<InboxIcon size={20} />}
        title="아직 문의가 없습니다. 고객사를 만들고 고객을 초대하면 고객이 포털에서 문의를 올립니다."
        action={<Link to="/companies">고객사 목록</Link>}
      />
    );
  } else if (hasCompanies === false) {
    empty = <EmptyState icon={<InboxIcon size={20} />} title="배정된 고객사가 없습니다. 관리자에게 배정을 요청하세요." />;
  } else {
    empty = <EmptyState icon={<InboxIcon size={20} />} title="배정된 고객사에 문의가 없습니다." />;
  }

  return (
    <div className="pd-screen pd-issues">
      <div className="pd-screen-head">
        <PageTitle titleRef={heading}>문의 목록</PageTitle>
        <Button
          variant="secondary"
          onClick={() => {
            // C-19 opens with the company the list is filtered by.
            void navigate(filters.companyCode === '' ? '/issues/new' : `/issues/new?${new URLSearchParams({ companyCode: filters.companyCode }).toString()}`);
          }}
        >
          대리 등록
        </Button>
      </div>
      <nav className="pd-views" aria-label="보기">
        {ISSUE_VIEWS.map((v) => (
          <Link key={v.name} to={issuesHref(v.filters)} aria-current={v.name === view ? 'page' : undefined}>
            {v.name}
          </Link>
        ))}
      </nav>
      <div className="pd-filters pd-issue-filters">
        <SearchField
          label={t('issueSearch.label')}
          hint={t('issueSearch.hint')}
          value={filters.q}
          onSearch={(value) => {
            const q = value.trim();
            // Typing replaces the entry: 뒤로 가기 does not walk through every pause.
            if (q !== filters.q) change({ ...filters, q }, { replace: true });
          }}
        />
        <CheckboxGroup
          legend="상태"
          options={STATUS_OPTIONS}
          value={filters.status}
          onChange={(status) => {
            change({ ...filters, status: ISSUE_STATUSES.filter((s) => status.includes(s)) });
          }}
        />
        <Select
          label="고객사"
          placeholder="전체"
          options={companyOptions}
          value={filters.companyCode}
          onChange={(companyCode) => {
            // A person is a candidate of one company; 나, 담당 없음 and 담당자가 볼 수 없음 stay.
            change({ ...filters, companyCode, assignee: isPerson(filters.assignee) ? '' : filters.assignee });
          }}
        />
        <Select
          label="담당자"
          placeholder="전체"
          options={assigneeOptions}
          value={filters.assignee}
          onChange={(assignee) => {
            change({ ...filters, assignee });
          }}
        />
        {reporterName !== null && (
          <span className="pd-filter-chip">
            <span>올린 사람: {reporterName}</span>
            <IconButton
              label="올린 사람 필터 지우기"
              icon={<CloseIcon size={14} />}
              size="sm"
              onClick={() => {
                change({ ...filters, reporter: null });
              }}
            />
          </span>
        )}
        {filtered && (
          <Link className="pd-filters-clear" to="/issues">
            {t('issueSearch.clear')}
          </Link>
        )}
      </div>
      {limited !== null &&
        (cooldown.active ? (
          <RateLimitBand active left={cooldown.left} initial={cooldown.initial} ended={false} />
        ) : (
          <Alert tone="error">{rateLimitedText(limited)}</Alert>
        ))}
      {load.status === 'failed' && limited === null && (
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
          rows={rows}
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
