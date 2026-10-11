// The signed-in frame: checks the session, draws the side menu (AppShell), and
// lets screens mark `main` busy while they load (screens.md 2절, 4절 공통 머리).
import { Alert, AppShell, Button, EmptyState, LockIcon, SearchOffIcon, type NavItem } from '@popdesk/ui';
import { useEffect, useRef, useState, type ReactNode, type RefObject } from 'react';
import { Link, Outlet, useLocation } from 'react-router';
import { api } from '../api';
import { BusyContext } from './busy';
import { useDocumentTitle, useFocusWhenReady } from './hooks';
import { t } from './locale';
import { TEXT } from './messages';
import { useLiveNavigate } from './navigate';
import { photoUrl } from './photos';
import { call } from './result';
import { useSession, useToLogin, type Me } from './sessionContext';

function menu(me: Me): { href: string; label: string }[] {
  const items = [{ href: '/issues', label: '문의' }];
  if (me.role === 'ADMIN') {
    items.push({ href: '/companies', label: '고객사' }, { href: '/members', label: '구성원' }, { href: '/system', label: '시스템 상태' });
  }
  return items;
}

function isCurrent(pathname: string, href: string): boolean {
  return pathname === href || pathname.startsWith(`${href}/`);
}

export function ConsoleLayout() {
  const { me, setMe } = useSession();
  const toLogin = useToLogin();
  const navigate = useLiveNavigate();
  const location = useLocation();
  const [problem, setProblem] = useState(false);
  const [busy, setBusy] = useState(false);
  const [collapsed, setCollapsed] = useState(false);
  const [leaving, setLeaving] = useState(false);
  const [attempt, setAttempt] = useState(0);

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

  const nav: NavItem[] = menu(me).map((item) => ({ ...item, current: isCurrent(location.pathname, item.href) }));
  return (
    <BusyContext value={setBusy}>
      <AppShell
        app="console"
        displayName={TEXT.displayName}
        nav={nav}
        navLabel={TEXT.menu}
        userName={me.name}
        userPhotoUrl={photoUrl(me.id, me.photoUpdatedAt)}
        renderUserLink={(children) => (
          <Link className="pd-shell-user-link" to="/me" aria-label={`${me.name}, ${t('myInfo.title')}`}>
            {children}
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
            {item.label}
          </Link>
        )}
      >
        <Outlet />
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
