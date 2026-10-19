import { Pagination } from '@popdesk/ui';
import { Link } from 'react-router';
import { pageHref } from './paging';
import { t, tf } from './locale';

/** Pagination with router links. Hidden when there is one page. */
export function PageNav(props: { path: string; page: number; pageCount: number; onPageChange: () => void; param?: string }) {
  const param = props.param ?? 'page';
  return (
    <Pagination
      label={t('paging.label')}
      page={props.page}
      pageCount={props.pageCount}
      getHref={(p) => pageHref(props.path, p, param)}
      pageLabel={(p) => tf('paging.page', { page: p })}
      previousLabel={t('paging.previous')}
      nextLabel={t('paging.next')}
      onPageChange={props.onPageChange}
      renderLink={(link) => (
        <Link
          to={link.href}
          className={link.className}
          aria-current={link['aria-current']}
          aria-label={link['aria-label']}
          onClick={link.onClick}
        >
          {link.children}
        </Link>
      )}
    />
  );
}
