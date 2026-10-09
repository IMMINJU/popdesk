import { Spinner } from '../icons';

export interface ProgressBarProps {
  /** Accessible name, e.g. the file name. */
  label: string;
  /** 0..1. Absent means 모름 (spinner, no `aria-valuenow`). */
  value?: number;
}

export function ProgressBar(props: ProgressBarProps) {
  const percent = props.value === undefined ? undefined : Math.round(Math.min(Math.max(props.value, 0), 1) * 100);
  return (
    <div
      className="pd-progress"
      data-pd="ProgressBar"
      data-indeterminate={percent === undefined ? 'true' : undefined}
      role="progressbar"
      aria-label={props.label}
      aria-valuemin={0}
      aria-valuemax={100}
      aria-valuenow={percent}
    >
      {percent === undefined ? (
        <Spinner size={14} />
      ) : (
        <span className="pd-progress-track" aria-hidden="true">
          <span className="pd-progress-fill" style={{ width: `${String(percent)}%` }} />
        </span>
      )}
    </div>
  );
}
