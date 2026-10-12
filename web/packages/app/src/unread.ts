// The unread count of the inbox, for the link in the head (4-talk screens.md "머리의 알림 링크").
import { createContext, useCallback, useContext, useEffect, useMemo, useRef, useState } from 'react';
import type { Result } from './result';

export const UNREAD_PERIOD_MS = 60_000;

export interface Unread {
  /** The last count the server gave; 0 until the first answer. */
  count: number;
  /** Asks again now. The inbox calls it after 모두 읽음, not after reading one item. */
  refresh: () => void;
}

export const UnreadContext = createContext<Unread>({ count: 0, refresh: () => undefined });

/** The head's unread count and the way to ask for it again. */
export function useUnread(): Unread {
  return useContext(UnreadContext);
}

/**
 * Asks for the count with `load` (the app's own unread-count call) once `on` turns true, then every
 * minute. A hidden tab skips its turn and asks at once when it shows again. A 401 ends the asking;
 * any other failure keeps the last count. Nothing is said on the screen either way, and nobody is
 * sent to the login screen (S4-AC-64, 69). `load` must keep its identity between renders.
 */
export function useUnreadCount(on: boolean, load: () => Promise<Result<{ count: number }>>): Unread {
  const [count, setCount] = useState(0);
  const ask = useRef<() => void>(() => undefined);

  useEffect(() => {
    if (!on) return undefined;
    let live = true;
    let stopped = false;
    let latest = 0;
    const timer = setInterval(() => {
      if (!document.hidden) run();
    }, UNREAD_PERIOD_MS);
    const onVisible = () => {
      if (!document.hidden) run();
    };
    const stop = () => {
      stopped = true;
      clearInterval(timer);
      document.removeEventListener('visibilitychange', onVisible);
    };
    function run() {
      if (stopped) return;
      latest += 1;
      const mine = latest;
      void load().then((result) => {
        // The frame has gone, or a later answer is already in.
        if (!live || mine !== latest) return;
        if (result.ok) setCount(result.data.count);
        else if (result.failure.kind === 'unauthenticated') stop();
      });
    }
    document.addEventListener('visibilitychange', onVisible);
    ask.current = run;
    run();
    return () => {
      live = false;
      stop();
      ask.current = () => undefined;
    };
  }, [on, load]);

  const refresh = useCallback(() => {
    ask.current();
  }, []);
  return useMemo(() => ({ count, refresh }), [count, refresh]);
}
