import type { ReactNode, Ref } from 'react';
import { LockIcon } from '../icons';
import { Avatar } from './Avatar';
import { MarkdownView } from './MarkdownView';
import { RelativeTime } from './RelativeTime';

/**
 * Who wrote it. The portal API gives no name for agents (ANON-1, D-49), so
 * the portal passes `support` and shows "지원팀".
 */
export type CommentAuthor = { kind: 'person'; name: string; isMe?: boolean } | { kind: 'support' };

export interface CommentItemProps {
  /** Element id, so the screen can scroll and focus a new comment. */
  id?: string;
  author: CommentAuthor;
  /** 공개, 내부 (whole box internal-bg, lock and "내부 메모" in the head, no left stripe). */
  visibility: 'public' | 'internal';
  /** ISO 8601. */
  createdAt: string;
  /** Markdown. Ignored when `deleted`. */
  body: string;
  /** 지워짐: a trace instead of the body. */
  deleted?: boolean;
  /** Attachments of this comment, usually an AttachmentList. */
  attachments?: ReactNode;
  /** Texts the shell needs before i18n: "지원팀", "나", "내부 메모", "지워진 댓글". */
  supportLabel?: string;
  meLabel?: string;
  internalLabel?: string;
  deletedLabel?: string;
  /** `tabIndex={-1}` target after sending (screens.md P-5). */
  ref?: Ref<HTMLElement>;
}

/** No card border; comments are split by space. */
export function CommentItem({ ref, ...props }: CommentItemProps) {
  const authorText =
    props.author.kind === 'support'
      ? (props.supportLabel ?? 'Support')
      : props.author.isMe
        ? (props.meLabel ?? props.author.name)
        : props.author.name;
  return (
    <article
      ref={ref}
      id={props.id}
      tabIndex={-1}
      className="pd-comment"
      data-pd="CommentItem"
      data-visibility={props.visibility}
      data-deleted={props.deleted ? 'true' : undefined}
    >
      <header className="pd-comment-head">
        <Avatar name={authorText} kind={props.author.kind === 'support' ? 'support' : 'person'} size="sm" decorative />
        <span className="pd-comment-author">{authorText}</span>
        {props.visibility === 'internal' && (
          <span className="pd-comment-internal">
            <LockIcon size={14} />
            <span>{props.internalLabel ?? 'Internal note'}</span>
          </span>
        )}
        <RelativeTime dateTime={props.createdAt} />
      </header>
      {props.deleted ? (
        <p className="pd-comment-deleted">{props.deletedLabel ?? 'Deleted comment'}</p>
      ) : (
        <div className="pd-comment-body">
          <MarkdownView source={props.body} />
        </div>
      )}
      {props.attachments !== undefined && <div className="pd-comment-attachments">{props.attachments}</div>}
    </article>
  );
}
