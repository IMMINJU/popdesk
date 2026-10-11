import { useCallback, useEffect, useMemo, useState, type ReactNode } from 'react';
import { LocaleContext, type Locale } from './locale';
import { SessionContext, type Me } from './sessionContext';

export function SessionProvider(props: { children: ReactNode }) {
  const [me, setMeState] = useState<Me | null>(null);
  const [hadSession, setHadSession] = useState(false);
  // Signed out, the screens speak the language of the page, or of the account last seen in this tab.
  const [locale, setLocale] = useState<Locale>(() => (document.documentElement.lang === 'en' ? 'en' : 'ko'));
  const setMe = useCallback((next: Me | null) => {
    setMeState(next);
    if (next) {
      setHadSession(true);
      setLocale(next.locale);
    }
  }, []);
  useEffect(() => {
    document.documentElement.lang = locale;
  }, [locale]);
  const value = useMemo(() => ({ me, setMe, hadSession }), [me, setMe, hadSession]);
  return (
    <SessionContext value={value}>
      <LocaleContext value={locale}>{props.children}</LocaleContext>
    </SessionContext>
  );
}
