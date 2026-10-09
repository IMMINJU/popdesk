import type { MouseEvent, ReactNode } from 'react';

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
  /** Accessible name of a page link, e.g. `(p) => \`${p}쪽\``. Default: the number. */
  pageLabel?: (page: number) => string;
  /** Names of the previous and next links. Without them those links are left out. */
  previousLabel?: string;
  nextLabel?: string;
  /**
   * Draws a link with the app's router. Default: a plain `<a>`. Gets the href,
   * the page, and the props the link must keep (`aria-current`, `aria-label`, class).
   */
  renderLink?: (link: PageLink) => ReactNode;
}

export interface PageLink {
  href: string;
  page: number;
  children: ReactNode;
  className: string;
  'aria-current'?: 'page';
  'aria-label'?: string;
  onClick: (e: MouseEvent<HTMLAnchorElement>) => void;
}

/** Pages to show: first, last, and two on each side of the current one; gaps become "…". */
function pagesAround(page: number, count: number): (number | 'gap')[] {
  const wanted = new Set([1, count, page - 2, page - 1, page, page + 1, page + 2]);
  const pages = [...wanted].filter((p) => p >= 1 && p <= count).sort((a, b) => a - b);
  const out: (number | 'gap')[] = [];
  let last = 0;
  for (const p of pages) {
    if (p - last === 2) out.push(p - 1);
    else if (p - last > 2) out.push('gap');
    out.push(p);
    last = p;
  }
  return out;
}

/** Hidden when there is one page. The current page has `aria-current="page"`. */
export function Pagination(props: PaginationProps) {
  if (props.pageCount <= 1) return null;
  const draw = (link: PageLink) =>
    props.renderLink ? (
      props.renderLink(link)
    ) : (
      <a
        href={link.href}
        className={link.className}
        aria-current={link['aria-current']}
        aria-label={link['aria-label']}
        onClick={link.onClick}
      >
        {link.children}
      </a>
    );
  const linkTo = (page: number, children: ReactNode, extra: Partial<PageLink> = {}): PageLink => ({
    href: props.getHref(page),
    page,
    children,
    className: 'pd-page',
    onClick: () => props.onPageChange?.(page),
    ...extra,
  });
  return (
    <nav className="pd-pagination" data-pd="Pagination" aria-label={props.label}>
      <ul>
        {props.previousLabel !== undefined && props.page > 1 && (
          <li>{draw(linkTo(props.page - 1, '‹', { 'aria-label': props.previousLabel, className: 'pd-page pd-page-step' }))}</li>
        )}
        {pagesAround(props.page, props.pageCount).map((p, i) =>
          p === 'gap' ? (
            <li key={`gap-${String(i)}`} className="pd-page-gap" aria-hidden="true">
              …
            </li>
          ) : (
            <li key={p}>
              {draw(
                linkTo(p, p, {
                  ...(p === props.page ? { 'aria-current': 'page' as const } : {}),
                  ...(props.pageLabel ? { 'aria-label': props.pageLabel(p) } : {}),
                }),
              )}
            </li>
          ),
        )}
        {props.nextLabel !== undefined && props.page < props.pageCount && (
          <li>{draw(linkTo(props.page + 1, '›', { 'aria-label': props.nextLabel, className: 'pd-page pd-page-step' }))}</li>
        )}
      </ul>
    </nav>
  );
}
