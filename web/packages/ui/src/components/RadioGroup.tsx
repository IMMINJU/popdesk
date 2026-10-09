import { useId, type ReactNode } from 'react';
import type { Option } from '../types';
import { describedBy } from '../aria';
import { FieldError, FieldHelp } from './fieldParts';

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
  /** Drawn inside each label after the text, e.g. a lock icon on 내부 메모. Decorative. */
  renderAdornment?: (option: Option<T>) => ReactNode;
}

/**
 * Native radios in a `fieldset` with the `radiogroup` role: one Tab stop, arrow
 * keys move and choose. The tabs look keeps the radio semantics (screens.md C-4).
 */
export function RadioGroup<T extends string>(props: RadioGroupProps<T>) {
  const id = useId();
  const name = props.name ?? id;
  const legendId = `${id}-legend`;
  const helpId = `${id}-help`;
  const errorId = `${id}-error`;
  const appearance = props.appearance ?? 'radio';
  const orientation = props.orientation ?? (appearance === 'tabs' ? 'horizontal' : 'vertical');
  return (
    <fieldset
      className="pd-group pd-radio-group"
      data-pd="RadioGroup"
      data-appearance={appearance}
      data-orientation={orientation}
      data-invalid={props.error ? 'true' : undefined}
      role="radiogroup"
      aria-labelledby={legendId}
      aria-describedby={describedBy(props.helpText && helpId, props.error && errorId)}
      disabled={props.disabled}
    >
      <legend id={legendId} className={props.legendHidden ? 'pd-sr-only' : 'pd-label'}>
        {props.legend}
      </legend>
      <div className="pd-group-options">
        {props.options.map((o) => {
          const optionId = `${id}-${o.value}`;
          return (
            <div key={o.value} className="pd-radio" data-checked={props.value === o.value ? 'true' : undefined}>
              <input
                id={optionId}
                type="radio"
                className="pd-radio-input"
                name={name}
                value={o.value}
                checked={props.value === o.value}
                disabled={o.disabled}
                onChange={() => {
                  props.onChange(o.value);
                }}
              />
              <label className="pd-radio-label" htmlFor={optionId}>
                <span className="pd-radio-dot" aria-hidden="true" />
                <span>{o.label}</span>
                {props.renderAdornment?.(o)}
              </label>
              {o.description && <span className="pd-field-help">{o.description}</span>}
            </div>
          );
        })}
      </div>
      <FieldHelp id={helpId} text={props.helpText} />
      <FieldError id={errorId} text={props.error} />
    </fieldset>
  );
}
