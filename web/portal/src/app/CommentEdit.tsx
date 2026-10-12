// 댓글 고치기 (4-talk screens.md 2절): the editor that takes the place of a comment's body.
// The portal has no "알릴 사람": the request holds the content alone (S4-AC-25).
import { Button, MarkdownEditor } from '@popdesk/ui';
import { useEffect, useLayoutEffect, useRef, useState, type SubmitEvent } from 'react';
import { api, type Schemas } from '../api';
import { RateLimitBand, SaveFailure } from './bands';
import { useCooldown, useFieldErrors, useLive } from './hooks';
import { t } from './locale';
import { TEXT } from './messages';
import { call, type Failure } from './result';

type Comment = Schemas['Comment'];

/** Why the comment can no longer be edited: the issue closed, the comment was deleted, or it went out of sight. */
export type EditGone = 'closed' | 'deleted' | 'notFound';

export interface CommentEditProps {
  issueKey: string;
  comment: Comment;
  /** The text differs from the comment: the screen asks before it throws it away. */
  onDirtyChange: (dirty: boolean) => void;
  /** "고치기 취소". The screen asks first when there is something to lose. */
  onCancel: () => void;
  /** Nothing was changed: closed without a request. */
  onUnchanged: () => void;
  onSaved: () => void;
  /** The server no longer takes the edit. `draft` is what was typed, for "보내지 못한 글". */
  onGone: (why: EditGone, draft: string) => void;
}

const EDITOR_ID = 'comment-edit-content';

export function CommentEdit(props: CommentEditProps) {
  const { comment, onDirtyChange } = props;
  const live = useLive();
  const [draft, setDraft] = useState(comment.content);
  const [sending, setSending] = useState(false);
  const [failure, setFailure] = useState<Failure | null>(null);
  const cooldown = useCooldown();
  const fields = useFieldErrors({ content: { id: EDITOR_ID, limits: { min: 1, max: 4000 } } });
  const editor = useRef<HTMLTextAreaElement>(null);

  const dirty = draft !== comment.content;
  useEffect(() => {
    onDirtyChange(dirty);
  }, [dirty, onDirtyChange]);

  useLayoutEffect(() => {
    editor.current?.focus();
  }, []);

  const save = async () => {
    if (sending || cooldown.active) return;
    setFailure(null);
    cooldown.clearEnded();
    fields.clear();
    if (!dirty) {
      props.onUnchanged();
      return;
    }
    if (draft.trim() === '') {
      fields.setError('content', '내용을 넣어 주세요.');
      return;
    }
    setSending(true);
    const result = await call(
      api.PATCH('/api/portal/issues/{key}/comments/{id}', {
        params: { path: { key: props.issueKey, id: comment.id } },
        body: { content: draft },
      }),
    );
    // The screen has gone: no toast, nothing read again (S4-AC-69).
    if (!live()) return;
    setSending(false);
    if (result.ok) {
      props.onSaved();
      return;
    }
    const f = result.failure;
    if (f.kind === 'invalid') {
      fields.applyFields(f.fields, f.message);
    } else if (f.kind === 'rateLimited') {
      cooldown.start(f.seconds);
    } else if (f.kind === 'rejected' && f.code === 'ISSUE_CLOSED') {
      props.onGone('closed', draft);
    } else if (f.kind === 'rejected' && f.code === 'COMMENT_DELETED') {
      props.onGone('deleted', draft);
    } else if (f.kind === 'notFound') {
      props.onGone('notFound', draft);
    } else {
      setFailure(f);
    }
  };

  const onSubmit = (e: SubmitEvent) => {
    e.preventDefault();
    void save();
  };

  return (
    <form className="pd-comment-edit" aria-label={t('comment.edit')} noValidate onSubmit={onSubmit}>
      <SaveFailure failure={failure} />
      <RateLimitBand {...cooldown} />
      <MarkdownEditor
        ref={editor}
        id={EDITOR_ID}
        label={t('comment.editContent')}
        labelHidden
        writeLabel={t('comment.editWrite')}
        previewLabel={t('comment.editPreview')}
        toolbarLabel={t('comment.editToolbar')}
        value={draft}
        onChange={setDraft}
        minHeight={112}
        maxLength={4000}
        showCount
        onSubmitShortcut={() => {
          void save();
        }}
        {...(fields.errors.content ? { error: fields.errors.content } : {})}
      />
      <div className="pd-form-actions">
        <Button variant="secondary" size="sm" onClick={props.onCancel}>
          {t('comment.cancelEdit')}
        </Button>
        <Button type="submit" size="sm" pending={sending} pendingLabel={TEXT.sending} blocked={cooldown.active}>
          {t('comment.saveEdit')}
        </Button>
      </div>
    </form>
  );
}
