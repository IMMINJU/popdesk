import { useEffect, useId, useRef } from 'react';
import { CheckIcon, CopyIcon } from '../icons';
import { Button } from './Button';

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

/** Read-only field and a copy button. When the clipboard is unavailable the text is selected for Ctrl+C. */
export function CopyField(props: CopyFieldProps) {
  const id = useId();
  const helpId = `${id}-help`;
  const input = useRef<HTMLInputElement>(null);
  const status = props.status ?? 'idle';

  useEffect(() => {
    if (status !== 'unavailable' || !input.current) return;
    input.current.focus();
    input.current.select();
  }, [status]);

  return (
    <div className="pd-field pd-copy" data-pd="CopyField" data-status={status}>
      <label className="pd-label" htmlFor={id}>
        {props.label}
      </label>
      <div className="pd-copy-row">
        <input
          ref={input}
          id={id}
          className="pd-input"
          type="text"
          value={props.value}
          readOnly
          spellCheck={false}
          aria-describedby={props.helpText ? helpId : undefined}
          onFocus={(e) => {
            e.currentTarget.select();
          }}
        />
        <Button variant="secondary" icon={status === 'copied' ? <CheckIcon /> : <CopyIcon />} onClick={props.onCopy}>
          {props.copyLabel}
        </Button>
      </div>
      {props.helpText && (
        <p id={helpId} className="pd-field-help" data-status={status}>
          {props.helpText}
        </p>
      )}
    </div>
  );
}
