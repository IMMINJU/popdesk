// P-3 문의 목록 (screens.md 3절). Newest first, 20 a page. Each row is one link;
// at PC width it reads as a table row, at mobile width as a card (portal.css).
// The filter row keeps its state in the address (app/issueFilters.ts); at mobile
// width only the search shows and 필터 opens the rest (3-issues screens.md 2절).
import {
  Alert,
  Badge,
  Checkbox,
  CheckboxGroup,
  ChevronDownIcon,
  EmptyState,
  InboxIcon,
  ISSUE_STATUS_WORDS,
  LockIcon,
  PriorityLabel,
  RelativeTime,
  SearchField,
  SearchOffIcon,
  Select,
  Skeleton,
  StatusBadge,
  type SelectOption,
} from '@popdesk/ui';
import { useEffect, useId, useRef, useState, type ReactNode } from 'react';
import { Link, useSearchParams } from 'react-router';
import { api, type Schemas } from '../api';
import { RateLimitBand } from '../app/bands';
import { useMainBusy } from '../app/busy';
import { useCooldown, useLoad } from '../app/hooks';
import { filterKey, ISSUE_STATUSES, issuesHref, readIssueFilters, writeIssueFilters, type IssueFilters } from '../app/issueFilters';
import { LoadFailed, PageTitle } from '../app/layout';
import { t } from '../app/locale';
import { personLabel, rateLimitedText, TEXT } from '../app/messages';
import { useNarrow } from '../app/narrow';
import { PageNav } from '../app/PageNav';
import { PAGE_SIZE, usePageMove, usePageParam } from '../app/paging';
import { call, type Result } from '../app/result';
import { useSession } from '../app/sessionContext';

type IssueSummary = Schemas['IssueSummary'];

interface ListData {
  issues: Schemas['PagedIssueSummary'];
  /** No membership: 문의 올리기 is hidden (PROJ-4). */
  member: boolean;
}

const STATUS_OPTIONS = ISSUE_STATUSES.map((value) => ({ value, label: ISSUE_STATUS_WORDS[value].customer }));

function IssueRow(props: { issue: IssueSummary; meId: number | undefined }) {
  const { issue } = props;
  const mine = issue.reporter.kind === 'CUSTOMER' && issue.reporter.accountId === props.meId;
  const reporter = personLabel(issue.reporter, props.meId);
  return (
    <li>
      <Link className="pd-issue-row" to={`/issues/${encodeURIComponent(issue.key)}`}>
        <span className="pd-issue-row-status">
          <StatusBadge kind="issue" status={issue.status} audience="customer" />
          {/* Resolved and mine: the support team waits for my word (S3-AC-83). */}
          {mine && issue.status === 'RESOLVED' && <Badge tone="accent">{t('portalIssues.needsCheck')}</Badge>}
        </span>
        <span className="pd-issue-row-key pd-key">{issue.key}</span>
        <span className="pd-issue-row-title">
          <span className="pd-issue-row-title-text">{issue.title}</span>
          {issue.private && (
            <span className="pd-issue-row-private">
              <LockIcon size={14} />
              <span className="pd-sr-only">{TEXT.private}</span>
            </span>
          )}
        </span>
        <span className="pd-issue-row-meta">
          <span>{issue.companyName}</span>
          <PriorityLabel priority={issue.priority} />
          <span>{issue.filedBySupport ? `${reporter} ${t('portalIssues.filedBySupport')}` : reporter}</span>
          <RelativeTime dateTime={issue.createdAt} focusable={false} />
        </span>
      </Link>
    </li>
  );
}

