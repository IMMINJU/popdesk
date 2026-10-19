// Words of slice 7a (7a-trash screens.md 2절, 3절): when the trash purges an issue, and the history line
// of moving an issue to the trash and back. Pure, so the sentences are checked without a screen.
import { t, tf } from './locale';

const DAY_MS = 24 * 60 * 60 * 1000;

/**
 * The 영구 삭제 cell of C-11: "{n}일 뒤", or "오늘" when less than 24 hours are left.
 *
 * `purgeAt` is the contract's `TrashedIssue.purgeAt` (an ISO date-time); `now` is milliseconds since the
 * epoch. Whole days are rounded up: 30 days left is "30일 뒤", 24 hours and a minute "2일 뒤", exactly
 * 24 hours "1일 뒤". Under 24 hours, a time already past, and a `purgeAt` that does not parse all say
 * "오늘": the hourly job is about to purge it.
 */
export function purgeInText(purgeAt: string, now: number): string {
  const left = Date.parse(purgeAt) - now;
  if (Number.isNaN(left) || left < DAY_MS) return t('trash.today');
  return tf('trash.inDays', { count: Math.ceil(left / DAY_MS) });
}

/**
 * The C-4 history line of a `TRASH` entry. `who` is the subject as the other lines start it ("최관리 님이",
 * "시스템이"; in English the bare name, "Lee Agent", "popdesk"). The new value `TRASHED` is a move to the trash, the old value `TRASHED` with no new value
 * a restore; any other pair, such as the purge (`TRASHED` → `PURGED`) or a value of a later release,
 * says only that the issue changed.
 */
export function trashHistoryText(who: string, oldValue: string | null, newValue: string | null): string {
  if (newValue === 'TRASHED') return tf('trash.historyTrashed', { who });
  if (oldValue === 'TRASHED' && newValue === null) return tf('trash.historyRestored', { who });
  return tf('trash.historyChanged', { who });
}
