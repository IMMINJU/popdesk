// The language of the screens: the account's own (`Me.locale`, S1A-AC-16). Only
// the slice 1a screens read their texts by language so far (@popdesk/i18n); the
// first-slice screens still take Korean from `messages.ts`.
import { messages, type Locale, type MessageKey } from '@popdesk/i18n';
import { createContext, useCallback, useContext } from 'react';

export type { Locale, MessageKey };

export const LocaleContext = createContext<Locale>('ko');

export function useLocale(): Locale {
  return useContext(LocaleContext);
}

export function textIn(locale: Locale, key: MessageKey): string {
  return messages[locale][key];
}

/** Texts in the language of the screens now; a saved language change draws them again. */
export function useT(): (key: MessageKey) => string {
  const locale = useLocale();
  return useCallback((key: MessageKey) => textIn(locale, key), [locale]);
}
