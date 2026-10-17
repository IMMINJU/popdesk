import { useCallback, useEffect, useLayoutEffect, useMemo, useRef, useState, type ReactNode } from 'react';
import type { Result } from './result';
import {
  BRAND_VARIABLES,
  BrandingContext,
  brandingStyle,
  parseBranding,
  type AccentColors,
  type BrandingLogos,
  type BrandingState,
  type PublicBranding,
} from './brandingContext';
import { TEXT } from './messages';

/** The one key of this browser's kept branding: the whole public answer as JSON (design.md 10절). */
const BRANDING_KEY = 'popdesk.branding';

interface Held {
  displayName: string;
  colors: AccentColors | null;
  /** Each theme's own logo, told by the theme in the URL path; the shown pair falls back to the other. */
  own: BrandingLogos;
}

const DEFAULT_HELD: Held = { displayName: TEXT.displayName, colors: null, own: { light: null, dark: null } };

function themeOfUrl(url: string): 'light' | 'dark' | null {
  const path = url.split('?')[0] ?? '';
  if (path.endsWith('/light')) return 'light';
  if (path.endsWith('/dark')) return 'dark';
  return null;
}

function heldOf(branding: PublicBranding): Held {
  const urls = [branding.logos.light, branding.logos.dark].filter((u): u is string => u !== null);
  return {
    displayName: branding.displayName,
    colors: branding.colors,
    own: {
      light: urls.find((u) => themeOfUrl(u) === 'light') ?? null,
      dark: urls.find((u) => themeOfUrl(u) === 'dark') ?? null,
    },
  };
}

function shownLogos(own: BrandingLogos): BrandingLogos {
  return { light: own.light ?? own.dark, dark: own.dark ?? own.light };
}

function publicOf(held: Held): PublicBranding {
  return { displayName: held.displayName, colors: held.colors, logos: shownLogos(held.own) };
}

function readKept(): Held {
  try {
    const raw = localStorage.getItem(BRANDING_KEY);
    const kept = raw === null ? null : parseBranding(JSON.parse(raw));
    return kept ? heldOf(kept) : DEFAULT_HELD;
  } catch {
    return DEFAULT_HELD;
  }
}

function keep(held: Held): void {
  try {
    localStorage.setItem(BRANDING_KEY, JSON.stringify(publicOf(held)));
  } catch {
    // A blocked storage only loses the head start of the next visit.
  }
}

/**
 * Holds the branding for the app; the app puts it outside `SessionProvider` (design.md 4절).
 *
 * The first render starts from the value kept in `localStorage` (when it passes `parseBranding`,
 * else the default), and a layout effect sets the eight variables of `brandingStyle` on
 * `document.documentElement` before the first paint; the default sets none, and nothing else is set
 * there. Unmounting removes them. After mounting it calls `load` once (the app's public branding
 * call; it must keep its identity); an answer that passes `parseBranding` is kept in `localStorage`
 * (try/catch) and shown. A failure keeps what is shown. An answer that comes after C-14 already set
 * the branding is older than what C-14 set, so it is dropped.
 */
export function BrandingProvider(props: { load: () => Promise<Result<PublicBranding>>; children: ReactNode }) {
  const { load } = props;
  const [held, setHeld] = useState<Held>(readKept);
  const changed = useRef(false);
  // Kept after a change only, never for the first value.
  const dirty = useRef(false);

  useLayoutEffect(() => {
    const style = document.documentElement.style;
    for (const [name, value] of Object.entries(brandingStyle(held.colors))) style.setProperty(name, value);
    return () => {
      for (const name of BRAND_VARIABLES) style.removeProperty(name);
    };
  }, [held.colors]);

  useEffect(() => {
    if (!dirty.current) return;
    dirty.current = false;
    keep(held);
  }, [held]);

  useEffect(() => {
    let live = true;
    void load().then((result) => {
      if (!live || !result.ok || changed.current) return;
      const branding = parseBranding(result.data);
      if (!branding) return;
      dirty.current = true;
      setHeld(heldOf(branding));
    });
    return () => {
      live = false;
    };
  }, [load]);

  const setNameAndColors = useCallback((next: { displayName: string; colors: AccentColors | null }) => {
    changed.current = true;
    dirty.current = true;
    setHeld((h) => ({ ...h, displayName: next.displayName, colors: next.colors }));
  }, []);

  const setLogo = useCallback((theme: 'light' | 'dark', url: string | null) => {
    changed.current = true;
    dirty.current = true;
    setHeld((h) => ({ ...h, own: { ...h.own, [theme]: url } }));
  }, []);

  const value = useMemo<BrandingState>(
    () => ({ displayName: held.displayName, colors: held.colors, logos: shownLogos(held.own), setNameAndColors, setLogo }),
    [held, setNameAndColors, setLogo],
  );
  return <BrandingContext value={value}>{props.children}</BrandingContext>;
}
