import { Fragment, type ReactNode } from 'react';

export interface NavItem {
  href: string;
  label: string;
  /** `aria-current="page"`. */
  current?: boolean;
}

export interface AppShellProps {
  app: 'portal' | 'console';
  /** "popdesk" until branding (BRAND). */
  displayName: string;
  /** Console: 문의, and for admins 고객사·구성원·시스템 상태. Portal has none in the first slice. */
  nav: readonly NavItem[];
  userName: string;
  onLogout: () => void;
  logoutLabel: string;
  /** "본문으로 건너뛰기". */
  skipLinkLabel: string;
  children: ReactNode;
  /** Console side menu 펼침·접힘. */
  sidebarCollapsed?: boolean;
  onSidebarToggle?: (collapsed: boolean) => void;
  /** Lets the app render nav links with its router. Default: a plain `<a>`. */
  renderLink?: (item: NavItem) => ReactNode;
}

/** Skip link, head (display name, menu, user, log out), and `main#main`. */
export function AppShell(props: AppShellProps) {
  return (
    <div data-pd="AppShell" data-app={props.app} data-sidebar-collapsed={props.sidebarCollapsed}>
      <a href="#main">{props.skipLinkLabel}</a>
      <header>
        <span>{props.displayName}</span>
        <nav>
          {props.nav.map((item) =>
            props.renderLink ? (
              <Fragment key={item.href}>{props.renderLink(item)}</Fragment>
            ) : (
              <a key={item.href} href={item.href} aria-current={item.current ? 'page' : undefined}>
                {item.label}
              </a>
            ),
          )}
        </nav>
        <span>{props.userName}</span>
        <button type="button" onClick={props.onLogout}>
          {props.logoutLabel}
        </button>
      </header>
      <main id="main" tabIndex={-1}>
        {props.children}
      </main>
    </div>
  );
}
