// 댓글 고치기 (4-talk screens.md 2절): the shared editor with the console's PATCH and
// "알릴 사람" under it. Only what changed is sent.
import { CommentEdit as CommentEditWith, type CommentEditProps as SharedProps } from '@popdesk/app/CommentEdit';
import { api, type Schemas } from '../api';
import { imagePaths } from './attachments';
import { MentionPicker } from './MentionPicker';
import { useMentions } from './mentions';
import { call } from './result';

export type { EditGone } from '@popdesk/app/CommentEdit';

type Comment = Schemas['Comment'];

export interface CommentEditProps extends Pick<SharedProps, 'onDirtyChange' | 'onCancel' | 'onUnchanged' | 'onSaved' | 'onGone'> {
  issueKey: string;
  comment: Comment;
  /** The issue's reporter: the one customer who can be mentioned, on a public comment. */
  reporterId: number;
}

export function CommentEdit(props: CommentEditProps) {
  const { issueKey, comment, reporterId, ...rest } = props;
  // The candidates are those of the comment's own kind, which an edit cannot change (S4-AC-07).
  const mentions = useMentions(issueKey, comment.internal, reporterId, (comment as Partial<Comment>).mentions ?? []);
  return (
    <CommentEditWith
      {...rest}
      comment={comment}
      alsoDirty={mentions.changed}
      images={imagePaths(issueKey)}
      save={(content, images) =>
        call(
          api.PATCH('/api/console/issues/{key}/comments/{id}', {
            params: { path: { key: issueKey, id: comment.id } },
            body: { ...(content === comment.content ? {} : { content }), ...(mentions.changed ? { mentions: mentions.ids } : {}), ...images },
          }),
        )
      }
      takeFields={(fields) => {
        const refused = fields.find((p) => p.field === 'mentions');
        if (refused) mentions.refused(refused.code);
        return fields.filter((p) => p.field !== 'mentions');
      }}
    >
      <MentionPicker id="comment-edit-mentions" mentions={mentions} />
    </CommentEditWith>
  );
}
