import { Pagination } from '@popdesk/ui';
import { Link } from 'react-router';
import { pageHref } from './paging';

/** Pagination with router links. Hidden when there is one page. */
export function PageNav(props: { path: string; page: number; pageCount: number; onPageChange: () => void; param?: string }) {
  const param = props.param ?? 'page';
  return (
    <Pagination
      label="쪽 넘기기"
      page={props.page}
      pageCount={props.pageCount}
      getHref={(p) => pageHref(props.path, p, param)}
      pageLabel={(p) => `${String(p)}쪽`}
      previousLabel="앞 쪽"
      nextLabel="다음 쪽"
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
