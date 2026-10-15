// C-7 알림함 (4-talk screens.md 1절). The caller's items, newest first: what happened, the
// issue, the time. Pressing one marks it read without waiting and goes where it leads.
import { Alert, Button, EmptyState, InboxList, Skeleton, type InboxListItem } from '@popdesk/ui';
import { useRef, useState } from 'react';
import { Link } from 'react-router';
import { api, type Schemas } from '../api';
import { SaveFailure } from '../app/bands';
import { useMainBusy } from '../app/busy';
import { useLive, useLoad } from '../app/hooks';
import { LoadFailed, PageTitle } from '../app/layout';
import { t, tf } from '../app/locale';
import { rateLimitedText, TEXT } from '../app/messages';
import { linkedIssueText } from '../app/linkWords';
import { PageNav } from '../app/PageNav';
import { PAGE_SIZE, pageHref, usePageMove, usePageParam } from '../app/paging';
import { call, type Failure } from '../app/result';
import { useToast } from '../app/toastContext';
import { useUnread } from '../app/unread';

type Notification = Schemas['Notification'];

const PATH = '/inbox';

/**
 * What happened, by kind. A kind the staff never gets (the three of the portal) and a kind this
 * build does not know read "새 알림이 있습니다".
 */
function sentence(n: Notification): string {
  // Accounts are never deleted, so an issue item without an actor is popdesk itself (7a-trash screens.md 3절).
  const who = n.actor === null ? t('inbox.system') : tf('inbox.who', { name: n.actor.name });
  switch (n.kind) {
    case 'CUSTOMER_COMMENT':
      return tf('inbox.customerComment', { who });
    case 'ISSUE_CLOSED':
      return tf('inbox.issueClosed', { who });
    case 'ISSUE_REOPENED':
      return tf('inbox.issueReopened', { who });
    case 'MENTIONED':
      return tf('inbox.mentioned', { who });
    case 'ASSIGNED':
      return tf('inbox.assigned', { who });
    case 'TASK_ASSIGNED':
      return tf('inbox.taskAssigned', { who, title: n.task?.title ?? '' });
    case 'LINKED_ISSUE_RESOLVED':
    case 'LINKED_ISSUE_CLOSED':
      return linkedIssueText(n.kind, who, n.linkedIssue);
    case 'MEMBERSHIP_REQUESTED':
      return tf('inbox.membershipRequested', { code: n.companyCode ?? '' });
    case 'DELIVERY_FAILED':
      return t('inbox.deliveryFailed');
    case 'ATTACHMENT_QUOTA_WARNING':
      return t('inbox.quotaWarning');
    case 'MAIL_STALLED':
      return t('inbox.mailStalled');
    case 'WEBHOOK_STALLED':
      return t('inbox.webhookStalled');
    case 'AUDIT_FORWARD_STALLED':
      return t('inbox.auditForwardStalled');
    default:
      return t('inbox.other');
  }
}

/** Where an item leads (S4-AC-66). An item of an unknown kind about no issue leads nowhere. */
function hrefOf(n: Notification): string | undefined {
  // Slice 6c: the linked issue that was resolved or closed (6c-links screens.md 3절).
  if (n.linkedIssue !== null) return `/issues/${encodeURIComponent(n.linkedIssue.key)}`;
  if (n.issue !== null) {
    const issue = `/issues/${encodeURIComponent(n.issue.key)}`;
    // Slice 6b: a task item leads to its task (6b-tasks screens.md 4절).
    if (n.task !== null) return `${issue}#task-${String(n.task.id)}`;
    return n.commentId === null ? issue : `${issue}#comment-${String(n.commentId)}`;
  }
  switch (n.kind) {
    case 'MEMBERSHIP_REQUESTED':
      return n.companyCode === null ? undefined : `/companies/${encodeURIComponent(n.companyCode)}`;
    case 'DELIVERY_FAILED':
    case 'ATTACHMENT_QUOTA_WARNING':
    case 'MAIL_STALLED':
    case 'WEBHOOK_STALLED':
    case 'AUDIT_FORWARD_STALLED':
      return '/system';
    default:
      return undefined;
  }
}

function lineOf(n: Notification): InboxListItem {
  const href = hrefOf(n);
  return {
    id: n.id,
    text: sentence(n),
    unread: n.readAt === null,
    createdAt: n.createdAt,
    ...(href === undefined ? {} : { href }),
    ...(n.issue === null ? {} : { issueKey: n.issue.key, issueTitle: n.issue.title }),
  };
}

export function C7Inbox() {
  const page = usePageParam();
  const live = useLive();
  const toast = useToast();
  const unread = useUnread();
  const heading = useRef<HTMLHeadingElement>(null);
  const [marking, setMarking] = useState(false);
  const [failure, setFailure] = useState<Failure | null>(null);

  const load = useLoad(() => call(api.GET('/api/console/notifications', { params: { query: { page, size: PAGE_SIZE } } })), String(page));
  const firstLoad = load.status === 'loading' && load.data === undefined;
  useMainBusy(firstLoad);
  const move = usePageMove(load.data, heading);
  const data = load.status === 'failed' ? undefined : load.data;

  const markAll = async () => {
    if (marking) return;
    setMarking(true);
    setFailure(null);
    const result = await call(api.POST('/api/console/notifications/read-all'));
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
    void call(api.POST('/api/console/notifications/{id}/read', { params: { path: { id: Number(item.id) } } }));
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
