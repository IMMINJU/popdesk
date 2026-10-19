import { useCallback, useMemo, useState, type ReactNode } from 'react';
import { browserLocale, type Locale } from './locale';
import { sessionContext } from './sessionContext';

const SessionContext = sessionContext<unknown>();

/** The account's language, when `me` carries one (`Me.locale`). */
function accountLocale(me: unknown): Locale | null {
  if (typeof me !== 'object' || me === null || !('locale' in me)) return null;
  return me.locale === 'en' || me.locale === 'ko' ? me.locale : null;
}

/**
 * Puts the screen language in `<html lang>` (B5-AC-04). It runs while the provider draws, before
 * the screens below it, so their texts and the shared components read the new language at once.
 */
function showLocale(locale: Locale) {
  if (document.documentElement.lang !== locale) document.documentElement.lang = locale;
}

export function SessionProvider(props: { children: ReactNode }) {
  const [me, setMeState] = useState<unknown>(null);
  const [hadSession, setHadSession] = useState(false);
  const setMe = useCallback((next: unknown) => {
    setMeState(next);
    if (next) setHadSession(true);
  }, []);
  // Signed in: the account's language (B5-AC-01). Signed out: the browser's (B5-AC-02).
  showLocale(accountLocale(me) ?? browserLocale());
  const value = useMemo(() => ({ me, setMe, hadSession }), [me, setMe, hadSession]);
  return <SessionContext value={value}>{props.children}</SessionContext>;
}
