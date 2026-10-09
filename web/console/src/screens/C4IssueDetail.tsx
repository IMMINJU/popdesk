// C-4 문의 상세 (screens.md 4절). Issue, public and internal comments in time
// order, attachments, and the comment form with 공개 답변·내부 메모.
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
  RadioGroup,
  RelativeTime,
  Skeleton,
  StatusBadge,
  type AttachmentItem,
  type UploadItem,
} from '@popdesk/ui';
import { useLayoutEffect, useRef, useState, type SubmitEvent, type MouseEvent } from 'react';
import { useParams } from 'react-router';
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
} from '../app/attachments';
import { RateLimitBand, SaveFailure } from '../app/bands';
import { useMainBusy } from '../app/busy';
import { useCooldown, useFieldErrors, useLeaveGuard, useLoad } from '../app/hooks';
import { LoadFailed, NotFound, PageTitle } from '../app/layout';
import { failureText, TEXT } from '../app/messages';
import { call, type Failure, type Result } from '../app/result';
import { useSession, useToLogin } from '../app/sessionContext';
import { useToast } from '../app/toastContext';

type Issue = Schemas['Issue'];
type Comment = Schemas['Comment'];
type Visibility = 'public' | 'internal';

const LIST_SIZE = 100;
const ISSUE_CLOSED_TEXT = '문의가 종료되어 댓글을 달 수 없습니다.';
const VISIBILITIES = [
  { value: 'public' as const, label: '공개 답변' },
  { value: 'internal' as const, label: '내부 메모' },
];

interface Detail {
  issue: Issue;
  comments: Comment[];
  attachments: Attachment[];
}

function byTime<T extends { createdAt: string }>(items: readonly T[]): T[] {
  return [...items].sort((a, b) => Date.parse(a.createdAt) - Date.parse(b.createdAt));
}

/** A file row the screen keeps while it uploads (FileUpload). */
interface Upload extends UploadItem {
  file?: File;
}

export function C4IssueDetail() {
  const key = useParams().issueKey ?? '';
  const { me } = useSession();
  const toLogin = useToLogin();
  const toast = useToast();

  const load = useLoad(async (): Promise<Result<Detail>> => {
    const path = { params: { path: { key } } };
    const list = { params: { path: { key }, query: { size: LIST_SIZE } } };
    const [issue, comments, attachments] = await Promise.all([
      call(api.GET('/api/console/issues/{key}', path)),
      call(api.GET('/api/console/issues/{key}/comments', list)),
      call(api.GET('/api/console/issues/{key}/attachments', list)),
    ]);
    if (!issue.ok) return issue;
    if (!comments.ok) return comments;
    if (!attachments.ok) return attachments;
    return {
      ok: true,
      data: { issue: issue.data, comments: comments.data.items, attachments: attachments.data.items },
      response: issue.response,
    };
  }, key);

  const firstLoad = load.status === 'loading' && load.data === undefined;
  useMainBusy(firstLoad);

  // Comment form
  const [visibility, setVisibility] = useState<Visibility>('public');
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
  const isAdmin = me?.role === 'ADMIN';

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
    const internal = visibility === 'internal';
    const result = await call(
      api.POST('/api/console/issues/{key}/comments', {
        params: { path: { key } },
        body: internal ? { content: draft, internal: true } : { content: draft },
      }),
    );
    setSending(false);
    if (result.ok) {
      focusComment.current = result.data.id;
      setDraft('');
      await load.reload();
      toast(internal ? '내부 메모를 남겼습니다' : '답변을 보냈습니다');
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
    if (result.failure.kind === 'unauthenticated') {
      replaceUpload(id, { state: 'failed', error: TEXT.saveSessionEnded });
      return;
    }
    replaceUpload(id, { state: 'failed', error: uploadFailureText(result.failure) });
  };

  const onSelect = (accepted: File[], rejected: { file: File; reason: keyof typeof REJECT_TEXT }[]) => {
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
    const result = await call(api.DELETE('/api/console/issues/{key}/attachments/{id}', { params: { path: { key, id: target.id } } }));
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
    uploaderLabel: a.uploadedBy.name,
    createdAt: a.createdAt,
    internal: a.internal,
    deleted: a.deleted,
    canDelete: !a.deleted && (isAdmin || a.uploadedBy.accountId === me?.id),
    deleting: deleting === a.id,
    ...(a.deleted ? {} : { href: attachmentHref(key, a.id) }),
  }));

  const comments = byTime(detail.comments);
  const internal = visibility === 'internal';

  return (
    <div className="pd-screen pd-detail">
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
              <StatusBadge kind="issue" status={issue.status} audience="agent" />
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
            <dd>{issue.reporter.name}</dd>
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
                  비공개
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
          internalLabel="내부"
          onDelete={(id) => {
            setDeleteTarget(detail.attachments.find((a) => a.id === id) ?? null);
          }}
          onDownload={onDownload}
        />
        {!closed && (
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
                author={{ kind: 'person', name: c.author.name }}
                visibility={c.internal ? 'internal' : 'public'}
                createdAt={c.createdAt}
                body={c.content}
                internalLabel="내부 메모"
              />
            ))}
          </div>
        )}
      </section>

      {closed ? (
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
      ) : (
        <form className="pd-comment-form" aria-label="댓글 쓰기" noValidate onSubmit={onSubmit} data-internal={internal ? 'true' : undefined}>
          <RadioGroup
            legend="댓글 종류"
            legendHidden
            appearance="tabs"
            options={VISIBILITIES}
            value={visibility}
            onChange={setVisibility}
            renderAdornment={(o) => (o.value === 'internal' ? <LockIcon size={14} /> : null)}
          />
          <SaveFailure failure={sendFailure} known={{ ISSUE_CLOSED: ISSUE_CLOSED_TEXT }} />
          <RateLimitBand {...cooldown} />
          <MarkdownEditor
            ref={editorRef}
            id="comment-content"
            label={internal ? '내부 메모 내용' : '답변 내용'}
            labelHidden
            value={draft}
            onChange={setDraft}
            minHeight={112}
            maxLength={4000}
            showCount
            internal={internal}
            placeholder={internal ? '상담 쪽만 보는 메모를 남깁니다.' : '고객에게 보낼 답변을 씁니다.'}
            helpText="Ctrl+Enter(맥은 Cmd+Enter)로 보냅니다."
            onSubmitShortcut={() => {
              void send();
            }}
            {...(fields.errors.content ? { error: fields.errors.content } : {})}
          />
          <div className="pd-form-actions">
            <Button
              type="submit"
              variant={internal ? 'internal' : 'primary'}
              pending={sending}
              pendingLabel={TEXT.sending}
              blocked={cooldown.active}
            >
              {internal ? '내부 메모 남기기' : '고객에게 답변 보내기'}
            </Button>
          </div>
        </form>
      )}

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
