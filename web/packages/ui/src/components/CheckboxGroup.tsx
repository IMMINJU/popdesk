import { useId } from 'react';
import { CheckIcon } from '../icons';
import type { Option } from '../types';
import { describedBy } from '../aria';
import { FieldError, FieldHelp } from './fieldParts';

export interface CheckboxGroupProps {
  /** `fieldset` legend, e.g. "소속 고객사". */
  legend: string;
  options: readonly Option[];
  /** Checked values. */
  value: readonly string[];
  onChange: (value: string[]) => void;
  name?: string;
  helpText?: string;
  /** 묶음 오류, e.g. "고객사를 하나 이상 고르세요". */
  error?: string;
  disabled?: boolean;
}

export function CheckboxGroup(props: CheckboxGroupProps) {
  const id = useId();
  const helpId = `${id}-help`;
  const errorId = `${id}-error`;
  return (
    <fieldset
      className="pd-group"
      data-pd="CheckboxGroup"
      data-invalid={props.error ? 'true' : undefined}
      disabled={props.disabled}
      aria-describedby={describedBy(props.helpText && helpId, props.error && errorId)}
    >
      <legend className="pd-label">{props.legend}</legend>
      <div className="pd-group-options">
        {props.options.map((o) => {
          const optionId = `${id}-${o.value}`;
          return (
            <div key={o.value} className="pd-check">
              <span className="pd-check-box">
                <input
                  id={optionId}
                  type="checkbox"
                  className="pd-check-input"
                  name={props.name}
                  value={o.value}
                  checked={props.value.includes(o.value)}
                  disabled={o.disabled}
                  onChange={(e) => {
                    props.onChange(
                      e.target.checked ? [...props.value, o.value] : props.value.filter((v) => v !== o.value),
                    );
                  }}
                />
                <span className="pd-check-mark" aria-hidden="true">
                  <CheckIcon size={14} />
                </span>
              </span>
              <label className="pd-check-label" htmlFor={optionId}>
                {o.label}
              </label>
            </div>
          );
        })}
      </div>
      <FieldHelp id={helpId} text={props.helpText} />
      <FieldError id={errorId} text={props.error} />
    </fieldset>
  );
}
