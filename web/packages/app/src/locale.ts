// The screens are Korean only for now: an account's language (`Me.locale`) is the
// language of its mail, not of the screens (S1A-AC-16). The slice 1a screens take
// their texts from @popdesk/i18n; the first-slice screens still take Korean from
// `messages.ts`.
import { ko, type Locale, type MessageKey } from '@popdesk/i18n';

export type { Locale };

export function t(key: MessageKey): string {
  return ko[key];
}

/** `t` with the `{name}` places of the text filled in, e.g. `tf('inbox.count', { count: 3 })`. */
export function tf(key: MessageKey, values: Record<string, string | number>): string {
  return t(key).replace(/\{(\w+)\}/g, (place, name: string) => (name in values ? String(values[name]) : place));
}
