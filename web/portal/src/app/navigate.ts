// The router outlives every screen. A navigate from a screen that has already
// gone (a late 401, a late sign-in answer) would still change the address of
// whatever screen the user is on now, so it is dropped.
import { useCallback, useEffect, useRef } from 'react';
import { useNavigate, type NavigateOptions, type To } from 'react-router';

/** `navigate` that does nothing once the calling component has unmounted. */
export function useLiveNavigate(): (to: To, options?: NavigateOptions) => void | Promise<void> {
  const navigate = useNavigate();
  const mounted = useRef(false);
  useEffect(() => {
    mounted.current = true;
    return () => {
      mounted.current = false;
    };
  }, []);
  return useCallback(
    (to: To, options?: NavigateOptions) => (mounted.current ? navigate(to, options) : undefined),
    [navigate],
  );
}
