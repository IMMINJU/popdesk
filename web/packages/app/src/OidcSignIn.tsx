// The OIDC button of both login screens (11 묶음 screens.md "C-1·P-1 로그인", acceptance.md 5절 16): a thin
// line with "또는" under the password form, then "회사 계정으로 로그인". The screen draws it only when the host
// offers OIDC (useSignInOptions in oidc.ts).
import { Button } from '@popdesk/ui';
import { useEffect, useRef, useState } from 'react';
import { t } from './locale';
import { oidcStartUrl } from './oidc';

export interface OidcSignInProps {
  /** The `next` of the address as it is, unchecked; null without one. */
  next: string | null;
  /** Moves the page. Default `location.assign`: not a form submit (CSP `form-action`). */
  navigate?: (url: string) => void;
}

function assign(url: string) {
  window.location.assign(url);
}

export function OidcSignIn({ next, navigate = assign }: OidcSignInProps) {
  const [moving, setMoving] = useState(false);
  const gone = useRef(false);

  // Back from the provider, a page shown again from the cache says the button again (C-7 웹 점검 13).
  useEffect(() => {
    const onShow = (e: PageTransitionEvent) => {
      if (!e.persisted) return;
      gone.current = false;
      setMoving(false);
    };
    window.addEventListener('pageshow', onShow);
    return () => {
      window.removeEventListener('pageshow', onShow);
    };
  }, []);

  return (
    <div className="pd-oidc-sign-in">
      <p className="pd-oidc-or">{t('oidc.or')}</p>
      <Button
        variant="secondary"
        pending={moving}
        pendingLabel={t('oidc.moving')}
        onClick={() => {
          if (gone.current) return;
          gone.current = true;
          setMoving(true);
          navigate(oidcStartUrl(next));
        }}
      >
        {t('oidc.signIn')}
      </Button>
    </div>
  );
}
