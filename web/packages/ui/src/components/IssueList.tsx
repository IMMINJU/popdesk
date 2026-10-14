import type { ReactNode } from 'react';
import { LockIcon } from '../icons';
import type { IssueStatus, LabelItem, Priority } from '../types';
import { LabelChip } from './LabelChip';
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
  /** 라벨, in name order (slice 6a). Not given or empty: no label chips. */
  labels?: readonly LabelItem[];
  /** 마감일 `YYYY-MM-DD` (slice 6a). Not given or `null`: no due date line. */
  dueDate?: string | null;
  /** The server's `overdue`: "지남" after the date as text, not colour alone (S6A-AC-81). */
  overdue?: boolean;
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
  /**
   * Slice 6a. Spoken and `title` text for the "+n" after the first chips, given the hidden
   * names, e.g. "라벨 더: 모바일, 보안".
   */
  moreLabelsText?: (hidden: readonly string[]) => string;
  /** Slice 6a. Before the due date, e.g. "마감일". */
  dueLabel?: string;
  /** Slice 6a. After an overdue date, e.g. "지남". */
  overdueText?: string;
}

/**
 * Slice 6a: label chips and the due date under the title. Drawn only when the item has either.
 *
 * Contract shell: every chip and the date as given. Showing two chips and "+n", the date as
 * "10월 20일" and the "지남" mark come later (C-9).
 */
function CardLabelsAndDue(props: { item: IssueListItem; dueLabel: string | undefined }) {
  const { item } = props;
  const labels = item.labels ?? [];
  const due = item.dueDate ?? null;
  if (labels.length === 0 && due === null) return null;
  return (
    <span className="pd-card-line pd-card-tags">
      {labels.map((l) => (
        <LabelChip key={l.id} name={l.name} color={l.color} />
      ))}
      {due !== null && (
        <span className="pd-card-due">
          {props.dueLabel !== undefined && <span>{props.dueLabel} </span>}
          <time dateTime={due}>{due}</time>
        </span>
      )}
    </span>
  );
}

function CardBody(props: { item: IssueListItem; privateLabel: string | undefined; dueLabel: string | undefined }) {
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
      <CardLabelsAndDue item={item} dueLabel={props.dueLabel} />
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
        const body = <CardBody item={item} privateLabel={props.privateLabel} dueLabel={props.dueLabel} />;
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
