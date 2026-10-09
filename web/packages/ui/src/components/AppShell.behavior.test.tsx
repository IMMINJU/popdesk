// screens.md 5절 27 AppShell. C-10 변이 테스트가 찾은 빈칸: 메뉴 없는 포털, 옆 메뉴 버튼이 가리키는 곳,
// 접힘 기본값, 로그아웃 보내는 중, 불러오는 중 aria-busy.
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, test, vi } from 'vitest';
import { found } from '../__tests__/support';
import { AppShell, type AppShellProps } from './AppShell';

function shell(over: Partial<AppShellProps> = {}) {
  return (
    <AppShell
      app="console"
      displayName="popdesk"
      nav={[{ href: '/issues', label: '문의', current: true }]}
      navLabel="메뉴"
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

const toggle = () =>
  found(() => screen.queryAllByRole('button').find((b) => b.hasAttribute('aria-expanded')), '옆 메뉴 버튼(aria-expanded)');

describe('AppShell 동작', () => {
  test('AppShell 포털: 메뉴가 없으면 nav를 그리지 않는다', () => {
    render(shell({ app: 'portal', nav: [] }));

    expect(screen.queryByRole('navigation')).toBeNull();
  });

  test('AppShell 옆 메뉴 버튼: 메뉴가 있으면 aria-controls가 그 nav를 가리킨다', async () => {
    render(shell({ onSidebarToggle: () => undefined, sidebarToggleLabel: '옆 메뉴' }));

    const t = await toggle();
    const nav = screen.getByRole('navigation', { name: '메뉴' });
    expect(t).toHaveAccessibleName('옆 메뉴');
    expect(t.getAttribute('aria-controls')).toBe(nav.id);
    expect(nav.id).not.toBe('');
  });

  test('AppShell 옆 메뉴 버튼: 메뉴가 없으면 aria-controls를 두지 않는다', async () => {
    render(shell({ nav: [], onSidebarToggle: () => undefined, sidebarToggleLabel: '옆 메뉴' }));

    expect(await toggle()).not.toHaveAttribute('aria-controls');
  });

  test('AppShell 접힘 기본값: sidebarCollapsed를 안 주면 펼친 상태(aria-expanded="true"), 누르면 접으라고(true) 알린다', async () => {
    const onSidebarToggle = vi.fn();
    render(shell({ onSidebarToggle, sidebarToggleLabel: '옆 메뉴' }));
    const user = userEvent.setup();

    const t = await toggle();
    expect(t).toHaveAttribute('aria-expanded', 'true');
    await user.click(t);
    expect(onSidebarToggle).toHaveBeenCalledWith(true);
  });

  test('AppShell 접힌 상태: 누르면 펴라고(false) 알린다', async () => {
    const onSidebarToggle = vi.fn();
    render(shell({ sidebarCollapsed: true, onSidebarToggle, sidebarToggleLabel: '옆 메뉴' }));
    const user = userEvent.setup();

    const t = await toggle();
    expect(t).toHaveAttribute('aria-expanded', 'false');
    await user.click(t);
    expect(onSidebarToggle).toHaveBeenCalledWith(false);
  });

  test('AppShell 옆 메뉴 버튼 없음: onSidebarToggle이 없으면 버튼도 없다', () => {
    render(shell());

    expect(screen.queryAllByRole('button').filter((b) => b.hasAttribute('aria-expanded'))).toHaveLength(0);
  });

  test('AppShell 로그아웃 보내는 중: 로그아웃 버튼이 aria-disabled이고 다시 눌러도 부르지 않는다', async () => {
    const onLogout = vi.fn();
    render(shell({ logoutPending: true, onLogout }));
    const user = userEvent.setup();

    const b = screen.getByRole('button', { name: /로그아웃/ });
    expect(b).toHaveAttribute('aria-disabled', 'true');
    await user.click(b);
    expect(onLogout).not.toHaveBeenCalled();
  });

  test('AppShell 불러오는 중: busy면 main에 aria-busy="true", 아니면 없다', () => {
    const { rerender } = render(shell({ busy: true }));
    expect(screen.getByRole('main')).toHaveAttribute('aria-busy', 'true');

    rerender(shell());
    expect(screen.getByRole('main')).not.toHaveAttribute('aria-busy');
  });
});
