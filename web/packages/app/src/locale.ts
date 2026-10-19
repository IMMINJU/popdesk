// The screen language (b5 screens.md 1절): the account's `Me.locale` while signed in, the
// browser's first language before. `SessionProvider` works it out and keeps it in `<html lang>`
// (B5-AC-04); `t`, `tf` and the shared components read it there each time they are called, so a
// screen drawn after the language changed is in the new language. Texts come from @popdesk/i18n.
import { messages, type Locale, type MessageKey } from '@popdesk/i18n';
import { pageLocale } from '@popdesk/ui';

export type { Locale };

/** The language of the screen now: `<html lang>`, Korean when it is empty. */
export function screenLocale(): Locale {
  return pageLocale();
}

/** Before sign in: Korean when the browser's first language is Korean, English otherwise (B5-AC-02). */
export function browserLocale(): Locale {
  if (typeof navigator === 'undefined') return 'ko';
  const first = navigator.languages[0] ?? navigator.language;
  return first.toLowerCase().startsWith('ko') ? 'ko' : 'en';
}

export function t(key: MessageKey): string {
  return messages[screenLocale()][key];
}

/** `t` in a given language, e.g. a toast said just after the language was saved (B5-AC-03). */
export function tIn(locale: Locale, key: MessageKey): string {
  return messages[locale][key];
}

/**
 * `t` with the `{name}` places of the text filled in, e.g. `tf('inbox.count', { count: 3 })`.
 * `{count|issue|issues}` picks the first word when `count` is 1 and the second otherwise
 * (English singular and plural, B5-AC-08); Korean texts do not need it.
 */
export function tf(key: MessageKey, values: Record<string, string | number>): string {
  return t(key).replace(/\{(\w+)(?:\|([^|{}]*)\|([^|{}]*))?\}/g, (place, name: string, one?: string, other?: string) => {
    if (!(name in values)) return place;
    if (one === undefined || other === undefined) return String(values[name]);
    return Number(values[name]) === 1 ? one : other;
  });
}

/** A number in the screen language: 1,234 (B5-AC-07). */
export function formatNumber(value: number): string {
  return value.toLocaleString(screenLocale());
}

/**
 * A table whose fields read `t(key)` each time they are read, so a table made once, when the
 * module loads, still follows the screen language: `texts({ save: 'myInfo.save' }).save`.
 */
export function texts<K extends string>(keys: Readonly<Record<K, MessageKey>>): Readonly<Record<K, string>> {
  const table = {} as Record<K, string>;
  for (const name of Object.keys(keys) as K[]) {
    Object.defineProperty(table, name, { enumerable: true, get: () => t(keys[name]) });
  }
  return table;
}
