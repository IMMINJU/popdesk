import { useId } from 'react';
import type { Priority } from '../types';
import { describedBy } from '../aria';
import { FieldError } from './fieldParts';
import { PriorityLabel } from './PriorityLabel';

const PRIORITIES: readonly Priority[] = ['LOW', 'NORMAL', 'HIGH', 'URGENT'];

export interface PriorityPickerProps {
  /** e.g. "우선순위". */
  legend: string;
  /** `null` is 안 고름. P-4 starts at `NORMAL`. */
  value: Priority | null;
  onChange: (value: Priority) => void;
  name?: string;
  disabled?: boolean;
  error?: string;
}

/** Four PriorityLabel tags as one radiogroup; arrow keys move between them. */
export function PriorityPicker(props: PriorityPickerProps) {
  const id = useId();
  const name = props.name ?? id;
  const legendId = `${id}-legend`;
  const errorId = `${id}-error`;
  return (
    <fieldset
      className="pd-group pd-priority-picker"
      data-pd="PriorityPicker"
      data-invalid={props.error ? 'true' : undefined}
      role="radiogroup"
      aria-labelledby={legendId}
      aria-describedby={describedBy(props.error && errorId)}
      disabled={props.disabled}
    >
      <legend id={legendId} className="pd-label">
        {props.legend}
      </legend>
      <div className="pd-group-options">
        {PRIORITIES.map((p) => {
          const optionId = `${id}-${p}`;
          return (
            <div key={p} className="pd-priority-option" data-checked={props.value === p ? 'true' : undefined}>
              <input
                id={optionId}
                type="radio"
                className="pd-radio-input"
                name={name}
                value={p}
                checked={props.value === p}
                onChange={() => {
                  props.onChange(p);
                }}
              />
              <label htmlFor={optionId}>
                <PriorityLabel priority={p} />
              </label>
            </div>
          );
        })}
      </div>
      <FieldError id={errorId} text={props.error} />
    </fieldset>
  );
}
