import { useCallback, useMemo, useState, type ReactNode } from 'react';
import { sessionContext } from './sessionContext';

const SessionContext = sessionContext<unknown>();

export function SessionProvider(props: { children: ReactNode }) {
  const [me, setMeState] = useState<unknown>(null);
  const [hadSession, setHadSession] = useState(false);
  const setMe = useCallback((next: unknown) => {
    setMeState(next);
    if (next) setHadSession(true);
  }, []);
  const value = useMemo(() => ({ me, setMe, hadSession }), [me, setMe, hadSession]);
  return <SessionContext value={value}>{props.children}</SessionContext>;
}
