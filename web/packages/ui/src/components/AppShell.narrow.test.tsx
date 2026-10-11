// tokens.md 6절 bp-narrow: 좁은 창에서 콘솔의 옆 메뉴는 스스로 접히고, 버튼으로 내용 위에 펼친다.
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest';
import { AppShell, type AppShellProps } from './AppShell';

function shell(over: Partial<AppShellProps> = {}) {
  return (
    <AppShell
      app="console"
      displayName="popdesk"
      nav={[
        { href: '#issues', label: '문의', current: true },
        { href: '#system', label: '시스템 상태' },
      ]}
      navLabel="메뉴"
      userName="관리자"
      onLogout={() => undefined}
      logoutLabel="로그아웃"
      skipLinkLabel="본문으로 건너뛰기"
      onSidebarToggle={() => undefined}
      sidebarToggleLabel="옆 메뉴"
      {...over}
    >
      <h1 tabIndex={-1}>문의 목록</h1>
    </AppShell>
  );
}

const root = () => document.querySelector('[data-pd="AppShell"]');
const toggle = () => screen.getByRole('button', { name: '옆 메뉴' });

describe('AppShell 좁은 창', () => {
  const before = Object.getOwnPropertyDescriptor(window, 'matchMedia');

  beforeEach(() => {
    // jsdom has no matchMedia. Every query matches: a window under bp-narrow.
    Object.defineProperty(window, 'matchMedia', {
      configurable: true,
      value: (media: string) => ({ media, matches: true, addEventListener: () => undefined, removeEventListener: () => undefined }),
    });
  });

  afterEach(() => {
    if (before) Object.defineProperty(window, 'matchMedia', before);
    else Reflect.deleteProperty(window, 'matchMedia');
  });

  test('AppShell 좁은 창: sidebarCollapsed가 false여도 접혀 있고, 버튼은 앱에 알리지 않고 내용 위에 펼친다', async () => {
    const onSidebarToggle = vi.fn();
    render(shell({ sidebarCollapsed: false, onSidebarToggle }));
    const user = userEvent.setup();

    expect(toggle()).toHaveAttribute('aria-expanded', 'false');
    expect(root()).toHaveAttribute('data-sidebar-collapsed', 'true');
    await user.click(toggle());

    expect(toggle()).toHaveAttribute('aria-expanded', 'true');
    expect(root()).not.toHaveAttribute('data-sidebar-collapsed');
    expect(root()).toHaveAttribute('data-sidebar-over', 'true');
    expect(onSidebarToggle).not.toHaveBeenCalled();
  });

  test('AppShell 좁은 창: 펼친 메뉴는 Escape로 닫히고 포커스가 버튼으로 간다', async () => {
    render(shell());
    const user = userEvent.setup();
    await user.click(toggle());
    screen.getByRole('link', { name: '시스템 상태' }).focus();

    await user.keyboard('{Escape}');

    expect(toggle()).toHaveAttribute('aria-expanded', 'false');
    expect(toggle()).toHaveFocus();
  });

  test('AppShell 좁은 창: 펼친 메뉴의 링크를 누르면 닫힌다', async () => {
    render(shell());
    const user = userEvent.setup();
    await user.click(toggle());

    await user.click(screen.getByRole('link', { name: '시스템 상태' }));

    expect(toggle()).toHaveAttribute('aria-expanded', 'false');
    expect(root()).not.toHaveAttribute('data-sidebar-over');
  });

  test('AppShell 좁은 창: 옆 메뉴 버튼이 없으면 접지 않는다(다시 펼 길이 없다)', () => {
    render(
      <AppShell
        app="console"
        displayName="popdesk"
        nav={[{ href: '#issues', label: '문의', current: true }]}
        userName="관리자"
        onLogout={() => undefined}
        logoutLabel="로그아웃"
        skipLinkLabel="본문으로 건너뛰기"
      >
        <h1 tabIndex={-1}>문의 목록</h1>
      </AppShell>,
    );

    expect(root()).not.toHaveAttribute('data-sidebar-collapsed');
  });

  test('AppShell 좁은 창: 포털은 그대로다', () => {
    render(shell({ app: 'portal' }));

    expect(root()).not.toHaveAttribute('data-sidebar-collapsed');
  });
});
