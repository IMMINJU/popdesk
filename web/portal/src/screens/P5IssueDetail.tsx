// P-5 문의 상세 (screens.md 3절). Issue, public comments in time order,
// attachments, and the comment form. The support side shows as "지원팀" (D-49).
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
  type AttachmentItem,
  type FileRejectReason,
  type UploadItem,
} from '@popdesk/ui';
import { useLayoutEffect, useRef, useState, type MouseEvent, type ReactNode, type SubmitEvent } from 'react';
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
import { useCooldown, useFieldErrors, useLeaveGuard, useLoad } from '../app/hooks';
import { LoadFailed, NotFound, PageTitle } from '../app/layout';
import { failureText, personLabel, TEXT } from '../app/messages';
import { photoUrl } from '../app/photos';
import { call, type Failure, type Result } from '../app/result';
import { useSession, useToLogin } from '../app/sessionContext';
import { useToast } from '../app/toastContext';

type Issue = Schemas['Issue'];
type Comment = Schemas['Comment'];

const LIST_SIZE = 100;
const ISSUE_CLOSED_TEXT = '문의가 종료되어 댓글을 달 수 없습니다.';

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

export function P5IssueDetail() {
  const key = useParams().issueKey ?? '';
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
  const blocker = useLeaveGuard(draft.trim() !== '');

  // Attachments
  const [uploads, setUploads] = useState<Upload[]>([]);
  const nextUpload = useRef(0);
  const [deleteTarget, setDeleteTarget] = useState<Attachment | null>(null);
  const [deleting, setDeleting] = useState<string | null>(null);
  const [fileBand, setFileBand] = useState<string | null>(null);

  const detail = load.data;

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
            <dd>{personLabel(issue.reporter, meId)}</dd>
          </div>
          <div>
            <dt>올린 시각</dt>
            <dd>
              <RelativeTime dateTime={issue.createdAt} />
            </dd>
          </div>
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

      <article className="pd-detail-body" aria-label="문의 내용">
        <MarkdownView source={issue.content} />
      </article>

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
        {comments.length === 0 ? (
          <p className="pd-muted">아직 댓글이 없습니다.</p>
        ) : (
          <div className="pd-comment-list">
            {comments.map((c) => (
              <CommentItem
                key={c.id}
                id={`comment-${String(c.id)}`}
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
              />
            ))}
          </div>
        )}
      </section>

      {footer}

      <ConfirmDialog
        open={deleteTarget !== null}
        tone="danger"
        title="첨부 지우기"
        message={`${deleteTarget?.fileName ?? ''}을(를) 지웁니다. 목록에는 "지워진 첨부"로 남습니다.`}
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
