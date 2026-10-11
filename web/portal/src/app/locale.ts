// The screens are Korean only for now: an account's language (`Me.locale`) is the
// language of its mail, not of the screens (S1A-AC-16). The slice 1a screens take
// their texts from @popdesk/i18n; the first-slice screens still take Korean from
// `messages.ts`.
import { ko, type Locale, type MessageKey } from '@popdesk/i18n';

export type { Locale };

export function t(key: MessageKey): string {
  return ko[key];
}
