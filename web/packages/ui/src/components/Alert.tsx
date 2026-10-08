import type { ReactNode } from 'react';

/** 오류, 경고, 안내, 성공. */
export type AlertTone = 'error' | 'warning' | 'info' | 'success';

export interface AlertProps {
  tone: AlertTone;
  children: ReactNode;
  title?: string;
  /** Extra control, e.g. "다시 불러오기" or a link. */
  action?: ReactNode;
  /** 닫기 있음 when given. */
  onClose?: () => void;
  /** Close button name, e.g. "닫기". */
  closeLabel?: string;
  /** Live region. Default `alert` for error and warning, `status` for info and success. */
  live?: 'alert' | 'status';
}

export function Alert(props: AlertProps) {
  const role = props.live ?? (props.tone === 'error' || props.tone === 'warning' ? 'alert' : 'status');
  return (
    <div data-pd="Alert" data-tone={props.tone} role={role}>
      {props.title && <p>{props.title}</p>}
      <div>{props.children}</div>
      {props.action}
      {props.onClose && (
        <button type="button" aria-label={props.closeLabel} onClick={props.onClose}>
          ×
        </button>
      )}
    </div>
  );
}
