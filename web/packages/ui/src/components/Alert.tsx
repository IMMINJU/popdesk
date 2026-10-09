import type { ReactNode } from 'react';
import { CheckCircleIcon, CloseIcon, InfoIcon, WarningIcon } from '../icons';

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

const ICONS: Record<AlertTone, ReactNode> = {
  error: <WarningIcon />,
  warning: <WarningIcon />,
  info: <InfoIcon />,
  success: <CheckCircleIcon />,
};

/** Band with a tinted background and text in the tone color, no left stripe (tokens.md 7절). */
export function Alert(props: AlertProps) {
  const role = props.live ?? (props.tone === 'error' || props.tone === 'warning' ? 'alert' : 'status');
  return (
    <div className="pd-alert" data-pd="Alert" data-tone={props.tone} role={role}>
      <span className="pd-alert-icon" aria-hidden="true">
        {ICONS[props.tone]}
      </span>
      <div className="pd-alert-body">
        {props.title && <p className="pd-alert-title">{props.title}</p>}
        <div className="pd-alert-text">{props.children}</div>
        {props.action !== undefined && <div className="pd-alert-action">{props.action}</div>}
      </div>
      {props.onClose && (
        <button type="button" className="pd-alert-close" aria-label={props.closeLabel} onClick={props.onClose}>
          <span aria-hidden="true">
            <CloseIcon size={14} />
          </span>
        </button>
      )}
    </div>
  );
}
