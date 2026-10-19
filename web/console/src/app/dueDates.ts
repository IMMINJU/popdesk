// How the console writes a due date (6a-labels screens.md 1·2·3절). A due date is a day, `YYYY-MM-DD`,
// with no time and no time zone: it is written as it is, never moved by the browser's time zone.
import { screenLocale } from './locale';

function parts(iso: string): [number, number, number] {
  const [y, m, d] = iso.split('-').map(Number);
  return [y ?? 0, m ?? 0, d ?? 0];
}

/** The day of `iso` in the screen language. The day is put at UTC midnight and written in UTC, so it never moves. */
function dayIn(iso: string, withYear: boolean): string {
  const [y, m, d] = parts(iso);
  const options: Intl.DateTimeFormatOptions = { month: 'short', day: 'numeric', timeZone: 'UTC', ...(withYear ? { year: 'numeric' } : {}) };
  return new Intl.DateTimeFormat(screenLocale(), options).format(Date.UTC(y, m - 1, d));
}

/** `2026년 10월 20일`, `Oct 20, 2026`: C-4 and the history. */
export function longDate(iso: string): string {
  return dayIn(iso, true);
}

/** `10월 20일`, or `2027년 1월 5일` outside the browser's year: C-3. */
export function shortDate(iso: string, now: Date = new Date()): string {
  return dayIn(iso, parts(iso)[0] !== now.getFullYear());
}

/**
 * Today in the browser, `YYYY-MM-DD`: only the `min` hint of the date input. Whether a date is
 * past is the server's call (`overdue`, the 400 of a past date), in the installation's time zone.
 */
export function todayInBrowser(now: Date = new Date()): string {
  const pad = (n: number) => String(n).padStart(2, '0');
  return `${String(now.getFullYear())}-${pad(now.getMonth() + 1)}-${pad(now.getDate())}`;
}
