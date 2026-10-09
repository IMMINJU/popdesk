import type { MouseEvent, Ref } from 'react';
import { WarningIcon } from '../icons';

export interface FieldError {
  /** `id` of the field; the link jumps there. */
  fieldId: string;
  message: string;
}

export interface FormErrorSummaryProps {
  /** Field errors shown in the form, in form order. */
  errors: readonly FieldError[];
  /** Heading, e.g. "2개 칸을 고쳐 주세요". */
  title: string;
  /** Server `message`, shown when `fields` named a field the screen does not have (screens.md 2절). */
  serverMessage?: string;
  ref?: Ref<HTMLDivElement>;
}

/**
 * 오류 요약 띠 above a form (`role="alert"`). It does not take focus: the screen
 * moves focus to the first invalid field.
 */
export function FormErrorSummary({ ref, ...props }: FormErrorSummaryProps) {
  const jump = (e: MouseEvent<HTMLAnchorElement>, fieldId: string) => {
    const target = document.getElementById(fieldId);
    if (!target) return;
    // Focus the field without changing the address (the hash would add a history entry).
    e.preventDefault();
    target.focus();
  };
  return (
    <div ref={ref} className="pd-alert pd-error-summary" data-pd="FormErrorSummary" data-tone="error" role="alert" tabIndex={-1}>
      <span className="pd-alert-icon" aria-hidden="true">
        <WarningIcon />
      </span>
      <div className="pd-alert-body">
        <p className="pd-alert-title">{props.title}</p>
        {props.errors.length > 0 && (
          <ul className="pd-error-summary-list">
            {props.errors.map((e) => (
              <li key={e.fieldId}>
                <a
                  href={`#${e.fieldId}`}
                  onClick={(event) => {
                    jump(event, e.fieldId);
                  }}
                >
                  {e.message}
                </a>
              </li>
            ))}
          </ul>
        )}
        {props.serverMessage && <p className="pd-alert-text">{props.serverMessage}</p>}
      </div>
    </div>
  );
}
