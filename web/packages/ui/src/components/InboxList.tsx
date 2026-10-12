import type { MouseEvent, ReactNode } from 'react';
import { RelativeTime } from './RelativeTime';

export interface InboxListItem {
  id: string | number;
  /** What happened, one sentence the app wrote, e.g. "김고객 님이 댓글을 남겼습니다". */
  text: string;
  /** Where the item leads. Absent: a line of text only, nothing to press. */
  href?: string;
  /** 안 읽음: bold, a dot before it, and `unreadLabel` in the link's name. */
  unread?: boolean;
  /** The issue it is about, e.g. "ACME-7" and its title now. Absent for an item about no issue. */
  issueKey?: string;
  issueTitle?: string;
  /** ISO 8601. */
  createdAt: string;
}

/** What a link of the list must keep when the app draws it with its router. */
export interface InboxListLink {
  href: string;
  children: ReactNode;
  className: string;
  onClick: (e: MouseEvent<HTMLAnchorElement>) => void;
}

export interface InboxListProps {
  /** In the order the server gave them (newest first). */
  items: readonly InboxListItem[];
  /** Hidden text of an unread item, "안 읽음". Unread is never told by color alone. */
  unreadLabel: string;
  /** Called when an item's link is pressed, before the move, e.g. to mark it read. */
  onOpen?: (item: InboxListItem) => void;
  /** Draws a link with the app's router. Default: a plain `<a>`. */
  renderLink?: (link: InboxListLink) => ReactNode;
}

/**
 * The lines of an inbox (slice 4 screens.md P-7, C-7): a `ul`, one link a line, so one Tab a
 * line. Not a table and no card borders; the lines are split by space and one thin line.
 * The time takes no Tab stop of its own.
 */
export function InboxList(props: InboxListProps) {
  return (
    <ul className="pd-inbox" data-pd="InboxList">
      {props.items.map((item) => {
        const row = (
          <>
            <span className="pd-inbox-dot" aria-hidden="true" />
            <span className="pd-inbox-main">
              <span className="pd-inbox-text">
                {/* The spaces stay outside the spans, or the link's name runs the parts together. */}
                {item.unread && (
                  <>
                    <span className="pd-sr-only">{props.unreadLabel}</span>{' '}
                  </>
                )}
                {item.text}
              </span>{' '}
              {item.issueKey !== undefined && (
                <span className="pd-inbox-issue">
                  <span className="pd-inbox-key">{item.issueKey}</span> {item.issueTitle}
                </span>
              )}
            </span>{' '}
            <RelativeTime dateTime={item.createdAt} focusable={false} />
          </>
        );
        const link: InboxListLink | null =
          item.href === undefined
            ? null
            : {
                href: item.href,
                children: row,
                className: 'pd-inbox-row',
                onClick: () => {
                  props.onOpen?.(item);
                },
              };
        return (
          <li key={item.id} className="pd-inbox-item" data-unread={item.unread ? 'true' : undefined}>
            {link === null ? (
              <div className="pd-inbox-row">{row}</div>
            ) : props.renderLink ? (
              props.renderLink(link)
            ) : (
              <a href={link.href} className={link.className} onClick={link.onClick}>
                {link.children}
              </a>
            )}
          </li>
        );
      })}
    </ul>
  );
}
