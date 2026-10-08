import { useId } from 'react';
import type { Priority } from '../types';
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
  const autoName = useId();
  const name = props.name ?? autoName;
  return (
    <fieldset data-pd="PriorityPicker" disabled={props.disabled} aria-invalid={props.error ? true : undefined}>
      <legend>{props.legend}</legend>
      {PRIORITIES.map((p) => (
        <label key={p}>
          <input
            type="radio"
            name={name}
            value={p}
            checked={props.value === p}
            onChange={() => {
              props.onChange(p);
            }}
          />
          <PriorityLabel priority={p} />
        </label>
      ))}
      {props.error && <p>{props.error}</p>}
    </fieldset>
  );
}
