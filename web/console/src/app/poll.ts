// Reading a list again while something on it is still going (10b design.md 4절), in the shape of
// `useUnreadCount` of @popdesk/app: a fixed interval that does not wait for the last answer.
import { useLayoutEffect, useRef } from 'react';
import type { Result } from './result';

/**
 * Calls `read` every `periodMs` while `on` is true; not at once, the screen has just read. A hidden
 * tab skips its turn and reads at once when it shows again. A 401 ends the reading until `on` turns
 * true again. `read` gives undefined for an answer it dropped (a later one is already in), as the
 * `reload` of `useLoad` does; dropping late answers is its work.
 */
export function usePoll(on: boolean, periodMs: number, read: () => Promise<Result<unknown> | undefined>): void {
  const readRef = useRef(read);
  useLayoutEffect(() => {
    readRef.current = read;
  });

  // A layout effect: the clock starts in the commit that shows the running line, not some time after.
  useLayoutEffect(() => {
    if (!on) return undefined;
    let stopped = false;
    const timer = setInterval(() => {
      if (!document.hidden) run();
    }, periodMs);
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
      void readRef.current().then((result) => {
        if (result && !result.ok && result.failure.kind === 'unauthenticated') stop();
      });
    }
    document.addEventListener('visibilitychange', onVisible);
    return stop;
  }, [on, periodMs]);
}
