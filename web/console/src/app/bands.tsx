// The shared bands with this app's texts.
import { RateLimitBand as RateLimitBandWith, SaveFailure as SaveFailureWith } from '@popdesk/app/bands';
import { failureText, rateLimitedText, TEXT } from './messages';
import type { Failure } from './result';

export { FieldSummary } from '@popdesk/app/bands';

const SAVE_WORDS = { openLogin: TEXT.openLogin, saveSessionEnded: TEXT.saveSessionEnded, failureText };
const RATE_WORDS = { canRetry: TEXT.canRetry, rateLimitedText };

export function SaveFailure(props: { failure: Failure | null; known?: Record<string, string> }) {
  return <SaveFailureWith {...props} words={SAVE_WORDS} />;
}

export function RateLimitBand(props: { active: boolean; left: number; initial: number; ended: boolean }) {
  return <RateLimitBandWith {...props} words={RATE_WORDS} />;
}
