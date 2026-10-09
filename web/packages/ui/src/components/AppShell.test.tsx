// screens.md 5절 27 AppShell. 머리(표시 이름, 메뉴, 사용자, 로그아웃), 건너뛰기 링크, 본문. 콘솔은 옆 메뉴 펼침·접힘.
import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, test, vi } from 'vitest';
import { expectNoAxeViolations, found, tabbables, text } from '../__tests__/support';
import { AppShell, type AppShellProps, type NavItem } from './AppShell';

const adminNav: NavItem[] = [
  { href: '/issues', label: '문의', current: true },
  { href: '/companies', label: '고객사' },
  { href: '/members', label: '구성원' },
  { href: '/system', label: '시스템 상태' },
];

function shell(over: Partial<AppShellProps> = {}) {
  return (
    <AppShell
      app="console"
      displayName="popdesk"
      nav={adminNav}
      userName="관리자"
      onLogout={() => undefined}
      logoutLabel="로그아웃"
      skipLinkLabel="본문으로 건너뛰기"
      {...over}
    >
      <h1 tabIndex={-1}>문의 목록</h1>
    </AppShell>
  );
}

describe('AppShell', () => {
  test('AppShell 건너뛰기 링크: 맨 앞 Tab 자리이고 tabindex=-1인 main을 가리킨다', () => {
    render(shell());

    const first = tabbables()[0];
    expect(text(first)).toBe('본문으로 건너뛰기');
    const id = (first?.getAttribute('href') ?? '').replace(/^#/, '');
    const target = document.getElementById(id);
    expect(target).toBe(screen.getByRole('main'));
    expect(target).toHaveAttribute('tabindex', '-1');
  });

  test('AppShell 본문: main 안에 children을 그린다', () => {
    render(shell());

    expect(within(screen.getByRole('main')).getByRole('heading', { level: 1, name: '문의 목록' })).toBeInTheDocument();
  });

  test('AppShell 메뉴: 메뉴 링크가 순서대로 있고 지금 화면만 aria-current="page"', () => {
    render(shell());

    const nav = screen.getByRole('navigation');
    const links = within(nav).getAllByRole('link');
    expect(links.map((a) => text(a))).toEqual(['문의', '고객사', '구성원', '시스템 상태']);
    expect(links.filter((a) => a.getAttribute('aria-current') === 'page').map((a) => text(a))).toEqual(['문의']);
  });

  test('AppShell 상담원 메뉴: 받은 메뉴만 그린다(관리자 메뉴를 스스로 더하지 않는다)', () => {
    render(shell({ nav: [{ href: '/issues', label: '문의', current: true }] }));

    const nav = screen.getByRole('navigation');
    expect(within(nav).getAllByRole('link').map((a) => text(a))).toEqual(['문의']);
  });

  test('AppShell 사용자와 로그아웃: 표시 이름, 사용자 이름, 로그아웃 버튼은 onLogout', async () => {
    const onLogout = vi.fn();
    render(shell({ onLogout }));
    const user = userEvent.setup();

    expect(text(document.body)).toContain('popdesk');
    expect(text(document.body)).toContain('관리자');
    await user.click(screen.getByRole('button', { name: '로그아웃' }));
    expect(onLogout).toHaveBeenCalledTimes(1);
  });

  test('AppShell renderLink: 앱이 준 링크 그리기를 메뉴에 쓴다', () => {
    render(
      shell({
        renderLink: (item) => (
          <a href={`/app${item.href}`} aria-current={item.current ? 'page' : undefined}>
            {item.label}
          </a>
        ),
      }),
    );

    expect(screen.getByRole('link', { name: '고객사' })).toHaveAttribute('href', '/app/companies');
  });

  test('AppShell 옆 메뉴 접힘: 콘솔에서 접고 펴는 버튼이 aria-expanded로 상태를 알리고 onSidebarToggle을 부른다', async () => {
    const onSidebarToggle = vi.fn();
    render(shell({ sidebarCollapsed: false, onSidebarToggle }));
    const user = userEvent.setup();

    const toggle = await found(
      () => screen.queryAllByRole('button').find((b) => b.hasAttribute('aria-expanded')),
      '옆 메뉴 접기 버튼(aria-expanded)',
    );
    expect(toggle).toHaveAttribute('aria-expanded', 'true');
    await user.click(toggle);
    expect(onSidebarToggle).toHaveBeenCalledWith(true);
  });

  test('AppShell axe: 관리자 콘솔 머리와 본문에 접근성 위반이 없다', async () => {
    render(shell());

    await expectNoAxeViolations();
  });
});
