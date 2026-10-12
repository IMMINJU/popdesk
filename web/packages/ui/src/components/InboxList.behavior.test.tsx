// 4번 묶음 screens.md 1절 P-7, C-7: 알림함의 줄. 항목 하나가 링크 하나이고, 안 읽음은 링크의 이름으로도 읽힌다.
import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, test, vi } from 'vitest';
import { expectNoAxeViolations } from '../__tests__/support';
import { InboxList, type InboxListItem } from './InboxList';

const ITEMS: InboxListItem[] = [
  { id: 1, text: '김고객 님이 댓글을 남겼습니다', issueKey: 'ACME-7', issueTitle: '로그인이 안 됩니다', href: '/issues/ACME-7#comment-12', unread: true, createdAt: '2026-10-09T01:00:00Z' },
  { id: 2, text: '보내지 못한 메일이나 웹훅이 있습니다', href: '/system', createdAt: '2026-10-08T01:00:00Z' },
  { id: 3, text: '새 알림이 있습니다', unread: true, createdAt: '2026-10-07T01:00:00Z' },
];

describe('InboxList', () => {
  test('InboxList 줄: ul 안에 받은 순서대로 한 줄씩이고, href가 있는 줄은 링크 하나, 없는 줄은 글자뿐이다. 시각은 Tab 자리가 아니다', async () => {
    render(<InboxList items={ITEMS} unreadLabel="안 읽음" />);

    const rows = within(screen.getByRole('list')).getAllByRole('listitem');
    expect(rows.map((li) => within(li).queryAllByRole('link').length)).toEqual([1, 1, 0]);
    expect(rows.map((li) => li.querySelector('time')?.getAttribute('datetime'))).toEqual(ITEMS.map((i) => i.createdAt));
    expect(document.querySelectorAll('[tabindex]')).toHaveLength(0);
    expect(within(rows[0] as HTMLElement).getByRole('link')).toHaveAttribute('href', '/issues/ACME-7#comment-12');
    expect(rows[0]).toHaveTextContent('ACME-7');
    expect(rows[0]).toHaveTextContent('로그인이 안 됩니다');
    expect(rows[1]).not.toHaveTextContent(/[A-Z]+-\d+/);
    await expectNoAxeViolations();
  });

  test('InboxList 안 읽음: 안 읽은 줄의 링크 이름에만 "안 읽음"이 있고 읽은 줄에는 그 글자가 없다', () => {
    render(<InboxList items={ITEMS} unreadLabel="안 읽음" />);

    const [unread, read] = screen.getAllByRole('link');
    expect(unread).toHaveAccessibleName(/^안 읽음 김고객 님이 댓글을 남겼습니다/);
    expect(read).not.toHaveAccessibleName(/안 읽음/);
    expect(read?.closest('li')).not.toHaveTextContent('안 읽음');
    expect(screen.getAllByRole('listitem')[2]).toHaveTextContent('안 읽음 새 알림이 있습니다');
  });

  test('InboxList 누르기: 링크를 누르면 onOpen이 그 항목으로 불리고, renderLink가 있으면 그것이 링크를 그린다', async () => {
    const onOpen = vi.fn<(item: InboxListItem) => void>();
    render(
      <InboxList
        items={ITEMS}
        unreadLabel="안 읽음"
        onOpen={onOpen}
        renderLink={(link) => (
          <a
            href={link.href}
            className={link.className}
            data-router="true"
            onClick={(e) => {
              e.preventDefault();
              link.onClick(e);
            }}
          >
            {link.children}
          </a>
        )}
      />,
    );
    const user = userEvent.setup();
    const links = screen.getAllByRole('link');
    expect(links.every((a) => a.getAttribute('data-router') === 'true')).toBe(true);

    await user.click(links[1] as HTMLElement);

    expect(onOpen).toHaveBeenCalledTimes(1);
    expect(onOpen.mock.calls[0]?.[0].id).toBe(2);
  });
});
