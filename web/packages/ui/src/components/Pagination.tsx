export interface PaginationProps {
  /** 1-based. */
  page: number;
  pageCount: number;
  /** Link for a page. Pages are links so they can open in a new tab. */
  getHref: (page: number) => string;
  /** Called on click before navigation, e.g. to move focus to the list heading. */
  onPageChange?: (page: number) => void;
  /** `nav` name, e.g. "쪽 넘기기". */
  label: string;
}

/** Hidden when there is one page. The current page has `aria-current="page"`. */
export function Pagination(props: PaginationProps) {
  if (props.pageCount <= 1) return null;
  const pages = Array.from({ length: props.pageCount }, (_, i) => i + 1);
  return (
    <nav data-pd="Pagination" aria-label={props.label}>
      {pages.map((p) => (
        <a
          key={p}
          href={props.getHref(p)}
          aria-current={p === props.page ? 'page' : undefined}
          onClick={() => props.onPageChange?.(p)}
        >
          {p}
        </a>
      ))}
    </nav>
  );
}
