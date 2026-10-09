import { useId, type Ref } from 'react';
import { ChevronDownIcon } from '../icons';
import type { Option } from '../types';
import { describedBy } from '../aria';
import { FieldError, FieldHelp } from './fieldParts';

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
  const helpId = `${id}-help`;
  const errorId = `${id}-error`;
  // A select has no read-only state, so the read-only one offers only its value.
  const options = props.readOnly ? props.options.filter((o) => o.value === props.value) : props.options;
  return (
    <div className="pd-field" data-pd="Select" data-invalid={props.error ? 'true' : undefined}>
      <label className="pd-label" htmlFor={id}>
        {props.label}
      </label>
      <div className="pd-input-wrap" data-has-end="true">
        <select
          ref={ref}
          id={id}
          className="pd-input pd-select"
          name={props.name}
          value={props.value}
          onChange={(e) => {
            if (!props.readOnly) props.onChange(e.target.value);
          }}
          disabled={props.disabled}
          required={props.required}
          data-readonly={props.readOnly ? 'true' : undefined}
          aria-invalid={props.error ? true : undefined}
          aria-busy={props.loading ? true : undefined}
          aria-describedby={describedBy(props.helpText && helpId, props.error && errorId)}
        >
          {props.placeholder !== undefined && !props.readOnly && <option value="">{props.placeholder}</option>}
          {options.map((o) => (
            <option key={o.value} value={o.value} disabled={o.disabled}>
              {o.label}
            </option>
          ))}
        </select>
        {props.loading && <span className="pd-select-skeleton pd-skeleton-pulse" aria-hidden="true" />}
        <span className="pd-select-arrow" aria-hidden="true">
          <ChevronDownIcon />
        </span>
      </div>
      <FieldHelp id={helpId} text={props.helpText} />
      <FieldError id={errorId} text={props.error} />
    </div>
  );
}
