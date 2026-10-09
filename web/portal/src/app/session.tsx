import { useCallback, useMemo, useState, type ReactNode } from 'react';
import { SessionContext, type Me } from './sessionContext';

export function SessionProvider(props: { children: ReactNode }) {
  const [me, setMeState] = useState<Me | null>(null);
  const [hadSession, setHadSession] = useState(false);
  const setMe = useCallback((next: Me | null) => {
    setMeState(next);
    if (next) setHadSession(true);
  }, []);
  const value = useMemo(() => ({ me, setMe, hadSession }), [me, setMe, hadSession]);
  return <SessionContext value={value}>{props.children}</SessionContext>;
}
