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
  /**
   * The logo shown in each theme: its own, else the other theme's. `null` without a provider.
   */
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
 */
export function brandingStyle(colors: AccentColors | null): Record<string, string> {
  if (colors === null) return {};
  const { light, dark } = colors;
  return {
    '--brand-accent-light': light.accent,
    '--brand-accent-dark': dark.accent,
    '--brand-accent-hover-light': light.accentHover,
    '--brand-accent-hover-dark': dark.accentHover,
    '--brand-on-accent-light': light.onAccent,
    '--brand-on-accent-dark': dark.onAccent,
    '--brand-accent-subtle-light': light.accentSubtle,
    '--brand-accent-subtle-dark': dark.accentSubtle,
  };
}

/** The names of the eight variables, for removing them. */
export const BRAND_VARIABLES: readonly string[] = Object.keys(
  brandingStyle({
    light: { accent: '', accentHover: '', onAccent: '', accentSubtle: '' },
    dark: { accent: '', accentHover: '', onAccent: '', accentSubtle: '' },
  }),
);

/**
 * Checks a public branding answer or the value kept in `localStorage` (design.md 4절):
 * `displayName` 1 to 50 characters, every color `#RRGGBB`, each logo `null` or a URL that starts
 * with `/api/public/branding/logos/`. Anything else gives `null`, and the caller keeps what it
 * has. Needed because the story fake server answers an unknown GET with a list-shaped 200.
 */
export function parseBranding(value: unknown): PublicBranding | null {
  if (!isRecord(value)) return null;
  const { displayName, colors, logos } = value;
  if (typeof displayName !== 'string') return null;
  const length = Array.from(displayName).length;
  if (length < 1 || length > 50) return null;
  const parsedColors = colors === null ? null : accentColorsOf(colors);
  if (parsedColors === undefined) return null;
  if (!isRecord(logos)) return null;
  const light = logoOf(logos.light);
  const dark = logoOf(logos.dark);
  if (light === undefined || dark === undefined) return null;
  return { displayName, colors: parsedColors, logos: { light, dark } };
}

const HEX = /^#[0-9A-Fa-f]{6}$/;
const LOGO_PATH = '/api/public/branding/logos/';

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

/** `undefined` when `value` is not two themes of four `#RRGGBB` each. */
function accentColorsOf(value: unknown): AccentColors | undefined {
  if (!isRecord(value)) return undefined;
  const light = themeColorsOf(value.light);
  const dark = themeColorsOf(value.dark);
  return light && dark ? { light, dark } : undefined;
}

function themeColorsOf(value: unknown): ThemeColors | undefined {
  if (!isRecord(value)) return undefined;
  const { accent, accentHover, onAccent, accentSubtle } = value;
  const all = [accent, accentHover, onAccent, accentSubtle];
  if (!all.every((c) => typeof c === 'string' && HEX.test(c))) return undefined;
  return { accent, accentHover, onAccent, accentSubtle } as ThemeColors;
}

/** `undefined` when `value` is neither `null` nor a public logo URL of this host. */
function logoOf(value: unknown): string | null | undefined {
  if (value === null) return null;
  return typeof value === 'string' && value.startsWith(LOGO_PATH) ? value : undefined;
}
