import type { ReactNode } from 'react';
import type { IssueStatus, Priority } from '../types';

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
}

/** Card list for P-3 at mobile width. Each card is one link. */
export function IssueList(props: IssueListProps) {
  const status = props.status ?? 'ready';
  if (status === 'loading') {
    return (
      <ul data-pd="IssueList" aria-busy="true">
        {Array.from({ length: props.skeletonCount ?? 5 }, (_, i) => (
          <li key={i} />
        ))}
      </ul>
    );
  }
  if (props.items.length === 0) return <div data-pd="IssueList">{props.empty}</div>;
  return (
    <ul data-pd="IssueList">
      {props.items.map((item) => (
        <li key={item.key}>
          <a href={item.href}>
            {item.key} {item.title}
          </a>
        </li>
      ))}
    </ul>
  );
}
