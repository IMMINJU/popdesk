import { useId, type Ref } from 'react';
import type { Option } from '../types';

export interface SelectProps {
  label: string;
  options: readonly Option[];
  /** Empty string means nothing chosen yet. */
  value: string;
  onChange: (value: string) => void;
  id?: string;
  name?: string;
  /** First empty choice, e.g. "고객사를 고르세요". */
  placeholder?: string;
  helpText?: string;
  error?: string;
  disabled?: boolean;
  /** P-4 with one membership: chosen and read-only. */
  readOnly?: boolean;
  /** 불러오는 중: the options are still loading (shows a skeleton). */
  loading?: boolean;
  required?: boolean;
  ref?: Ref<HTMLSelectElement>;
}

/** Native select with `appearance: none` and a drawn arrow (tokens.md 7절). */
export function Select({ ref, ...props }: SelectProps) {
  const autoId = useId();
  const id = props.id ?? autoId;
  return (
    <div data-pd="Select">
      <label htmlFor={id}>{props.label}</label>
      <select
        ref={ref}
        id={id}
        name={props.name}
        value={props.value}
        onChange={(e) => {
          props.onChange(e.target.value);
        }}
        disabled={props.disabled}
        required={props.required}
        aria-invalid={props.error ? true : undefined}
        aria-busy={props.loading}
      >
        {props.placeholder !== undefined && <option value="">{props.placeholder}</option>}
        {props.options.map((o) => (
          <option key={o.value} value={o.value} disabled={o.disabled}>
            {o.label}
          </option>
        ))}
      </select>
      {props.helpText && <p>{props.helpText}</p>}
      {props.error && <p>{props.error}</p>}
    </div>
  );
}
