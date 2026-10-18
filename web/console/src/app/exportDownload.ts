// The check before a download that C-10 (정보 내보내기) and C-12 (감사 로그 내보내기)
// share (10a design.md 0절 11): `HEAD` first, so a 503 or a 403 shows a band
// instead of an error page, then the browser goes to the same address.
import { failureText } from './messages';
import { failureOf, retryAfter } from './result';

export type ExportCheck = { ok: true } | { ok: false; status: number; text: string };

export interface ExportOptions {
  /** Starts the download; the screen passes `window.location.assign`, or drops it when the answer came too late. */
  navigate: (url: string) => void;
  /** The 503 band with the seconds of `Retry-After`, or null when the answer had none. */
  busyText: (seconds: number | null) => string;
  /** Codes the screen has its own text for (NOT_FOUND for a 404), as for `failureText`. */
  known?: Record<string, string>;
}

/** Checks asked and not answered yet, by address: a second click meanwhile gets the same answer. */
const pending = new Map<string, Promise<ExportCheck>>();

/**
 * Asks `HEAD url`; on 200 calls `navigate(url)` once and gives `{ ok: true }`. Otherwise
 * it does not navigate and gives the status (0 for a lost connection) with the band text.
 * A 401 is given back like the others: the screen sends the person to the login.
 */
export function startExport(url: string, options: ExportOptions): Promise<ExportCheck> {
  const asked = pending.get(url);
  if (asked) return asked;
  const check = ask(url, options).finally(() => pending.delete(url));
  pending.set(url, check);
  return check;
}

async function ask(url: string, options: ExportOptions): Promise<ExportCheck> {
  let response: Response;
  try {
    response = await fetch(url, { method: 'HEAD', credentials: 'same-origin' });
  } catch {
    return { ok: false, status: 0, text: failureText({ kind: 'network' }) };
  }
  if (response.ok) {
    options.navigate(url);
    return { ok: true };
  }
  const status = response.status;
  // The 503 of a `HEAD` has no body to tell it by, only the status.
  if (status === 503) {
    return { ok: false, status, text: options.busyText(response.headers.has('Retry-After') ? retryAfter(response) : null) };
  }
  return { ok: false, status, text: failureText(failureOf(response, undefined), options.known) };
}
