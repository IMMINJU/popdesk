import { en } from './en';
import { ko } from './ko';

export { en, ko };

/** The languages an account can have (MAIL-1). */
export type Locale = 'ko' | 'en';
export type MessageKey = keyof typeof ko;

export const messages: Record<Locale, Record<MessageKey, string>> = { ko, en };
