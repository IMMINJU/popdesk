// Who is signed in. Each app asks its own `me` endpoint and keeps the answer
// here; `M` is that app's `Me` type.
import { createContext, useCallback, useContext, useEffect, useRef, type Context } from 'react';
import { useLocation } from 'react-router';
import { useLiveNavigate } from './navigate';

export interface SessionValue<M> {
  me: M | null;
  setMe: (me: M | null) => void;
  /** A session was seen in this tab, so a later 401 means it ended (screens.md P-1 세션 끝남). */
  hadSession: boolean;
}

const SessionContext = createContext<SessionValue<unknown> | null>(null);

/**
 * The one session context, typed with the app's `Me`. A bundle holds one app,
 * so the context only ever carries that app's `Me`.
 */
// eslint-disable-next-line @typescript-eslint/no-unnecessary-type-parameters -- the caller names its app's `Me`
export function sessionContext<M>(): Context<SessionValue<M> | null> {
  return SessionContext as unknown as Context<SessionValue<M> | null>;
}

// eslint-disable-next-line @typescript-eslint/no-unnecessary-type-parameters -- the caller names its app's `Me`
export function useSession<M>(): SessionValue<M> {
  const value = useContext(sessionContext<M>());
  if (!value) throw new Error('useSession outside SessionProvider');
  return value;
}

/** State the login screen reads when another screen sent it there. */
export interface LoginState {
  expired?: boolean;
}

/**
 * Sends the user to the login screen after a 401 while loading, with the
 * current path as `next` so they come back (screens.md 2절). A 401 that
 * arrives after the screen has gone changes nothing, not even the session: the
 * screen on now gets its own 401 and goes to login itself (V-3).
 */
export function useToLogin(): () => void {
  const navigate = useLiveNavigate();
  const location = useLocation();
  const { hadSession, setMe } = useSession();
  const mounted = useRef(false);
  useEffect(() => {
    mounted.current = true;
    return () => {
      mounted.current = false;
    };
  }, []);
  return useCallback(() => {
    if (!mounted.current) return;
    const next = `${location.pathname}${location.search}`;
    const state: LoginState = { expired: hadSession };
    setMe(null);
    void navigate(`/login?next=${encodeURIComponent(next)}`, { replace: true, state });
  }, [navigate, location.pathname, location.search, hadSession, setMe]);
}

/**
 * Only a path inside this app: one leading `/`, not `//` or `/\`, no scheme.
 * Anything else falls back to the issue list (screens.md 2절 401).
 */
export function safeNext(next: string | null): string {
  if (!next) return '/issues';
  if (!next.startsWith('/') || next.startsWith('//') || next.startsWith('/\\')) return '/issues';
  // Control characters could turn into something else in the address bar.
  if (Array.from(next).some((c) => (c.codePointAt(0) ?? 0) < 0x20)) return '/issues';
  if (next === '/login' || next.startsWith('/login?') || next.startsWith('/invite')) return '/issues';
  if (next.startsWith('/forgot-password') || next.startsWith('/reset-password')) return '/issues';
  return next;
}
