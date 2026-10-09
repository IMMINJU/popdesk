import type { ReactNode } from 'react';
import { LockIcon } from '../icons';
import type { IssueStatus, Priority } from '../types';
import { PriorityLabel } from './PriorityLabel';
import { RelativeTime } from './RelativeTime';
import { StatusBadge } from './StatusBadge';

/** One issue row. The P-3 table and the mobile card list take the same rows. */
export interface IssueListItem {
  /** e.g. `ACME-7`. */
  key: string;
  title: string;
  href: string;
  projectName: string;
  status: IssueStatus;
  priority: Priority;
  /** 올린 사람, already "나" when it is mine. */
  authorLabel: string;
  isPrivate: boolean;
  /** ISO 8601. */
  createdAt: string;
}

export interface IssueListProps {
  items: readonly IssueListItem[];
  /** 불러오는 중, 데이터. With no items, `ready` shows `empty`. Default `ready`. */
  status?: 'loading' | 'ready';
  /** Skeleton cards while loading. Default 5. */
  skeletonCount?: number;
  empty?: ReactNode;
  /** Hidden text for the lock icon, e.g. "비공개". */
  privateLabel?: string;
  /**
   * Draws the card link with the app's router. Default: a plain `<a>`. The
   * function gets the item and the card content and must return one link.
   */
  renderLink?: (item: IssueListItem, children: ReactNode) => ReactNode;
}

function CardBody(props: { item: IssueListItem; privateLabel: string | undefined }) {
  const { item } = props;
  return (
    <>
      <span className="pd-card-line">
        <StatusBadge kind="issue" status={item.status} audience="customer" />
        <span className="pd-card-key">{item.key}</span>
        {item.isPrivate && (
          <span className="pd-card-private">
            <LockIcon size={14} />
            <span className="pd-sr-only">{props.privateLabel ?? 'Private'}</span>
          </span>
        )}
      </span>
      <span className="pd-card-title">{item.title}</span>
      <span className="pd-card-line pd-card-meta">
        <span>{item.projectName}</span>
        <PriorityLabel priority={item.priority} />
        <RelativeTime dateTime={item.createdAt} focusable={false} />
      </span>
    </>
  );
}

/** Card list for P-3 at mobile width. Each card is one link. */
export function IssueList(props: IssueListProps) {
  const status = props.status ?? 'ready';
  if (status === 'loading') {
    return (
      <ul className="pd-cards" data-pd="IssueList" aria-busy="true">
        {Array.from({ length: props.skeletonCount ?? 5 }, (_, i) => (
          <li key={i} className="pd-card pd-card-skeleton" aria-hidden="true">
            <span className="pd-skeleton-item pd-skeleton-pulse" style={{ width: '40%' }} />
            <span className="pd-skeleton-item pd-skeleton-pulse" style={{ width: '85%' }} />
            <span className="pd-skeleton-item pd-skeleton-pulse" style={{ width: '60%' }} />
          </li>
        ))}
      </ul>
    );
  }
  if (props.items.length === 0) {
    return (
      <div className="pd-cards-empty" data-pd="IssueList">
        {props.empty}
      </div>
    );
  }
  return (
    <ul className="pd-cards" data-pd="IssueList">
      {props.items.map((item) => {
        const body = <CardBody item={item} privateLabel={props.privateLabel} />;
        return (
          <li key={item.key} className="pd-card">
            {props.renderLink ? (
              props.renderLink(item, body)
            ) : (
              <a className="pd-card-link" href={item.href}>
                {body}
              </a>
            )}
          </li>
        );
      })}
    </ul>
  );
}
