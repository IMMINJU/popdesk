// 2번 묶음 design.md 6절, screens.md "콘솔 알림함" 절: NavItem의 count와 countLabel, 포털의 nav.
// 숫자는 링크 안 글자 뒤에 그린다. 링크의 접근 가능한 이름은 글자 뒤에 countLabel이 붙고 보이는 숫자는 aria-hidden이다.
import { render, screen, within } from '@testing-library/react';
import { describe, expect, test } from 'vitest';
import { expectNoAxeViolations, text } from '../__tests__/support';
import { AppShell, type AppShellProps, type NavItem } from './AppShell';

function shell(nav: NavItem[], over: Partial<AppShellProps> = {}) {
  return (
    <AppShell app="console" displayName="popdesk" nav={nav} navLabel="메뉴" userName="관리자" onLogout={() => undefined} logoutLabel="로그아웃" skipLinkLabel="본문으로 건너뛰기" {...over}>
      <h1 tabIndex={-1}>문의 목록</h1>
    </AppShell>
  );
}

const links = () => within(screen.getByRole('navigation')).getAllByRole('link');
const hiddenParts = (a: HTMLElement) => Array.from(a.querySelectorAll('[aria-hidden="true"]')).map((el) => text(el));

describe('AppShell NavItem의 count (2번 묶음)', () => {
  test('AppShell count와 countLabel: 링크 안에 숫자가 보이고, 링크의 이름은 글자 뒤에 countLabel이 붙는다. 보이는 숫자는 aria-hidden', async () => {
    render(
      shell([
        { href: '/issues', label: '문의', current: true },
        { href: '/companies', label: '고객사', count: 3, countLabel: '소속 요청 3건' },
      ]),
    );

    const companies = screen.queryByRole('link', { name: '고객사 소속 요청 3건' });
    expect(companies, '링크 "고객사 소속 요청 3건"').not.toBeNull();
    expect(companies ? hiddenParts(companies) : []).toContain('3');
    expect(companies?.closest('nav') ?? null, '메뉴 안').not.toBeNull();
    expect(companies).toHaveAttribute('href', '/companies');
    await expectNoAxeViolations();
  });

  test.each([
    ['주지 않으면', {}],
    ['0이면', { count: 0, countLabel: '소속 요청 0건' }],
  ])('AppShell count를 %s: 숫자가 없고 링크의 이름은 글자뿐이다', (_, counted) => {
    render(shell([{ href: '/companies', label: '고객사', ...counted }]));

    const [companies] = links();
    expect(companies).toHaveAccessibleName('고객사');
    expect(text(companies)).toBe('고객사');
  });

  test('AppShell 포털의 nav: app이 portal이어도 nav를 주면 항목이 링크로 보인다', () => {
    render(
      shell(
        [
          { href: '/issues', label: '문의' },
          { href: '/members', label: '우리 회사 사람', current: true },
        ],
        { app: 'portal', userName: '김고객' },
      ),
    );

    expect(links().map((a) => [text(a), a.getAttribute('href')])).toEqual([
      ['문의', '/issues'],
      ['우리 회사 사람', '/members'],
    ]);
    expect(screen.getByRole('link', { name: '우리 회사 사람' })).toHaveAttribute('aria-current', 'page');
  });
});
