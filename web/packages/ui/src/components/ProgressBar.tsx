export interface ProgressBarProps {
  /** Accessible name, e.g. the file name. */
  label: string;
  /** 0..1. Absent means 모름 (spinner, no `aria-valuenow`). */
  value?: number;
}

export function ProgressBar(props: ProgressBarProps) {
  const percent = props.value === undefined ? undefined : Math.round(props.value * 100);
  return (
    <div
      data-pd="ProgressBar"
      role="progressbar"
      aria-label={props.label}
      aria-valuemin={0}
      aria-valuemax={100}
      aria-valuenow={percent}
    />
  );
}
