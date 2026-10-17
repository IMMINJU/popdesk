// The branding of this app, read from the public branding call (slice 9 design.md 4절).
import type { BrandingLogos } from '@popdesk/app/branding';
import { api } from '../api';
import { call } from './result';

export { BrandingProvider, useBranding } from '@popdesk/app/branding';

/** The public branding call `BrandingProvider` makes once; a module constant so it keeps its identity. */
export const loadBranding = () => call(api.GET('/api/public/branding'));

/** The `logo` of `AppShell` and `AuthBrand`: absent when no theme has a logo. */
export function logoOf(logos: BrandingLogos | null): { light: string; dark: string } | undefined {
  return logos?.light && logos.dark ? { light: logos.light, dark: logos.dark } : undefined;
}
