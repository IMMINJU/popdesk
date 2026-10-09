import { Fragment, type ReactNode, type Ref } from 'react';
import { SidebarIcon } from '../icons';
import { Avatar } from './Avatar';
import { Button } from './Button';
import { IconButton } from './IconButton';

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
  /** Name of the side menu toggle, e.g. "옆 메뉴". Default "Menu". */
  sidebarToggleLabel?: string;
  /** `nav` name, e.g. "메뉴". */
  navLabel?: string;
  /** Lets the app render nav links with its router. Default: a plain `<a>`. */
  renderLink?: (item: NavItem) => ReactNode;
  /** The display name links here, e.g. `/issues`. Default: no link. */
  homeHref?: string;
  /** Draws the home link with the app's router. */
  renderHomeLink?: (children: ReactNode) => ReactNode;
  /** 불러오는 중: `aria-busy="true"` on `main` (screens.md 2절). */
  busy?: boolean;
  /** Pending log out: the button shows the spinner. */
  logoutPending?: boolean;
  mainRef?: Ref<HTMLElement>;
}

/**
 * Skip link, head (display name, menu, user, log out), and `main#main`.
 * The console has no top bar: the side menu holds the name, the menu, and the
 * user with log out at the bottom (tokens.md 9절). The portal has a top bar.
 */
export function AppShell({ mainRef, ...props }: AppShellProps) {
  const collapsed = props.sidebarCollapsed ?? false;
  const brand = (
    <span className="pd-shell-brand">
      <span className="pd-shell-logo" aria-hidden="true" />
      <span className="pd-shell-name">{props.displayName}</span>
    </span>
  );
  const nav =
    props.nav.length > 0 ? (
      <nav id="pd-shell-menu" className="pd-shell-nav" aria-label={props.navLabel}>
        <ul>
          {props.nav.map((item) => (
            <li key={item.href}>
              {props.renderLink ? (
                <Fragment>{props.renderLink(item)}</Fragment>
              ) : (
                <a href={item.href} aria-current={item.current ? 'page' : undefined}>
                  {item.label}
                </a>
              )}
            </li>
          ))}
        </ul>
      </nav>
    ) : null;
  const user = (
    <div className="pd-shell-user">
      <Avatar name={props.userName} size="sm" decorative />
      <span className="pd-shell-user-name">{props.userName}</span>
      <Button variant="quiet" size="sm" pending={props.logoutPending ?? false} onClick={props.onLogout}>
        {props.logoutLabel}
      </Button>
    </div>
  );
  const home = props.renderHomeLink
    ? props.renderHomeLink(brand)
    : props.homeHref
      ? (
          <a className="pd-shell-home" href={props.homeHref}>
            {brand}
          </a>
        )
      : brand;
  return (
    <div className="pd-shell" data-pd="AppShell" data-app={props.app} data-sidebar-collapsed={collapsed ? 'true' : undefined}>
      <a className="pd-skip-link" href="#main">
        {props.skipLinkLabel}
      </a>
      <header className="pd-shell-head">
        <div className="pd-shell-top">
          {home}
          {props.onSidebarToggle && (
            <IconButton
              label={props.sidebarToggleLabel ?? 'Menu'}
              icon={<SidebarIcon />}
              size="sm"
              tooltip={false}
              expanded={!collapsed}
              {...(props.nav.length > 0 ? { controls: 'pd-shell-menu' } : {})}
              onClick={() => props.onSidebarToggle?.(!collapsed)}
            />
          )}
        </div>
        {nav}
        {user}
      </header>
      <main ref={mainRef} id="main" className="pd-shell-main" tabIndex={-1} aria-busy={props.busy ? true : undefined}>
        {props.children}
      </main>
    </div>
  );
}
