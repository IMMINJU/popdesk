// OIDC sign-in on the login screens of both apps (11 묶음 screens.md "C-1·P-1 로그인", design.md 0절 9):
// whether the host offers it, the start address, and the band an `oidc` query value shows. The button
// is OidcSignIn.tsx. The apps take these through ./hooks and ./bands (the export paths are fixed).
import { useEffect, useState } from 'react';
import { useSearchParams } from 'react-router';
import { texts } from './locale';
import type { Result } from './result';

/** Where the button moves the page; the server checks `next` (S11-AC-31). */
export const OIDC_START = '/api/public/oidc/start';

/** The start address with `next` as given, or without a query when there is none. */
export function oidcStartUrl(next: string | null): string {
  return next === null ? OIDC_START : `${OIDC_START}?${new URLSearchParams({ next }).toString()}`;
}

/** The band each `oidc` value the server sends back to the login screen shows (S11-AC-32). */
const OIDC_BANDS: Readonly<Record<string, string>> = texts({
  failed: 'oidc.failed',
  unavailable: 'oidc.unavailable',
  busy: 'oidc.busy',
});

/**
 * Asks `load` (the app's own sign-in-options call) once when the screen opens. False while it asks
 * and when it fails, so the screen stays as it was without OIDC. `load` must keep its identity.
 */
export function useSignInOptions(load: () => Promise<Result<{ oidc: boolean }>>): boolean {
  const [oidc, setOidc] = useState(false);
  useEffect(() => {
    let live = true;
    void load().then((result) => {
      if (live && result.ok) setOidc(result.data.oidc);
    });
    return () => {
      live = false;
    };
  }, [load]);
  return oidc;
}

export interface OidcBand {
  /** The text of the band; null without an `oidc` value or with one the screen does not know. */
  text: string | null;
  /** Takes the band away, as a password sign-in does. */
  clear: () => void;
}

/**
 * The band of the `oidc` query the screen opened with. The value leaves the address at once
 * (replace, the other queries stay), so a reload does not show the band again.
 */
export function useOidcBand(): OidcBand {
  const [params, setParams] = useSearchParams();
  const [text, setText] = useState<string | null>(() => {
    const value = params.get('oidc');
    return value !== null && Object.hasOwn(OIDC_BANDS, value) ? (OIDC_BANDS[value] ?? null) : null;
  });
  const inAddress = params.has('oidc');

  useEffect(() => {
    if (!inAddress) return;
    setParams(
      (now) => {
        const kept = new URLSearchParams(now);
        kept.delete('oidc');
        return kept;
      },
      { replace: true },
    );
  }, [inAddress, setParams]);

  return {
    text,
    clear: () => {
      setText(null);
    },
  };
}
