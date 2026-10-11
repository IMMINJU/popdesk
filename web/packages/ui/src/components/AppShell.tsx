import { Fragment, useEffect, useRef, useState, useSyncExternalStore, type ReactNode, type Ref } from 'react';
import { SidebarIcon } from '../icons';
import { Avatar } from './Avatar';
import { Button } from './Button';
import { IconButton } from './IconButton';

export interface NavItem {
  href: string;
  label: string;
  /** `aria-current="page"`. */
  current?: boolean;
  /**
   * A number beside the label, e.g. the pending membership requests on 고객사.
   * Absent or 0 draws nothing.
   */
  count?: number;
  /**
   * What the number counts, with the number, e.g. "소속 요청 3건". It joins the link's accessible
   * name after the label. Needed whenever `count` is drawn.
   */
  countLabel?: string;
}

export interface AppShellProps {
  app: 'portal' | 'console';
  /** "popdesk" until branding (BRAND). */
  displayName: string;
  /** Console: 문의, and for admins 고객사·구성원·시스템 상태. Portal: 문의 and 우리 회사 사람 for a company manager, else none. */
  nav: readonly NavItem[];
  userName: string;
  /** The user's photo. Absent or `null` shows the initials (S1A-AC-60). */
  userPhotoUrl?: string | null;
  /** Draws the photo and the name as a link with the app's router, e.g. to 내 정보. Default: no link. */
  renderUserLink?: (children: ReactNode) => ReactNode;
  onLogout: () => void;
  logoutLabel: string;
  /** "본문으로 건너뛰기". */
  skipLinkLabel: string;
  children: ReactNode;
  /** Console side menu 펼침·접힘. Under `bp-narrow` the shell folds it by itself and opens it over the content. */
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
 * What a menu link holds: the label and, for a `count` above 0, the number after it.
 * The number is for the eye; a screen reader gets `countLabel` in the link's name
 * ("고객사 소속 요청 3건"). An app that draws its own links (`renderLink`) puts this inside them.
 */
export function NavItemLabel(props: { item: NavItem }) {
  const { label, count, countLabel } = props.item;
  if (count === undefined || count <= 0) return <>{label}</>;
  return (
    <>
      {label}
      <span className="pd-shell-nav-count" aria-hidden="true">
        {count}
      </span>
      {/* The space stays outside the hidden span, or the name reads "고객사소속 요청 3건". */}{' '}
      <span className="pd-sr-only">{countLabel ?? String(count)}</span>
    </>
  );
}

/** `bp-narrow` (tokens.md 6절): the console's side menu is folded. */
const NARROW = '(max-width: 1023.98px)';

function watchNarrow(onChange: () => void): () => void {
  if (typeof window.matchMedia !== 'function') return () => undefined;
  const query = window.matchMedia(NARROW);
  query.addEventListener('change', onChange);
  return () => {
    query.removeEventListener('change', onChange);
  };
}

function isNarrow(): boolean {
  return typeof window.matchMedia === 'function' && window.matchMedia(NARROW).matches;
}

/**
 * Skip link, head (display name, menu, user, log out), and `main#main`.
 * The console has no top bar: the side menu holds the name, the menu, and the
 * user with log out at the bottom (tokens.md 9절). The portal has a top bar.
 *
 * A folded side menu shows no menu, only the button that opens it. In a narrow
 * window the menu is folded whatever `sidebarCollapsed` says, and the button
 * opens it over the content until a link, Escape, or a click beside it closes it.
 */
export function AppShell({ mainRef, ...props }: AppShellProps) {
  const head = useRef<HTMLElement>(null);
  const narrowWindow = useSyncExternalStore(watchNarrow, isNarrow, () => false);
  // Without the button a folded menu could not be opened again.
  const narrow = narrowWindow && props.app === 'console' && props.onSidebarToggle !== undefined;
  const [over, setOver] = useState(false);
  const opened = narrow && over;
  const collapsed = narrow ? !over : (props.sidebarCollapsed ?? false);
  useEffect(() => {
    if (!opened) return undefined;
    const onKey = (e: KeyboardEvent) => {
      if (e.key !== 'Escape') return;
      setOver(false);
      // The menu goes away with whatever had focus in it.
      head.current?.querySelector<HTMLElement>('button[aria-expanded]')?.focus();
    };
    document.addEventListener('keydown', onKey);
    return () => {
      document.removeEventListener('keydown', onKey);
    };
  }, [opened]);
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
                  <NavItemLabel item={item} />
                </a>
              )}
            </li>
          ))}
        </ul>
      </nav>
    ) : null;
  const who = (
    <>
      <Avatar name={props.userName} photoUrl={props.userPhotoUrl ?? null} size="sm" decorative />
      <span className="pd-shell-user-name">{props.userName}</span>
    </>
  );
  const user = (
    <div className="pd-shell-user">
      {props.renderUserLink ? props.renderUserLink(who) : who}
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
    <div
      className="pd-shell"
      data-pd="AppShell"
      data-app={props.app}
      data-sidebar-collapsed={collapsed ? 'true' : undefined}
      data-sidebar-over={opened ? 'true' : undefined}
    >
      <a className="pd-skip-link" href="#main">
        {props.skipLinkLabel}
      </a>
      <header
        ref={head}
        className="pd-shell-head"
        onClick={(e) => {
          // A link of the opened menu leads to a screen: the menu gets out of its way.
          if (opened && e.target instanceof Element && e.target.closest('a')) setOver(false);
        }}
      >
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
              onClick={() => {
                if (narrow) setOver(!over);
                else props.onSidebarToggle?.(!collapsed);
              }}
            />
          )}
        </div>
        {nav}
        {user}
      </header>
      {opened && (
        <div
          className="pd-shell-scrim"
          aria-hidden="true"
          onClick={() => {
            setOver(false);
          }}
        />
      )}
      <main ref={mainRef} id="main" className="pd-shell-main" tabIndex={-1} aria-busy={props.busy ? true : undefined}>
        {props.children}
      </main>
    </div>
  );
}
