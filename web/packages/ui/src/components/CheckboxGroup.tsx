import type { Option } from '../types';

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
  return (
    <fieldset data-pd="CheckboxGroup" disabled={props.disabled} aria-invalid={props.error ? true : undefined}>
      <legend>{props.legend}</legend>
      {props.options.map((o) => (
        <label key={o.value}>
          <input
            type="checkbox"
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
          {o.label}
        </label>
      ))}
      {props.helpText && <p>{props.helpText}</p>}
      {props.error && <p>{props.error}</p>}
    </fieldset>
  );
}
