// 9번 묶음 screens.md 1절 AppShell의 로고(logo prop): 로고가 있으면 <img alt=""> 둘(밝게, 어둡게)과 표시 이름, 주소가 같으면
// 하나. 홈 링크의 이름은 표시 이름이다. 접힌 띠는 로고 대신 강조색 네모다(S9-AC-22, 25).
import { render, screen } from '@testing-library/react';
import { describe, expect, test } from 'vitest';
import { expectNoAxeViolations, found, text } from '../__tests__/support';
import { AppShell, type AppShellProps } from './AppShell';

const LIGHT = '/api/public/branding/logos/light?v=0123456789abcdef';
const DARK = '/api/public/branding/logos/dark?v=fedcba9876543210';

function shell(over: Partial<AppShellProps> = {}) {
  return (
    <AppShell
      app="console"
      displayName="Acme 지원"
      nav={[{ href: '/issues', label: '문의', current: true }]}
      navLabel="메뉴"
      userName="관리자"
      onLogout={() => undefined}
      logoutLabel="로그아웃"
      skipLinkLabel="본문으로 건너뛰기"
      homeHref="/issues"
      {...over}
    >
      <h1 tabIndex={-1}>문의 목록</h1>
    </AppShell>
  );
}

const header = () => found(() => document.querySelector<HTMLElement>('[data-pd="AppShell"] header'), '머리');
const sources = (root: ParentNode) => Array.from(root.querySelectorAll('img')).map((img) => img.getAttribute('src'));
const alts = (root: ParentNode) => Array.from(root.querySelectorAll('img')).map((img) => img.getAttribute('alt'));

describe('AppShell 로고 (9번 묶음)', () => {
  test.each(['console', 'portal'] as const)('S9-AC-22 S9-AC-25 AppShell(%s) 로고 둘: 머리에 <img alt=""> 둘(밝게, 어둡게)과 표시 이름, 홈 링크의 이름은 표시 이름', async (app) => {
    render(shell({ app, logo: { light: LIGHT, dark: DARK } }));

    const top = await header();
    expect(sources(top)).toEqual([LIGHT, DARK]);
    expect(alts(top)).toEqual(['', '']);
    const home = await found(() => screen.queryByRole('link', { name: 'Acme 지원' }), '홈 링크 "Acme 지원"');
    expect(home.querySelectorAll('img'), '로고는 홈 링크 안').toHaveLength(2);
    expect(text(home)).toBe('Acme 지원');
    await expectNoAxeViolations();
  });

  test('S9-AC-22 AppShell 두 주소가 같으면 <img>는 하나다', async () => {
    render(shell({ logo: { light: LIGHT, dark: LIGHT } }));

    expect(sources(await header())).toEqual([LIGHT]);
  });

  test('S9-AC-22 AppShell 로고가 없으면 <img> 없이 강조색 네모와 표시 이름이다(지금 그대로)', async () => {
    render(shell());

    const top = await header();
    expect(sources(top)).toEqual([]);
    expect(top.querySelector('.pd-shell-logo'), '강조색 네모').not.toBeNull();
    expect(text(top)).toContain('Acme 지원');
    // 있음도 같이 본다: 로고를 주면 <img>가 생긴다.
    render(shell({ logo: { light: LIGHT, dark: DARK } }));
    expect(document.querySelectorAll('[data-pd="AppShell"] header img').length).toBeGreaterThan(0);
  });

  test('S9-AC-22 AppShell 접힌 옆 메뉴: 로고가 있어도 강조색 네모가 있다(40px 안의 로고는 읽히지 않는다)', async () => {
    render(shell({ logo: { light: LIGHT, dark: DARK }, sidebarCollapsed: true, onSidebarToggle: () => undefined, sidebarToggleLabel: '옆 메뉴' }));

    const top = await header();
    expect(top.closest('[data-sidebar-collapsed="true"]'), '접힘').not.toBeNull();
    expect(top.querySelector('.pd-shell-logo'), '강조색 네모').not.toBeNull();
    expect(sources(top), '펼치면 보일 로고').toEqual([LIGHT, DARK]);
  });

  test('S9-AC-22 AppShell 긴 표시 이름(50자)도 글 그대로 머리에 있다(말줄임은 CSS)', async () => {
    const long = 'A'.repeat(50);
    render(shell({ displayName: long, logo: { light: LIGHT, dark: DARK } }));

    expect(text(await header())).toContain(long);
    expect(sources(await header())).toEqual([LIGHT, DARK]);
  });
});
