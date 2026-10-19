// 댓글 고치기 (4-talk screens.md 2절): the editor that takes the place of a comment's body.
// The PATCH is the app's own call. What an app edits besides the text (the console's
// "알릴 사람") is drawn as `children` and joins through `alsoDirty` and `takeFields`.
import { Button, MarkdownEditor } from '@popdesk/ui';
import { useEffect, useLayoutEffect, useRef, useState, type ReactNode, type SubmitEvent } from 'react';
import { IMAGE_SAVE_TEXT, useEditorImages, type ImageIds, type ImagePaths } from './bodyImages';
import { RateLimitBand, SaveFailure } from './bands';
import { useCooldown, useFieldErrors, useLive } from './hooks';
import { t } from './locale';
import { TEXT } from './messages';
import type { Failure, FieldProblem, Result } from './result';

/** Why the comment can no longer be edited: the issue closed, the comment was deleted, or it went out of sight. */
export type EditGone = 'closed' | 'deleted' | 'notFound';

export interface CommentEditProps {
  /** The comment as it is. An internal one keeps the internal look. */
  comment: { id: number; content: string; internal?: boolean };
  /**
   * The app's own PATCH of the comment. `content` is the text as typed, changed or not;
   * `images` holds the `imageIds` of this edit, to spread into the body.
   */
  save: (content: string, images: ImageIds) => Promise<Result<unknown>>;
  /** Slice 5: with it the editor takes images. They are drawn from these paths. */
  images?: ImagePaths;
  /** Something besides the text differs from the comment. */
  alsoDirty?: boolean;
  /** Takes the field errors of a 400 that are not about the text and gives back the rest. */
  takeFields?: (fields: FieldProblem[]) => FieldProblem[];
  /** Drawn under the editor. */
  children?: ReactNode;
  /** The comment differs from what was read: the screen asks before it throws the changes away. */
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
  const images = useEditorImages(props.images);

  const contentChanged = draft !== comment.content;
  const dirty = contentChanged || props.alsoDirty === true;
  useEffect(() => {
    onDirtyChange(dirty);
  }, [dirty, onDirtyChange]);

  useLayoutEffect(() => {
    editor.current?.focus();
  }, []);

  const save = async () => {
    // An image on its way would land in a text already sent: the save waits, by the button and by Ctrl+Enter.
    if (sending || cooldown.active || images.busy) return;
    setFailure(null);
    cooldown.clearEnded();
    fields.clear();
    if (!dirty) {
      props.onUnchanged();
      return;
    }
    if (contentChanged && draft.trim() === '') {
      fields.setError('content', t('comment.contentRequired'));
      return;
    }
    setSending(true);
    const result = await props.save(draft, images.idsFor(draft));
    // The screen has gone: no toast, nothing read again (S4-AC-69).
    if (!live()) return;
    setSending(false);
    if (result.ok) {
      props.onSaved();
      return;
    }
    const f = result.failure;
    if (f.kind === 'invalid') {
      const rest = props.takeFields?.(f.fields) ?? f.fields;
      if (rest.length > 0) fields.applyFields(rest, f.message);
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
      <SaveFailure failure={failure} known={IMAGE_SAVE_TEXT} />
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
        internal={comment.internal === true}
        onSubmitShortcut={() => {
          void save();
        }}
        {...images.editor}
        {...(fields.errors.content ? { error: fields.errors.content } : {})}
      />
      {props.children}
      <div className="pd-form-actions">
        <Button variant="secondary" size="sm" onClick={props.onCancel}>
          {t('comment.cancelEdit')}
        </Button>
        <Button type="submit" size="sm" variant={comment.internal === true ? 'internal' : 'primary'} pending={sending} pendingLabel={TEXT.sending} blocked={cooldown.active || images.busy}>
          {t('comment.saveEdit')}
        </Button>
      </div>
    </form>
  );
}
