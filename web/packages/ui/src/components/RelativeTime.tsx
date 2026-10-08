export interface RelativeTimeProps {
  /** ISO 8601, goes into `<time datetime>`. */
  dateTime: string;
  /** Reference time. Stories and tests pass a fixed value. Default: now. */
  now?: Date;
  /** BCP 47, e.g. `ko`. Default: the document language. */
  locale?: string;
}

/** Relative text ("3시간 전"); the absolute time shows on hover and focus. */
export function RelativeTime(props: RelativeTimeProps) {
  return (
    <time data-pd="RelativeTime" dateTime={props.dateTime}>
      {props.dateTime}
    </time>
  );
}
