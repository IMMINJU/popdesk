// 댓글 고치기 (4-talk screens.md 2절): the shared editor with the portal's PATCH.
// The portal has no "알릴 사람": the request holds the content alone (S4-AC-25).
import { CommentEdit as CommentEditWith, type CommentEditProps as SharedProps } from '@popdesk/app/CommentEdit';
import { api, type Schemas } from '../api';
import { imagePaths } from './attachments';
import { call } from './result';

export type { EditGone } from '@popdesk/app/CommentEdit';

type Comment = Schemas['Comment'];

export interface CommentEditProps extends Pick<SharedProps, 'onDirtyChange' | 'onCancel' | 'onUnchanged' | 'onSaved' | 'onGone'> {
  issueKey: string;
  comment: Comment;
}

export function CommentEdit(props: CommentEditProps) {
  const { issueKey, comment, ...rest } = props;
  return (
    <CommentEditWith
      {...rest}
      comment={comment}
      images={imagePaths(issueKey)}
      save={(content, images) =>
        call(
          api.PATCH('/api/portal/issues/{key}/comments/{id}', {
            params: { path: { key: issueKey, id: comment.id } },
            body: { content, ...images },
          }),
        )
      }
    />
  );
}
