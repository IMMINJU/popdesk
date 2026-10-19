// How the console writes SLA minutes (8a-sla screens.md 1·2·3·4절). The minutes are business
// minutes the server counted; the screen never counts them from a due time. A day is 24 hours.

import { tf } from './locale';

const DAY = 24 * 60;

/** A length of minutes: "8시간", "2일 4시간", "3시간 20분", "0분". */
export function slaLength(minutes: number): string {
  const whole = Math.abs(Math.trunc(minutes));
  const days = Math.floor(whole / DAY);
  const hours = Math.floor((whole % DAY) / 60);
  const rest = whole % 60;
  const parts = [days > 0 ? tf('sla.days', { n: days }) : '', hours > 0 ? tf('sla.hours', { n: hours }) : '', rest > 0 ? tf('sla.minutes', { n: rest }) : ''].filter(Boolean);
  return parts.length === 0 ? tf('sla.minutes', { n: 0 }) : parts.join(' ');
}

/** The time left of a running goal: "3시간 20분 남음", or past its due time "1일 2시간 넘김". */
export function slaWords(remainingMinutes: number): string {
  return tf(remainingMinutes < 0 ? 'sla.over' : 'sla.left', { length: slaLength(remainingMinutes) });
}
