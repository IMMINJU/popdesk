// 4번 묶음 screens.md 1절 "머리의 알림 링크": AppShell의 inbox. 머리(banner) 안, 메뉴 nav 밖의 링크 하나.
// 이름은 label과 countLabel을 이은 것이고("알림 3건"), 보이는 숫자는 aria-hidden이다(S4-AC-64).
import { render, screen, within } from '@testing-library/react';
import { afterEach, describe, expect, test } from 'vitest';
import { expectNoAxeViolations, text } from '../__tests__/support';
import { AppShell, type AppShellProps, type InboxLink } from './AppShell';

function shell(inbox: InboxLink | undefined, over: Partial<AppShellProps> = {}) {
  return (
    <AppShell
      app="console"
      displayName="popdesk"
      nav={[
        { href: '/issues', label: '문의', current: true },
        { href: '/companies', label: '고객사' },
      ]}
      navLabel="메뉴"
      userName="이상담"
      onLogout={() => undefined}
      logoutLabel="로그아웃"
      skipLinkLabel="본문으로 건너뛰기"
      {...(inbox ? { inbox } : {})}
      {...over}
    >
      <h1 tabIndex={-1}>문의 목록</h1>
    </AppShell>
  );
}

const inboxLinks = () => screen.queryAllByRole('link', { name: /^알림/ });
const hiddenParts = (a: HTMLElement) => Array.from(a.querySelectorAll('[aria-hidden="true"]')).map((el) => text(el));
const shownDigits = (a: HTMLElement) => hiddenParts(a).filter((part) => /\d/.test(part));

describe('AppShell 알림 링크 (4번 묶음)', () => {
  const before = Object.getOwnPropertyDescriptor(window, 'matchMedia');

  afterEach(() => {
    if (before) Object.defineProperty(window, 'matchMedia', before);
    else Reflect.deleteProperty(window, 'matchMedia');
  });

  test.each([
    ['콘솔', 'console' as const],
    ['포털', 'portal' as const],
  ])('S4-AC-64 AppShell inbox(%s): 머리(banner) 안이고 메뉴 nav 밖인 링크 하나다. navigation은 하나뿐이다', (_, app) => {
    render(shell({ href: '/inbox', label: '알림', count: 3, countLabel: '3건' }, { app }));

    const links = inboxLinks();
    expect(links).toHaveLength(1);
    const [link] = links;
    expect(link).toHaveAttribute('href', '/inbox');
    expect(within(screen.getByRole('banner')).queryAllByRole('link', { name: /^알림/ }), 'banner 안').toHaveLength(1);
    expect(within(screen.getByRole('navigation')).queryAllByRole('link', { name: /^알림/ }), '메뉴 nav 안').toHaveLength(0);
    expect(screen.getAllByRole('navigation')).toHaveLength(1);
    expect(link?.closest('[role="status"], [role="alert"], [aria-live]') ?? null, '라이브 영역 밖').toBeNull();
    expect(link).not.toHaveAttribute('aria-current');
    // After the skip link, before the user's own controls.
    const order = Array.from(document.querySelectorAll('a, button'));
    expect(order.indexOf(link as HTMLElement)).toBeGreaterThan(order.indexOf(screen.getByRole('link', { name: '본문으로 건너뛰기' })));
    expect(order.indexOf(link as HTMLElement)).toBeLessThan(order.indexOf(screen.getByRole('button', { name: '로그아웃' })));
  });

  test.each([
    ['주지 않으면', {}],
    ['0이면', { count: 0, countLabel: '0건' }],
  ])('S4-AC-64 AppShell inbox의 count를 %s: 숫자가 없고 링크의 이름은 "알림"이다', (_, counted) => {
    render(shell({ href: '/inbox', label: '알림', ...counted }));

    const [link] = inboxLinks();
    expect(link).toHaveAccessibleName('알림');
    expect(link ? shownDigits(link) : ['없음']).toEqual([]);
    expect(text(link)).toBe('알림');
  });

  test.each([
    [3, '3건', '3', '알림 3건'],
    [99, '99건', '99', '알림 99건'],
    [100, '100건', '99+', '알림 100건'],
    [120, '120건', '99+', '알림 120건'],
  ])('S4-AC-64 AppShell inbox의 count %i: 이름은 label과 countLabel("%s")을 이은 것이고, 보이는 숫자 "%s"는 aria-hidden이다', (count, countLabel, shown, name) => {
    render(shell({ href: '/inbox', label: '알림', count, countLabel }));

    const link = screen.getByRole('link', { name });
    expect(shownDigits(link)).toEqual([shown]);
  });

  test('S4-AC-64 AppShell inbox의 current: 지금 화면이 알림함이면 aria-current="page"', () => {
    render(shell({ href: '/inbox', label: '알림', current: true, count: 2, countLabel: '2건' }));

    expect(screen.getByRole('link', { name: '알림 2건' })).toHaveAttribute('aria-current', 'page');
  });

  test('S4-AC-64 AppShell inbox를 주지 않으면: 알림 링크가 없다. 주면 생긴다', () => {
    const { rerender } = render(shell(undefined));
    expect(inboxLinks()).toHaveLength(0);

    rerender(shell({ href: '/inbox', label: '알림' }));
    expect(inboxLinks()).toHaveLength(1);
  });

  test('S4-AC-64 AppShell 좁은 창: 옆 메뉴가 접혀 있어도 알림 링크는 이름 그대로 있다', () => {
    // jsdom has no matchMedia. Every query matches: a window under bp-narrow.
    Object.defineProperty(window, 'matchMedia', {
      configurable: true,
      value: (media: string) => ({ media, matches: true, addEventListener: () => undefined, removeEventListener: () => undefined }),
    });
    render(shell({ href: '/inbox', label: '알림', count: 3, countLabel: '3건' }, { onSidebarToggle: () => undefined, sidebarToggleLabel: '옆 메뉴' }));

    expect(screen.getByRole('button', { name: '옆 메뉴' })).toHaveAttribute('aria-expanded', 'false');
    const link = screen.getByRole('link', { name: '알림 3건' });
    expect(link.closest('nav'), '메뉴 밖').toBeNull();
    expect(link.closest('[hidden], [inert], [aria-hidden="true"]'), '숨기지 않는다').toBeNull();
  });

  test.each([
    ['알림 0', { href: '/inbox', label: '알림' }],
    ['알림 120, 지금 화면', { href: '/inbox', label: '알림', count: 120, countLabel: '120건', current: true }],
  ])('AppShell axe (%s): 접근성 위반이 없다', async (_, inbox) => {
    render(shell(inbox));

    expect(inboxLinks()).toHaveLength(1);
    await expectNoAxeViolations();
  });
});
