// C-3 문의 목록 (screens.md 4절, 3-issues screens.md 2절). Newest first, 20 a page.
// 보기 고르기 and the filter row keep their state in the address (app/issueFilters.ts).
// Slice 6a (6a-labels screens.md 2절) adds the 라벨 and 마감일 columns, the 라벨 filter once a company
// is chosen, and 정렬.
// Slice 6b (6b-tasks screens.md 4절) adds "작업 n" beside the title of an issue with open tasks.
// Slice 8a (8a-sla screens.md 2절) adds the SLA column at the end, the SLA filter, and "SLA 기한순".
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
  LabelChips,
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
import { shortDate } from '../app/dueDates';
import { byName, labelItem, moreLabelsText } from '../app/labelItems';
import { useCooldown, useLoad } from '../app/hooks';
import {
  filterKey,
  isPerson,
  ISSUE_STATUSES,
  ISSUE_VIEWS,
  issuesHref,
  readIssueFilters,
  readIssueSort,
  writeIssueFilters,
  type IssueFilters,
  type IssueSort,
} from '../app/issueFilters';
import { LoadFailed, PageTitle } from '../app/layout';
import { formatNumber, t, texts, tf } from '../app/locale';
import { rateLimitedText, slaWords } from '../app/messages';
import { useLiveNavigate } from '../app/navigate';
import { PageNav } from '../app/PageNav';
import { PAGE_SIZE, usePageMove, usePageParam } from '../app/paging';
import { call } from '../app/result';
import { useSession } from '../app/sessionContext';
import { SlaBadge } from '../app/SlaBadge';
import { goalText, worseGoal } from '../app/slaGoals';

type IssueSummary = Schemas['IssueSummary'];
type Person = Schemas['Person'];

// Read while drawing, so the words follow the screen language.
const WORDS = texts({
  me: 'issueList.assigneeMe',
  unassigned: 'issueList.unassigned',
  unavailable: 'issueList.assigneeUnavailable',
  sortCreated: 'issueList.sortCreated',
  sortDue: 'issueList.sortDue',
  sortSla: 'issueList.sortSla',
  slaWarning: 'issueList.slaWarning',
  slaBreached: 'issueList.slaBreached',
  key: 'issueList.key',
  title: 'issueList.issueTitle',
  company: 'issueList.company',
  status: 'issueList.status',
  priority: 'issueList.priority',
  reporter: 'issueList.reporter',
  assignee: 'issueList.assignee',
  labels: 'issueList.labels',
  due: 'issueList.due',
  created: 'issueList.created',
  private: 'issueList.private',
  filedOnBehalf: 'issueList.filedOnBehalf',
  overdue: 'issueList.overdue',
  all: 'issueList.viewAll',
});

const statusOptions = () => ISSUE_STATUSES.map((value) => ({ value, label: ISSUE_STATUS_WORDS[value].agent }));
const fixedAssigneeOptions = (): SelectOption[] => [
  { value: 'me', label: WORDS.me },
  { value: 'none', label: WORDS.unassigned },
  { value: 'unavailable', label: WORDS.unavailable },
];

const sortOptions = (): SelectOption[] => [
  { value: 'createdAt', label: WORDS.sortCreated },
  { value: 'dueDate', label: WORDS.sortDue },
  { value: 'slaDue', label: WORDS.sortSla },
];

const SORTS: readonly IssueSort[] = ['createdAt', 'dueDate', 'slaDue'];

const slaOptions = (): SelectOption[] => [
  { value: 'warning', label: WORDS.slaWarning },
  { value: 'breached', label: WORDS.slaBreached },
];

/** The worse running goal: its badge, then the time left; the goal's name is read, not shown. */
function SlaCell(props: { issue: IssueSummary }) {
  const worse = worseGoal(props.issue.sla);
  if (worse === null || worse.goal.remainingMinutes === null) return null;
  return (
    <span className="pd-inline pd-sla-cell">
      <SlaBadge state={worse.goal.state} />
      <span>
        <span className="pd-sr-only">{goalText(worse.name)} </span>
        {slaWords(worse.goal.remainingMinutes)}
      </span>
    </span>
  );
}

