// P-7 알림함 (4-talk screens.md 1절). The caller's items, newest first: what happened, the
// issue, the time. Pressing one marks it read without waiting and goes to its issue.
// Whoever did it is "지원팀": the portal draws no name of who caused an item (S4-AC-68).
import { Alert, Button, EmptyState, InboxList, Skeleton, type InboxListItem } from '@popdesk/ui';
import { useRef, useState } from 'react';
import { Link } from 'react-router';
import { api, type Schemas } from '../api';
import { SaveFailure } from '../app/bands';
import { useMainBusy } from '../app/busy';
import { useLive, useLoad } from '../app/hooks';
import { LoadFailed, PageTitle } from '../app/layout';
import { t } from '../app/locale';
import { rateLimitedText, TEXT } from '../app/messages';
import { PageNav } from '../app/PageNav';
import { PAGE_SIZE, pageHref, usePageMove, usePageParam } from '../app/paging';
import { call, type Failure } from '../app/result';
import { useToast } from '../app/toastContext';
import { useUnread } from '../app/unread';

type Notification = Schemas['Notification'];

const PATH = '/inbox';

/** What happened, by kind. A kind this build does not know reads "새 알림이 있습니다". */
function sentence(n: Notification): string {
  switch (n.kind) {
    case 'STAFF_COMMENT':
      return t('inbox.portal.staffComment');
    case 'ISSUE_RESOLVED':
      return t('inbox.portal.issueResolved');
    case 'ISSUE_CLOSED':
      return t('inbox.portal.issueClosed');
    case 'ISSUE_REOPENED':
      return t('inbox.portal.issueReopened');
    case 'ISSUE_FILED_ON_BEHALF':
      return t('inbox.portal.filedOnBehalf');
    case 'MENTIONED':
      return t('inbox.portal.mentioned');
    default:
      return t('inbox.other');
  }
}

/** Every item of the portal is about an issue: it leads there, to the comment when it is about one (S4-AC-66). */
function lineOf(n: Notification): InboxListItem {
  const issue = `/issues/${encodeURIComponent(n.issue.key)}`;
  return {
    id: n.id,
    text: sentence(n),
    unread: n.readAt === null,
    createdAt: n.createdAt,
    href: n.commentId === null ? issue : `${issue}#comment-${String(n.commentId)}`,
    issueKey: n.issue.key,
    issueTitle: n.issue.title,
  };
}

export function P7Inbox() {
  const page = usePageParam();
  const live = useLive();
  const toast = useToast();
  const unread = useUnread();
  const heading = useRef<HTMLHeadingElement>(null);
  const [marking, setMarking] = useState(false);
  const [failure, setFailure] = useState<Failure | null>(null);

  const load = useLoad(() => call(api.GET('/api/portal/notifications', { params: { query: { page, size: PAGE_SIZE } } })), String(page));
  const firstLoad = load.status === 'loading' && load.data === undefined;
  useMainBusy(firstLoad);
  const move = usePageMove(load.data, heading);
  const data = load.status === 'failed' ? undefined : load.data;

  const markAll = async () => {
    if (marking) return;
    setMarking(true);
    setFailure(null);
    const result = await call(api.POST('/api/portal/notifications/read-all'));
    if (!live()) return;
    setMarking(false);
    if (!result.ok) {
      setFailure(result.failure);
      return;
    }
    unread.refresh();
    await load.reload();
    if (live()) toast(t('inbox.markedAll'));
  };

  // Not waited for, and a failure says nothing: the move does not hang on it (S4-AC-66).
  const markRead = (item: InboxListItem) => {
    if (!item.unread) return;
    void call(api.POST('/api/portal/notifications/{id}/read', { params: { path: { id: Number(item.id) } } }));
  };

  return (
    <div className="pd-screen pd-inbox-screen">
      <div className="pd-screen-head">
        <PageTitle titleRef={heading}>{t('inbox.title')}</PageTitle>
        {/* The head's count, not this page's lines: the unread ones may be on another page (S4-AC-67). */}
        {unread.count > 0 && data !== undefined && data.totalElements > 0 && (
          <Button
            variant="secondary"
            size="sm"
            pending={marking}
            pendingLabel={TEXT.sending}
            onClick={() => {
              void markAll();
            }}
          >
            {t('inbox.markAll')}
          </Button>
        )}
      </div>
      {failure?.kind === 'rateLimited' ? <Alert tone="error">{rateLimitedText(failure.seconds)}</Alert> : <SaveFailure failure={failure} />}
      {load.status === 'failed' && (
        <LoadFailed
          onRetry={() => {
            void load.reload();
          }}
        />
      )}
      {load.slow && (
        <div aria-hidden="true">
          <Skeleton shape="line" count={6} />
        </div>
      )}
      {data !== undefined &&
        !load.slow &&
        (data.items.length > 0 ? (
          <InboxList
            items={data.items.map(lineOf)}
            unreadLabel={t('inbox.unread')}
            onOpen={markRead}
            renderLink={(link) => (
              <Link to={link.href} className={link.className} onClick={link.onClick}>
                {link.children}
              </Link>
            )}
          />
        ) : data.page > 1 ? (
          <EmptyState title={t('inbox.emptyPage')} action={<Link to={pageHref(PATH, 1)}>{t('inbox.toFirstPage')}</Link>} />
        ) : (
          <EmptyState title={t('inbox.empty')} />
        ))}
      {data && <PageNav path={PATH} page={data.page} pageCount={data.totalPages} onPageChange={move.onPageChange} />}
      <p role="status" className="pd-sr-only">
        {move.announcement}
      </p>
    </div>
  );
}
