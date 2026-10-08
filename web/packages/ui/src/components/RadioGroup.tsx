import { useId } from 'react';
import type { Option } from '../types';

export interface RadioGroupProps<T extends string> {
  legend: string;
  /** Keep the legend for screen readers only (공개·내부 탭). */
  legendHidden?: boolean;
  options: readonly Option<T>[];
  /** `null` is 안 고름. */
  value: T | null;
  onChange: (value: T) => void;
  name?: string;
  /** `radio` is a list of radios (역할). `tabs` looks like tabs but stays a radiogroup (C-4 공개·내부). Default `radio`. */
  appearance?: 'radio' | 'tabs';
  /** Arrow keys move along this axis. Default `vertical` for `radio`, `horizontal` for `tabs`. */
  orientation?: 'horizontal' | 'vertical';
  helpText?: string;
  /** 묶음 오류. */
  error?: string;
  disabled?: boolean;
}

export function RadioGroup<T extends string>(props: RadioGroupProps<T>) {
  const autoName = useId();
  const name = props.name ?? autoName;
  return (
    <fieldset
      data-pd="RadioGroup"
      data-appearance={props.appearance ?? 'radio'}
      disabled={props.disabled}
      aria-invalid={props.error ? true : undefined}
    >
      <legend>{props.legend}</legend>
      {props.options.map((o) => (
        <label key={o.value}>
          <input
            type="radio"
            name={name}
            value={o.value}
            checked={props.value === o.value}
            disabled={o.disabled}
            onChange={() => {
              props.onChange(o.value);
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
