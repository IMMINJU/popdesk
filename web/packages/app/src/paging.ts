// Paged lists (COM-4, screens.md P-3 쪽 넘기기): the page lives in `?page=`,
// moving pages focuses the list heading and says "2쪽, 45건 가운데 21~40" once.
import { useLayoutEffect, useRef, useState } from 'react';
import { useSearchParams } from 'react-router';

export const PAGE_SIZE = 20;

export interface PageInfo {
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
  items: readonly unknown[];
}

/** The app's text for the line read after a page change. */
export type PageAnnouncement = (page: number, size: number, total: number, shown: number) => string;

/** The page in the address, from 1. Anything else is page 1. */
export function usePageParam(name = 'page'): number {
  const [params] = useSearchParams();
  const raw = Number(params.get(name) ?? '1');
  return Number.isInteger(raw) && raw >= 1 ? raw : 1;
}

/**
 * After a page change: once the new page is in, focus `heading` and set the
 * announcement text for a `role="status"` line.
 */
export function usePageMove(info: PageInfo | undefined, heading: { current: HTMLElement | null }, pageAnnouncement: PageAnnouncement) {
  const moved = useRef(false);
  const [announcement, setAnnouncement] = useState('');
  const page = info?.page;
  useLayoutEffect(() => {
    if (!moved.current || !info) return;
    moved.current = false;
    heading.current?.focus();
    setAnnouncement(pageAnnouncement(info.page, info.size, info.totalElements, info.items.length));
    // Runs when a new page arrives.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [page, info]);
  return {
    announcement,
    /** Pagination onPageChange. */
    onPageChange: () => {
      moved.current = true;
    },
  };
}

/** Link for page `p` of `path`, keeping other query values. Page 1 has no `page`. */
export function pageHref(path: string, p: number, name = 'page'): string {
  const params = new URLSearchParams(window.location.search);
  if (p === 1) params.delete(name);
  else params.set(name, String(p));
  const query = params.toString();
  return query === '' ? path : `${path}?${query}`;
}
