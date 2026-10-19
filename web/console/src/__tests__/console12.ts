// More test support for slice 12 (design/archive/12-mailin/screens.md). The earlier support files stay as
// they are; slice 12 adds no endpoint, so this file holds the `mailIn` part of the system status, in the
// shape of docs/integrate/openapi-console.json, and the words the 12 screen tests share.
import type { ConsoleComponents } from '@popdesk/api/console';
import { mailInOff, state, text, type SystemStatus } from './console';
import { before } from './console4b';
import { contract } from './console6a';

type Schemas = ConsoleComponents['schemas'];
export type MailInStatus = Schemas['MailInStatus'];
export type MailInFailureKind = NonNullable<MailInStatus['lastFailure']>['kind'];

// ---------------------------------------------------------------------------
// C-18 시스템 상태 (screens.md "C-18 시스템 상태: 메일 받기")

export const MAIL_IN_TITLE = '메일 받기';
export const NOT_SET = '설정 없음';
export const HOW_TO = '메일 받기 설정 방법';
export const SANDBOX_OFF = '꺼짐(샌드박스)';
export const ON = '켜짐';
export const NO_SUCCESS = '아직 없음';
/** The kinds screens.md gives a sentence for. `UNSUPPORTED` has none there (see the 12 web test report). */
export const FAILURE_WORDS: Readonly<Record<MailInFailureKind, string>> = {
  CONNECT: '메일 서버에 연결하지 못함',
  AUTH: '로그인이 거절됨',
  FOLDER: '받는 폴더가 없음',
  UNSUPPORTED: '메일 서버가 메일 옮기기를 지원하지 않음',
};
export const QUARANTINE_NOTE = '메일함의 격리 폴더(설정 IMAP_QUARANTINE_FOLDER)에서 확인하세요.';
export const COUNT_NOTE = '회차와 실패 수는 앱이 다시 시작하면 0부터 셉니다.';
export const roundFailuresText = (count: number) => `실패한 회차: ${count.toLocaleString('ko')}회`;
export const retryingText = (count: number) => `다시 시도할 메일: ${count.toLocaleString('ko')}통`;
export const quarantinedText = (count: number) => `격리한 메일(90일): ${count.toLocaleString('ko')}통`;

/**
 * The first `<time>` of `part` inside or after the innermost element that holds `words`, or null. It reads one line
 * ("마지막 성공: <time>") and a term with its value (`<dt>마지막 성공</dt><dd><time>`) the same way.
 */
export function timeAfter(part: HTMLElement, words: string): HTMLTimeElement | null {
  const label = Array.from(part.querySelectorAll('*'))
    .filter((el) => text(el).includes(words))
    .at(-1);
  return label ? (Array.from(part.querySelectorAll('time')).find((t) => before(label, t)) ?? null) : null;
}

/** The instant a `<time>` stands for, NaN without one. */
export const timeOf = (el: HTMLTimeElement | null) => new Date(el?.getAttribute('datetime') ?? '').getTime();

/** Receiving email on, with no round and no failure yet unless given. */
export function mailInOn(over: Partial<MailInStatus> = {}): MailInStatus {
  return { enabled: true, offReason: null, lastSuccessAt: null, roundFailures: 0, lastFailure: null, retrying: 0, quarantined90d: 0, ...over };
}

/** Receiving email off in a sandbox (S12-AC-03): the settings are there, the counts are 0. */
export function mailInSandbox(): MailInStatus {
  return { ...mailInOff(), offReason: 'SANDBOX' };
}

/** The system status with this `mailIn` part and the rest as `base` (console.ts by default), after the contract check. */
export function withMailIn(mailIn: MailInStatus, base: SystemStatus = state.status): SystemStatus {
  return contract('getSystemStatus', 200, { ...base, mailIn });
}
