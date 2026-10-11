import { useId, type Ref } from 'react';
import { codePoints } from '../format';
import { describedBy } from '../aria';
import { FieldError, FieldHelp } from './fieldParts';

export interface TextFieldProps {
  label: string;
  value: string;
  onChange: (value: string) => void;
  id?: string;
  name?: string;
  /** Default `text`. `date` takes and gives `YYYY-MM-DD`, or an empty string. */
  type?: 'text' | 'email' | 'url' | 'search' | 'date';
  autoComplete?: string;
  placeholder?: string;
  /** 도움말 under the field, joined with `aria-describedby`. */
  helpText?: string;
  /** 오류 문구. Sets `aria-invalid="true"` and joins the message with `aria-describedby`. */
  error?: string;
  disabled?: boolean;
  readOnly?: boolean;
  required?: boolean;
  maxLength?: number;
  /** 글자 수 표시 ("12 / 200"). Needs `maxLength`. */
  showCount?: boolean;
  /** Mobile keyboards, e.g. `characters` for the company code. */
  autoCapitalize?: string;
  spellCheck?: boolean;
  /** Lets the form move focus to the first invalid field. */
  ref?: Ref<HTMLInputElement>;
}

/** `field` background with a bottom line (tokens.md 7절). */
export function TextField({ ref, ...props }: TextFieldProps) {
  const autoId = useId();
  const id = props.id ?? autoId;
  const helpId = `${id}-help`;
  const errorId = `${id}-error`;
  const count = props.showCount === true && props.maxLength !== undefined;
  return (
    <div className="pd-field" data-pd="TextField" data-invalid={props.error ? 'true' : undefined}>
      <div className="pd-field-head">
        <label className="pd-label" htmlFor={id}>
          {props.label}
        </label>
        {count && (
          <span className="pd-field-count">
            {codePoints(props.value)} / {props.maxLength?.toLocaleString('en-US')}
          </span>
        )}
      </div>
      <input
        ref={ref}
        id={id}
        className="pd-input"
        name={props.name}
        type={props.type ?? 'text'}
        value={props.value}
        onChange={(e) => {
          props.onChange(e.target.value);
        }}
        autoComplete={props.autoComplete}
        autoCapitalize={props.autoCapitalize}
        spellCheck={props.spellCheck}
        placeholder={props.placeholder}
        disabled={props.disabled}
        readOnly={props.readOnly}
        required={props.required}
        maxLength={props.maxLength}
        aria-invalid={props.error ? true : undefined}
        aria-describedby={describedBy(props.helpText && helpId, props.error && errorId)}
      />
      <FieldHelp id={helpId} text={props.helpText} />
      <FieldError id={errorId} text={props.error} />
    </div>
  );
}
