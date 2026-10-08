import { useId, type Ref } from 'react';

export interface TextFieldProps {
  label: string;
  value: string;
  onChange: (value: string) => void;
  id?: string;
  name?: string;
  /** Default `text`. */
  type?: 'text' | 'email' | 'url' | 'search';
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
  /** Lets the form move focus to the first invalid field. */
  ref?: Ref<HTMLInputElement>;
}

export function TextField({ ref, ...props }: TextFieldProps) {
  const autoId = useId();
  const id = props.id ?? autoId;
  return (
    <div data-pd="TextField">
      <label htmlFor={id}>{props.label}</label>
      <input
        ref={ref}
        id={id}
        name={props.name}
        type={props.type ?? 'text'}
        value={props.value}
        onChange={(e) => {
          props.onChange(e.target.value);
        }}
        autoComplete={props.autoComplete}
        placeholder={props.placeholder}
        disabled={props.disabled}
        readOnly={props.readOnly}
        required={props.required}
        maxLength={props.maxLength}
        aria-invalid={props.error ? true : undefined}
      />
      {props.helpText && <p>{props.helpText}</p>}
      {props.error && <p>{props.error}</p>}
    </div>
  );
}