export function P3IssueList() {
  const { me } = useSession();
  const page = usePageParam();
  const [params, setParams] = useSearchParams();
  const heading = useRef<HTMLHeadingElement>(null);
  const cooldown = useCooldown();
  const narrow = useNarrow();
  const moreId = useId();

  const filters = readIssueFilters(params);
  const key = filterKey(filters);
  const filtered = key !== '';
  // At mobile width the filters other than the search are folded. They start open when one of them is on.
  const [moreOpen, setMoreOpen] = useState(filters.status.length > 0 || filters.companyCode !== '' || filters.mine);

  const load = useLoad(
    async (): Promise<Result<ListData>> => {
      const [issues, companies] = await Promise.all([
        call(
          api.GET('/api/portal/issues', {
            params: {
              query: {
                page,
                size: PAGE_SIZE,
                ...(filters.q === '' ? {} : { q: filters.q }),
                ...(filters.status.length === 0 ? {} : { status: filters.status }),
                ...(filters.companyCode === '' ? {} : { companyCode: filters.companyCode }),
                ...(filters.mine ? { mine: true } : {}),
              },
            },
          }),
        ),
        call(api.GET('/api/portal/companies', { params: { query: { size: 1 } } })),
      ]);
      if (!issues.ok) return issues;
      if (!companies.ok) return companies;
      return { ok: true, data: { issues: issues.data, member: companies.data.totalElements > 0 }, response: issues.response };
    },
    `${String(page)}\n${key}`,
  );

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

  const firstLoad = load.status === 'loading' && load.data === undefined;
  useMainBusy(firstLoad);
  const move = usePageMove(load.data?.issues, heading);

  const data = load.data?.issues;
  const member = load.data?.member ?? false;
  const outOfRange = data !== undefined && data.items.length === 0 && page > 1;

  const change = (next: IssueFilters, options: { replace?: boolean } = {}) => {
    // Without `page`: a new filter starts on the first page.
    setParams(writeIssueFilters(next), options);
  };

  // The company filter is for a customer of two or more companies (the session holds them).
  const companies = me?.companies ?? [];
  const companyOptions: SelectOption[] = companies.map((c) => ({ value: c.code, label: c.name }));
  if (filters.companyCode !== '' && !companyOptions.some((o) => o.value === filters.companyCode)) {
    // A company the choices do not hold (an old link): shown as it was asked.
    companyOptions.push({ value: filters.companyCode, label: filters.companyCode });
  }

  let list: ReactNode = null;
  if (firstLoad) {
    list = load.slow ? (
      <ul className="pd-issue-rows" aria-hidden="true">
        {Array.from({ length: 5 }, (_, i) => (
          <li key={i} className="pd-issue-row pd-issue-row-skeleton">
            <Skeleton shape="line" width="30%" />
            <Skeleton shape="line" width="70%" />
          </li>
        ))}
      </ul>
    ) : null;
  } else if (outOfRange) {
    list = <EmptyState title="이 쪽에는 문의가 없습니다" action={<Link to={issuesHref(filters)}>1쪽으로</Link>} />;
  } else if (data && data.items.length === 0 && filtered) {
    list = <EmptyState icon={<SearchOffIcon size={20} />} title={t('issueSearch.none')} action={<Link to="/issues">{t('issueSearch.clear')}</Link>} />;
  } else if (data && data.items.length === 0) {
    list = <EmptyState icon={<InboxIcon size={20} />} title="아직 올린 문의가 없습니다." />;
  } else if (data) {
    list = (
      <ul className="pd-issue-rows" aria-labelledby="issue-list-title">
        {data.items.map((i) => (
          <IssueRow key={i.key} issue={i} meId={me?.id} />
        ))}
      </ul>
    );
  }

  return (
    <div className="pd-screen">
      <div className="pd-screen-head">
        <PageTitle titleRef={heading}>{t('issueList.title')}</PageTitle>
        {member && (
          <Link className="pd-button" data-variant="primary" data-size="md" to="/issues/new">
            <span className="pd-button-text">문의 올리기</span>
          </Link>
        )}
      </div>
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
        {narrow && (
          <button
            type="button"
            className="pd-button pd-filters-toggle"
            data-variant="secondary"
            data-size="md"
            data-open={moreOpen ? 'true' : undefined}
            aria-expanded={moreOpen}
            aria-controls={moreOpen ? moreId : undefined}
            onClick={() => {
              setMoreOpen(!moreOpen);
            }}
          >
            <span className="pd-button-text">{t('portalIssues.filters')}</span>
            <ChevronDownIcon size={14} />
          </button>
        )}
        {(!narrow || moreOpen) && (
          <div id={moreId} className="pd-filters-more">
            <CheckboxGroup
              legend={t('portalIssues.status')}
              options={STATUS_OPTIONS}
              value={filters.status}
              onChange={(status) => {
                change({ ...filters, status: ISSUE_STATUSES.filter((s) => status.includes(s)) });
              }}
            />
            {(companies.length > 1 || filters.companyCode !== '') && (
              <Select
                label={t('portalIssues.company')}
                placeholder={t('portalIssues.allCompanies')}
                options={companyOptions}
                value={filters.companyCode}
                onChange={(companyCode) => {
                  change({ ...filters, companyCode });
                }}
              />
            )}
            <Checkbox
              label={t('portalIssues.mineOnly')}
              checked={filters.mine}
              onChange={(mine) => {
                change({ ...filters, mine });
              }}
            />
          </div>
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
      {load.data && !member && <Alert tone="info">{TEXT.noMembership}</Alert>}
      <h2 id="issue-list-title" className="pd-sr-only">
        {`문의 ${String(data?.totalElements ?? 0)}건`}
      </h2>
      {list}
      {data && <PageNav path="/issues" page={data.page} pageCount={data.totalPages} onPageChange={move.onPageChange} />}
      <p role="status" className="pd-sr-only">
        {move.announcement}
      </p>
    </div>
  );
}
