// P-5 문의 상세 (screens.md 3절). Issue, public comments in time order,
// attachments, and the comment form. The support side shows as "지원팀" (D-49).
// Slice 3 (3-issues screens.md 2절) adds the times and the mark of a filing by the
// support team to the head, the band of a resolved issue, what its reporter can do
// (문의 종료, 해결 확인, 다시 열기), and 고치기.
// Slice 4 (4-talk screens.md 2절) adds 댓글 고치기·댓글 지우기 on my own comments, the trace of a
// deleted comment, and the `#comment-ID` of the address. No mentions here (S4-AC-25).
import {
  Alert,
  AttachmentList,
  Badge,
  Button,
  CommentItem,
  ConfirmDialog,
  FileUpload,
  LockIcon,
  MarkdownEditor,
  MarkdownView,
  PriorityLabel,
  RelativeTime,
  Skeleton,
  StatusBadge,
  commentActionDescribedBy,
  type AttachmentItem,
  type FileRejectReason,
  type UploadItem,
} from '@popdesk/ui';
import { useCallback, useLayoutEffect, useRef, useState, type MouseEvent, type ReactNode, type SubmitEvent } from 'react';
import { useLocation, useParams } from 'react-router';
import { api, type Schemas } from '../api';
import {
  ATTACHMENT_LIMITS,
  attachmentHref,
  checkAttachment,
  publicBytes,
  REJECT_TEXT,
  uploadAttachment,
  uploadFailureText,
  type Attachment,
  type UploadFailure,
} from '../app/attachments';
import { RateLimitBand, SaveFailure } from '../app/bands';
import { useMainBusy } from '../app/busy';
import { CommentEdit, type EditGone } from '../app/CommentEdit';
import { useCooldown, useFieldErrors, useLeaveGuard, useLive, useLoad } from '../app/hooks';
import { IssueEdit } from '../app/IssueEdit';
import { LoadFailed, NotFound, PageTitle } from '../app/layout';
import { t } from '../app/locale';
import { failureText, personLabel, rateLimitedText, TEXT } from '../app/messages';
import { photoUrl } from '../app/photos';
import { call, type Failure, type Result } from '../app/result';
import { useSession, useToLogin } from '../app/sessionContext';
import { useToast } from '../app/toastContext';

type Issue = Schemas['Issue'];
type Comment = Schemas['Comment'];

const LIST_SIZE = 100;
const ISSUE_CLOSED_TEXT = '문의가 종료되어 댓글을 달 수 없습니다.';
/** The band over the comments when an edit could not be saved any more. */
const EDIT_GONE_TEXT = { closed: 'comment.editClosed', deleted: 'comment.alreadyDeleted', notFound: 'comment.notFound' } as const;
/** The server says so when the status changed before the request came (S3-AC-82). */
const ALREADY_CHANGED = ['ISSUE_CLOSED', 'NOT_REOPENABLE', 'INVALID_TRANSITION'];
/** 문의 종료 asks first; 해결 확인 is the same request without the question. */
type Action = 'close' | 'confirm' | 'reopen';

interface Detail {
  issue: Issue;
  comments: Comment[];
  attachments: Attachment[];
  /** Still in the issue's company. A customer who left keeps reading their own issues (PROJ-4). */
  member: boolean;
}

function byTime<T extends { createdAt: string }>(items: readonly T[]): T[] {
  return [...items].sort((a, b) => Date.parse(a.createdAt) - Date.parse(b.createdAt));
}

/** A file row the screen keeps while it uploads (FileUpload). */
interface Upload extends UploadItem {
  file?: File;
}

/** P-4 hands over the files it could not send (screens.md P-4 첨부 일부 실패). */
function UploadFailures(props: { failures: readonly UploadFailure[] }) {
  if (props.failures.length === 0) return null;
  return (
    <Alert tone="warning" title={`첨부 ${String(props.failures.length)}개를 올리지 못했습니다.`}>
      <ul className="pd-failed-files">
        {props.failures.map((f, i) => (
          <li key={i}>
            <span className="pd-strong">{f.name}</span>: {f.reason}
          </li>
        ))}
      </ul>
      <p>아래 첨부에서 다시 올릴 수 있습니다.</p>
    </Alert>
  );
}

