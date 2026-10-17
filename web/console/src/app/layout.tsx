// The signed-in frame: checks the session, draws the side menu (AppShell), and
// lets screens mark `main` busy while they load (screens.md 2절, 4절 공통 머리).
import { Alert, AppShell, AuthBrand, Button, EmptyState, InboxLinkLabel, LockIcon, NavItemLabel, SearchOffIcon, type InboxLink, type NavItem } from '@popdesk/ui';
import { useCallback, useEffect, useRef, useState, type ReactNode, type RefObject } from 'react';
import { Link, Outlet, useLocation } from 'react-router';
import { api } from '../api';
import { logoOf, useBranding } from './branding';
import { BusyContext } from './busy';
import { useDocumentTitle, useFocusWhenReady } from './hooks';
import { MY_ISSUES_HREF } from './issueFilters';
import { t, tf } from './locale';
import { TEXT } from './messages';
import { NavCountContext } from './navCount';
import { useLiveNavigate } from './navigate';
import { photoUrl } from './photos';
import { call } from './result';
import { useSession, useToLogin, type Me } from './sessionContext';
import { UnreadContext, useUnreadCount } from './unread';

/** `requests`: the open membership requests of all companies, shown on 고객사 (S2-AC-48). */
function menu(me: Me, requests: number): Omit<NavItem, 'current'>[] {
  // 문의 opens the 내 담당 view (S3-AC-77).
  const items: Omit<NavItem, 'current'>[] = [
    { href: MY_ISSUES_HREF, label: '문의' },
    { href: '/tasks', label: '내 작업' },
    { href: '/stats', label: '통계' },
  ];
  if (me.role === 'ADMIN') {
    items.push(
      { href: '/companies', label: '고객사', count: requests, countLabel: `소속 요청 ${String(requests)}건` },
      { href: '/members', label: '구성원' },
      { href: '/trash', label: '휴지통' },
      { href: '/sla', label: 'SLA 설정' },
      { href: '/audit-log', label: t('auditLog.title') },
      { href: '/system', label: '시스템 상태' },
      { href: '/branding', label: '브랜딩' },
    );
  }
  return items;
}

const INBOX_HREF = '/inbox';

function isCurrent(pathname: string, href: string): boolean {
  const path = href.split('?')[0] ?? href;
  return pathname === path || pathname.startsWith(`${path}/`);
}

