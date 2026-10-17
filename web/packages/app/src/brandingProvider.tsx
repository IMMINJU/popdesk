import type { ReactNode } from 'react';
import type { Result } from './result';
import { BrandingContext, DEFAULT_BRANDING, type PublicBranding } from './brandingContext';

/**
 * Holds the branding for the app; the app puts it outside `SessionProvider` (design.md 4절).
 *
 * What it does from C-9: the first render starts from the value kept in `localStorage` (when it
 * passes `parseBranding`, else the default), and a layout effect sets the eight variables of
 * `brandingStyle` on `document.documentElement` before the first paint. Unmounting removes them.
 * After mounting it calls `load` once (the app's public branding call; it must keep its identity);
 * an answer that passes `parseBranding` is kept in `localStorage` (try/catch) and shown. A failure
 * keeps what is shown.
 *
 * Shell (C-4): only the default context around `children`; `load` is not called.
 */
export function BrandingProvider(props: { load: () => Promise<Result<PublicBranding>>; children: ReactNode }) {
  return <BrandingContext value={DEFAULT_BRANDING}>{props.children}</BrandingContext>;
}
