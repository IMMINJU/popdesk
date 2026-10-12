import type { ReactNode, Ref } from 'react';
import { LockIcon } from '../icons';
import { Avatar } from './Avatar';
import { MarkdownView } from './MarkdownView';
import { RelativeTime } from './RelativeTime';

/**
 * Who wrote it. The portal API gives no name for agents (ANON-1, D-49), so
 * the portal passes `support` and shows "지원팀".
 */
export type CommentAuthor =
  | {
      kind: 'person';
      name: string;
      isMe?: boolean;
      /** Photo of the author (ACCT-8). Absent or `null` shows the initials. */
      photoUrl?: string | null;
    }
  | { kind: 'support' };

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
  /**
   * 고침 (slice 4): ISO 8601 of the last change of the content. Draws `editedLabel` and this time
   * after the written time. Absent or `null`: nothing.
   */
  editedAt?: string | null;
  /** "고침". */
  editedLabel?: string;
  /**
   * 멘션 (console): the names of the mentioned people, drawn as one line under the body,
   * "알림: 김상담, 이관리". No photos. Absent or empty: no line. Ignored when `deleted`.
   */
  mentions?: readonly string[];
  /** The head of the mention line, "알림". */
  mentionsLabel?: string;
  /**
   * 동작: what the reader may do with this comment, usually the buttons "댓글 고치기" and
   * "댓글 지우기", at the end of the head. The screen decides who gets which. Ignored when `deleted`.
   * With an `id`, a button joins the author and the time with `commentActionDescribedBy(id)`.
   */
  actions?: ReactNode;
  /** 고치는 중: drawn in place of the body, usually the form with the editor. Ignored when `deleted`. */
  editing?: ReactNode;
  /** 지운 사람, already a sentence, e.g. "김관리 님이 지움" (console). Drawn after `deletedLabel` when `deleted`. */
  deletedByLabel?: string;
  /** ISO 8601 of the deletion. Drawn at the end of the trace when `deleted`. Absent or `null`: nothing. */
  deletedAt?: string | null;
  /** `tabIndex={-1}` target after sending (screens.md P-5). */
  ref?: Ref<HTMLElement>;
}

/**
 * No card border; comments are split by space.
 *
 * Slice 4 contract shell: 고침, 멘션, 동작, 고치는 중 and the 지운 사람 line are drawn where they
 * belong, without styles of their own yet (C-9). Without those props the markup is what it was.
 */
export function CommentItem({ ref, ...props }: CommentItemProps) {
  const actions = props.deleted ? undefined : props.actions;
  // The ids the action buttons point at exist only where there are actions.
  const described = actions !== undefined && props.id !== undefined ? props.id : undefined;
  const written = <RelativeTime dateTime={props.createdAt} />;
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
        {props.author.kind === 'support' ? (
          <Avatar name={authorText} kind="support" size="sm" decorative />
        ) : (
          <Avatar name={authorText} photoUrl={props.author.photoUrl ?? null} size="sm" decorative />
        )}
        <span className="pd-comment-author" id={described === undefined ? undefined : `${described}-author`}>
          {authorText}
        </span>
        {props.visibility === 'internal' && (
          <span className="pd-comment-internal">
            <LockIcon size={14} />
            <span>{props.internalLabel ?? 'Internal note'}</span>
          </span>
        )}
        {described === undefined ? written : <span id={`${described}-time`}>{written}</span>}
        {props.editedAt != null && (
          <span className="pd-comment-edited">
            {props.editedLabel ?? 'Edited'} <RelativeTime dateTime={props.editedAt} focusable={false} />
          </span>
        )}
        {actions !== undefined && <div className="pd-comment-actions">{actions}</div>}
      </header>
      {props.deleted ? (
        <p className="pd-comment-deleted">
          {props.deletedLabel ?? 'Deleted comment'}
          {props.deletedByLabel !== undefined && <span className="pd-comment-deleted-by"> {props.deletedByLabel}</span>}
          {props.deletedAt != null && (
            <>
              {' '}
              <RelativeTime dateTime={props.deletedAt} focusable={false} />
            </>
          )}
        </p>
      ) : props.editing !== undefined ? (
        <div className="pd-comment-editing">{props.editing}</div>
      ) : (
        <div className="pd-comment-body">
          <MarkdownView source={props.body} />
        </div>
      )}
      {!props.deleted && props.mentions !== undefined && props.mentions.length > 0 && (
        <p className="pd-comment-mentions">
          {props.mentionsLabel ?? 'Notified'}: {props.mentions.join(', ')}
        </p>
      )}
      {props.attachments !== undefined && <div className="pd-comment-attachments">{props.attachments}</div>}
    </article>
  );
}