export function ConsoleLayout() {
  const branding = useBranding();
  const logo = logoOf(branding.logos);
  const { me, setMe } = useSession();
  const toLogin = useToLogin();
  const navigate = useLiveNavigate();
  const location = useLocation();
  const [problem, setProblem] = useState(false);
  const [busy, setBusy] = useState(false);
  const [collapsed, setCollapsed] = useState(false);
  const [leaving, setLeaving] = useState(false);
  const [attempt, setAttempt] = useState(0);
  const [requests, setRequests] = useState(0);
  const [counted, setCounted] = useState(0);
  const admin = me?.role === 'ADMIN';

  // The sum for 고객사: when the frame opens and each time a screen closed a request.
  // Only an admin may ask. A failed answer says nothing; the number is just not drawn.
  useEffect(() => {
    if (!admin) return undefined;
    let live = true;
    void call(api.GET('/api/console/membership-requests', { params: { query: { size: 1 } } })).then((result) => {
      if (live) setRequests(result.ok ? result.data.totalElements : 0);
    });
    return () => {
      live = false;
    };
  }, [admin, counted]);
  const recount = useCallback(() => {
    setCounted((n) => n + 1);
  }, []);
  // The inbox link's number: asked by the frame alone, on its own clock, never when a screen changes.
  const unread = useUnreadCount(me !== null);

  useEffect(() => {
    let live = true;
    void call(api.GET('/api/console/me')).then((result) => {
      if (!live) return;
      if (result.ok) {
        setProblem(false);
        setMe(result.data);
      } else if (result.failure.kind === 'unauthenticated') {
        toLogin();
      } else {
        setProblem(true);
      }
    });
    return () => {
      live = false;
    };
    // The session is checked when the frame opens and on 다시 불러오기, not on every move.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [attempt]);

  const logout = async () => {
    setLeaving(true);
    await call(api.POST('/api/public/sign-out'));
    setLeaving(false);
    setMe(null);
    void navigate('/login', { replace: true });
  };

  if (problem) {
    return (
      <main id="main" className="pd-bare-main" tabIndex={-1}>
        <Alert
          tone="error"
          action={
            <Button
              variant="secondary"
              size="sm"
              onClick={() => {
                setAttempt((n) => n + 1);
              }}
            >
              {TEXT.retryLoad}
            </Button>
          }
        >
          {TEXT.server}
        </Alert>
      </main>
    );
  }
  if (!me) return null;

  const nav: NavItem[] = menu(me, admin ? requests : 0).map((item) => ({ ...item, current: isCurrent(location.pathname, item.href) }));
  const inbox: InboxLink = {
    href: INBOX_HREF,
    label: t('inbox.link'),
    current: isCurrent(location.pathname, INBOX_HREF),
    count: unread.count,
    countLabel: tf('inbox.count', { count: unread.count }),
  };
  return (
    <BusyContext value={setBusy}>
      <AppShell
        app="console"
        displayName={branding.displayName}
        {...(logo ? { logo } : {})}
        nav={nav}
        navLabel={TEXT.menu}
        userName={me.name}
        userPhotoUrl={photoUrl(me.id, me.photoUpdatedAt)}
        renderUserLink={(children) => (
          <Link className="pd-shell-user-link" to="/me">
            {children}
            {/* The name comes from the content, so it holds the words one sees: "최관리, 내 정보". */}
            <span className="pd-sr-only">, {t('myInfo.title')}</span>
          </Link>
        )}
        onLogout={() => {
          void logout();
        }}
        logoutPending={leaving}
        logoutLabel={TEXT.logout}
        skipLinkLabel={TEXT.skipLink}
        busy={busy}
        sidebarCollapsed={collapsed}
        onSidebarToggle={setCollapsed}
        sidebarToggleLabel={TEXT.sidebar}
        renderHomeLink={(children) => (
          <Link className="pd-shell-home" to="/issues">
            {children}
          </Link>
        )}
        renderLink={(item) => (
          <Link to={item.href} aria-current={item.current ? 'page' : undefined}>
            <NavItemLabel item={item} />
          </Link>
        )}
        inbox={inbox}
        renderInboxLink={(item) => (
          <Link to={item.href} aria-current={item.current ? 'page' : undefined}>
            <InboxLinkLabel inbox={item} />
          </Link>
        )}
      >
        <NavCountContext value={recount}>
          <UnreadContext value={unread}>
            <Outlet />
          </UnreadContext>
        </NavCountContext>
      </AppShell>
    </BusyContext>
  );
}

/**
 * The screen's `h1` (`tabindex="-1"`). It takes focus when it first appears and
 * names the document.
 */
export function PageTitle(props: { children: string; extra?: ReactNode; titleRef?: RefObject<HTMLHeadingElement | null> }) {
  const own = useRef<HTMLHeadingElement>(null);
  const ref = props.titleRef ?? own;
  useDocumentTitle(props.children);
  useFocusWhenReady(ref, true);
  return (
    <h1 ref={ref} className="pd-page-title" tabIndex={-1}>
      {props.children}
      {props.extra}
    </h1>
  );
}

/** Whole-screen state with an `h1` that takes focus: 404, 권한 없음. */
function ScreenState(props: { title: string; description: string; icon: ReactNode; action?: ReactNode }) {
  const heading = useRef<HTMLHeadingElement>(null);
  useDocumentTitle(props.title);
  useFocusWhenReady(heading, true);
  return (
    <EmptyState
      headingLevel={1}
      headingRef={heading}
      icon={props.icon}
      title={props.title}
      description={props.description}
      {...(props.action === undefined ? {} : { action: props.action })}
    />
  );
}

/** 404: does not tell missing from hidden (COM-1). Links back to the list. */
export function NotFound(props: { listHref: string; listLabel: string }) {
  return (
    <ScreenState
      title={TEXT.notFound}
      description={TEXT.notFoundHelp}
      icon={<SearchOffIcon size={20} />}
      action={<Link to={props.listHref}>{props.listLabel}</Link>}
    />
  );
}

/** 403 on an admin screen (S0-AC-30): the whole screen, no admin controls. */
export function NoPermission() {
  return <ScreenState title={TEXT.noPermission} description={TEXT.noPermissionHelp} icon={<LockIcon size={20} />} action={<Link to="/issues">문의 목록으로</Link>} />;
}

/** Admin screens: an agent sees 권한이 없습니다 without calling the API. */
export function AdminOnly(props: { children: ReactNode }) {
  const { me } = useSession();
  if (me?.role !== 'ADMIN') return <NoPermission />;
  return <>{props.children}</>;
}

/** 5xx or no connection while loading: band with 다시 불러오기. */
export function LoadFailed(props: { onRetry: () => void; text?: string }) {
  return (
    <Alert
      tone="error"
      action={
        <Button variant="secondary" size="sm" onClick={props.onRetry}>
          {TEXT.retryLoad}
        </Button>
      }
    >
      {props.text ?? TEXT.server}
    </Alert>
  );
}

/** The logo and display name above the forms before log in, from the branding (slice 9 screens.md 1절). */
export function Brand() {
  const branding = useBranding();
  const logo = logoOf(branding.logos);
  return <AuthBrand displayName={branding.displayName} {...(logo ? { logo } : {})} />;
}
