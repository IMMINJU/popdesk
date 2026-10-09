import { useId, useState } from 'react';

export interface RelativeTimeProps {
  /** ISO 8601, goes into `<time datetime>`. */
  dateTime: string;
  /** Reference time. Stories and tests pass a fixed value. Default: now. */
  now?: Date;
  /** BCP 47, e.g. `ko`. Default: the document language. */
  locale?: string;
  /**
   * Default true: a Tab stop that shows the absolute time on focus. Pass false
   * inside a row or card that is already one link (one Tab per row, screens.md P-3);
   * the absolute time is then in `title`.
   */
  focusable?: boolean;
}

const STEPS: [Intl.RelativeTimeFormatUnit, number][] = [
  ['second', 60],
  ['minute', 60],
  ['hour', 24],
  ['day', 7],
  ['week', 4.35],
  ['month', 12],
  ['year', Number.POSITIVE_INFINITY],
];

function pageLocale(locale: string | undefined): string {
  if (locale) return locale;
  if (typeof document !== 'undefined' && document.documentElement.lang) return document.documentElement.lang;
  return typeof navigator === 'undefined' ? 'en' : navigator.language;
}

/** "3시간 전", "그저께", "방금 전" style text for `at` seen from `now`. */
function relativeText(at: Date, now: Date, locale: string): string {
  const format = new Intl.RelativeTimeFormat(locale, { numeric: 'auto' });
  let value = (at.getTime() - now.getTime()) / 1000;
  for (const [unit, size] of STEPS) {
    if (Math.abs(value) < size) return format.format(Math.round(value), unit);
    value /= size;
  }
  return format.format(Math.round(value), 'year');
}

function absoluteText(at: Date, locale: string): string {
  return new Intl.DateTimeFormat(locale, { dateStyle: 'long', timeStyle: 'short' }).format(at);
}

/** Relative text ("3시간 전"); the absolute time shows on hover and focus. */
export function RelativeTime(props: RelativeTimeProps) {
  const tipId = useId();
  const [mountedAt] = useState(() => new Date());
  const [shown, setShown] = useState(false);
  const locale = pageLocale(props.locale);
  const at = new Date(props.dateTime);
  if (Number.isNaN(at.getTime())) return <time dateTime={props.dateTime}>{props.dateTime}</time>;
  const absolute = absoluteText(at, locale);
  const focusable = props.focusable ?? true;
  return (
    <span className="pd-time" data-pd="RelativeTime">
      <time
        dateTime={props.dateTime}
        title={absolute}
        tabIndex={focusable ? 0 : undefined}
        aria-describedby={focusable && shown ? tipId : undefined}
        onMouseEnter={() => {
          setShown(true);
        }}
        onMouseLeave={() => {
          setShown(false);
        }}
        onFocus={() => {
          setShown(true);
        }}
        onBlur={() => {
          setShown(false);
        }}
      >
        {relativeText(at, props.now ?? mountedAt, locale)}
      </time>
      {focusable && shown && (
        <span id={tipId} role="tooltip" className="pd-tooltip">
          {absolute}
        </span>
      )}
    </span>
  );
}
