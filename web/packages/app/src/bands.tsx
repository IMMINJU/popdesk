// Bands for a failed save (screens.md 2절): 401 with a login link in a new tab,
// 403, 400 without fields, 409, 5xx, and the 429 countdown. The texts are the
// ones both apps share (messages.ts) unless others come in as `words`.
import { Alert, FormErrorSummary, type FieldError } from '@popdesk/ui';
import { failureText, rateLimitedText, TEXT } from './messages';
import type { Failure } from './result';

// Slice 11: the OIDC button under the login form (OidcSignIn.tsx); the export paths of this package are fixed.
export { OidcSignIn, type OidcSignInProps } from './OidcSignIn';

export interface SaveFailureWords {
  openLogin: string;
  saveSessionEnded: string;
  failureText: (failure: Failure, known?: Record<string, string>) => string;
}

export interface RateLimitWords {
  canRetry: string;
  rateLimitedText: (seconds: number) => string;
}

const SAVE_WORDS: SaveFailureWords = { openLogin: TEXT.openLogin, saveSessionEnded: TEXT.saveSessionEnded, failureText };
const RATE_WORDS: RateLimitWords = { canRetry: TEXT.canRetry, rateLimitedText };

/**
 * The band for `failure`. 400 with fields goes to the summary band and 429 to
 * RateLimitBand, so both draw nothing here.
 */
export function SaveFailure(props: { failure: Failure | null; known?: Record<string, string>; words?: SaveFailureWords }) {
  const { failure, words = SAVE_WORDS } = props;
  if (!failure || failure.kind === 'invalid' || failure.kind === 'rateLimited') return null;
  if (failure.kind === 'unauthenticated') {
    return (
      <Alert
        tone="error"
        action={
          <a href="/login" target="_blank" rel="noopener">
            {words.openLogin}
          </a>
        }
      >
        {words.saveSessionEnded}
      </Alert>
    );
  }
  return <Alert tone="error">{words.failureText(failure, props.known)}</Alert>;
}

/**
 * 429: the seconds fall on the screen, but a screen reader hears the first text
 * once; the live region is not read again every second. When sending is open
 * again, one polite notice says so.
 */
export function RateLimitBand(props: { active: boolean; left: number; initial: number; ended: boolean; words?: RateLimitWords }) {
  const { words = RATE_WORDS } = props;
  if (props.active) {
    return (
      <Alert tone="error">
        <span aria-hidden="true">{words.rateLimitedText(props.left)}</span>
        <span className="pd-sr-only">{words.rateLimitedText(props.initial)}</span>
      </Alert>
    );
  }
  if (props.ended) return <Alert tone="info">{words.canRetry}</Alert>;
  return null;
}

/** Summary band above a form after a 400 with fields (useFieldErrors().summary). */
export function FieldSummary(props: { summary: { title: string; items: FieldError[]; serverMessage?: string } | null }) {
  const { summary } = props;
  if (!summary) return null;
  return (
    <FormErrorSummary
      title={summary.title}
      errors={summary.items}
      {...(summary.serverMessage === undefined ? {} : { serverMessage: summary.serverMessage })}
    />
  );
}
