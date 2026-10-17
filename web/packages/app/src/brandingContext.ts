// The branding of the whole app: display name, accent colors, logos (slice 9 design.md 4절).
// `@popdesk/app` does not know the API client: the app passes the public call as `load`,
// the way `useUnreadCount` takes it. Without a provider everything is the default.
import { createContext, useContext } from 'react';
import { TEXT } from './messages';

/** One theme's accent colors, each `#RRGGBB` in capitals, made by the server (design.md 3절). */
export interface ThemeColors {
  accent: string;
  accentHover: string;
  onAccent: string;
  accentSubtle: string;
}

/** The accent colors of both themes. */
export interface AccentColors {
  light: ThemeColors;
  dark: ThemeColors;
}

/**
 * Logo URLs per theme, each `/api/public/branding/logos/{theme}?v=…` or `null`. When only one theme
 * has a logo the server gives the same URL in both.
 */
export interface BrandingLogos {
  light: string | null;
  dark: string | null;
}

/** `GET /api/public/branding`. `colors` is `null` for the default accent of `tokens.css`. */
export interface PublicBranding {
  displayName: string;
  colors: AccentColors | null;
  logos: BrandingLogos;
}

/** What `useBranding()` gives. */
export interface BrandingState {
  /** "popdesk" until the branding is read. */
  displayName: string;
  /** `null`: the default accent of `tokens.css`. */
  colors: AccentColors | null;
  /** `null` until the branding is read, and without a provider. */
  logos: BrandingLogos | null;
  /**
   * From the C-14 save answer only. It leaves the logos alone, so a late save answer does not put
   * an old logo back.
   */
  setNameAndColors: (next: { displayName: string; colors: AccentColors | null }) => void;
  /**
   * From the C-14 logo answer only (`url` after an upload, `null` after a removal). It leaves the
   * name and colors alone.
   */
  setLogo: (theme: 'light' | 'dark', url: string | null) => void;
}

export const DEFAULT_BRANDING: BrandingState = {
  displayName: TEXT.displayName,
  colors: null,
  logos: null,
  setNameAndColors: () => undefined,
  setLogo: () => undefined,
};

export const BrandingContext = createContext<BrandingState>(DEFAULT_BRANDING);

/** The branding of the nearest `BrandingProvider`, or the default without one. */
export function useBranding(): BrandingState {
  return useContext(BrandingContext);
}

/**
 * The eight `tokens.css` variables for `colors`: `--brand-accent-light`, `--brand-accent-dark`,
 * `--brand-accent-hover-light`, `--brand-accent-hover-dark`, `--brand-on-accent-light`,
 * `--brand-on-accent-dark`, `--brand-accent-subtle-light`, `--brand-accent-subtle-dark`, each a
 * plain hex. `null` gives `{}` (the defaults of `tokens.css`). Hex only, never a `light-dark()`
 * string: the build lowers the one in `tokens.css`, not one set at run time (design.md 0절 16).
 *
 * Shell (C-4): gives `{}` for now; C-9 fills it.
 */
// A typed const, not a function, while the shell has no use for its argument.
export const brandingStyle: (colors: AccentColors | null) => Record<string, string> = () => ({});

/**
 * Checks a public branding answer or the value kept in `localStorage` (design.md 4절):
 * `displayName` 1 to 50 characters, every color `#RRGGBB`, each logo `null` or a URL that starts
 * with `/api/public/branding/logos/`. Anything else gives `null`, and the caller keeps what it
 * has. Needed because the story fake server answers an unknown GET with a list-shaped 200.
 *
 * Shell (C-4): gives `null` for now; C-9 fills it.
 */
export const parseBranding: (value: unknown) => PublicBranding | null = () => null;
