// screens.md 5절 15 Pagination. 처음, 가운데, 끝, 한 쪽뿐(숨김). 지금 쪽 aria-current="page".
import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, test, vi } from 'vitest';
import { expectNoAxeViolations, found } from '../__tests__/support';
import { Pagination } from './Pagination';

const href = (p: number) => `/issues?page=${String(p)}`;
const nav = () => found(() => screen.queryByRole('navigation', { name: '쪽 넘기기' }), 'nav "쪽 넘기기"');
const pageLink = (n: number) => screen.queryByRole('link', { name: new RegExp(`^${String(n)}(쪽)?$`) });

describe('Pagination', () => {
  test('Pagination 한 쪽뿐: 숨긴다', () => {
    const { container } = render(<Pagination page={1} pageCount={1} getHref={href} label="쪽 넘기기" />);

    expect(screen.queryByRole('navigation')).toBeNull();
    expect(container.querySelectorAll('a')).toHaveLength(0);
  });

  test('Pagination 가운데: nav 이름은 label, 지금 쪽만 aria-current="page", 쪽마다 주소', async () => {
    render(<Pagination page={2} pageCount={3} getHref={href} label="쪽 넘기기" />);

    const n = await nav();
    const current = within(n).getAllByRole('link').filter((a) => a.getAttribute('aria-current') === 'page');
    expect(current).toHaveLength(1);
    expect(current[0]).toBe(pageLink(2));
    expect(pageLink(3)).toHaveAttribute('href', '/issues?page=3');
  });

  test('Pagination 누름: onPageChange(쪽)를 부른다', async () => {
    const onPageChange = vi.fn();
    render(
      <Pagination
        page={1}
        pageCount={3}
        getHref={href}
        label="쪽 넘기기"
        onPageChange={(p) => {
          onPageChange(p);
        }}
      />,
    );
    const user = userEvent.setup();
    await nav();

    const two = await found(() => pageLink(2), '2쪽 링크');
    two.addEventListener('click', (e) => {
      e.preventDefault();
    });
    await user.click(two);
    expect(onPageChange).toHaveBeenCalledWith(2);
  });

  test('Pagination axe: 가운데 쪽에 접근성 위반이 없다', async () => {
    render(<Pagination page={2} pageCount={3} getHref={href} label="쪽 넘기기" />);
    await nav();

    await expectNoAxeViolations();
  });
});
