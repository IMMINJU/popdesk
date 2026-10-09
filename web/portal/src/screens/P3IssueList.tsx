// P-3 문의 목록 (screens.md 3절). Newest first, 20 a page. Each row is one link;
// at PC width it reads as a table row, at mobile width as a card (portal.css).
import { Alert, EmptyState, InboxIcon, LockIcon, PriorityLabel, RelativeTime, Skeleton, StatusBadge } from '@popdesk/ui';
import { useRef, type ReactNode } from 'react';
import { Link } from 'react-router';
import { api, type Schemas } from '../api';
import { useMainBusy } from '../app/busy';
import { useLoad } from '../app/hooks';
import { LoadFailed, PageTitle } from '../app/layout';
import { personLabel, TEXT } from '../app/messages';
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

function IssueRow(props: { issue: IssueSummary; meId: number | undefined }) {
  const { issue } = props;
  return (
    <li>
      <Link className="pd-issue-row" to={`/issues/${encodeURIComponent(issue.key)}`}>
        <span className="pd-issue-row-status">
          <StatusBadge kind="issue" status={issue.status} audience="customer" />
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
          <span>{personLabel(issue.reporter, props.meId)}</span>
          <RelativeTime dateTime={issue.createdAt} focusable={false} />
        </span>
      </Link>
    </li>
  );
}

export function P3IssueList() {
  const { me } = useSession();
  const page = usePageParam();
  const heading = useRef<HTMLHeadingElement>(null);

  const load = useLoad(async (): Promise<Result<ListData>> => {
    const [issues, companies] = await Promise.all([
      call(api.GET('/api/portal/issues', { params: { query: { page, size: PAGE_SIZE } } })),
      call(api.GET('/api/portal/companies', { params: { query: { size: 1 } } })),
    ]);
    if (!issues.ok) return issues;
    if (!companies.ok) return companies;
    return { ok: true, data: { issues: issues.data, member: companies.data.totalElements > 0 }, response: issues.response };
  }, String(page));

  const firstLoad = load.status === 'loading' && load.data === undefined;
  useMainBusy(firstLoad);
  const move = usePageMove(load.data?.issues, heading);

  const data = load.data?.issues;
  const member = load.data?.member ?? false;
  const outOfRange = data !== undefined && data.items.length === 0 && page > 1;

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
    list = <EmptyState title="이 쪽에는 문의가 없습니다" action={<Link to="/issues">1쪽으로</Link>} />;
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
        <PageTitle titleRef={heading}>문의 목록</PageTitle>
        {member && (
          <Link className="pd-button" data-variant="primary" data-size="md" to="/issues/new">
            <span className="pd-button-text">문의 올리기</span>
          </Link>
        )}
      </div>
      {load.status === 'failed' && (
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
