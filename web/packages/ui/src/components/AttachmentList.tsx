import { useId, type MouseEvent } from 'react';
import { formatBytes } from '../format';
import { FileIcon } from '../icons';
import { Avatar } from './Avatar';
import { Badge } from './Badge';
import { Button } from './Button';
import { RelativeTime } from './RelativeTime';

export interface AttachmentItem {
  id: string;
  name: string;
  /** Bytes. */
  size: number;
  /** Download link. Absent on a deleted attachment. */
  href?: string;
  /** 올린 사람, already "지원팀" or "나" where the screen needs it (D-49). */
  uploaderLabel: string;
  /**
   * Shows the uploader's avatar before the label: a photo or initials for a
   * person, the 지원팀 mark for `support` (ACCT-8, D-49). Absent: the label alone.
   */
  uploaderAvatar?: { kind: 'person'; photoUrl: string | null } | { kind: 'support' };
  /** ISO 8601. */
  createdAt: string;
  /** 내부 표시: attachment of an internal comment (C-4). */
  internal?: boolean;
  /** 지워진 흔적 (ATT-5, COM-7). */
  deleted?: boolean;
  /** Show the delete button (own uploads; admins see it on all in C-4). */
  canDelete?: boolean;
  /** 지우는 중. */
  deleting?: boolean;
}

export interface AttachmentListProps {
  /** Section heading, e.g. "첨부". */
  title: string;
  items: readonly AttachmentItem[];
  /** The screen asks with a ConfirmDialog first. */
  onDelete?: (id: string) => void;
  /** 비어 있음 text, e.g. "첨부 없음". */
  emptyText: string;
  deleteLabel?: string;
  deletedLabel?: string;
  internalLabel?: string;
  /**
   * Called when a download link is clicked. The screen may stop the default
   * and ask the server first (`HEAD`, screens.md P-5 내려받기).
   */
  onDownload?: (item: AttachmentItem, event: MouseEvent<HTMLAnchorElement>) => void;
  /** Default 2. */
  headingLevel?: 2 | 3;
}

/** 데이터, 비어 있음, 지워진 흔적, 내부 표시, 지우는 중. */
export function AttachmentList(props: AttachmentListProps) {
  const id = useId();
  const headingId = `${id}-title`;
  const Heading = props.headingLevel === 3 ? 'h3' : 'h2';
  return (
    <section className="pd-attachments" data-pd="AttachmentList" aria-labelledby={headingId}>
      <Heading id={headingId} className="pd-section-title">
        {props.title}
      </Heading>
      {props.items.length === 0 ? (
        <p className="pd-attachments-empty">{props.emptyText}</p>
      ) : (
        <ul className="pd-attachments-list">
          {props.items.map((item) => {
            const nameId = `${id}-${item.id}`;
            if (item.deleted) {
              return (
                <li key={item.id} className="pd-attachment" data-deleted="true">
                  <span className="pd-attachment-icon" aria-hidden="true">
                    <FileIcon />
                  </span>
                  <span className="pd-attachment-deleted">{props.deletedLabel ?? 'Deleted attachment'}</span>
                </li>
              );
            }
            return (
              <li key={item.id} className="pd-attachment" data-internal={item.internal ? 'true' : undefined}>
                <span className="pd-attachment-icon" aria-hidden="true">
                  <FileIcon />
                </span>
                <span className="pd-attachment-main">
                  <span className="pd-attachment-name" id={nameId}>
                    {item.href ? (
                      <a
                        href={item.href}
                        onClick={(e) => {
                          props.onDownload?.(item, e);
                        }}
                      >
                        {item.name}
                      </a>
                    ) : (
                      item.name
                    )}
                  </span>
                  <span className="pd-attachment-meta">
                    {item.internal && <Badge tone="internal">{props.internalLabel ?? 'Internal'}</Badge>}
                    <span>{formatBytes(item.size)}</span>
                    {item.uploaderAvatar ? (
                      <span className="pd-attachment-uploader">
                        {item.uploaderAvatar.kind === 'support' ? (
                          <Avatar name={item.uploaderLabel} kind="support" size="sm" decorative />
                        ) : (
                          <Avatar name={item.uploaderLabel} photoUrl={item.uploaderAvatar.photoUrl} size="sm" decorative />
                        )}
                        <span>{item.uploaderLabel}</span>
                      </span>
                    ) : (
                      <span>{item.uploaderLabel}</span>
                    )}
                    <RelativeTime dateTime={item.createdAt} focusable={false} />
                  </span>
                </span>
                {item.canDelete && props.onDelete && (
                  <Button
                    variant="quiet"
                    size="sm"
                    pending={item.deleting ?? false}
                    describedBy={nameId}
                    onClick={() => {
                      props.onDelete?.(item.id);
                    }}
                  >
                    {props.deleteLabel ?? 'Delete'}
                  </Button>
                )}
              </li>
            );
          })}
        </ul>
      )}
    </section>
  );
}
