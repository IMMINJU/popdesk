export interface AttachmentItem {
  id: string;
  name: string;
  /** Bytes. */
  size: number;
  /** Download link. Absent on a deleted attachment. */
  href?: string;
  /** 올린 사람, already "지원팀" or "나" where the screen needs it (D-49). */
  uploaderLabel: string;
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
}

export function AttachmentList(props: AttachmentListProps) {
  return (
    <section data-pd="AttachmentList">
      <h2>{props.title}</h2>
      {props.items.length === 0 ? (
        <p>{props.emptyText}</p>
      ) : (
        <ul>
          {props.items.map((item) => (
            <li key={item.id} data-deleted={item.deleted} data-internal={item.internal}>
              {item.href && !item.deleted ? <a href={item.href}>{item.name}</a> : item.name}
            </li>
          ))}
        </ul>
      )}
    </section>
  );
}