const columns = (): DataTableColumn<IssueSummary>[] => [
  { key: 'key', header: WORDS.key, cell: (i) => <span className="pd-key">{i.key}</span>, sticky: true, width: '6.5rem' },
  {
    key: 'title',
    header: WORDS.title,
    sticky: true,
    width: '14rem',
    cell: (i) => (
      <span className="pd-title-cell">
        <Link to={`/issues/${encodeURIComponent(i.key)}`}>{i.title}</Link>
        {i.private && (
          <Badge tone="neutral" icon={<LockIcon size={12} />}>
            {WORDS.private}
          </Badge>
        )}
        {i.filedOnBehalf && <Badge tone="neutral">{WORDS.filedOnBehalf}</Badge>}
        {i.openTaskCount > 0 && <Badge tone="neutral">{tf('issueList.openTasks', { count: i.openTaskCount })}</Badge>}
      </span>
    ),
  },
  { key: 'company', header: WORDS.company, cell: (i) => i.companyName },
  { key: 'status', header: WORDS.status, cell: (i) => <StatusBadge kind="issue" status={i.status} audience="agent" /> },
  { key: 'priority', header: WORDS.priority, cell: (i) => <PriorityLabel priority={i.priority} /> },
  { key: 'reporter', header: WORDS.reporter, cell: (i) => i.reporter.name },
  { key: 'assignee', header: WORDS.assignee, cell: (i) => <AssigneeName person={i.assignee} unavailable={i.assigneeUnavailable} /> },
  { key: 'labels', header: WORDS.labels, cell: (i) => <LabelChips labels={byName(i.labels).map(labelItem)} moreText={moreLabelsText} /> },
  {
    key: 'due',
    header: WORDS.due,
    cell: (i) =>
      i.dueDate === null ? null : (
        <span className="pd-inline pd-due-cell">
          <time dateTime={i.dueDate}>{shortDate(i.dueDate)}</time>
          {i.overdue && <Badge tone="warning">{WORDS.overdue}</Badge>}
        </span>
      ),
  },
  { key: 'created', header: WORDS.created, cell: (i) => <RelativeTime dateTime={i.createdAt} focusable={false} />, align: 'end' },
  { key: 'sla', header: 'SLA', cell: (i) => <SlaCell issue={i} /> },
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

/** The labels of one company, for the 라벨 filter. An answer for a company left behind is dropped (S6A-AC-86). */
function useCompanyLabels(companyCode: string): SelectOption[] {
  const [answer, setAnswer] = useState<{ code: string; items: SelectOption[] } | null>(null);
  useEffect(() => {
    if (companyCode === '') return undefined;
    let current = true;
    void call(api.GET('/api/console/companies/{code}/labels', { params: { path: { code: companyCode } } })).then((result) => {
      if (current && result.ok) setAnswer({ code: companyCode, items: byName(result.data.items).map((l) => ({ value: String(l.id), label: l.name })) });
    });
    return () => {
      current = false;
    };
  }, [companyCode]);
  return companyCode !== '' && answer?.code === companyCode ? answer.items : [];
}

export function C3IssueList() {
  const { me } = useSession();
  const page = usePageParam();
  const [params, setParams] = useSearchParams();
  const navigate = useLiveNavigate();
  const heading = useRef<HTMLHeadingElement>(null);
  const cooldown = useCooldown();

  const filters = readIssueFilters(params);
  const sort = readIssueSort(params);
  const key = filterKey(filters);
  const filtered = key !== '';
  const view = ISSUE_VIEWS.find((v) => filterKey(v.filters) === key)?.id ?? null;

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
              ...(filters.label.length === 0 ? {} : { label: filters.label }),
              ...(filters.sla === '' ? {} : { sla: filters.sla }),
              ...(sort === 'createdAt' ? {} : { sort }),
            },
          },
        }),
      ),
    `${String(page)}\n${key}\n${sort}`,
  );
  // The companies the reader sees: the choices of the 고객사 filter, and for an agent whether any is assigned.
  const companies = useLoad(() => call(api.GET('/api/console/companies', { params: { query: { size: 100 } } })), 'companies');
  const candidates = useCandidates(filters.companyCode);
  const labelOptions = useCompanyLabels(filters.companyCode);

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
    setParams(writeIssueFilters(next, sort), options);
  };

  const companyOptions: SelectOption[] = (companies.data?.items ?? []).map((c) => ({ value: c.code, label: c.name }));
  if (filters.companyCode !== '' && !companyOptions.some((o) => o.value === filters.companyCode)) {
    // A company the choices do not hold (an old link, more than a page of companies): shown as it was asked.
    const row = rows.find((i) => i.companyCode === filters.companyCode);
    companyOptions.push({ value: filters.companyCode, label: row?.companyName ?? filters.companyCode });
  }

  const people = candidates.filter((p) => p.accountId !== me?.id);
  const assigneeOptions: SelectOption[] = [...fixedAssigneeOptions(), ...people.map((p) => ({ value: String(p.accountId), label: p.name }))];
  if (isPerson(filters.assignee) && !assigneeOptions.some((o) => o.value === filters.assignee)) {
    // A person who is not a choice now: no company chosen, or no longer a candidate. The name comes with the rows.
    const id = Number(filters.assignee);
    const name = id === me?.id ? me.name : rows.find((i) => i.assignee?.accountId === id)?.assignee?.name;
    assigneeOptions.push({ value: filters.assignee, label: name ?? t('issueList.otherPerson') });
  }
  const reporterName = filters.reporter === null ? null : (rows.find((i) => i.reporter.accountId === filters.reporter)?.reporter.name ?? t('issueList.onePerson'));

  let empty: ReactNode;
  if (outOfRange) {
    empty = <EmptyState title={t('issueList.pageEmpty')} action={<Link to={issuesHref(filters, sort)}>{t('issueList.firstPage')}</Link>} />;
  } else if (view === 'mine') {
    empty = (
      <EmptyState
        icon={<InboxIcon size={20} />}
        title={t('issueList.mineEmpty')}
        action={<Link to={issuesHref(ISSUE_VIEWS[1].filters)}>{t('issueList.showUnassigned')}</Link>}
      />
    );
  } else if (filtered) {
    empty = <EmptyState icon={<SearchOffIcon size={20} />} title={t('issueSearch.none')} action={<Link to={issuesHref({}, sort)}>{t('issueSearch.clear')}</Link>} />;
  } else if (me?.role === 'ADMIN') {
    empty = (
      <EmptyState
        icon={<InboxIcon size={20} />}
        title={t('issueList.adminEmpty')}
        action={<Link to="/companies">{t('issueList.toCompanies')}</Link>}
      />
    );
  } else if (hasCompanies === false) {
    empty = <EmptyState icon={<InboxIcon size={20} />} title={t('issueOnBehalf.noCompaniesAgent')} />;
  } else {
    empty = <EmptyState icon={<InboxIcon size={20} />} title={t('issueList.assignedEmpty')} />;
  }

  return (
    <div className="pd-screen pd-issues">
      <div className="pd-screen-head">
        <PageTitle titleRef={heading}>{t('issueList.title')}</PageTitle>
        <Button
          variant="secondary"
          onClick={() => {
            // C-19 opens with the company the list is filtered by.
            void navigate(filters.companyCode === '' ? '/issues/new' : `/issues/new?${new URLSearchParams({ companyCode: filters.companyCode }).toString()}`);
          }}
        >
          {t('issueOnBehalf.title')}
        </Button>
      </div>
      <nav className="pd-views" aria-label={t('issueList.views')}>
        {ISSUE_VIEWS.map((v) => (
          <Link key={v.id} to={issuesHref(v.filters)} aria-current={v.id === view ? 'page' : undefined}>
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
          legend={WORDS.status}
          options={statusOptions()}
          value={filters.status}
          onChange={(status) => {
            change({ ...filters, status: ISSUE_STATUSES.filter((s) => status.includes(s)) });
          }}
        />
        <Select
          label={WORDS.company}
          placeholder={WORDS.all}
          options={companyOptions}
          value={filters.companyCode}
          onChange={(companyCode) => {
            // A person is a candidate of one company; 나, 담당 없음 and 담당자가 볼 수 없음 stay.
            // Labels belong to one company: another company, or none, clears them.
            change({ ...filters, companyCode, assignee: isPerson(filters.assignee) ? '' : filters.assignee, label: [] });
          }}
        />
        {labelOptions.length > 0 && (
          <CheckboxGroup
            legend={WORDS.labels}
            options={labelOptions}
            value={filters.label.map(String)}
            onChange={(label) => {
              change({ ...filters, label: label.map(Number) });
            }}
          />
        )}
        <Select
          label={WORDS.assignee}
          placeholder={WORDS.all}
          options={assigneeOptions}
          value={filters.assignee}
          onChange={(assignee) => {
            change({ ...filters, assignee });
          }}
        />
        <Select
          label="SLA"
          placeholder={WORDS.all}
          options={slaOptions()}
          value={filters.sla}
          onChange={(sla) => {
            change({ ...filters, sla: sla === 'warning' || sla === 'breached' ? sla : '' });
          }}
        />
        {reporterName !== null && (
          <span className="pd-filter-chip">
            <span>{tf('issueList.reporterChip', { name: reporterName })}</span>
            <IconButton
              label={t('issueList.clearReporter')}
              icon={<CloseIcon size={14} />}
              size="sm"
              onClick={() => {
                change({ ...filters, reporter: null });
              }}
            />
          </span>
        )}
        <Select
          label={t('issueList.sort')}
          options={sortOptions()}
          value={sort}
          onChange={(next) => {
            setParams(writeIssueFilters(filters, SORTS.find((o) => o === next)));
          }}
        />
        {filtered && (
          <Link className="pd-filters-clear" to={issuesHref({}, sort)}>
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
          caption={tf('issueList.caption', { count: formatNumber(data?.totalElements ?? 0) })}
          columns={columns()}
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
