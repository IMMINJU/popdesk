// The signed-in frame: checks the session, draws the top bar (AppShell), and
// lets screens mark `main` busy while they load (screens.md 2절, 1절 화면 머리).
import { Alert, AppShell, Button, EmptyState, SearchOffIcon } from '@popdesk/ui';
import { useEffect, useRef, useState, type RefObject } from 'react';
import { Link, Outlet } from 'react-router';
import { api } from '../api';
import { BusyContext } from './busy';
import { useDocumentTitle, useFocusWhenReady } from './hooks';
import { TEXT } from './messages';
import { useLiveNavigate } from './navigate';
import { call } from './result';
import { useSession, useToLogin } from './sessionContext';

export function PortalLayout() {
  const { me, setMe } = useSession();
  const toLogin = useToLogin();
  const navigate = useLiveNavigate();
  const [problem, setProblem] = useState(false);
  const [busy, setBusy] = useState(false);
  const [leaving, setLeaving] = useState(false);
  const [attempt, setAttempt] = useState(0);

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

  return (
    <BusyContext value={setBusy}>
      <AppShell
        app="portal"
        displayName={TEXT.displayName}
        nav={[]}
        userName={me.name}
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

/** 404: the whole screen, with an `h1` that takes focus. Does not tell missing from hidden (COM-1). */
export function NotFound(props: { listHref: string; listLabel: string }) {
  const heading = useRef<HTMLHeadingElement>(null);
  useDocumentTitle(TEXT.notFound);
  useFocusWhenReady(heading, true);
  return (
    <EmptyState
      headingLevel={1}
      headingRef={heading}
      icon={<SearchOffIcon size={20} />}
      title={TEXT.notFound}
      description={TEXT.notFoundHelp}
      action={<Link to={props.listHref}>{props.listLabel}</Link>}
    />
  );
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
