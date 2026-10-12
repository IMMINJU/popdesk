// C-4 고치기 (3-issues screens.md 2절, S3-AC-81): title, content and priority as
// inputs in place. The save carries the version that was read; a 409 keeps what
// was typed and shows what the issue holds now.
import { Alert, Button, ConfirmDialog, MarkdownEditor, PriorityLabel, PriorityPicker, StatusBadge, TextField, type Priority } from '@popdesk/ui';
import { useEffect, useId, useRef, useState, type SubmitEvent } from 'react';
import { api, type Schemas } from '../api';
import { FieldSummary, SaveFailure } from './bands';
import { Disclosure } from './Disclosure';
import { useFieldErrors, useLive } from './hooks';
import { t } from './locale';
import { rateLimitedText, TEXT } from './messages';
import { call, type Failure } from './result';
import { useToast } from './toastContext';

type Issue = Schemas['Issue'];

export interface IssueEditProps {
  /** The issue as last read. After a 409 this is what it holds now. */
  issue: Issue;
  /** Reads the issue again and gives what came. */
  reload: () => Promise<Issue | undefined>;
  onSaved: () => void;
  /** Back to reading, nothing saved. */
  onCancel: () => void;
  /** Something typed differs from what was read: the screen asks before leaving (UX-4). */
  onDirtyChange: (dirty: boolean) => void;
}

export function IssueEdit(props: IssueEditProps) {
  const toast = useToast();
  const live = useLive();
  const nowId = useId();
  // What was read when 고치기 was pressed. Only what differs from it is sent, also after a 409:
  // a field left alone must not overwrite what somebody else saved meanwhile.
  const [base] = useState(() => ({ title: props.issue.title, content: props.issue.content, priority: props.issue.priority }));
  const [version, setVersion] = useState(props.issue.version);
  const [title, setTitle] = useState(base.title);
  const [content, setContent] = useState(base.content);
  const [priority, setPriority] = useState<Priority>(base.priority);
  const [pending, setPending] = useState(false);
  const [failure, setFailure] = useState<Failure | null>(null);
  const [conflict, setConflict] = useState(false);
  const [closed, setClosed] = useState(false);
  const [asking, setAsking] = useState(false);
  const titleRef = useRef<HTMLInputElement>(null);
  const fields = useFieldErrors({
    title: { id: 'edit-title', limits: { min: 1, max: 200 } },
    content: { id: 'edit-content', limits: { max: 10000 } },
    priority: { id: 'edit-priority' },
  });

  const dirty = title !== base.title || content !== base.content || priority !== base.priority;
  const { onDirtyChange } = props;
  useEffect(() => {
    onDirtyChange(dirty);
  }, [dirty, onDirtyChange]);
  useEffect(() => {
    titleRef.current?.focus();
  }, []);

  const submit = async (e: SubmitEvent) => {
    e.preventDefault();
    if (pending || !dirty || closed) return;
    setPending(true);
    setFailure(null);
    fields.clear();
    const result = await call(
      api.PATCH('/api/console/issues/{key}', {
        params: { path: { key: props.issue.key } },
        body: {
          version,
          ...(title === base.title ? {} : { title }),
          ...(content === base.content ? {} : { content }),
          ...(priority === base.priority ? {} : { priority }),
        },
      }),
    );
    if (!live()) return;
    if (result.ok) {
      toast(t('issueEdit.saved'));
      props.onSaved();
      return;
    }
    const f = result.failure;
    if (f.kind === 'rejected' && f.code === 'VERSION_CONFLICT') {
      // Somebody else changed the issue: read it again and save with its version next time.
      const now = await props.reload();
      if (!live()) return;
      if (now) setVersion(now.version);
      setConflict(true);
    } else if (f.kind === 'rejected' && f.code === 'ISSUE_CLOSED') {
      // Closed while editing. The text stays so it can be copied elsewhere.
      setClosed(true);
      setConflict(false);
      void props.reload();
    } else if (f.kind === 'invalid') {
      fields.applyFields(f.fields, f.message);
    } else {
      setFailure(f);
    }
    setPending(false);
  };

  const now = props.issue;
  return (
    <form
      className="pd-form pd-issue-edit"
      aria-label={t('issueEdit.formName')}
      noValidate
      onSubmit={(e) => {
        void submit(e);
      }}
    >
      <FieldSummary summary={fields.summary} />
      {closed && <Alert tone="error">{t('issueEdit.closed')}</Alert>}
      {failure?.kind === 'rateLimited' ? <Alert tone="error">{rateLimitedText(failure.seconds)}</Alert> : <SaveFailure failure={failure} />}
      {conflict && (
        <>
          <Alert tone="error">{t('issueEdit.conflict')}</Alert>
          <div className="pd-now-values" role="group" aria-labelledby={nowId}>
            <p id={nowId} className="pd-label">
              {t('issueEdit.now')}
            </p>
            <dl className="pd-meta">
              <div>
                <dt>제목</dt>
                <dd>{now.title}</dd>
              </div>
              <div>
                <dt>우선순위</dt>
                <dd>
                  <PriorityLabel priority={now.priority} />
                </dd>
              </div>
              <div>
                <dt>상태</dt>
                <dd>
                  <StatusBadge kind="issue" status={now.status} audience="agent" />
                </dd>
              </div>
            </dl>
            <Disclosure label={t('issueEdit.nowContent')}>
              <pre className="pd-plain-text">{now.content}</pre>
            </Disclosure>
          </div>
        </>
      )}
      <TextField
        ref={titleRef}
        id="edit-title"
        label="제목"
        value={title}
        onChange={setTitle}
        maxLength={200}
        {...(fields.errors.title ? { error: fields.errors.title } : {})}
      />
      <MarkdownEditor
        id="edit-content"
        label="내용"
        value={content}
        onChange={setContent}
        minHeight={240}
        maxLength={10000}
        showCount
        {...(fields.errors.content ? { error: fields.errors.content } : {})}
      />
      <div id="edit-priority">
        <PriorityPicker legend="우선순위" value={priority} onChange={setPriority} {...(fields.errors.priority ? { error: fields.errors.priority } : {})} />
      </div>
      <div className="pd-form-actions">
        <Button
          variant="secondary"
          onClick={() => {
            if (dirty) setAsking(true);
            else props.onCancel();
          }}
        >
          {TEXT.cancel}
        </Button>
        <Button type="submit" disabled={!dirty} blocked={closed} pending={pending} pendingLabel={TEXT.sending}>
          {t('issueEdit.save')}
        </Button>
      </div>
      <ConfirmDialog
        open={asking}
        title={TEXT.leaveTitle}
        message={t('issueEdit.cancelMessage')}
        confirmLabel={t('issueEdit.discard')}
        cancelLabel={t('issueEdit.keep')}
        tone="danger"
        onConfirm={props.onCancel}
        onCancel={() => {
          setAsking(false);
        }}
      />
    </form>
  );
}
