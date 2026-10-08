import { useId } from 'react';

export interface CopyFieldProps {
  label: string;
  /**
   * Text to copy, e.g. an invitation link. The screen drops it from memory
   * when the dialog closes and never logs it (MAIL-3).
   */
  value: string;
  /** 기본, 복사됨, 클립보드 못 씀 (the field is selected for Ctrl+C). Default `idle`. */
  status?: 'idle' | 'copied' | 'unavailable';
  onCopy: () => void;
  copyLabel: string;
  /** e.g. "이 링크는 지금만 보입니다", or "Ctrl+C로 복사하세요" when unavailable. */
  helpText?: string;
}

export function CopyField(props: CopyFieldProps) {
  const id = useId();
  return (
    <div data-pd="CopyField" data-status={props.status ?? 'idle'}>
      <label htmlFor={id}>{props.label}</label>
      <input id={id} type="text" value={props.value} readOnly />
      <button type="button" onClick={props.onCopy}>
        {props.copyLabel}
      </button>
      {props.helpText && <p>{props.helpText}</p>}
    </div>
  );
}
