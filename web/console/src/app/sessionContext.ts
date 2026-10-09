// Who is signed in. The console asks `GET /api/console/me` once when a signed-in
// screen opens; screens read the role from here (admin screens, C-3 empty list).
import { createContext, useCallback, useContext } from 'react';
import { useLocation, useNavigate } from 'react-router';
import type { Schemas } from '../api';

export type Me = Schemas['Me'];

export interface SessionValue {
  me: Me | null;
  setMe: (me: Me | null) => void;
  /** A session was seen in this tab, so a later 401 means it ended (screens.md P-1 세션 끝남). */
  hadSession: boolean;
}

export const SessionContext = createContext<SessionValue | null>(null);

export function useSession(): SessionValue {
  const value = useContext(SessionContext);
  if (!value) throw new Error('useSession outside SessionProvider');
  return value;
}

/** State the login screen reads when another screen sent it there. */
export interface LoginState {
  expired?: boolean;
}

/**
 * Sends the user to the login screen after a 401 while loading, with the
 * current path as `next` so they come back (screens.md 2절).
 */
export function useToLogin(): () => void {
  const navigate = useNavigate();
  const location = useLocation();
  const { hadSession, setMe } = useSession();
  return useCallback(() => {
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
  return next;
}
