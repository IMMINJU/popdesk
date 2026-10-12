// The shared paging hooks with this app's announcement text.
import { usePageMove as usePageMoveWith, type PageInfo } from '@popdesk/app/paging';
import { pageAnnouncement } from './messages';

export { PAGE_SIZE, pageHref, usePageParam, type PageInfo } from '@popdesk/app/paging';

export function usePageMove(info: PageInfo | undefined, heading: { current: HTMLElement | null }) {
  return usePageMoveWith(info, heading, pageAnnouncement);
}