/**
 * One screen for each issue: a move to another issue starts anew, so its dialogs
 * close and an answer for the issue left behind finds nothing to change (S3-AC-87).
 */
export function P5IssueDetail() {
  const key = useParams().issueKey ?? '';
  return <IssueDetail key={key} issueKey={key} />;
}

function IssueDetail(props: { issueKey: string }) {
  const key = props.issueKey;
  const live = useLive();
  const location = useLocation();
  const meId = useSession().me?.id;
  const toLogin = useToLogin();
  const toast = useToast();
  const [handedOver] = useState<UploadFailure[]>(() => (location.state as { uploadFailures?: UploadFailure[] } | null)?.uploadFailures ?? []);

  const load = useLoad(async (): Promise<Result<Detail>> => {
    const path = { params: { path: { key } } };
    const list = { params: { path: { key }, query: { size: LIST_SIZE } } };
    const [issue, comments, attachments, companies] = await Promise.all([
      call(api.GET('/api/portal/issues/{key}', path)),
      call(api.GET('/api/portal/issues/{key}/comments', list)),
      call(api.GET('/api/portal/issues/{key}/attachments', list)),
      call(api.GET('/api/portal/companies', { params: { query: { size: LIST_SIZE } } })),
    ]);
    if (!issue.ok) return issue;
    if (!comments.ok) return comments;
    if (!attachments.ok) return attachments;
    if (!companies.ok) return companies;
    return {
      ok: true,
      data: {
        issue: issue.data,
        comments: comments.data.items,
        attachments: attachments.data.items,
        member: companies.data.items.some((c) => c.code === issue.data.companyCode),
      },
      response: issue.response,
    };
  }, key);

  const firstLoad = load.status === 'loading' && load.data === undefined;
  useMainBusy(firstLoad);

  // Comment form
  const [draft, setDraft] = useState('');
  const [sending, setSending] = useState(false);
  const [sendFailure, setSendFailure] = useState<Failure | null>(null);
  const [closedWhileWriting, setClosedWhileWriting] = useState(false);
  const cooldown = useCooldown();
  const editorRef = useRef<HTMLTextAreaElement>(null);
  // Focus targets after a send, taken in the next commit (screens.md P-5).
  const focusComment = useRef<number | null>(null);
  const focusEditor = useRef(false);
  const fields = useFieldErrors({ content: { id: 'comment-content', limits: { min: 1, max: 4000 } } });

  // Slice 3: what the reporter does to the issue, and 고치기.
  const [acting, setActing] = useState<Action | null>(null);
  const [asking, setAsking] = useState<'close' | 'reopen' | null>(null);
  const [actionFailure, setActionFailure] = useState<Failure | null>(null);
  const [editing, setEditing] = useState(false);
  const [editDirty, setEditDirty] = useState(false);

  // Slice 4: one comment is edited at a time; a deleted one is asked about first.
  const [editingComment, setEditingComment] = useState<number | null>(null);
  const [commentDirty, setCommentDirty] = useState(false);
  // What to do once the text in the comment's editor may be thrown away: close it (null) or edit another comment.
  const [discarding, setDiscarding] = useState<{ next: number | null } | null>(null);
  const [editGone, setEditGone] = useState<{ why: EditGone; draft: string } | null>(null);
  const [removing, setRemoving] = useState<Comment | null>(null);
  const [removePending, setRemovePending] = useState(false);
  const [removeError, setRemoveError] = useState<string | undefined>(undefined);
  // Counts the questions: an answer for a question already closed is dropped.
  const removeAsk = useRef(0);
  const editButtons = useRef(new Map<number, HTMLButtonElement>());
  const focusEditButton = useRef<number | null>(null);
  const hashDone = useRef(false);
  const blocker = useLeaveGuard(draft.trim() !== '' || (editing && editDirty) || (editingComment !== null && commentDirty));

  // Attachments
  const [uploads, setUploads] = useState<Upload[]>([]);
  const nextUpload = useRef(0);
  const [deleteTarget, setDeleteTarget] = useState<Attachment | null>(null);
  const [deleting, setDeleting] = useState<string | null>(null);
  const [fileBand, setFileBand] = useState<string | null>(null);

  const detail = load.data;
  const reload = load.reload;
  const reloadIssue = useCallback(async () => {
    const result = await reload();
    return result?.ok ? result.data.issue : undefined;
  }, [reload]);

  useLayoutEffect(() => {
    if (focusComment.current !== null) {
      const el = document.getElementById(`comment-${String(focusComment.current)}`);
      if (el) {
        focusComment.current = null;
        el.focus();
      }
    }
    if (focusEditor.current) {
      focusEditor.current = false;
      editorRef.current?.focus();
    }
    if (focusEditButton.current !== null) {
      const button = editButtons.current.get(focusEditButton.current);
      if (button) {
        focusEditButton.current = null;
        button.focus();
      }
    }
    // `#comment-ID` of the address: once, when the comments are in. A comment that is not among them is left alone.
    if (detail && !hashDone.current) {
      hashDone.current = true;
      const wanted = /^#(comment-\d+)$/.exec(location.hash)?.[1];
      const el = wanted === undefined ? null : document.getElementById(wanted);
      if (el) {
        // Marked while it holds this focus, so the ring shows which comment the address named.
        el.dataset.arrived = 'true';
        el.addEventListener(
          'blur',
          () => {
            delete el.dataset.arrived;
          },
          { once: true },
        );
        el.focus();
        // jsdom has none.
        if (typeof el.scrollIntoView === 'function') el.scrollIntoView({ block: 'center' });
      }
    }
  });

  if (load.status === 'failed' && load.failure?.kind === 'notFound') {
    return <NotFound listHref="/issues" listLabel="문의 목록으로" />;
  }

  if (!detail) {
    return (
      <div className="pd-screen">
        {load.status === 'failed' && (
          <LoadFailed
            onRetry={() => {
              void load.reload();
            }}
          />
        )}
        {load.slow && (
          <div className="pd-detail-skeleton" aria-hidden="true">
            <Skeleton shape="line" width="40%" height="24px" />
            <Skeleton shape="line" count={4} />
            <Skeleton shape="block" count={3} />
          </div>
        )}
      </div>
    );
  }

  const { issue } = detail;
  const closed = issue.status === 'CLOSED';
  const mine = issue.reporter.kind === 'CUSTOMER' && issue.reporter.accountId === meId;
  // Comments and files on my own issue only (RULE-09, CMT-1), and only while I am in its company (PROJ-4).
  const canWrite = mine && detail.member;
  const resolved = issue.status === 'RESOLVED';
  // The reporter alone acts on an issue; a colleague reads (S3-AC-82). The server says whether it can be reopened.
  const canEdit = canWrite && !closed;
  const askResolved = canWrite && resolved;
  // After leaving the company the reporter can still close it, and nothing else.
  const canClose = mine && !closed && !askResolved;
  const canReopen = canWrite && closed && issue.reopenable;

  const act = async (what: Action) => {
    if (acting !== null) return;
    setActing(what);
    setActionFailure(null);
    const path = { params: { path: { key } } };
    const result =
      what === 'reopen' ? await call(api.POST('/api/portal/issues/{key}/reopen', path)) : await call(api.POST('/api/portal/issues/{key}/close', path));
    // The screen has gone, or moved to another issue: no toast, nothing read again.
    if (!live()) return;
    const changedFirst = !result.ok && result.failure.kind === 'rejected' && ALREADY_CHANGED.includes(result.failure.code);
    if (result.ok) {
      toast(t(what === 'reopen' ? 'portalIssue.reopenedToast' : what === 'confirm' ? 'portalIssue.confirmedToast' : 'portalIssue.closedToast'));
    } else if (changedFirst) {
      toast(t('portalIssue.changedAlready'), 'info');
    } else {
      setActionFailure(result.failure);
    }
    // 403: left the company while reading. Reading again (the companies too) takes the actions away.
    if (result.ok || changedFirst || result.failure.kind === 'forbidden') {
      await load.reload();
      if (!live()) return;
    }
    setActing(null);
    setAsking(null);
  };

  const send = async () => {
    if (sending || cooldown.active) return;
    setSendFailure(null);
    cooldown.clearEnded();
    fields.clear();
    if (draft.trim() === '') {
      fields.setError('content', '내용을 넣어 주세요.');
      return;
    }
    setSending(true);
    const result = await call(
      api.POST('/api/portal/issues/{key}/comments', {
        params: { path: { key } },
        body: { content: draft },
      }),
    );
    setSending(false);
    if (result.ok) {
      focusComment.current = result.data.id;
      setDraft('');
      await load.reload();
      toast('댓글을 남겼습니다');
      return;
    }
    const f = result.failure;
    if (f.kind === 'invalid') {
      fields.applyFields(f.fields, f.message);
      return;
    }
    if (f.kind === 'rateLimited') {
      cooldown.start(f.seconds);
    } else if (f.kind === 'rejected' && f.code === 'ISSUE_CLOSED') {
      setSendFailure(f);
      setClosedWhileWriting(true);
      void load.reload();
      return;
    } else {
      setSendFailure(f);
    }
    focusEditor.current = true;
  };

  const onSubmit = (e: SubmitEvent) => {
    e.preventDefault();
    void send();
  };

  // Comments: 고치기, 지우기 --------------------------------------------
  const closeCommentEdit = (next: number | null) => {
    if (next === null) focusEditButton.current = editingComment;
    setEditingComment(next);
    setCommentDirty(false);
    setDiscarding(null);
  };

  /** Closes the editor, or moves it to another comment; with text to lose it asks first. */
  const leaveCommentEdit = (next: number | null) => {
    if (editingComment !== null && commentDirty) setDiscarding({ next });
    else closeCommentEdit(next);
  };

  const onCommentSaved = async (id: number) => {
    setEditingComment(null);
    setCommentDirty(false);
    setEditGone(null);
    focusComment.current = id;
    await load.reload();
    if (live()) toast(t('comment.editedToast'));
  };

  const onCommentGone = (why: EditGone, text: string) => {
    setEditingComment(null);
    setCommentDirty(false);
    setEditGone({ why, draft: text });
    // The issue too: closed, or no longer in sight (then the whole screen says 찾을 수 없습니다).
    void load.reload();
  };

  const closeRemove = () => {
    removeAsk.current += 1;
    setRemoving(null);
    setRemovePending(false);
    setRemoveError(undefined);
  };

  const confirmRemove = async () => {
    if (!removing || removePending) return;
    const target = removing;
    const asked = removeAsk.current;
    setRemovePending(true);
    setRemoveError(undefined);
    const result = await call(api.DELETE('/api/portal/issues/{key}/comments/{id}', { params: { path: { key, id: target.id } } }));
    // The screen has gone, or the question was closed while it was on its way.
    if (!live() || asked !== removeAsk.current) return;
    if (result.ok) {
      closeRemove();
      focusComment.current = target.id;
      await load.reload();
      if (live()) toast(t('comment.deletedToast'));
    } else if (result.failure.kind === 'notFound') {
      closeRemove();
      void load.reload();
    } else {
      setRemovePending(false);
      setRemoveError(failureText(result.failure));
    }
  };

  // Attachments --------------------------------------------------------
  const replaceUpload = (id: string, patch: Partial<Upload>) => {
    setUploads((current) => current.map((u) => (u.id === id ? { ...u, ...patch } : u)));
  };

  const startUpload = async (id: string, file: File) => {
    replaceUpload(id, { state: 'uploading' });
    const result = await uploadAttachment(key, file);
    if (result.ok) {
      setUploads((current) => current.filter((u) => u.id !== id));
      await load.reload();
      return;
    }
    replaceUpload(id, { state: 'failed', error: uploadFailureText(result.failure) });
  };

  const onSelect = (accepted: File[], rejected: { file: File; reason: FileRejectReason }[]) => {
    const rows: Upload[] = [];
    const starts: [string, File][] = [];
    for (const { file, reason } of rejected) {
      nextUpload.current += 1;
      rows.push({ id: `u${String(nextUpload.current)}`, name: file.name, size: file.size, state: 'rejected', error: REJECT_TEXT[reason] });
    }
    for (const file of accepted) {
      nextUpload.current += 1;
      const id = `u${String(nextUpload.current)}`;
      rows.push({ id, name: file.name, size: file.size, state: 'uploading', file });
      starts.push([id, file]);
    }
    setUploads((current) => [...current, ...rows]);
    // One file at a time, in the order they were picked.
    void (async () => {
      for (const [id, file] of starts) await startUpload(id, file);
    })();
  };

  const onDownload = (item: AttachmentItem, e: MouseEvent<HTMLAnchorElement>) => {
    e.preventDefault();
    setFileBand(null);
    void checkAttachment(key, item.id).then((result) => {
      if (result.ok) {
        window.location.assign(attachmentHref(key, item.id));
      } else if (result.failure.kind === 'notFound') {
        setFileBand('파일을 찾을 수 없습니다');
      } else if (result.failure.kind === 'unauthenticated') {
        toLogin();
      } else {
        setFileBand(failureText(result.failure));
      }
    });
  };

  const confirmDelete = async () => {
    if (!deleteTarget) return;
    const target = deleteTarget;
    setDeleting(target.id);
    const result = await call(api.DELETE('/api/portal/issues/{key}/attachments/{id}', { params: { path: { key, id: target.id } } }));
    setDeleting(null);
    setDeleteTarget(null);
    if (result.ok || result.failure.kind === 'notFound') {
      await load.reload();
      if (result.ok) toast('첨부를 지웠습니다');
      return;
    }
    setFileBand(failureText(result.failure));
  };

  const attachmentItems: AttachmentItem[] = detail.attachments.map((a) => ({
    id: a.id,
    name: a.fileName ?? '',
    size: a.size,
    uploaderLabel: personLabel(a.uploadedBy, meId),
    // The support side has no photo in the portal (D-49).
    uploaderAvatar:
      a.uploadedBy.kind === 'SUPPORT_TEAM'
        ? { kind: 'support' }
        : { kind: 'person', photoUrl: photoUrl(a.uploadedBy.accountId, a.uploadedBy.photoUpdatedAt) },
    createdAt: a.createdAt,
    deleted: a.deleted,
    canDelete: canWrite && !a.deleted && a.uploadedBy.kind === 'CUSTOMER' && a.uploadedBy.accountId === meId,
    deleting: deleting === a.id,
    ...(a.deleted ? {} : { href: attachmentHref(key, a.id) }),
  }));

  const comments = byTime(detail.comments);
  // The second sentence of the question: only for files of the comment itself that are still there.
  const removeFiles = removing !== null && detail.attachments.some((a) => a.commentId === removing.id && !a.deleted);

  let footer: ReactNode = null;
  if (closed) {
    footer = (
      <div className="pd-detail-closed">
        {closedWhileWriting && <SaveFailure failure={sendFailure} known={{ ISSUE_CLOSED: ISSUE_CLOSED_TEXT }} />}
        <Alert tone="info">종료된 문의입니다. 댓글과 첨부를 더할 수 없습니다.</Alert>
        {draft.trim() !== '' && (
          <div className="pd-kept-draft">
            <p className="pd-label">보내지 못한 글</p>
            <pre>{draft}</pre>
          </div>
        )}
      </div>
    );
  } else if (mine && !detail.member) {
    footer = <Alert tone="info">이 고객사에서 빠져 댓글을 달 수 없습니다.</Alert>;
  } else if (canWrite) {
    footer = (
      <form className="pd-comment-form" aria-label="댓글 쓰기" noValidate onSubmit={onSubmit}>
        <SaveFailure failure={sendFailure} known={{ ISSUE_CLOSED: ISSUE_CLOSED_TEXT }} />
        <RateLimitBand {...cooldown} />
        <MarkdownEditor
          ref={editorRef}
          id="comment-content"
          label="댓글"
          labelHidden
          value={draft}
          onChange={setDraft}
          minHeight={112}
          maxLength={4000}
          showCount
          placeholder="지원팀에 전할 내용을 씁니다."
          helpText="Ctrl+Enter(맥은 Cmd+Enter)로 보냅니다."
          onSubmitShortcut={() => {
            void send();
          }}
          {...(fields.errors.content ? { error: fields.errors.content } : {})}
        />
        <div className="pd-form-actions">
          <Button type="submit" pending={sending} pendingLabel={TEXT.sending} blocked={cooldown.active}>
            보내기
          </Button>
        </div>
      </form>
    );
  }

  return (
    <div className="pd-screen pd-detail">
      <UploadFailures failures={handedOver} />
      <header className="pd-detail-head">
        <p className="pd-detail-key">
          <span className="pd-key">{issue.key}</span>
          <span>{issue.companyName}</span>
        </p>
        <PageTitle>{issue.title}</PageTitle>
        {askResolved && !editing && (
          <Alert
            tone="info"
            action={
              <>
                <Button
                  size="sm"
                  pending={acting === 'confirm'}
                  pendingLabel={TEXT.sending}
                  blocked={acting !== null && acting !== 'confirm'}
                  onClick={() => {
                    void act('confirm');
                  }}
                >
                  {t('portalIssue.confirmResolved')}
                </Button>
                <Button
                  variant="secondary"
                  size="sm"
                  blocked={acting !== null}
                  onClick={() => {
                    setAsking('reopen');
                  }}
                >
                  {t('portalIssue.reopen')}
                </Button>
              </>
            }
          >
            {t('portalIssue.resolvedBand')}
          </Alert>
        )}
        {!editing && (canEdit || canClose || canReopen) && (
          <div className="pd-detail-actions">
            {canEdit && (
              <Button
                variant="secondary"
                size="sm"
                blocked={acting !== null}
                onClick={() => {
                  setActionFailure(null);
                  setEditing(true);
                }}
              >
                {t('issueEdit.edit')}
              </Button>
            )}
            {canClose && (
              <Button
                variant="secondary"
                size="sm"
                blocked={acting !== null}
                onClick={() => {
                  setAsking('close');
                }}
              >
                {t('portalIssue.close')}
              </Button>
            )}
            {canReopen && (
              <Button
                variant="secondary"
                size="sm"
                blocked={acting !== null}
                onClick={() => {
                  setAsking('reopen');
                }}
              >
                {t('portalIssue.reopen')}
              </Button>
            )}
          </div>
        )}
        {canWrite && closed && !issue.reopenable && <p className="pd-muted">{t('portalIssue.tooLateToReopen')}</p>}
        {actionFailure?.kind === 'rateLimited' ? (
          <Alert tone="error">{rateLimitedText(actionFailure.seconds)}</Alert>
        ) : (
          <SaveFailure failure={actionFailure} />
        )}
        <dl className="pd-meta">
          <div>
            <dt>상태</dt>
            <dd>
              <StatusBadge kind="issue" status={issue.status} audience="customer" />
            </dd>
          </div>
          <div>
            <dt>우선순위</dt>
            <dd>
              <PriorityLabel priority={issue.priority} />
            </dd>
          </div>
          <div>
            <dt>고객사</dt>
            <dd>{issue.companyName}</dd>
          </div>
          <div>
            <dt>올린 사람</dt>
            <dd>{issue.filedBySupport ? `${personLabel(issue.reporter, meId)} ${t('portalIssues.filedBySupport')}` : personLabel(issue.reporter, meId)}</dd>
          </div>
          <div>
            <dt>올린 시각</dt>
            <dd>
              <RelativeTime dateTime={issue.createdAt} />
            </dd>
          </div>
          {issue.resolvedAt !== null && (
            <div>
              <dt>{t('portalIssue.resolvedAt')}</dt>
              <dd>
                <RelativeTime dateTime={issue.resolvedAt} />
              </dd>
            </div>
          )}
          {issue.closedAt !== null && (
            <div>
              <dt>{t('portalIssue.closedAt')}</dt>
              <dd>
                <RelativeTime dateTime={issue.closedAt} />
              </dd>
            </div>
          )}
          {issue.private && (
            <div>
              <dt className="pd-sr-only">공개 범위</dt>
              <dd>
                <Badge tone="neutral" icon={<LockIcon size={12} />}>
                  {TEXT.private}
                </Badge>
              </dd>
            </div>
          )}
        </dl>
      </header>

      {editing ? (
        <IssueEdit
          issue={issue}
          reload={reloadIssue}
          onDirtyChange={setEditDirty}
          onCancel={() => {
            setEditing(false);
            setEditDirty(false);
          }}
          onSaved={() => {
            setEditing(false);
            setEditDirty(false);
            void load.reload();
          }}
        />
      ) : (
        <article className="pd-detail-body" aria-label="문의 내용">
          <MarkdownView source={issue.content} />
        </article>
      )}

      <div className="pd-detail-attachments">
        {fileBand && (
          <Alert
            tone="error"
            onClose={() => {
              setFileBand(null);
            }}
            closeLabel={TEXT.close}
          >
            {fileBand}
          </Alert>
        )}
        <AttachmentList
          title="첨부"
          items={attachmentItems}
          emptyText="첨부 없음"
          deleteLabel="지우기"
          deletedLabel="지워진 첨부"
          onDelete={(id) => {
            setDeleteTarget(detail.attachments.find((a) => a.id === id) ?? null);
          }}
          onDownload={onDownload}
        />
        {canWrite && !closed && (
          <FileUpload
            label="첨부 올리기"
            items={uploads}
            onSelect={onSelect}
            onRemove={(id) => {
              setUploads((current) => current.filter((u) => u.id !== id));
            }}
            onRetry={(id) => {
              const row = uploads.find((u) => u.id === id);
              if (row?.file) void startUpload(id, row.file);
            }}
            limits={ATTACHMENT_LIMITS}
            usedBytes={publicBytes(detail.attachments)}
            helpText="파일 하나 10MB까지. 고르면 바로 문의에 올립니다."
            removeLabel="빼기"
            retryLabel="다시 올리기"
            dropHint="또는 여기로 끌어 놓으세요"
          />
        )}
      </div>

      <section className="pd-detail-comments" aria-labelledby="comments-title">
        <h2 id="comments-title" className="pd-section-title">
          댓글 {comments.length}개
        </h2>
        {editGone && (
          <div className="pd-comment-unsent">
            <Alert
              tone="error"
              onClose={() => {
                setEditGone(null);
              }}
              closeLabel={TEXT.close}
            >
              {t(EDIT_GONE_TEXT[editGone.why])}
            </Alert>
            <div className="pd-kept-draft">
              <p className="pd-label">{t('comment.unsent')}</p>
              <pre>{editGone.draft}</pre>
            </div>
          </div>
        )}
        {comments.length === 0 ? (
          <p className="pd-muted">아직 댓글이 없습니다.</p>
        ) : (
          <div className="pd-comment-list">
            {comments.map((c) => {
              const domId = `comment-${String(c.id)}`;
              // Who gets which button is the screen's to say (S4-AC-60): the writer alone, while still in the company.
              const own = c.author.kind === 'CUSTOMER' && c.author.accountId === meId && detail.member;
              const canEditComment = own && !closed;
              const editingThis = editingComment === c.id;
              return (
                <CommentItem
                  key={c.id}
                  id={domId}
                  author={
                    c.author.kind === 'SUPPORT_TEAM'
                      ? { kind: 'support' }
                      : {
                          kind: 'person',
                          name: c.author.name ?? '',
                          isMe: c.author.accountId === meId,
                          photoUrl: photoUrl(c.author.accountId, c.author.photoUpdatedAt),
                        }
                  }
                  visibility="public"
                  createdAt={c.createdAt}
                  body={c.content}
                  supportLabel={TEXT.supportTeam}
                  meLabel={TEXT.me}
                  deleted={c.deleted}
                  deletedLabel={t('comment.deleted')}
                  deletedAt={c.deletedAt}
                  // A deleted comment shows no 고침, whatever the server still holds.
                  editedAt={c.deleted ? null : c.editedAt}
                  editedLabel={t('comment.edited')}
                  {...(!c.deleted && !editingThis && own
                    ? {
                        actions: (
                          <>
                            {canEditComment && (
                              <Button
                                ref={(el) => {
                                  if (el) editButtons.current.set(c.id, el);
                                  else editButtons.current.delete(c.id);
                                }}
                                variant="quiet"
                                size="sm"
                                describedBy={commentActionDescribedBy(domId)}
                                onClick={() => {
                                  leaveCommentEdit(c.id);
                                }}
                              >
                                {t('comment.edit')}
                              </Button>
                            )}
                            <Button
                              variant="quiet"
                              size="sm"
                              describedBy={commentActionDescribedBy(domId)}
                              onClick={() => {
                                setRemoving(c);
                              }}
                            >
                              {t('comment.delete')}
                            </Button>
                          </>
                        ),
                      }
                    : {})}
                  {...(editingThis
                    ? {
                        editing: (
                          <CommentEdit
                            issueKey={key}
                            comment={c}
                            onDirtyChange={setCommentDirty}
                            onCancel={() => {
                              leaveCommentEdit(null);
                            }}
                            onUnchanged={() => {
                              closeCommentEdit(null);
                            }}
                            onSaved={() => {
                              void onCommentSaved(c.id);
                            }}
                            onGone={onCommentGone}
                          />
                        ),
                      }
                    : {})}
                />
              );
            })}
          </div>
        )}
      </section>

      {footer}

      <ConfirmDialog
        open={deleteTarget !== null}
        tone="danger"
        title="첨부 지우기"
        message={`첨부 ${deleteTarget?.fileName ?? ''} 파일을 지웁니다. 목록에는 "지워진 첨부"로 남습니다.`}
        confirmLabel="지우기"
        cancelLabel={TEXT.cancel}
        pending={deleting !== null}
        pendingLabel="지우는 중…"
        onConfirm={() => {
          void confirmDelete();
        }}
        onCancel={() => {
          setDeleteTarget(null);
        }}
      />
      <ConfirmDialog
        open={removing !== null}
        tone="danger"
        title={t('comment.delete')}
        message={removeFiles ? `${t('comment.askDelete')} ${t('comment.askDeleteFiles')}` : t('comment.askDelete')}
        confirmLabel={t('comment.delete')}
        cancelLabel={t('comment.keep')}
        pending={removePending}
        pendingLabel={t('comment.deleting')}
        {...(removeError === undefined ? {} : { error: removeError })}
        onConfirm={() => {
          void confirmRemove();
        }}
        onCancel={closeRemove}
      />
      <ConfirmDialog
        open={discarding !== null}
        title={TEXT.leaveTitle}
        message={TEXT.leaveMessage}
        confirmLabel={TEXT.leave}
        cancelLabel={TEXT.stay}
        tone="danger"
        onConfirm={() => {
          closeCommentEdit(discarding?.next ?? null);
        }}
        onCancel={() => {
          setDiscarding(null);
        }}
      />
      <ConfirmDialog
        open={asking !== null}
        title={t(asking === 'reopen' ? 'portalIssue.reopen' : 'portalIssue.close')}
        message={t(asking === 'reopen' ? 'portalIssue.askReopen' : 'portalIssue.askClose')}
        confirmLabel={t(asking === 'reopen' ? 'portalIssue.reopen' : 'portalIssue.closeConfirm')}
        cancelLabel={TEXT.cancel}
        pending={acting !== null}
        pendingLabel={TEXT.sending}
        onConfirm={() => {
          void act(asking === 'reopen' ? 'reopen' : 'close');
        }}
        onCancel={() => {
          setAsking(null);
        }}
      />
      <ConfirmDialog
        open={blocker.state === 'blocked'}
        title={TEXT.leaveTitle}
        message={TEXT.leaveMessage}
        confirmLabel={TEXT.leave}
        cancelLabel={TEXT.stay}
        tone="danger"
        onConfirm={() => {
          blocker.proceed?.();
        }}
        onCancel={() => {
          blocker.reset?.();
        }}
      />
    </div>
  );
}
