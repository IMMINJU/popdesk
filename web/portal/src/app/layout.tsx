// The signed-in frame: checks the session, draws the top bar (AppShell), and
// lets screens mark `main` busy while they load (screens.md 2절, 1절 화면 머리).
import { Alert, AppShell, AuthBrand, Button, EmptyState, InboxLinkLabel, LockIcon, NavItemLabel, SearchOffIcon, type InboxLink, type NavItem } from '@popdesk/ui';
import { useEffect, useRef, useState, type ReactNode, type RefObject } from 'react';
import { Link, Outlet, useLocation } from 'react-router';
import { api } from '../api';
import { logoOf, useBranding } from './branding';
import { BusyContext } from './busy';
import { focusTitleFor, useDocumentTitle, useFocusWhenReady } from './hooks';
import { t, tf } from './locale';
import { TEXT } from './messages';
import { useLiveNavigate } from './navigate';
import { photoUrl } from './photos';
import { call } from './result';
import { useSession, useToLogin, type Me } from './sessionContext';
import { UnreadContext, useUnreadCount } from './unread';

const INBOX_HREF = '/inbox';

/**
 * 문의 and 우리 회사 사람 for a manager of at least one company. Anyone else would
 * have 문의 alone, so there is no menu (2-manager screens.md P-8, S2-AC-45).
 */
function menu(me: Me): Omit<NavItem, 'current'>[] {
  if (!me.companies.some((c) => c.manager)) return [];
  return [
    { href: '/issues', label: '문의' },
    { href: '/members', label: t('companyPeople.title') },
  ];
}

function isCurrent(pathname: string, href: string): boolean {
  return pathname === href || pathname.startsWith(`${href}/`);
}

export function PortalLayout() {
  const branding = useBranding();
  const logo = logoOf(branding.logos);
  const { me, setMe } = useSession();
  const toLogin = useToLogin();
  const navigate = useLiveNavigate();
  const location = useLocation();
  const [problem, setProblem] = useState(false);
  const [busy, setBusy] = useState(false);
  const [leaving, setLeaving] = useState(false);
  const [attempt, setAttempt] = useState(0);
  // The inbox link's number: asked by the frame alone, on its own clock, never when a screen changes.
  const unread = useUnreadCount(me !== null);

  useEffect(() => {
    let live = true;
    void call(api.GET('/api/portal/me')).then((result) => {
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
        app="portal"
        displayName={branding.displayName}
        {...(logo ? { logo } : {})}
        nav={nav}
        navLabel={TEXT.menu}
        renderLink={(item) => (
          <Link to={item.href} aria-current={item.current ? 'page' : undefined}>
            <NavItemLabel item={item} />
          </Link>
        )}
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
        renderHomeLink={(children) => (
          <Link className="pd-shell-home" to="/issues">
            {children}
          </Link>
        )}
        inbox={inbox}
        renderInboxLink={(item) => (
          <Link to={item.href} aria-current={item.current ? 'page' : undefined}>
            <InboxLinkLabel inbox={item} />
          </Link>
        )}
      >
        <UnreadContext value={unread}>
          <Outlet />
        </UnreadContext>
      </AppShell>
    </BusyContext>
  );
}

/**
 * The screen's `h1` (`tabindex="-1"`). It takes focus when it first appears and
 * names the document.
 */
export function PageTitle(props: { children: string; titleRef?: RefObject<HTMLHeadingElement | null> }) {
  const own = useRef<HTMLHeadingElement>(null);
  const ref = props.titleRef ?? own;
  useDocumentTitle(props.children);
  useFocusWhenReady(ref, true);
  return (
    <h1 ref={ref} className="pd-page-title" tabIndex={-1}>
      {props.children}
    </h1>
  );
}

/** Whole-screen state with an `h1` that takes focus: 404, 권한 없음. */
function ScreenState(props: { title: string; description: string; icon: ReactNode; action: ReactNode }) {
  const heading = useRef<HTMLHeadingElement>(null);
  useDocumentTitle(props.title);
  useFocusWhenReady(heading, true);
  return <EmptyState headingLevel={1} headingRef={heading} icon={props.icon} title={props.title} description={props.description} action={props.action} />;
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

/** 403 on 우리 회사 사람 (S2-AC-45): the whole screen, nothing of the company on it. */
export function NoPermission() {
  return <ScreenState title={TEXT.noPermission} description={TEXT.noPermissionHelp} icon={<LockIcon size={20} />} action={<Link to="/issues">문의 목록으로</Link>} />;
}

/** 5xx or no connection while loading: band with 다시 불러오기. */
export function LoadFailed(props: { onRetry: () => void; text?: string }) {
  return (
    <Alert
      tone="error"
      action={
        <Button
          variant="secondary"
          size="sm"
          onClick={(e) => {
            focusTitleFor(e.currentTarget);
            props.onRetry();
          }}
        >
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
