import { BrandLogo } from './BrandLogo';

export interface AuthBrandProps {
  /** The branding display name, "popdesk" by default. Up to 50 characters. */
  displayName: string;
  /**
   * Branding logos, one URL per theme; the same URL twice when only one theme has a logo.
   * Absent: the name alone, as before.
   */
  logo?: { light: string; dark: string };
}

/**
 * The logo and display name above the forms before log in (login, invitation, password screens).
 *
 * With a logo (slice 9 screens.md 1절): the logo at most 40px high, ratio kept, above the name. Two
 * `<img alt="">` and CSS shows the one of the current theme by the token conditions (`data-theme`,
 * else `prefers-color-scheme`); one `<img>` when both URLs are the same. A long name (50
 * characters) wraps with `word-break: keep-all` and `overflow-wrap: anywhere`.
 */
export function AuthBrand(props: AuthBrandProps) {
  return (
    <div className="pd-auth-brand" data-pd="AuthBrand">
      {props.logo && (
        <span className="pd-auth-brand-logo">
          <BrandLogo logo={props.logo} />
        </span>
      )}
      <p className="pd-auth-brand-name">{props.displayName}</p>
    </div>
  );
}
